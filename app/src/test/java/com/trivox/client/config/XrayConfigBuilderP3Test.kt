package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigBuilderP3Test {
    @Test
    fun fragmentUsesFreedomDialerAndBootstrapHosts() {
        val outbound = JSONObject()
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray()))
            .put("streamSettings", JSONObject().put("network", "ws").put("security", "tls"))
        val profile = ConfigProfile(
            name = "test",
            protocol = "vless",
            server = "edge.example.com",
            port = 443,
            raw = "",
            outboundJson = outbound.toString()
        )
        val root = JSONObject(
            XrayConfigBuilder.build(
                profile = profile,
                settings = AppSettings(fragmentEnabled = true, fragmentAuto = true),
                mode = ConnectionMode.PROXY,
                bootstrapIps = listOf("203.0.113.10")
            )
        )
        val outbounds = root.getJSONArray("outbounds")
        val proxy = (0 until outbounds.length())
            .map { outbounds.getJSONObject(it) }
            .first { it.optString("tag") == "proxy" }
        val sockopt = proxy.getJSONObject("streamSettings").getJSONObject("sockopt")
        assertEquals("fragment-out", sockopt.getString("dialerProxy"))
        assertFalse(sockopt.has("happyEyeballs"))
        assertTrue((0 until outbounds.length()).map { outbounds.getJSONObject(it).optString("tag") }.contains("fragment-out"))
        val hosts = root.getJSONObject("dns").getJSONObject("hosts")
        assertEquals("203.0.113.10", hosts.getString("full:edge.example.com"))
    }
}
