package com.koala.tiktok.live.api

import com.koala.tiktok.live.auth.DouyinAuth
import com.koala.tiktok.live.signature.SignatureService
import com.koala.tiktok.live.util.DouyinUtil
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class LiveRoomInfo(
    val roomId: String,
    val userId: String,
    val userUniqueId: String,
    val anchorId: String,
    val secUid: String,
    val ttwid: String?,
    val roomStatus: String,
    val roomTitle: String,
)

@Component
class DouyinApiClient(
    private val okHttpClient: OkHttpClient,
    private val signatureService: SignatureService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun getLiveInfo(auth: DouyinAuth, liveId: String): LiveRoomInfo {
        val url = "https://live.douyin.com/$liveId"
        val request = Request.Builder()
            .url(url)
            .headers(liveInfoHeaders())
            .header("Cookie", auth.cookieStr)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Failed to fetch live info: HTTP ${response.code}")
            val html = response.body?.string().orEmpty()
            val ttwid = response.headers.values("Set-Cookie")
                .firstOrNull { it.startsWith("ttwid=") }
                ?.substringAfter("ttwid=")
                ?.substringBefore(";")
            val scripts = Jsoup.parse(html).select("script[nonce]")

            for (script in scripts) {
                val text = script.data().ifBlank { script.html() }
                if (!text.contains("roomId")) continue
                val info = parseLiveRoomInfo(text, ttwid)
                if (info != null) {
                    logger.info("Live room info: {}", info)
                    return info
                }
            }
        }
        error("Could not parse live room info for liveId=$liveId")
    }

    fun getWebcastDetail(auth: DouyinAuth, userId: String, roomId: String, referer: String): ByteArray {
        val params = linkedMapOf(
            "resp_content_type" to "protobuf",
            "did_rule" to "3",
            "device_id" to "",
            "app_name" to "douyin_web",
            "endpoint" to "live_pc",
            "support_wrds" to "1",
            "user_unique_id" to userId,
            "identity" to "audience",
            "need_persist_msg_count" to "15",
            "insert_task_id" to "",
            "live_reason" to "",
            "room_id" to roomId,
            "version_code" to "180800",
            "last_rtt" to "0",
            "live_id" to "1",
            "aid" to "6383",
            "fetch_rule" to "1",
            "cursor" to "",
            "internal_ext" to "",
            "device_platform" to "web",
            "cookie_enabled" to "true",
            "screen_width" to "2560",
            "screen_height" to "1440",
            "browser_language" to "en",
            "browser_platform" to "Win32",
            "browser_name" to "Mozilla",
            "browser_version" to "5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "browser_online" to "true",
            "tz_name" to "Asia/Shanghai",
            "msToken" to auth.msToken,
        )
        params["a_bogus"] = signatureService.generateABogus(params)

        val httpUrl = "https://live.douyin.com/webcast/im/fetch/".toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

        val request = Request.Builder()
            .url(httpUrl)
            .headers(formHeaders(referer, auth.cookieStr))
            .header("Cookie", auth.cookieStr)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Failed to fetch webcast detail: HTTP ${response.code}")
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    fun buildWebSocketUrl(roomInfo: LiveRoomInfo, cursor: String, internalExt: String): String {
        val params = linkedMapOf(
            "app_name" to "douyin_web",
            "version_code" to "180800",
            "webcast_sdk_version" to "1.0.15",
            "update_version_code" to "1.0.15",
            "compress" to "gzip",
            "device_platform" to "web",
            "cookie_enabled" to "true",
            "screen_width" to "1707",
            "screen_height" to "960",
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Mozilla",
            "browser_version" to USER_AGENT.substringAfter("Mozilla/"),
            "browser_online" to "true",
            "tz_name" to "Etc/GMT-8",
            "cursor" to cursor,
            "internal_ext" to internalExt,
            "host" to "https://live.douyin.com",
            "aid" to "6383",
            "live_id" to "1",
            "did_rule" to "3",
            "endpoint" to "live_pc",
            "support_wrds" to "1",
            "user_unique_id" to roomInfo.userId,
            "im_path" to "/webcast/im/fetch/",
            "identity" to "audience",
            "need_persist_msg_count" to "15",
            "insert_task_id" to "",
            "live_reason" to "",
            "room_id" to roomInfo.roomId,
            "heartbeatDuration" to "0",
            "signature" to signatureService.generateSignature(roomInfo.roomId, roomInfo.userId),
        )
        return "wss://webcast100-ws-web-hl.douyin.com/webcast/im/push/v2/?${DouyinUtil.queryString(params)}"
    }

    fun webSocketHeaders(auth: DouyinAuth): Headers =
        Headers.Builder()
            .add("Pragma", "no-cache")
            .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            .add("User-Agent", USER_AGENT)
            .add("Upgrade", "websocket")
            .add("Cache-Control", "no-cache")
            .add("Connection", "Upgrade")
            .add("Origin", "https://live.douyin.com")
            .add("Cookie", auth.cookieStr)
            .build()

    private fun parseLiveRoomInfo(script: String, ttwid: String?): LiveRoomInfo? {
        return runCatching {
            val userId = script.firstGroup("""\\"user_unique_id\\":\\"(\d+)\\"""")
            val roomId = script.firstGroup("""\\"roomId\\":\\"(\d+)\\"""")
            val roomInfo = Regex("""\\"roomInfo\\":\{\\"room\\":\{\\"id_str\\":\\".*?\\",\\"status\\":(.*?),\\"status_str\\":\\".*?\\",\\"title\\":\\"(.*?)\\"""")
                .find(script) ?: return null
            LiveRoomInfo(
                roomId = roomId,
                userId = userId,
                userUniqueId = userId,
                anchorId = script.firstGroup("""\\"anchor\\":\{\\"id_str\\":\\"(\d+)\\""""),
                secUid = script.firstGroup("""\\"sec_uid\\":\\"(.*?)\\""""),
                ttwid = ttwid,
                roomStatus = roomInfo.groupValues[1],
                roomTitle = roomInfo.groupValues[2],
            )
        }.getOrNull()
    }

    private fun String.firstGroup(pattern: String): String =
        Regex(pattern).find(this)?.groupValues?.get(1) ?: error("Pattern not found: $pattern")

    private fun liveInfoHeaders(): Headers =
        Headers.Builder()
            .add("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .add("accept-language", "zh-CN,zh;q=0.9,zh-TW;q=0.8,en;q=0.7,ja;q=0.6")
            .add("cache-control", "no-cache")
            .add("pragma", "no-cache")
            .add("priority", "u=0, i")
            .add("referer", "https://live.douyin.com/?from_nav=1")
            .add("sec-ch-ua", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"")
            .add("sec-ch-ua-mobile", "?0")
            .add("sec-ch-ua-platform", "\"Windows\"")
            .add("sec-fetch-dest", "empty")
            .add("sec-fetch-mode", "navigate")
            .add("sec-fetch-site", "same-origin")
            .add("upgrade-insecure-requests", "1")
            .add("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
            .build()

    private fun formHeaders(referer: String, cookieStr: String): Headers {
        val builder = Headers.Builder()
            .add("user-agent", USER_AGENT)
            .add("cache-control", "no-cache")
            .add("pragma", "no-cache")
            .add("accept", "application/json, text/plain, */*")
            .add("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .add("origin", "https://live.douyin.com")
            .add("referer", referer)
        csrfToken(cookieStr)?.let { builder.add("x-secsdk-csrf-token", it) }
        return builder.build()
    }

    private fun csrfToken(cookieStr: String): String? {
        val request = Request.Builder()
            .url("https://www.douyin.com/service/2/abtest_config/")
            .head()
            .header("accept", "*/*")
            .header("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            .header("cache-control", "no-cache")
            .header("cookie", cookieStr)
            .header("pragma", "no-cache")
            .header("priority", "u=1, i")
            .header("referer", "https://www.douyin.com/?recommend=1")
            .header("sec-ch-ua", "\"Microsoft Edge\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-origin")
            .header("user-agent", USER_AGENT)
            .header("x-secsdk-csrf-request", "1")
            .header("x-secsdk-csrf-version", "1.2.22")
            .build()

        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                response.header("X-Ware-Csrf-Token")
                    ?.split(",")
                    ?.getOrNull(1)
            }
        }.getOrNull()
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/117.0"
    }
}
