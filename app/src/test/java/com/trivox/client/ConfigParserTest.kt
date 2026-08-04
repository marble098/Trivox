package com.trivox.client

import com.trivox.client.config.ConfigParseException
import com.trivox.client.config.ConfigParser
import com.trivox.client.config.Validators
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.core.CoreManifest
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConnectionMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class ConfigParserTest {
    @Test fun decodesStandardBase64Subscription() {
        val content = "vless://id@example.com:443?security=tls#one"
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
        assertEquals("example.com", ConfigParser.parseText(encoded).single().server)
    }

    @Test fun decodesUrlSafeBase64WithoutPadding() {
        val content = "trojan://secret@example.org:443?security=tls#two"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(content.toByteArray())
        assertEquals("trojan", ConfigParser.parseText(encoded).single().protocol)
    }

    @Test fun importsVmess() {
        val json = """{"v":"2","ps":"VMess test","add":"vm.example","port":"443","id":"e7c8b8b4-a744-4f92-bb67-8f20dbe4332f","aid":"0","net":"ws","host":"cdn.example","path":"/ws","tls":"tls"}"""
        val link = "vmess://" + Base64.getEncoder().withoutPadding()
            .encodeToString(json.toByteArray())
        val profile = ConfigParser.parseUri(link)
        assertEquals("vm.example", profile.server)
        assertEquals(
            "ws",
            JSONObject(profile.outboundJson)
                .getJSONObject("streamSettings").getString("network")
        )
    }

    @Test fun importsVlessReality() {
        val profile = ConfigParser.parseUri(
            "vless://user-id@[2001:db8::1]:8443?type=grpc&security=reality&sni=example.com&pbk=public&sid=01#Reality"
        )
        assertEquals("2001:db8::1", profile.server)
        assertEquals(8443, profile.port)
        assertEquals(
            "reality",
            JSONObject(profile.outboundJson)
                .getJSONObject("streamSettings").getString("security")
        )
    }

    @Test fun importsXhttpExtraAsJsonObject() {
        val extra = URLEncoder.encode(
            """{"noGRPCHeader":true,"xmux":{"maxConcurrency":"16-32"}}""",
            StandardCharsets.UTF_8.name()
        )
        val profile = ConfigParser.parseUri(
            "vless://user-id@example.com:443?type=xhttp&security=tls&sni=example.com&path=%2Fxhttp&extra=$extra#XHTTP"
        )
        val xhttp = JSONObject(profile.outboundJson)
            .getJSONObject("streamSettings").getJSONObject("xhttpSettings")
        assertTrue(xhttp.get("extra") is JSONObject)
        assertTrue(xhttp.getJSONObject("extra").getBoolean("noGRPCHeader"))
    }

    @Test fun rejectsMalformedXhttpExtra() {
        val error = assertThrows(ConfigParseException::class.java) {
            ConfigParser.parseUri(
                "vless://user-id@example.com:443?type=xhttp&extra=not-json#XHTTP"
            )
        }
        assertTrue(error.message!!.contains("extra"))
    }

    @Test fun importsTrojan() {
        val profile = ConfigParser.parseUri(
            "trojan://p%40ss@example.com:443?security=tls&sni=example.com#Trojan"
        )
        assertEquals("trojan", profile.protocol)
        assertEquals(
            "p@ss",
            JSONObject(profile.outboundJson).getJSONObject("settings")
                .getJSONArray("servers").getJSONObject(0).getString("password")
        )
    }

    @Test fun importsShadowsocks() {
        val credentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:password".toByteArray())
        val profile = ConfigParser.parseUri(
            "ss://$credentials@example.com:8388#SS"
        )
        assertEquals("shadowsocks", profile.protocol)
        assertEquals(8388, profile.port)
    }

    @Test fun removesOnlyExactDuplicates() {
        val first = "vless://a@one.example:443?security=tls#same"
        val second = "vless://b@two.example:443?security=tls#same"
        assertEquals(2, ConfigParser.parseText("$first\n$first\n$second").size)
    }

    @Test fun rejectsMalformedLinkWithFieldName() {
        val error = assertThrows(ConfigParseException::class.java) {
            ConfigParser.parseText("vless://id@example.com#missing-port")
        }
        assertTrue(error.message!!.contains("port", ignoreCase = true))
    }

    @Test fun importsHysteria2Uri() {
        val profile = ConfigParser.parseText(
            "hysteria2://secret@example.com:443?sni=example.com#HY2"
        ).single()
        assertEquals("hysteria", profile.protocol)
        val outbound = JSONObject(profile.outboundJson)
        assertEquals(
            "hysteria",
            outbound.getJSONObject("streamSettings").getString("network")
        )
        assertEquals(
            "secret",
            outbound.getJSONObject("streamSettings")
                .getJSONObject("hysteriaSettings").getString("auth")
        )
    }

    @Test fun parsesMixedSubscriptionText() {
        val text = "\n  vless://id@example.com:443?security=tls#A\n\ntrojan://pw@example.org:443?security=tls#B\n"
        assertEquals(
            setOf("vless", "trojan"),
            ConfigParser.parseText(text).map { it.protocol }.toSet()
        )
    }

    @Test fun generatesXrayProxyAndVpnJson() {
        val profile = ConfigParser.parseUri(
            "vless://id@example.com:443?security=tls&sni=example.com#A"
        )
        val proxy = JSONObject(
            XrayConfigBuilder.build(profile, AppSettings(), ConnectionMode.PROXY)
        )
        val mixed = proxy.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("mixed", mixed.getString("protocol"))
        assertEquals("mixed-in", mixed.getString("tag"))
        assertEquals("127.0.0.1", mixed.getString("listen"))
        assertEquals(10202, mixed.getInt("port"))
        assertTrue(mixed.getJSONObject("settings").getBoolean("udp"))
        assertFalse(proxy.toString().contains("geoip:"))
        assertTrue(proxy.toString().contains("10.0.0.0/8"))

        val vpn = JSONObject(
            XrayConfigBuilder.build(
                profile,
                AppSettings(),
                ConnectionMode.VPN,
                42
            )
        )
        assertEquals(
            "tun",
            vpn.getJSONArray("inbounds").getJSONObject(0).getString("protocol")
        )
        assertEquals("42", vpn.getJSONObject("env").getString("xray.tun.fd"))
    }

    @Test fun validatesDnsAndPorts() {
        assertTrue(Validators.validPort(65535))
        assertFalse(Validators.validPort(0))
        assertTrue(Validators.validateDns("https://1.1.1.1/dns-query"))
        assertTrue(Validators.validateDns("2001:4860:4860::8888"))
        assertFalse(Validators.validateDns("bad dns value"))
    }

    @Test fun parsesCoreManifest() {
        val manifest = CoreManifest.parse(
            """{"name":"Xray-core","adapter":"libXray","version":"v26.7.28","artifact":"libXray.aar","sha256":"abc","sourceUrl":"official","buildDate":"now","abis":["arm64-v8a"],"capabilities":["proxy"],"prepared":true}"""
        )
        assertTrue(manifest.prepared)
        assertEquals(listOf("arm64-v8a"), manifest.abis)
    }
}
