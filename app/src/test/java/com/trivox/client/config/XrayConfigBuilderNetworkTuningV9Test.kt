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

class XrayConfigBuilderNetworkTuningV9Test {
    @Test
    fun documentedSocketOptionsAreAddedWithoutOverwritingProviderValues() {
        val outbound = vlessOutbound()
        outbound.getJSONObject("streamSettings")
            .put(
                "sockopt",
                JSONObject().put("tcpKeepAliveIdle", 120)
            )
        val root = JSONObject(
            XrayConfigBuilder.build(
                profile(outbound, "vless"),
                AppSettings(
                    tcpFastOpen = true,
                    tcpKeepAliveIdleSeconds = 30,
                    tcpKeepAliveIntervalSeconds = 10,
                    tcpUserTimeoutMs = 18_000,
                    adaptiveHandshake = true
                ),
                ConnectionMode.PROXY
            )
        )
        val sockopt = root.getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("sockopt")

        assertEquals(120, sockopt.getInt("tcpKeepAliveIdle"))
        assertEquals(10, sockopt.getInt("tcpKeepAliveInterval"))
        assertEquals(18_000, sockopt.getInt("tcpUserTimeout"))
        assertTrue(sockopt.getBoolean("tcpFastOpen"))
        assertFalse(sockopt.has("domainStrategy"))
        assertFalse(sockopt.has("happyEyeballs"))
    }

    @Test
    fun newMethodAliasIsConvertedToCoreNetworkField() {
        val outbound = vlessOutbound()
        outbound.getJSONObject("streamSettings")
            .remove("network")
        outbound.getJSONObject("streamSettings")
            .put("method", "websocket")

        val root = JSONObject(
            XrayConfigBuilder.build(
                profile(outbound, "vless"),
                AppSettings(networkTuningEnabled = false),
                ConnectionMode.PROXY
            )
        )
        val stream = root.getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("streamSettings")
        assertEquals("websocket", stream.getString("network"))
        assertFalse(stream.has("method"))
    }

    @Test
    fun wireGuardExplicitZeroKeepAliveIsPreservedForIpv4OnlyProfile() {
        val outbound = JSONObject()
            .put("protocol", "wireguard")
            .put(
                "settings",
                JSONObject()
                    .put("secretKey", "private")
                    .put("address", JSONArray().put("10.0.0.2/32"))
                    .put(
                        "peers",
                        JSONArray().put(
                            JSONObject()
                                .put("publicKey", "public")
                                .put("endpoint", "example.com:51820")
                                .put("keepAlive", 0)
                        )
                    )
            )
        val root = JSONObject(
            XrayConfigBuilder.build(
                profile(outbound, "wireguard"),
                AppSettings(
                    wireGuardMtu = 1300,
                    wireGuardWorkers = 3,
                    wireGuardKeepAliveSeconds = 25,
                    wireGuardDomainStrategy = "ForceIPv4"
                ),
                ConnectionMode.VPN
            )
        )
        val settings = root.getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("settings")
        val peer = settings.getJSONArray("peers").getJSONObject(0)

        assertEquals(0, peer.getInt("keepAlive"))
        assertFalse(settings.has("workers"))
        assertEquals(1300, settings.getInt("mtu"))
        assertEquals("ForceIPv4", settings.getString("domainStrategy"))
        val allowedIps = peer.getJSONArray("allowedIPs")
        assertEquals(1, allowedIps.length())
        assertEquals("0.0.0.0/0", allowedIps.getString(0))
    }

    @Test
    fun hysteria2JsonAliasIsCanonicalizedWithoutDroppingAuth() {
        val outbound = JSONObject()
            .put("protocol", "hy2")
            .put(
                "settings",
                JSONObject()
                    .put("address", "example.com")
                    .put("port", 443)
            )
            .put(
                "streamSettings",
                JSONObject()
                    .put("method", "hysteria")
                    .put("security", "tls")
                    .put(
                        "hysteriaSettings",
                        JSONObject().put("auth", "secret")
                    )
            )

        val root = JSONObject(
            XrayConfigBuilder.build(
                profile(outbound, "hysteria"),
                AppSettings(),
                ConnectionMode.PROXY
            )
        )
        val normalized = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria", normalized.getString("protocol"))
        assertEquals(2, normalized.getJSONObject("settings").getInt("version"))
        val stream = normalized.getJSONObject("streamSettings")
        assertEquals("hysteria", stream.getString("network"))
        assertEquals(
            "secret",
            stream.getJSONObject("hysteriaSettings").getString("auth")
        )
    }


    @Test
    fun hysteria2PortableAliasesArePromotedToCanonicalFields() {
        val outbound = JSONObject()
            .put("protocol", "hysteria2")
            .put(
                "settings",
                JSONObject()
                    .put("server", "hy.example.com")
                    .put("server_port", "8443")
                    .put("password", "portable-secret")
                    .put("sni", "cdn.example.com")
                    .put("insecure", true)
            )

        val root = JSONObject(
            XrayConfigBuilder.build(
                profile(outbound, "hysteria"),
                AppSettings(),
                ConnectionMode.PROXY
            )
        )
        val normalized = root.getJSONArray("outbounds").getJSONObject(0)
        val settings = normalized.getJSONObject("settings")
        val stream = normalized.getJSONObject("streamSettings")

        assertEquals("hysteria", normalized.getString("protocol"))
        assertEquals("hy.example.com", settings.getString("address"))
        assertEquals(8443, settings.getInt("port"))
        assertEquals(
            "portable-secret",
            stream.getJSONObject("hysteriaSettings").getString("auth")
        )
        assertEquals(
            "cdn.example.com",
            stream.getJSONObject("tlsSettings").getString("serverName")
        )
        assertTrue(
            stream.getJSONObject("tlsSettings").getBoolean("allowInsecure")
        )
    }

    private fun vlessOutbound() = JSONObject()
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
                                    .put("id", "00000000-0000-0000-0000-000000000000")
                                    .put("encryption", "none")
                            )
                        )
                )
            )
        )
        .put("streamSettings", JSONObject().put("network", "tcp"))

    private fun profile(outbound: JSONObject, protocol: String) = ConfigProfile(
        name = "test",
        protocol = protocol,
        server = "example.com",
        port = if (protocol == "wireguard") 51820 else 443,
        raw = outbound.toString(),
        outboundJson = outbound.toString()
    )
}
