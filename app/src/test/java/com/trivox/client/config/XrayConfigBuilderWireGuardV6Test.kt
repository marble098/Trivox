package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class XrayConfigBuilderWireGuardV6Test {
    @Test
    fun normalizesPortableWireGuardFieldsToDocumentedStrategy() {
        val outbound = JSONObject()
            .put("protocol", "wireguard")
            .put(
                "settings",
                JSONObject()
                    .put("secretKey", "private")
                    .put("address", JSONArray().put("10.5.0.2"))
                    .put("domainStrategy", "AsIs")
                    .put(
                        "peers",
                        JSONArray().put(
                            JSONObject()
                                .put("publicKey", "public")
                                .put("endpoint", "udp://example.com:51820")
                        )
                    )
            )
        val profile = ConfigProfile(
            name = "wg",
            protocol = "wireguard",
            server = "example.com",
            port = 51820,
            raw = outbound.toString(),
            outboundJson = outbound.toString()
        )

        val built = JSONObject(
            XrayConfigBuilder.build(profile, AppSettings(), ConnectionMode.PROXY)
        )
        val settings = built.getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("settings")

        assertEquals("10.5.0.2/32", settings.getJSONArray("address").getString(0))
        assertEquals("ForceIPv4", settings.getString("domainStrategy"))
        assertFalse(settings.has("workers"))
        assertEquals(
            "example.com:51820",
            settings.getJSONArray("peers").getJSONObject(0).getString("endpoint")
        )
        assertEquals(25, settings.getJSONArray("peers").getJSONObject(0).getInt("keepAlive"))
    }
}
