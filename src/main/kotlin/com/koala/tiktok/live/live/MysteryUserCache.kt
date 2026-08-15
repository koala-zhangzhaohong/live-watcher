package com.koala.tiktok.live.live

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.koala.tiktok.live.config.DouyinLiveProperties
import com.koala.tiktok.live.proto.LiveProto
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

data class CachedMysteryUser(
    val id: Long,
    val nickname: String,
    @JsonProperty("short_id")
    val shortId: Long,
    @JsonProperty("sec_uid")
    val secUid: String,
    @JsonProperty("extra_info")
    val extraInfo: String? = null,
)

enum class MysteryUserType(val keySegment: String) {
    MYSTERY_PERSON("mystery-person"),
    MYSTERY_GUEST("mystery-guest"),
    DOU("dou");

    companion object {
        fun fromBareNickname(nickname: String): MysteryUserType? = when (nickname) {
            "神秘嘉宾" -> MYSTERY_GUEST
            "神秘人" -> MYSTERY_PERSON
            "dou" -> DOU
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
            cacheByFullNickname(user, extraInfo)
            return
        }
        if (roomId.isBlank()) return
        val type = MysteryUserType.fromBareNickname(user.nickname) ?: return

        runCatching {
            val root = typeRoot(roomId, type)
            val identityKey = "$root:identity:${userIdentity(user)}"
            val generatedDataKey = "$root:${UUID.randomUUID().toString().replace("-", "")}"
            val duration = Duration.ofSeconds(properties.roomRetentionSeconds)
            val created = redis.opsForValue().setIfAbsent(identityKey, generatedDataKey, duration) == true
            val dataKey = if (created) generatedDataKey else redis.opsForValue().get(identityKey) ?: generatedDataKey
            val previous = readUser(dataKey)
            val value = CachedMysteryUser(user.id, user.nickname, user.shortId, user.secUid, extraInfo ?: previous?.extraInfo)

            redis.expire(identityKey, duration)
            redis.opsForValue().set(dataKey, objectMapper.writeValueAsString(value), duration)
            redis.opsForSet().add(indexKey(root), dataKey)
            redis.expire(indexKey(root), duration)
        }.onFailure {
            logger.warn("Failed to cache mystery user: roomId={}, nickname={}, userId={}", roomId, user.nickname, user.id, it)
        }
    }

    private fun cacheByFullNickname(
        user: LiveProto.User,
        extraInfo: String?,
    ) {
        runCatching {
            val key = "${properties.redisKeyPrefix}:user:data:${suffixedKeySegment(user.nickname)}"
            val previous = readUser(key)
            val value = CachedMysteryUser(user.id, user.nickname, user.shortId, user.secUid, extraInfo ?: previous?.extraInfo)
            redis.opsForValue().set(
                key,
                objectMapper.writeValueAsString(value),
                Duration.ofSeconds(properties.roomRetentionSeconds),
            )
        }.onFailure {
            logger.warn("Failed to cache suffixed mystery user: nickname={}, userId={}", user.nickname, user.id, it)
        }
    }

    private fun readUser(key: String): CachedMysteryUser? =
        redis.opsForValue().get(key)?.let { value ->
            runCatching { objectMapper.readValue(value, CachedMysteryUser::class.java) }.getOrNull()
        }

    fun findByType(roomId: String, type: MysteryUserType): List<CachedMysteryUser> {
        val indexKey = indexKey(typeRoot(roomId, type))
        val keys = redis.opsForSet().members(indexKey).orEmpty().toList()
        if (keys.isEmpty()) return emptyList()

        val values = redis.opsForValue().multiGet(keys).orEmpty()
        val expiredKeys = keys.filterIndexed { index, _ -> values.getOrNull(index) == null }
        if (expiredKeys.isNotEmpty()) redis.opsForSet().remove(indexKey, *expiredKeys.toTypedArray())

        return values.filterNotNull().mapNotNull { value ->
            runCatching { objectMapper.readValue(value, CachedMysteryUser::class.java) }
                .onFailure { logger.warn("Ignoring invalid cached mystery user JSON", it) }
                .getOrNull()
        }.sortedWith(compareBy(CachedMysteryUser::nickname, CachedMysteryUser::id))
    }

    private fun typeRoot(roomId: String, type: MysteryUserType) =
        "${properties.redisKeyPrefix}:user:data:$roomId:${type.keySegment}"

    private fun indexKey(root: String) = "$root:index"

    private fun userIdentity(user: LiveProto.User): String = when {
        user.id != 0L -> user.id.toString()
        user.secUid.isNotBlank() -> user.secUid.hashCode().toUInt().toString(16)
        else -> "${user.shortId}:${user.nickname}".hashCode().toUInt().toString(16)
    }

    private fun suffixedKeySegment(nickname: String): String {
        val match = SUFFIXED_NICKNAME.matchEntire(nickname) ?: error("Unsupported suffixed nickname: $nickname")
        val type =
            when (match.groupValues[1]) {
                "神秘人" -> MysteryUserType.MYSTERY_PERSON
                "神秘嘉宾" -> MysteryUserType.MYSTERY_GUEST
                else -> MysteryUserType.DOU
            }
        return "${type.keySegment}_${match.groupValues[2]}"
    }

    private companion object {
        const val MASKED_USER_ID = 111111L
        val SUFFIXED_NICKNAME = Regex("^(神秘人|神秘嘉宾|dou)(\\d+)$")
    }
}
