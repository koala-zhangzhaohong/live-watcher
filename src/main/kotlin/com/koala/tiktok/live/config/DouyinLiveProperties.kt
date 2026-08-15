package com.koala.tiktok.live.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "douyin.live")
data class DouyinLiveProperties(
    var autoStart: Boolean = false,
    var liveId: String = "",
    var cookies: String = "",
    var reconnectOnClose: Boolean = true,
    var heartbeatSeconds: Long = 5,
    var coordinationMode: String = "local",
    var instanceId: String = "",
    var reconcileSeconds: Long = 5,
    var instanceTimeoutSeconds: Long = 20,
    var leaseSeconds: Long = 15,
    var redisKeyPrefix: String = "douyin:live",
)
