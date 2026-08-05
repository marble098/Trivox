package com.trivox.client.config

import com.trivox.client.data.ConfigProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

object NativeConfigImporter {
    fun parseTextOrNull(input: String): List<ConfigProfile>? {
        val text = input.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return emptyList()
        return when {
            text.startsWith("{") -> parseSingBoxJsonOrNull(text)
            text.contains("proxies:") || text.contains("proxy-providers:") || text.startsWith("mixed-port:") -> parseMihomoYaml(text)
            else -> null
        }
    }

    private fun parseSingBoxJsonOrNull(text: String): List<ConfigProfile>? = runCatching {
        val root = JSONObject(text)
        val outs = root.optJSONArray("outbounds") ?: return null
        val result = mutableListOf<ConfigProfile>()
        for (i in 0 until outs.length()) {
            val out = outs.optJSONObject(i) ?: continue
            val type = out.optString("type", "").lowercase(Locale.ROOT)
            if (type in setOf("direct", "block", "dns", "selector", "urltest")) continue
            singBoxOutboundToProfile(out, text)?.let(result::add)
        }
        result.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun singBoxOutboundToProfile(out: JSONObject, rawText: String): ConfigProfile? {
        val type = out.optString("type", "").lowercase(Locale.ROOT)
        val server = out.optString("server", "")
        val port = out.optInt("server_port", 0)
        if (server.isBlank() || port <= 0) return null
        val protocol = if (type == "ss") "shadowsocks" else type
        val xray = JSONObject().put("tag", "proxy").put("protocol", protocol)
        val settings = JSONObject()
        when (protocol) {
            "vless", "vmess" -> {
                val user = JSONObject().put("id", out.optString("uuid", ""))
                if (protocol == "vless") user.put("encryption", "none")
                if (protocol == "vmess") {
                    user.put("security", out.optString("security", "auto"))
                    user.put("alterId", out.optInt("alter_id", 0))
                }
                out.optString("flow", "").takeIf(String::isNotBlank)?.let { user.put("flow", it) }
                settings.put("vnext", JSONArray().put(JSONObject().put("address", server).put("port", port).put("users", JSONArray().put(user))))
            }
            "trojan" -> settings.put("servers", JSONArray().put(JSONObject().put("address", server).put("port", port).put("password", out.optString("password", ""))))
            "shadowsocks" -> settings.put("servers", JSONArray().put(JSONObject().put("address", server).put("port", port).put("method", out.optString("method", "")).put("password", out.optString("password", ""))))
            else -> return null
        }
        xray.put("settings", settings)
        val stream = JSONObject().put("network", transportName(out.optJSONObject("transport")))
        out.optJSONObject("tls")?.let { tls ->
            stream.put("security", if (tls.optJSONObject("reality")?.optBoolean("enabled") == true) "reality" else "tls")
            val tlsSettings = JSONObject()
            tls.optString("server_name", "").takeIf(String::isNotBlank)?.let { tlsSettings.put("serverName", it) }
            tls.optJSONObject("reality")?.let { r ->
                r.optString("public_key", "").takeIf(String::isNotBlank)?.let { tlsSettings.put("publicKey", it) }
                r.optString("short_id", "").takeIf(String::isNotBlank)?.let { tlsSettings.put("shortId", it) }
            }
            stream.put(if (stream.optString("security") == "reality") "realitySettings" else "tlsSettings", tlsSettings)
        }
        xray.put("streamSettings", stream)
        return ConfigProfile(
            id = UUID.randomUUID().toString(),
            name = out.optString("tag", "$server:$port"),
            protocol = protocol,
            server = server,
            port = port,
            raw = rawText,
            outboundJson = xray.toString(),
            probeServer = server,
            probePort = port
        )
    }

    private fun transportName(transport: JSONObject?): String = when (transport?.optString("type", "tcp")?.lowercase(Locale.ROOT) ?: "tcp") {
        "ws", "websocket" -> "ws"
        "grpc" -> "grpc"
        "httpupgrade" -> "httpupgrade"
        "http" -> "http"
        else -> "tcp"
    }

    private fun parseMihomoYaml(text: String): List<ConfigProfile>? {
        val proxies = mutableListOf<MutableMap<String, String>>()
        var inProxies = false
        var current: MutableMap<String, String>? = null
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed == "proxies:") {
                inProxies = true
                return@forEach
            }
            if (!inProxies) return@forEach
            if (trimmed.endsWith(":") && !trimmed.startsWith("-") && !trimmed.contains(": ")) {
                inProxies = false
                return@forEach
            }
            if (trimmed.startsWith("- ")) {
                current = linkedMapOf<String, String>().also { proxies += it }
                parseYamlPair(trimmed.removePrefix("- "))?.let { (k, v) -> current?.put(k, v) }
            } else if (current != null) {
                parseYamlPair(trimmed)?.let { (k, v) -> current?.put(k, v) }
            }
        }
        val profiles = proxies.mapNotNull { mihomoProxyToProfile(it, text) }
        return profiles.takeIf { it.isNotEmpty() }
    }

    private fun parseYamlPair(value: String): Pair<String, String>? {
        val idx = value.indexOf(':')
        if (idx <= 0) return null
        val key = value.substring(0, idx).trim()
        var v = value.substring(idx + 1).trim()
        if ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\""))) v = v.substring(1, v.length - 1)
        v = v.replace("''", "'")
        return key to v
    }

    private fun mihomoProxyToProfile(map: Map<String, String>, rawText: String): ConfigProfile? {
        val type = map["type"]?.lowercase(Locale.ROOT) ?: return null
        val server = map["server"].orEmpty()
        val port = map["port"]?.toIntOrNull() ?: return null
        if (server.isBlank()) return null
        val protocol = if (type == "ss") "shadowsocks" else type
        val xray = JSONObject().put("tag", "proxy").put("protocol", protocol)
        val settings = JSONObject()
        when (protocol) {
            "vless", "vmess" -> {
                val user = JSONObject().put("id", map["uuid"].orEmpty())
                if (protocol == "vless") user.put("encryption", map["encryption"] ?: "none")
                if (protocol == "vmess") {
                    user.put("security", map["cipher"] ?: "auto")
                    user.put("alterId", map["alterId"]?.toIntOrNull() ?: 0)
                }
                map["flow"]?.takeIf(String::isNotBlank)?.let { user.put("flow", it) }
                settings.put("vnext", JSONArray().put(JSONObject().put("address", server).put("port", port).put("users", JSONArray().put(user))))
            }
            "trojan" -> settings.put("servers", JSONArray().put(JSONObject().put("address", server).put("port", port).put("password", map["password"].orEmpty())))
            "shadowsocks" -> settings.put("servers", JSONArray().put(JSONObject().put("address", server).put("port", port).put("method", map["cipher"].orEmpty()).put("password", map["password"].orEmpty())))
            else -> return null
        }
        xray.put("settings", settings)
        val stream = JSONObject().put("network", map["network"] ?: "tcp")
        if (map["tls"] == "true") {
            stream.put("security", "tls")
            val tls = JSONObject()
            map["servername"]?.takeIf(String::isNotBlank)?.let { tls.put("serverName", it) }
            stream.put("tlsSettings", tls)
        }
        xray.put("streamSettings", stream)
        val name = map["name"]?.takeIf(String::isNotBlank) ?: "$server:$port"
        return ConfigProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = protocol,
            server = server,
            port = port,
            raw = rawText,
            outboundJson = xray.toString(),
            probeServer = server,
            probePort = port
        )
    }
}
