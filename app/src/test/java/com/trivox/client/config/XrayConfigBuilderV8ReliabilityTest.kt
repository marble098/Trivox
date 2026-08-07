package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.DnsMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigBuilderV8ReliabilityTest {
    @Test
    fun builtInDnsTrafficIsRoutedThroughSelectedOutbound() {
        val root = JSONObject(
            XrayConfigBuilder.build(
                profile = vlessProfile(),
                settings = AppSettings(
                    mode = ConnectionMode.VPN,
                    dnsMode = DnsMode.TRIVOX_DEFAULT
                ),
                mode = ConnectionMode.VPN
            )
        )

        assertEquals("dns-in", root.getJSONObject("dns").getString("tag"))
        assertTrue(
            root.getJSONObject("routing")
                .getJSONArray("rules")
                .objects()
                .any {
                    it.optJSONArray("inboundTag")
                        ?.strings()
                        ?.contains("dns-in") == true &&
                        it.optString("outboundTag") == "proxy"
                }
        )
    }

    @Test
    fun wireGuardAliasesAndPrivateDnsAreNormalized() {
        val wireGuard = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "wireguard")
            .put(
                "settings",
                JSONObject()
                    .put("privateKey", "private-key")
                    .put("address", "10.2.0.2")
                    .put("mtu", 1420)
                    .put(
                        "peers",
                        JSONArray().put(
                            JSONObject()
                                .put("public_key", "public-key")
                                .put("preshared_key", "psk")
                                .put("endpoint", "udp://198.51.100.10:51820")
                                .put("allowed_ips", "0.0.0.0/0, ::/0")
                        )
                    )
            )
            .put("streamSettings", JSONObject.NULL)

        val profile = ConfigProfile(
            name = "WG",
            protocol = "wireguard",
            server = "198.51.100.10",
            port = 51820,
            raw = "{}",
            outboundJson = wireGuard.toString(),
            originalDnsJson = JSONObject()
                .put("servers", JSONArray().put("10.2.0.1"))
                .toString()
        )
        val root = JSONObject(
            XrayConfigBuilder.build(
                profile = profile,
                settings = AppSettings(
                    mode = ConnectionMode.VPN,
                    mtu = 1500,
                    dnsMode = DnsMode.IMPORTED
                ),
                mode = ConnectionMode.VPN
            )
        )

        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        val settings = outbound.getJSONObject("settings")
        val peer = settings.getJSONArray("peers").getJSONObject(0)

        assertFalse(outbound.has("streamSettings"))
        assertEquals("private-key", settings.getString("secretKey"))
        assertTrue(settings.getBoolean("noKernelTun"))
        assertTrue(settings.getInt("mtu") <= 1360)
        assertEquals("198.51.100.10:51820", peer.getString("endpoint"))
        assertEquals("public-key", peer.getString("publicKey"))
        assertEquals("psk", peer.getString("preSharedKey"))
        assertEquals(25, peer.getInt("keepAlive"))
        assertEquals(2, peer.getJSONArray("allowedIPs").length())
        assertEquals("dns-in", root.getJSONObject("dns").getString("tag"))
    }

    private fun vlessProfile() = ConfigProfile(
        name = "Test",
        protocol = "vless",
        server = "example.com",
        port = 443,
        raw = "vless://test",
        outboundJson = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
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
                                        .put(
                                            "id",
                                            "00000000-0000-0000-0000-000000000000"
                                        )
                                        .put("encryption", "none")
                                )
                            )
                    )
                )
            )
            .toString()
    )

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull(::optJSONObject)

    private fun JSONArray.strings(): List<String> =
        (0 until length()).mapNotNull {
            optString(it).takeIf(String::isNotBlank)
        }
}
