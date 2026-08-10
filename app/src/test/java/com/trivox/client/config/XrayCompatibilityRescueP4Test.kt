package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Test

class XrayCompatibilityRescueP4Test {
    @Test
    fun smartDefaultsDoNotInventEndpointIpStrategy() {
        val outbound = JSONObject()
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray()))
            .put(
                "streamSettings",
                JSONObject()
                    .put("network", "ws")
                    .put("security", "tls")
            )
        val profile = ConfigProfile(
            name = "compat",
            protocol = "vless",
            server = "edge.example.com",
            port = 443,
            raw = "",
            outboundJson = outbound.toString()
        )

        val root = JSONObject(
            XrayConfigBuilder.build(
                profile = profile,
                settings = AppSettings(
                    networkTuningEnabled = true,
                    adaptiveHandshake = true,
                    smartConnectionOptimizer = true
                ),
                mode = ConnectionMode.PROXY,
                bootstrapIps = listOf("203.0.113.10")
            )
        )
        val values = root.getJSONArray("outbounds")
        val proxy = (0 until values.length())
            .map { values.getJSONObject(it) }
            .first { it.optString("tag") == "proxy" }
        val sockopt = proxy.getJSONObject("streamSettings")
            .optJSONObject("sockopt")

        if (sockopt != null) {
            assertFalse(sockopt.has("domainStrategy"))
            assertFalse(sockopt.has("happyEyeballs"))
        }
    }
}
