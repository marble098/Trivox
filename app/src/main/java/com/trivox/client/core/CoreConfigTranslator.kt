package com.trivox.client.core

import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.CoreId
import org.json.JSONArray
import org.json.JSONObject

object CoreConfigTranslator {
    fun build(request: CoreStartRequest, coreId: CoreId): String = when (coreId) {
        CoreId.XRAY -> XrayConfigBuilder.build(
            profile = request.profile,
            settings = request.settings,
            mode = request.mode,
            tunFd = request.tunFd,
            errorLogPath = com.trivox.client.util.Diagnostics.xrayErrorLogPath()
        )
        CoreId.SING_BOX -> buildSingBox(request.profile, request.settings.socksPort)
        CoreId.MIHOMO -> buildMihomo(request.profile, request.settings.socksPort)
    }

    private fun outbound(profile: ConfigProfile): JSONObject =
        runCatching { JSONObject(profile.outboundJson) }.getOrElse { JSONObject() }

    private fun buildSingBox(profile: ConfigProfile, mixedPort: Int): String {
        val ob = outbound(profile)
        val type = ob.optString("protocol", profile.protocol).lowercase()
        val settings = ob.optJSONObject("settings") ?: JSONObject()
        val stream = ob.optJSONObject("streamSettings") ?: JSONObject()
        val tls = stream.optJSONObject("tlsSettings") ?: stream.optJSONObject("realitySettings")
        val server = profile.probeServer.ifBlank { profile.server }
        val port = profile.probePort.takeIf { it > 0 } ?: profile.port
        val out = JSONObject()
            .put("type", type.replace("shadowsocks", "shadowsocks"))
            .put("tag", "proxy")
            .put("server", server)
            .put("server_port", port)
        when (type) {
            "vless", "vmess" -> {
                val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
                val user = vnext?.optJSONArray("users")?.optJSONObject(0)
                out.put("uuid", user?.optString("id", "") ?: "")
                if (type == "vmess") out.put("security", user?.optString("security", "auto") ?: "auto")
                out.put("tls", tls != null)
            }
            "trojan" -> {
                val user = settings.optJSONArray("servers")?.optJSONObject(0)
                out.put("password", user?.optString("password", "") ?: "")
                out.put("tls", true)
            }
            "shadowsocks" -> {
                val s = settings.optJSONArray("servers")?.optJSONObject(0)
                out.put("method", s?.optString("method", "") ?: "")
                out.put("password", s?.optString("password", "") ?: "")
            }
        }
        val transport = stream.optString("network").takeIf { it.isNotBlank() && it != "tcp" }
        if (transport != null) out.put("transport", JSONObject().put("type", transport))
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(JSONObject().put("type", "mixed").put("listen", "127.0.0.1").put("listen_port", mixedPort)))
            .put("outbounds", JSONArray().put(out).put(JSONObject().put("type", "direct").put("tag", "direct")))
            .toString()
    }

    private fun buildMihomo(profile: ConfigProfile, mixedPort: Int): String {
        val ob = outbound(profile)
        val type = ob.optString("protocol", profile.protocol).lowercase()
        val settings = ob.optJSONObject("settings") ?: JSONObject()
        val stream = ob.optJSONObject("streamSettings") ?: JSONObject()
        val server = profile.probeServer.ifBlank { profile.server }
        val port = profile.probePort.takeIf { it > 0 } ?: profile.port
        val proxy = JSONObject()
            .put("name", "proxy")
            .put("type", if (type == "shadowsocks") "ss" else type)
            .put("server", server)
            .put("port", port)
            .put("udp", true)
        when (type) {
            "vless", "vmess" -> {
                val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
                val user = vnext?.optJSONArray("users")?.optJSONObject(0)
                proxy.put("uuid", user?.optString("id", "") ?: "")
                proxy.put("tls", stream.has("tlsSettings") || stream.has("realitySettings"))
                proxy.put("network", stream.optString("network", "tcp"))
            }
            "trojan" -> proxy.put("password", settings.optJSONArray("servers")?.optJSONObject(0)?.optString("password", "") ?: "")
            "shadowsocks" -> {
                val s = settings.optJSONArray("servers")?.optJSONObject(0)
                proxy.put("cipher", s?.optString("method", "") ?: "")
                proxy.put("password", s?.optString("password", "") ?: "")
            }
        }
        return JSONObject()
            .put("mixed-port", mixedPort)
            .put("allow-lan", false)
            .put("mode", "rule")
            .put("log-level", "warning")
            .put("proxies", JSONArray().put(proxy))
            .put("proxy-groups", JSONArray().put(JSONObject().put("name", "Proxy").put("type", "select").put("proxies", JSONArray().put("proxy"))))
            .put("rules", JSONArray().put("MATCH,Proxy"))
            .toString()
    }
}
