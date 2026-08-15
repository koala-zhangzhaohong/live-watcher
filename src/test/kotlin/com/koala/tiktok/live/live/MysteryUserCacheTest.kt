package com.koala.tiktok.live.live

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koala.tiktok.live.config.DouyinLiveProperties
import com.koala.tiktok.live.proto.LiveProto
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MysteryUserCacheTest {
    private val redis = mock(StringRedisTemplate::class.java)
    private val valueOperations = mock(ValueOperations::class.java) as ValueOperations<String, String>
    private val setOperations = mock(SetOperations::class.java) as SetOperations<String, String>
    private val properties = DouyinLiveProperties(redisKeyPrefix = "tiktok-live:test")
    private val cache = MysteryUserCache(redis, jacksonObjectMapper(), properties)

    init {
        `when`(redis.opsForValue()).thenReturn(valueOperations)
        `when`(redis.opsForSet()).thenReturn(setOperations)
    }

    @Test
    fun `uses the same protobuf user fields as python client`() {
        val descriptor = LiveProto.User.getDescriptor()

        assertEquals(1, descriptor.findFieldByName("id").number)
        assertEquals(2, descriptor.findFieldByName("short_id").number)
        assertEquals(46, descriptor.findFieldByName("sec_uid").number)
    }

    @Test
    fun `only classifies bare mystery nicknames for room lists`() {
        assertEquals(MysteryUserType.MYSTERY_PERSON, MysteryUserType.fromBareNickname("神秘人"))
        assertEquals(MysteryUserType.MYSTERY_PERSON, MysteryUserType.fromBareNickname("神秘人一阶"))
        assertEquals(MysteryUserType.MYSTERY_PERSON, MysteryUserType.fromBareNickname("神秘人七阶"))
        assertEquals(MysteryUserType.MYSTERY_PERSON, MysteryUserType.fromBareNickname("神秘人.X"))
        assertEquals(MysteryUserType.MYSTERY_PERSON, MysteryUserType.fromBareNickname("神秘人·X"))
        assertEquals(MysteryUserType.MYSTERY_GUEST, MysteryUserType.fromBareNickname("神秘嘉宾"))
        assertEquals(MysteryUserType.DOU, MysteryUserType.fromBareNickname("dou"))
        assertNull(MysteryUserType.fromBareNickname("神秘人23823782"))
        assertNull(MysteryUserType.fromBareNickname("神秘嘉宾8728378293"))
        assertNull(MysteryUserType.fromBareNickname("dou2283289"))
        assertNull(MysteryUserType.fromBareNickname("神秘人八阶"))
        assertNull(MysteryUserType.fromBareNickname("神秘人.123"))
        assertNull(MysteryUserType.fromBareNickname("神秘人.测试"))
        assertNull(MysteryUserType.fromBareNickname("普通用户"))
    }

    @Test
    fun `stores suffixed protobuf user by full nickname outside room list`() {
        val user =
            LiveProto.User
                .newBuilder()
                .setId(123456)
                .setShortId(654321)
                .setSecUid("MS4wLjABAAAA-test")
                .setNickname("dou2283289")
                .build()

        cache.cacheIfNeeded("99887766", user)

        verify(valueOperations).set(
            "tiktok-live:test:user:data:dou_2283289",
            "{\"id\":123456,\"nickname\":\"dou2283289\",\"short_id\":654321,\"sec_uid\":\"MS4wLjABAAAA-test\",\"extra_info\":[]}",
            Duration.ofSeconds(properties.roomRetentionSeconds),
        )
    }

    @Test
    fun `gift info is prepended to history and refreshes suffixed user expiry`() {
        val key = "tiktok-live:test:user:data:mystery-person_23823782"
        `when`(valueOperations.get(key)).thenReturn(
            "{\"id\":123456,\"nickname\":\"神秘人23823782\",\"short_id\":654321,\"sec_uid\":\"sec\",\"extra_info\":\"旧礼物\"}",
        )
        val user =
            LiveProto.User.newBuilder()
                .setId(123456)
                .setShortId(654321)
                .setSecUid("sec")
                .setNickname("神秘人23823782")
                .build()

        cache.cacheIfNeeded("99887766", user, "新礼物")

        val valueCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(valueOperations).set(
            org.mockito.ArgumentMatchers.eq(key),
            valueCaptor.capture(),
            org.mockito.ArgumentMatchers.any(Duration::class.java),
        )
        val stored = jacksonObjectMapper().readTree(valueCaptor.value)
        assertEquals(2, stored["extra_info"].size())
        assertEquals("新礼物", stored["extra_info"][0]["content"].asText())
        assertEquals(true, stored["extra_info"][0]["gift_time"].asLong() > 0)
        assertEquals("旧礼物", stored["extra_info"][1]["content"].asText())
    }

    @Test
    fun `finds one suffixed user without room id`() {
        val key = "tiktok-live:test:user:data:mystery-guest_8728378293"
        `when`(valueOperations.get(key)).thenReturn(
            "{\"id\":123456,\"nickname\":\"神秘嘉宾8728378293\",\"short_id\":654321,\"sec_uid\":\"sec\",\"extra_info\":null}",
        )

        val result = cache.findBySuffixedNickname("神秘嘉宾8728378293")

        assertEquals("神秘嘉宾8728378293", result?.nickname)
        assertEquals(123456, result?.id)
        verify(valueOperations).get(key)
    }

    @Test
    fun `rejects non suffixed nickname in single user lookup`() {
        assertNull(cache.findBySuffixedNickname("神秘人"))
        assertNull(cache.findBySuffixedNickname("普通用户"))
    }

    @Test
    fun `stores bare mystery user under room sec uid key`() {
        val user =
            LiveProto.User.newBuilder()
                .setId(123456)
                .setNickname("神秘人")
                .setSecUid("MS4wLjABAAAA-bare-user")
                .build()

        cache.cacheIfNeeded("99887766", user)

        val keyCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(valueOperations).set(
            keyCaptor.capture(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Duration::class.java),
        )
        assertEquals(
            "tiktok-live:test:user:data:99887766:mystery-person:MS4wLjABAAAA-bare-user",
            keyCaptor.value,
        )
    }

    @Test
    fun `does not store masked user id`() {
        val user =
            LiveProto.User
                .newBuilder()
                .setId(111111)
                .setNickname("神秘人23823782")
                .build()

        cache.cacheIfNeeded("99887766", user)

        verify(valueOperations, never()).set(anyString(), anyString())
        verify(valueOperations, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration::class.java))
    }
}
