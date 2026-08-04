package com.trivox.client.network

import java.net.HttpURLConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthVerifierV8Test {
    @Test
    fun generate204RequiresExactNoContent() {
        assertTrue(
            TunnelHealthVerifier.acceptsHttpStatus(
                "https://example.test/generate_204",
                HttpURLConnection.HTTP_NO_CONTENT
            )
        )
        assertFalse(
            TunnelHealthVerifier.acceptsHttpStatus(
                "https://example.test/generate_204",
                HttpURLConnection.HTTP_OK
            )
        )
    }

    @Test
    fun ordinaryHttpsTargetAcceptsOnlySuccessClass() {
        assertTrue(
            TunnelHealthVerifier.acceptsHttpStatus(
                "https://example.test/trace",
                HttpURLConnection.HTTP_OK
            )
        )
        assertFalse(
            TunnelHealthVerifier.acceptsHttpStatus(
                "https://example.test/trace",
                HttpURLConnection.HTTP_MOVED_TEMP
            )
        )
    }
}
