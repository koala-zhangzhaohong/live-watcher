package com.koala.tiktok.live.live

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.koala.tiktok.live.config.DouyinLiveProperties
import com.koala.tiktok.live.proto.LiveProto
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

data class CachedMysteryUser(
    val id: Long,
    val nickname: String,
    @JsonProperty("short_id")
    val shortId: Long,
    @JsonProperty("sec_uid")
    val secUid: String,
    @JsonProperty("extra_info")
    val extraInfo: List<GiftExtraInfo> = emptyList(),
)

data class GiftExtraInfo(
    @JsonProperty("gift_time")
    val giftTime: Long,
    val content: String,
)

enum class MysteryUserType(val keySegment: String) {
    MYSTERY_PERSON("mystery-person"),
    MYSTERY_GUEST("mystery-guest"),
    DOU("dou");

    companion object {
        fun fromBareNickname(nickname: String): MysteryUserType? =
            when {
                nickname == "神秘嘉宾" -> MYSTERY_GUEST
                MYSTERY_PERSON_ROOM_NICKNAME.matches(nickname) -> MYSTERY_PERSON
                nickname == "dou" -> DOU
                else -> null
            }

        fun fromQuery(value: String): MysteryUserType? = entries.firstOrNull {
            value.equals(it.keySegment, ignoreCase = true) ||
                value == when (it) {
                    MYSTERY_PERSON -> "神秘人"
                    MYSTERY_GUEST -> "神秘嘉宾"
                    DOU -> "dou"
                }
        }

        private val MYSTERY_PERSON_ROOM_NICKNAME = Regex("^神秘人(?:[一二三四五六七]阶|[.·・]X)?$")
    }
}

@Component
class MysteryUserCache(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: DouyinLiveProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun cacheIfNeeded(
        roomId: String,
        user: LiveProto.User,
        extraInfo: String? = null,
    ) {
        if (user.id == MASKED_USER_ID) return
        if (SUFFIXED_NICKNAME.matches(user.nickname)) {
            cacheByFullNickname(roomId, user, extraInfo)
            return
        }
        if (roomId.isBlank()) return
        val type = MysteryUserType.fromBareNickname(user.nickname) ?: return
        if (user.secUid.isBlank()) {
            logger.warn("Skipping bare mystery user without sec_uid: roomId={}, nickname={}, userId={}", roomId, user.nickname, user.id)
            return
        }

        runCatching {
            val root = typeRoot(roomId, type)
            val duration = Duration.ofSeconds(properties.roomRetentionSeconds)
            val dataKey = "$root:${user.secUid}"
            val previous = readUser(dataKey)
            val value = CachedMysteryUser(user.id, user.nickname, user.shortId, user.secUid, mergeExtraInfo(previous, extraInfo))

            redis.opsForValue().set(dataKey, objectMapper.writeValueAsString(value), duration)
            redis.opsForSet().add(indexKey(root), dataKey)
            redis.expire(indexKey(root), duration)
        }.onFailure {
            logger.warn("Failed to cache mystery user: roomId={}, nickname={}, userId={}", roomId, user.nickname, user.id, it)
        }
    }

    private fun cacheByFullNickname(
        roomId: String,
        user: LiveProto.User,
        extraInfo: String?,
    ) {
        runCatching {
            val type = suffixedNicknameType(user.nickname)
            val key = "${properties.redisKeyPrefix}:user:data:${suffixedKeySegment(user.nickname)}"
            val previous = readUser(key)
            val value = CachedMysteryUser(user.id, user.nickname, user.shortId, user.secUid, mergeExtraInfo(previous, extraInfo))
            val duration = Duration.ofSeconds(properties.roomRetentionSeconds)
            redis.opsForValue().set(
                key,
                objectMapper.writeValueAsString(value),
                duration,
            )
            if (roomId.isNotBlank()) {
                val roomIndexKey = indexKey(typeRoot(roomId, type))
                redis.opsForSet().add(roomIndexKey, key)
                redis.expire(roomIndexKey, duration)
            }
        }.onFailure {
            logger.warn("Failed to cache suffixed mystery user: nickname={}, userId={}", user.nickname, user.id, it)
        }
    }

    private fun readUser(key: String): CachedMysteryUser? =
        redis.opsForValue().get(key)?.let(::readUserJson)

    private fun readUserJson(value: String): CachedMysteryUser? =
        runCatching {
            val root = objectMapper.readTree(value)
            val extraInfoNode = root.get("extra_info")
            val extraInfo =
                when {
                    extraInfoNode == null || extraInfoNode.isNull -> emptyList()
                    extraInfoNode.isArray ->
                        extraInfoNode.mapNotNull { item ->
                            val content = item.get("content")?.asText()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                            GiftExtraInfo(item.get("gift_time")?.asLong() ?: 0L, content)
                        }
                    // Compatibility with records written before extra_info became an array.
                    extraInfoNode.isTextual && extraInfoNode.asText().isNotBlank() ->
                        listOf(GiftExtraInfo(0L, extraInfoNode.asText()))
                    else -> emptyList()
                }
            CachedMysteryUser(
                id = root.path("id").asLong(),
                nickname = root.path("nickname").asText(),
                shortId = root.path("short_id").asLong(),
                secUid = root.path("sec_uid").asText(),
                extraInfo = extraInfo.sortedByDescending(GiftExtraInfo::giftTime).take(MAX_GIFT_HISTORY),
            )
        }.getOrNull()

    private fun mergeExtraInfo(previous: CachedMysteryUser?, newContent: String?): List<GiftExtraInfo> {
        val history = previous?.extraInfo.orEmpty()
        if (newContent.isNullOrBlank()) return history
        return (listOf(GiftExtraInfo(System.currentTimeMillis(), newContent)) + history).take(MAX_GIFT_HISTORY)
    }

    fun findByType(roomId: String, type: MysteryUserType): List<CachedMysteryUser> {
        val indexKey = indexKey(typeRoot(roomId, type))
        val keys = redis.opsForSet().members(indexKey).orEmpty().toList()
        if (keys.isEmpty()) return emptyList()

        val values = redis.opsForValue().multiGet(keys).orEmpty()
        val expiredKeys = keys.filterIndexed { index, _ -> values.getOrNull(index) == null }
        if (expiredKeys.isNotEmpty()) redis.opsForSet().remove(indexKey, *expiredKeys.toTypedArray())

        return values.filterNotNull().mapNotNull { value ->
            runCatching { readUserJson(value) }
                .onFailure { logger.warn("Ignoring invalid cached mystery user JSON", it) }
                .getOrNull()
        }.filterNotNull().sortedWith(compareBy(CachedMysteryUser::nickname, CachedMysteryUser::id))
    }

    fun findBySuffixedNickname(nickname: String): CachedMysteryUser? {
        if (!SUFFIXED_NICKNAME.matches(nickname)) return null
        val key = "${properties.redisKeyPrefix}:user:data:${suffixedKeySegment(nickname)}"
        return readUser(key)
    }

    private fun typeRoot(roomId: String, type: MysteryUserType) =
        "${properties.redisKeyPrefix}:user:data:$roomId:${type.keySegment}"

    private fun indexKey(root: String) = "$root:index"

    private fun suffixedKeySegment(nickname: String): String {
        val match = SUFFIXED_NICKNAME.matchEntire(nickname) ?: error("Unsupported suffixed nickname: $nickname")
        val type = suffixedNicknameType(match.groupValues[1])
        return "${type.keySegment}_${match.groupValues[2]}"
    }

    private fun suffixedNicknameType(nickname: String): MysteryUserType {
        val prefix = SUFFIXED_NICKNAME.matchEntire(nickname)?.groupValues?.get(1) ?: nickname
        return when (prefix) {
            "神秘人" -> MysteryUserType.MYSTERY_PERSON
            "神秘嘉宾" -> MysteryUserType.MYSTERY_GUEST
            else -> MysteryUserType.DOU
        }
    }

    private companion object {
        const val MASKED_USER_ID = 111111L
        const val MAX_GIFT_HISTORY = 10
        val SUFFIXED_NICKNAME = Regex("^(神秘人|神秘嘉宾|dou)(\\d+)$")
    }
}
