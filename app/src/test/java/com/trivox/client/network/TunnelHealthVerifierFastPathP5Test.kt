package com.trivox.client.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthVerifierFastPathP5Test {
    @Test
    fun configuredUrlIsFirstAndDnsFreeTraceIsSecond() {
        val requested = "https://example.com/generate_204"
        val targets = TunnelHealthVerifier.probeTargets(requested, true)

        assertEquals(requested, targets.first().url)
        assertEquals(VerifiedHttpProbe.strongTraceTarget.url, targets[1].url)
        assertTrue(targets.size >= 4)
    }

    @Test
    fun dnsFreeRetryNeverSpendsTimeOnHostnameTargets() {
        val targets = TunnelHealthVerifier.probeTargets(
            "https://example.com/generate_204",
            false
        )

        assertTrue(targets.isNotEmpty())
        assertTrue(targets.all { it.url.contains("1.1.1.1") || it.url.contains("1.0.0.1") })
        assertFalse(targets.any { it.url.contains("example.com") })
    }
}
