package com.koala.tiktok.live.live

import com.koala.tiktok.live.api.DouyinApiClient
import com.koala.tiktok.live.auth.DouyinAuth
import com.koala.tiktok.live.config.DouyinLiveProperties
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class DouyinLiveService(
    private val properties: DouyinLiveProperties,
    private val apiClient: DouyinApiClient,
    private val okHttpClient: OkHttpClient,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val clients = ConcurrentHashMap<String, DouyinLiveClient>()

    override fun run(args: ApplicationArguments) {
        if (properties.autoStart && properties.liveId.isNotBlank()) {
            start(properties.liveId, properties.cookies)
        }
    }

    fun start(
        liveId: String,
        cookies: String = properties.cookies,
    ): String {
        require(liveId.isNotBlank()) { "liveId must not be blank" }
        val effectiveCookies = cookies.ifBlank { properties.cookies }
        require(effectiveCookies.isNotBlank()) { "cookies must not be blank. Set DY_LIVE_COOKIES or pass cookies in request." }
        clients[liveId]?.stop()
        val client = DouyinLiveClient(liveId, DouyinAuth.prepare(effectiveCookies), apiClient, properties, okHttpClient)
        clients[liveId] = client
        client.start()
        logger.info("Started Douyin live client: {}", liveId)
        return liveId
    }

    fun stop(liveId: String): Boolean = clients.remove(liveId)?.also { it.stop() } != null

    fun stopAll() {
        clients.values.forEach { it.stop() }
        clients.clear()
    }

    fun activeLiveIds(): Set<String> = clients.keys
}
