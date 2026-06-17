package com.koala.tiktok.live.util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.random.Random

object DouyinUtil {
    private const val MS_TOKEN_CHARS = "ABCDEFGHIGKLMNOPQRSTUVWXYZabcdefghigklmnopqrstuvwxyz0123456789="

    fun parseCookies(cookieStr: String): MutableMap<String, String> {
        if (cookieStr.isBlank()) return linkedMapOf()
        return cookieStr.split(";")
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isBlank() || !trimmed.contains("=")) {
                    null
                } else {
                    val key = trimmed.substringBefore("=")
                    val value = trimmed.substringAfter("=")
                    key to value
                }
            }
            .toMap(LinkedHashMap())
    }

    fun generateMsToken(length: Int = 107): String =
        buildString {
            repeat(length) {
                append(MS_TOKEN_CHARS[Random.nextInt(MS_TOKEN_CHARS.length)])
            }
        }

    fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun queryString(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    fun spliceUrl(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) ->
            "$key=${encode(value)}"
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
