package com.trivox.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsNetworkTuningV9Test {
    @Test
    fun networkAndWireGuardSettingsRoundTrip() {
        val original = AppSettings(
            adaptiveHandshake = false,
            networkTuningEnabled = true,
            tcpFastOpen = true,
            tcpKeepAliveIdleSeconds = 90,
            tcpKeepAliveIntervalSeconds = 20,
            tcpUserTimeoutMs = 22_000,
            networkBufferSizeKb = 96,
            wireGuardMtu = 1320,
            wireGuardWorkers = 4,
            wireGuardKeepAliveSeconds = 0,
            wireGuardHandshakeTimeoutMs = 30_000,
            wireGuardDomainStrategy = "ForceIPv4"
        )

        val restored = AppSettings.fromJson(original.toJson())
        assertFalse(restored.adaptiveHandshake)
        assertTrue(restored.networkTuningEnabled)
        assertTrue(restored.tcpFastOpen)
        assertEquals(90, restored.tcpKeepAliveIdleSeconds)
        assertEquals(20, restored.tcpKeepAliveIntervalSeconds)
        assertEquals(22_000, restored.tcpUserTimeoutMs)
        assertEquals(96, restored.networkBufferSizeKb)
        assertEquals(1320, restored.wireGuardMtu)
        assertEquals(4, restored.wireGuardWorkers)
        assertEquals(0, restored.wireGuardKeepAliveSeconds)
        assertEquals(30_000, restored.wireGuardHandshakeTimeoutMs)
        assertEquals("ForceIPv4", restored.wireGuardDomainStrategy)
    }

    @Test
    fun unsafeStoredValuesAreBounded() {
        val restored = AppSettings.fromJson(
            AppSettings().toJson()
                .put("networkBufferSizeKb", 4096)
                .put("wireGuardWorkers", 99)
                .put("wireGuardHandshakeTimeoutMs", 1)
                .put("wireGuardDomainStrategy", "invalid")
        )

        assertEquals(256, restored.networkBufferSizeKb)
        assertEquals(8, restored.wireGuardWorkers)
        assertEquals(5_000, restored.wireGuardHandshakeTimeoutMs)
        assertEquals(
            AppSettings.DEFAULT_WIREGUARD_DOMAIN_STRATEGY,
            restored.wireGuardDomainStrategy
        )
    }
}
