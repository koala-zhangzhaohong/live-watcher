package com.koala.tiktok.live.config

import kotlin.test.Test
import kotlin.test.assertTrue

class RedisEnvironmentIsolationTest {
    @Test
    fun `test and production profiles use separate databases and key namespaces`() {
        val testConfig = resource("application-test.yml")
        val prodConfig = resource("application-prod.yml")

        assertTrue(testConfig.contains("database: \${TEST_REDIS_DATABASE:1}"))
        assertTrue(prodConfig.contains("database: \${PROD_REDIS_DATABASE:0}"))
        assertTrue(testConfig.contains("host: \${REDIS_HOST:116.255.208.81}"))
        assertTrue(prodConfig.contains("host: \${REDIS_HOST:116.255.208.81}"))
        assertTrue(testConfig.contains("port: \${REDIS_PORT:55000}"))
        assertTrue(prodConfig.contains("port: \${REDIS_PORT:55000}"))
        assertTrue(testConfig.contains("tiktok-live:test"))
        assertTrue(prodConfig.contains("tiktok-live:prod"))
        assertTrue(testConfig.contains("coordination-mode: \${DY_LIVE_COORDINATION_MODE:redis}"))
        assertTrue(prodConfig.contains("coordination-mode: \${DY_LIVE_COORDINATION_MODE:redis}"))
        assertTrue(testConfig.contains("max-active: 16"))
        assertTrue(prodConfig.contains("max-active: 16"))
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "$name should exist" }
            .bufferedReader()
            .use { it.readText() }
}
