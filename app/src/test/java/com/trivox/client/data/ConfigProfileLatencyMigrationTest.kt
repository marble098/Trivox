package com.trivox.client.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigProfileLatencyMigrationTest {
    @Test
    fun migratesLegacyTcpWithoutInventingRealDelay() {
        val profile = ConfigProfile.fromJson(
            JSONObject()
                .put("name", "legacy")
                .put("protocol", "vless")
                .put("server", "example.com")
                .put("port", 443)
                .put("raw", "vless://legacy")
                .put("outboundJson", "{}")
                .put("latencyMs", 42)
                .put("latencyJitterMs", 3)
                .put("latencySuccessRatio", 1.0)
                .put("latencyMethod", PingMethod.TCP_CONNECT.name)
                .put("testStatus", TestStatus.ALIVE.name)
                .put("lastTestAt", 1234L)
        )

        assertEquals(42L, profile.tcpLatencyMs)
        assertEquals(TestStatus.ALIVE, profile.tcpTestStatus)
        assertNull(profile.realLatencyMs)
        assertEquals(TestStatus.UNTESTED, profile.realTestStatus)
    }

    @Test
    fun serializesTcpAndRealDelayIndependently() {
        val original = ConfigProfile(
            name = "dual",
            protocol = "vless",
            server = "example.com",
            port = 443,
            raw = "vless://dual",
            outboundJson = "{}",
            tcpLatencyMs = 28,
            tcpTestStatus = TestStatus.ALIVE,
            tcpLastTestAt = 100L,
            realLatencyMs = 91,
            realTestStatus = TestStatus.ALIVE,
            realLastTestAt = 200L
        )
        val restored = ConfigProfile.fromJson(original.toJson())

        assertEquals(28L, restored.tcpLatencyMs)
        assertEquals(91L, restored.realLatencyMs)
        assertEquals(100L, restored.tcpLastTestAt)
        assertEquals(200L, restored.realLastTestAt)
    }
}
