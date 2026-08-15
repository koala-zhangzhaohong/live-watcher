package com.koala.tiktok.live.util

import kotlin.random.Random

data class BrowserProfile(
    val ua: String,
    val secChUa: String,
    val secChUaPlatform: String,
    val browserName: String,
    val browserVersion: String,
    val engineName: String,
    val engineVersion: String,
    val osName: String,
    val osVersion: String,
    val platform: String,
    val cpuCoreNum: String,
    val deviceMemory: String,
    val screenWidth: String,
    val screenHeight: String,
)

object BrowserFingerprint {
    private val geoPresets =
        listOf(
            1920 to 1080,
            1366 to 768,
            1536 to 864,
            1440 to 900,
            1280 to 720,
        )

    val profile: BrowserProfile by lazy {
        val (screenWidth, screenHeight) = geoPresets[Random.nextInt(geoPresets.size)]
        BrowserProfile(
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            secChUa = "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\"",
            secChUaPlatform = "\"Windows\"",
            browserName = "Chrome",
            browserVersion = "150.0.0.0",
            engineName = "Blink",
            engineVersion = "150.0.0.0",
            osName = "Windows",
            osVersion = "10",
            platform = "Win32",
            cpuCoreNum = "12",
            deviceMemory = "8",
            screenWidth = screenWidth.toString(),
            screenHeight = screenHeight.toString(),
        )
    }
}
