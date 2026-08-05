package com.trivox.client.config

import com.trivox.client.core.CoreConfigTranslator
import com.trivox.client.core.CoreStartRequest
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.CoreId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MulticoreAndConverterTest {

    @Test
    fun testTuicUriParsing() {
        val tuicUri = "tuic://b5420959-1e35-4309-9069-4e7a8a07502c:mysecretpassword@1.2.3.4:8443?congestion_control=bbr&sni=example.com#TUIC-Node"
        val profiles = ConfigParser.parseText(tuicUri)
        assertEquals(1, profiles.size)
        val profile = profiles.first()
        assertEquals("tuic", profile.protocol)
        assertEquals("1.2.3.4", profile.server)
        assertEquals(8443, profile.port)
        assertEquals("TUIC-Node", profile.name)
    }

    @Test
    fun testSingBoxTranslationForHysteria2AndTuic() {
        val hy2Profile = ConfigProfile(
            name = "Hy2-Test",
            protocol = "hysteria2",
            server = "1.2.3.4",
            port = 443,
            raw = "hy2://secretpass@1.2.3.4:443?sni=hy2.example.com#Hy2-Test",
            outboundJson = JSONObject()
                .put("tag", "proxy")
                .put("protocol", "hysteria")
                .put("settings", JSONObject().put("address", "1.2.3.4").put("port", 443).put("auth", "secretpass"))
                .put("streamSettings", JSONObject().put("security", "tls").put("tlsSettings", JSONObject().put("serverName", "hy2.example.com")))
                .toString()
        )

        val request = CoreStartRequest(
            profile = hy2Profile,
            settings = AppSettings(socksPort = 10808),
            mode = ConnectionMode.PROXY
        )

        val singBoxJsonStr = CoreConfigTranslator.build(request, CoreId.SING_BOX)
        val json = JSONObject(singBoxJsonStr)
        val outbounds = json.getJSONArray("outbounds")
        val proxy = outbounds.getJSONObject(0)
        assertEquals("hysteria2", proxy.getString("type"))
        assertEquals("1.2.3.4", proxy.getString("server"))
        assertEquals(443, proxy.getInt("server_port"))
        assertEquals("secretpass", proxy.getString("password"))

        val mihomoYaml = CoreConfigTranslator.build(request, CoreId.MIHOMO)
        assertTrue(mihomoYaml.contains("type: 'hysteria2'"))
        assertTrue(mihomoYaml.contains("server: '1.2.3.4'"))
        assertTrue(mihomoYaml.contains("password: 'secretpass'"))
    }

    @Test
    fun testMihomoAndSingBoxImport() {
        val mihomoYaml = """
            proxies:
              - name: "Mihomo-Vless"
                type: vless
                server: 5.6.7.8
                port: 443
                uuid: 00000000-0000-0000-0000-000000000000
                network: ws
                tls: true
                servername: test.example.com
                ws-opts:
                  path: /vless
        """.trimIndent()

        val parsed = NativeConfigImporter.parseTextOrNull(mihomoYaml)
        assertNotNull(parsed)
        assertTrue(parsed!!.isNotEmpty())
        val profile = parsed.first()
        assertEquals("vless", profile.protocol)
        assertEquals("5.6.7.8", profile.server)
        assertEquals(443, profile.port)
        assertEquals("Mihomo-Vless", profile.name)

        val singBoxTranslated = CoreConfigTranslator.build(
            CoreStartRequest(profile, AppSettings(socksPort = 10808), ConnectionMode.PROXY),
            CoreId.SING_BOX
        )
        assertTrue(singBoxTranslated.contains("vless"))
        assertTrue(singBoxTranslated.contains("5.6.7.8"))
    }
}
