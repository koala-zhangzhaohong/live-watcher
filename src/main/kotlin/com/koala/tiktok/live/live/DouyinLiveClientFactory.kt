package com.koala.tiktok.live.live

import com.koala.tiktok.live.api.DouyinApiClient
import com.koala.tiktok.live.auth.DouyinAuth
import com.koala.tiktok.live.config.DouyinLiveProperties
import okhttp3.OkHttpClient
import org.springframework.stereotype.Component

interface LiveClientFactory {
    fun create(
        liveId: String,
        auth: DouyinAuth,
    ): LiveClient
}

@Component
class DouyinLiveClientFactory(
    private val apiClient: DouyinApiClient,
    private val properties: DouyinLiveProperties,
    private val okHttpClient: OkHttpClient,
    private val mysteryUserCache: MysteryUserCache,
) : LiveClientFactory {
    override fun create(
        liveId: String,
        auth: DouyinAuth,
    ): LiveClient = DouyinLiveClient(liveId, auth, apiClient, properties, okHttpClient, mysteryUserCache)
}
