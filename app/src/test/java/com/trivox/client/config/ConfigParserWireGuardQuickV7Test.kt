package com.trivox.client.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigParserWireGuardQuickV7Test {
    @Test
    fun parsesStandardQuickConfigAndPreservesLowMtu() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val profile = ConfigParser.parseText(
            """
            [Interface]
            PrivateKey = $key
            Address = 10.7.0.2/32
            DNS = 1.1.1.1
            MTU = 1180

            [Peer]
            PublicKey = $key
            PresharedKey = $key
            AllowedIPs = 0.0.0.0/0
            Endpoint = frankfurt.example:51820
            PersistentKeepalive = 25
            """.trimIndent()
        ).single()

        assertEquals("wireguard", profile.protocol)
        assertEquals("frankfurt.example", profile.server)
        assertEquals(51820, profile.port)
        val settings = JSONObject(profile.outboundJson).getJSONObject("settings")
        assertEquals(1180, settings.getInt("mtu"))
        assertEquals(25, settings.getJSONArray("peers").getJSONObject(0).getInt("keepAlive"))
        assertTrue(profile.originalDnsJson.orEmpty().contains("1.1.1.1"))
    }

    @Test(expected = ConfigParseException::class)
    fun rejectsAmneziaOnlyFieldsInsteadOfCreatingBrokenXrayProfile() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        ConfigParser.parseText(
            """
            [Interface]
            PrivateKey = $key
            Address = 10.7.0.2/32
            Jc = 4

            [Peer]
            PublicKey = $key
            Endpoint = example.com:51820
            """.trimIndent()
        )
    }
}
