package com.trivox.client.network

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkBufferPoolV9Test {
    @Test
    fun boundedUtf8ReadReturnsCompletePayloadAndReusesBoundedBuffers() {
        NetworkBufferPool.clear()
        val text = "trivox-" + "x".repeat(32_000)
        val result = NetworkBufferPool.readUtf8Bounded(
            ByteArrayInputStream(text.toByteArray()),
            maxBytes = 64 * 1024,
            preferredBufferKb = 16
        )
        assertEquals(text, result)
        assertTrue(NetworkBufferPool.pooledCount() in 1..4)
    }

    @Test(expected = IllegalStateException::class)
    fun oversizedResponseIsRejected() {
        NetworkBufferPool.readUtf8Bounded(
            ByteArrayInputStream(ByteArray(2048)),
            maxBytes = 1024,
            preferredBufferKb = 8
        )
    }
}
