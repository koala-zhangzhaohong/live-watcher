package com.koala.tiktok.live.util

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

object Gzip {
    fun decompress(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
