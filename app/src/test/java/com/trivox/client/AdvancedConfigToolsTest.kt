package com.trivox.client

import com.trivox.client.config.ConfigEditorCodec
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class AdvancedConfigToolsTest {
    @Test
    fun vmessNoneTransportIsNormalizedToTcp() {
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", "example.com")
                            .put("port", 443)
                            .put(
                                "users",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "00000000-0000-0000-0000-000000000001")
                                        .put("security", "auto")
                                )
                            )
                    )
                )
            )
            .put(
                "streamSettings",
                JSONObject().put("network", "none")
            )

        val profile = ConfigProfile(
            name = "VMess test",
            protocol = "vmess",
            server = "example.com",
            port = 443,
            raw = "vmess://test",
            outboundJson = outbound.toString()
        )

        val fields = ConfigEditorCodec.read(profile)
        assertEquals("tcp", fields.transport)

        val updated = ConfigEditorCodec.updateFromFields(profile, fields)
        val updatedNetwork = JSONObject(updated.outboundJson)
            .getJSONObject("streamSettings")
            .getString("network")
        assertEquals("tcp", updatedNetwork)

        val payload = updated.raw.substringAfter("vmess://")
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val shareJson = JSONObject(
            Base64.getDecoder().decode(padded)
                .toString(StandardCharsets.UTF_8)
        )
        assertEquals("tcp", shareJson.getString("net"))
    }

    @Test
    fun runtimeBuilderAlsoNormalizesLegacyNoneTransport() {
        val profile = ConfigProfile(
            name = "Legacy",
            protocol = "vmess",
            server = "example.com",
            port = 443,
            raw = "vmess://legacy",
            outboundJson = JSONObject()
                .put("protocol", "vmess")
                .put("settings", JSONObject())
                .put(
                    "streamSettings",
                    JSONObject().put("network", "none")
                )
                .toString()
        )

        val root = JSONObject(
            XrayConfigBuilder.build(
                profile,
                AppSettings(),
                ConnectionMode.PROXY
            )
        )

        val network = root
            .getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("streamSettings")
            .getString("network")

        assertEquals("tcp", network)
    }

    @Test
    fun pureOutboundJsonCanBeEdited() {
        val profile = ConfigProfile(
            name = "VLESS",
            protocol = "vless",
            server = "old.example",
            port = 443,
            raw = "vless://old",
            outboundJson = "{}"
        )

        val outbound = JSONObject()
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", "new.example")
                            .put("port", 8443)
                            .put(
                                "users",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "00000000-0000-0000-0000-000000000002")
                                        .put("encryption", "none")
                                )
                            )
                    )
                )
            )
            .put("streamSettings", JSONObject().put("network", "ws"))

        val updated = ConfigEditorCodec.updateFromJson(
            profile,
            outbound.toString()
        )

        assertEquals("new.example", updated.server)
        assertEquals(8443, updated.port)
        assertTrue(updated.raw.contains("outbounds"))
    }
}
