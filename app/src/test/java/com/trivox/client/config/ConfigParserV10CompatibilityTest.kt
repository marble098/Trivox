package com.trivox.client.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigParserV10CompatibilityTest {
    @Test(expected = ConfigParseException::class)
    fun rejectsRemovedLegacyH2TransportInsteadOfCreatingBrokenProfile() {
        ConfigParser.parseUri(
            "vless://id@example.com:443?type=h2&security=tls&host=cdn.example&path=%2Flegacy#H2"
        )
    }

    @Test(expected = ConfigParseException::class)
    fun rejectsRemovedLegacyQuicTransport() {
        ConfigParser.parseUri(
            "vmess://eyJ2IjoiMiIsInBzIjoiUSIsImFkZCI6ImV4YW1wbGUuY29tIiwicG9ydCI6IjQ0MyIsImlkIjoiZTdhOGI4YjQtYTc0NC00ZjkyLWJiNjctOGYyMGRiZTQzMzJmIiwibmV0IjoicXVpYyJ9"
        )
    }

    @Test fun importsWireGuardUriAlias() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val profile = ConfigParser.parseUri(
            "wireguard://$key@example.com:51820?publicKey=$key&address=10.0.0.2%2F32&allowedIPs=0.0.0.0%2F0#WG"
        )
        assertEquals("wireguard", profile.protocol)
        assertTrue(profile.outboundJson.contains("allowedIPs"))
    }
}
