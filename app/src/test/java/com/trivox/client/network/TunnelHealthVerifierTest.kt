package com.trivox.client.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthVerifierTest {
    @Test
    fun generate204RequiresExactNoContent() {
        assertTrue(
            TunnelHealthVerifier.acceptsHttpStatus(
                "https://cp.cloudflare.com/generate_204",
                204
            )
        )
        assertFalse(
            TunnelHealthVerifier.acceptsHttpStatus(
                "https://cp.cloudflare.com/generate_204",
                200
            )
        )
    }

    @Test
    fun redirectAndCaptivePortalStatusesAreRejected() {
        assertFalse(
            TunnelHealthVerifier.acceptsHttpStatus(
                "https://example.com/check",
                302
            )
        )
        assertTrue(
            TunnelHealthVerifier.acceptsHttpStatus(
                "https://example.com/check",
                200
            )
        )
    }
}
