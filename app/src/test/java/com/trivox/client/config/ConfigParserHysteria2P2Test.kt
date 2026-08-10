package com.trivox.client.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigParserHysteria2P2Test {
    @Test
    fun importsStandardHysteria2SalamanderShareLink() {
        val link = "hysteria2://demo-auth@178.255.222.102:30895" +
            "?security=tls&obfs=salamander&alpn=h3" +
            "&obfs-password=demo-obfs-password&insecure=0#Netherlands"
        val profile = ConfigParser.parseUri(link)
        val outbound = JSONObject(profile.outboundJson)
        assertEquals("hysteria", profile.protocol)
        assertEquals("hysteria", outbound.getString("protocol"))
        assertEquals(2, outbound.getJSONObject("settings").getInt("version"))
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("hysteria", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
        assertEquals("h3", stream.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
        val mask = stream.getJSONObject("finalmask").getJSONArray("udp").getJSONObject(0)
        assertEquals("salamander", mask.getString("type"))
        assertEquals("demo-obfs-password", mask.getJSONObject("settings").getString("password"))
        assertTrue(stream.getJSONObject("hysteriaSettings").getString("auth").isNotBlank())
    }
}
