package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class XrayFragmentPolicyP3Test {
    private fun profile(protocol: String, network: String, security: String) = ConfigProfile(
        name = "test",
        protocol = protocol,
        server = "example.com",
        port = 443,
        raw = "",
        outboundJson = JSONObject()
            .put("protocol", protocol)
            .put("streamSettings", JSONObject().put("network", network).put("security", security))
            .toString()
    )

    @Test
    fun autoFragmentUsesTlsHelloForWebsocketTls() {
        val plan = XrayFragmentPolicy.resolve(
            profile("vless", "ws", "tls"),
            AppSettings(fragmentEnabled = true, fragmentAuto = true)
        )
        assertNotNull(plan)
        assertEquals("tlshello", plan!!.packets)
    }

    @Test
    fun autoFragmentSkipsUdpFirstAndWireguard() {
        val settings = AppSettings(fragmentEnabled = true, fragmentAuto = true)
        assertNull(XrayFragmentPolicy.resolve(profile("hysteria", "hysteria", "tls"), settings))
        assertNull(XrayFragmentPolicy.resolve(profile("wireguard", "tcp", "none"), settings))
    }
}
