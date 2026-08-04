package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class XrayConfigBuilderWireGuardV7Test {
    @Test
    fun doesNotRaiseImportedMtuAndRemovesUnsupportedStreamSettings() {
        val outbound = JSONObject()
            .put("protocol", "wireguard")
            .put(
                "settings",
                JSONObject()
                    .put("secretKey", "key")
                    .put("address", JSONArray().put("10.0.0.2/32"))
                    .put("mtu", 1180)
                    .put(
                        "peers",
                        JSONArray().put(
                            JSONObject()
                                .put("publicKey", "peer")
                                .put("endpoint", "udp://example.com:51820")
                        )
                    )
            )
            .put("streamSettings", JSONObject().put("network", "tcp"))
        val profile = ConfigProfile(
            name = "WG",
            protocol = "wireguard",
            server = "example.com",
            port = 51820,
            raw = "{}",
            outboundJson = outbound.toString()
        )

        val root = JSONObject(
            XrayConfigBuilder.build(
                profile,
                AppSettings(mtu = 1500),
                ConnectionMode.PROXY
            )
        )
        val normalized = root.getJSONArray("outbounds").getJSONObject(0)
        assertFalse(normalized.has("streamSettings"))
        assertEquals(1180, normalized.getJSONObject("settings").getInt("mtu"))
        assertEquals(
            "example.com:51820",
            normalized.getJSONObject("settings")
                .getJSONArray("peers")
                .getJSONObject(0)
                .getString("endpoint")
        )
    }
}
