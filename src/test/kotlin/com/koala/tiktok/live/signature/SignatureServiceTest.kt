package com.koala.tiktok.live.signature

import kotlin.test.Test
import kotlin.test.assertTrue

class SignatureServiceTest {
    private val signatureService = SignatureService()

    @Test
    fun `loads a bogus script and returns value`() {
        val result = signatureService.generateABogus(
            linkedMapOf(
                "app_name" to "douyin_web",
                "room_id" to "123456",
                "msToken" to "test-token",
            ),
        )

        assertTrue(result.isNotBlank())
    }

    @Test
    fun `loads live signature script and returns x bogus`() {
        val result = signatureService.generateSignature("123456", "78910")

        assertTrue(result.isNotBlank())
    }
}
