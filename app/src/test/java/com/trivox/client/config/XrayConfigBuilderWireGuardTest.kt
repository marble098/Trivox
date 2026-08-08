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

class XrayConfigBuilderWireGuardTest {
    @Test
    fun wireGuardUsesIpv4SafeDnsReliableMtuAndNoForcedMixedInbound() {
        val outbound = JSONObject()
            .put("protocol", "wireguard")
            .put(
                "settings",
                JSONObject()
                    .put("secretKey", "private")
                    .put("address", JSONArray().put("10.5.0.2/32"))
                    .put("mtu", 1420)
                    .put(
                        "peers",
                        JSONArray().put(
                            JSONObject()
                                .put("publicKey", "public")
                                .put("endpoint", "198.51.100.10:51820")
                        )
                    )
            )
        val profile = ConfigProfile(
            name = "WireGuard test",
            protocol = "wireguard",
            server = "198.51.100.10",
            port = 51820,
            raw = outbound.toString(),
            outboundJson = outbound.toString()
        )
        val settings = AppSettings(
            mode = ConnectionMode.VPN,
            mtu = 1500,
            ipv6 = true,
            dnsMode = DnsMode.TRIVOX_DEFAULT,
            localProxyInVpn = false
        )

        val root = JSONObject(
            XrayConfigBuilder.build(profile, settings, ConnectionMode.VPN)
        )
        val inbounds = root.getJSONArray("inbounds")
        assertFalse((0 until inbounds.length()).any {
            inbounds.getJSONObject(it).optString("protocol") == "mixed"
        })

        val wireGuard = root.getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("settings")
        assertEquals(1360, wireGuard.getInt("mtu"))
        assertEquals(25, wireGuard.getJSONArray("peers").getJSONObject(0).getInt("keepAlive"))
        assertTrue(wireGuard.getBoolean("noKernelTun"))

        val dns = root.getJSONObject("dns")
        assertEquals("UseIPv4", dns.getString("queryStrategy"))
        assertEquals("1.1.1.1", dns.getJSONArray("servers").getString(0))
        assertEquals("8.8.8.8", dns.getJSONArray("servers").getString(1))
        assertTrue(dns.getBoolean("enableParallelQuery"))

        val firstRule = root.getJSONObject("routing")
            .getJSONArray("rules")
            .getJSONObject(0)
        assertEquals("53", firstRule.getString("port"))
        assertEquals("dns-out", firstRule.getString("outboundTag"))
    }

    @Test
    fun nordLynxUsesProviderDnsInsideTheWireGuardRoute() {
        val outbound = JSONObject()
            .put("protocol", "wireguard")
            .put(
                "settings",
                JSONObject()
                    .put("secretKey", "private")
                    .put("address", JSONArray().put("10.5.0.2/32"))
                    .put(
                        "peers",
                        JSONArray().put(
                            JSONObject()
                                .put("publicKey", "public")
                                .put("endpoint", "198.51.100.20:51820")
                                .put(
                                    "allowedIPs",
                                    JSONArray().put("0.0.0.0/0")
                                )
                        )
                    )
            )
        val profile = ConfigProfile(
            name = "NordVPN Emirates - Dubai",
            protocol = "wireguard",
            server = "198.51.100.20",
            port = 51820,
            raw = outbound.toString(),
            outboundJson = outbound.toString()
        )

        val root = JSONObject(
            XrayConfigBuilder.build(
                profile,
                AppSettings(ipv6 = true),
                ConnectionMode.PROXY
            )
        )
        val dns = root.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")

        assertEquals("UseIPv4", dns.getString("queryStrategy"))
        assertEquals("103.86.96.100", servers.getString(0))
        assertEquals("103.86.99.100", servers.getString(1))
        val dnsRoute = root.getJSONObject("routing")
            .getJSONArray("rules")
            .getJSONObject(1)
        assertEquals("proxy", dnsRoute.getString("outboundTag"))
    }
}
