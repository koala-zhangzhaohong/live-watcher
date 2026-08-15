package com.koala.tiktok.live.live

import com.koala.tiktok.live.config.DouyinLiveProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Duration

data class CookieUpdateResult(
    val dyCookieUpdated: Boolean,
    val dyLiveCookieUpdated: Boolean,
)

@Service
class DouyinCookieService(
    private val redis: StringRedisTemplate,
    private val resourceLoader: ResourceLoader,
    properties: DouyinLiveProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val dyCookieKey = "${properties.redisKeyPrefix}:cookie:dy"
    private val dyLiveCookieKey = "${properties.redisKeyPrefix}:cookie:dy-live"

    @PostConstruct
    fun initializeRedisCookies() {
        runCatching { dyCookie() }
            .onFailure { logger.error("Failed to initialize DY_COOKIES in Redis", it) }
        runCatching { liveCookie() }
            .onFailure { logger.error("Failed to initialize DY_LIVE_COOKIES in Redis", it) }
    }

    fun dyCookie(): String = getOrSeed(dyCookieKey, DY_COOKIE_RESOURCE)

    fun liveCookie(): String = getOrSeed(dyLiveCookieKey, DY_LIVE_COOKIE_RESOURCE)

    fun update(
        dyCookie: String?,
        dyLiveCookie: String?,
    ): CookieUpdateResult {
        val normalizedDyCookie = dyCookie?.trim().orEmpty()
        val normalizedDyLiveCookie = dyLiveCookie?.trim().orEmpty()
        if (normalizedDyCookie.isNotEmpty()) redis.opsForValue().set(dyCookieKey, normalizedDyCookie, COOKIE_TTL)
        if (normalizedDyLiveCookie.isNotEmpty()) redis.opsForValue().set(dyLiveCookieKey, normalizedDyLiveCookie, COOKIE_TTL)
        return CookieUpdateResult(normalizedDyCookie.isNotEmpty(), normalizedDyLiveCookie.isNotEmpty())
    }

    private fun getOrSeed(
        key: String,
        resourcePath: String,
    ): String {
        redis.opsForValue().get(key)?.takeIf(String::isNotBlank)?.let { return it }
        val localCookie = readResource(resourcePath)
        require(localCookie.isNotBlank()) { "Cookie resource is empty: $resourcePath" }
        redis.opsForValue().set(key, localCookie, COOKIE_TTL)
        return redis.opsForValue().get(key)?.takeIf(String::isNotBlank)
            ?: error("Cookie was written but could not be read from Redis: $key")
    }

    private fun readResource(path: String): String =
        resourceLoader.getResource(path).inputStream.use {
            String(it.readAllBytes(), StandardCharsets.UTF_8).trim()
        }

    private companion object {
        val COOKIE_TTL: Duration = Duration.ofDays(14)
        const val DY_COOKIE_RESOURCE = "classpath:cookie/custom.dy.cookie.txt"
        const val DY_LIVE_COOKIE_RESOURCE = "classpath:cookie/custom.dy-live.cookie.txt"
    }
}
