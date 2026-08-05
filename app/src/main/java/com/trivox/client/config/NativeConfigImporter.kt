package com.trivox.client.config

import com.trivox.client.data.ConfigProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

object NativeConfigImporter {

    fun nativeInstallSubscriptionUrl(input: String): String? {
        val text = input.trim().removePrefix("\uFEFF")
        val lower = text.lowercase(Locale.ROOT)
        if (!(lower.startsWith("clash://") || lower.startsWith("clashmeta://") || lower.startsWith("mihomo://") || lower.startsWith("sing-box://"))) {
            return null
        }
        val query = text.substringAfter('?', "")
        if (query.isBlank()) return null
        val pairs = query.split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index) to urlDecode(part.substring(index + 1))
        }.toMap()
        return (pairs["url"] ?: pairs["link"] ?: pairs["subscription"] ?: pairs["sub"] ?: pairs["config_url"])
            ?.trim()
            ?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
    }
    fun parseTextOrNull(input: String): List<ConfigProfile>? {
        val text = input.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return emptyList()
        decodeNativeInstallUrl(text)?.let { decoded ->
            parseTextOrNull(decoded)?.let { return it }
        }
        return when {
            text.startsWith("{") -> parseJsonNative(text)
            looksLikeMihomoYaml(text) -> parseMihomoYaml(text)
            else -> null
        }
    }

    private fun looksLikeMihomoYaml(text: String): Boolean =
        text.contains("proxies:") ||
            text.contains("proxy-providers:") ||
            (Regex("(?m)^\\s*-").containsMatchIn(text) && text.contains("type:") && text.contains("server:"))

    private fun decodeNativeInstallUrl(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        if (!(lower.startsWith("clash://") || lower.startsWith("clashmeta://") || lower.startsWith("mihomo://") || lower.startsWith("sing-box://"))) {
            return null
        }
        val query = text.substringAfter('?', "")
        if (query.isBlank()) return null
        val pairs = query.split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index) to urlDecode(part.substring(index + 1))
        }.toMap()
        return pairs["config"]?.takeIf { it.startsWith("{") || it.contains("proxies:") }
            ?: pairs["content"]?.takeIf { it.startsWith("{") || it.contains("proxies:") }
            ?: pairs["data"]?.takeIf { it.startsWith("{") || it.contains("proxies:") }
    }

    private fun parseJsonNative(text: String): List<ConfigProfile>? = runCatching {
        val root = JSONObject(text)
        when {
            root.has("outbounds") -> parseSingBoxJson(root)
            root.has("proxies") -> parseMihomoJson(root)
            root.has("type") && root.has("server") -> singBoxOutboundToProfile(root)?.let(::listOf)
            else -> null
        }
    }.getOrNull()

    private fun parseSingBoxJson(root: JSONObject): List<ConfigProfile>? {
        val outs = root.optJSONArray("outbounds") ?: return null
        val result = mutableListOf<ConfigProfile>()
        for (i in 0 until outs.length()) {
            val out = outs.optJSONObject(i) ?: continue
            val type = out.optString("type", "").lowercase(Locale.ROOT)
            if (type in setOf("direct", "block", "dns", "selector", "urltest")) continue
            singBoxOutboundToProfile(out)?.let(result::add)
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun parseMihomoJson(root: JSONObject): List<ConfigProfile>? {
        val proxies = root.optJSONArray("proxies") ?: return null
        val result = mutableListOf<ConfigProfile>()
        for (i in 0 until proxies.length()) {
            val proxy = proxies.optJSONObject(i) ?: continue
            mihomoProxyToProfile(jsonToStringMap(proxy))?.let(result::add)
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun singBoxOutboundToProfile(out: JSONObject): ConfigProfile? {
        val type = out.optString("type", "").lowercase(Locale.ROOT)
        val server = out.optString("server", "")
        val port = out.optInt("server_port", 0)
        if (server.isBlank() || port <= 0) return null
        val protocol = when (type) {
            "ss" -> "shadowsocks"
            "shadowsocks" -> "shadowsocks"
            else -> type
        }
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
        xray.put("streamSettings", singBoxStreamSettings(out, server))
        return ConfigProfile(
            id = UUID.randomUUID().toString(),
            name = out.optString("tag", "$server:$port"),
            protocol = protocol,
            server = server,
            port = port,
            raw = out.toString(),
            outboundJson = xray.toString(),
            probeServer = server,
            probePort = port
        )
    }

    private fun singBoxStreamSettings(out: JSONObject, server: String): JSONObject {
        val stream = JSONObject().put("network", transportName(out.optJSONObject("transport")))
        out.optJSONObject("tls")?.let { tls ->
            val isReality = tls.optJSONObject("reality")?.optBoolean("enabled") == true
            stream.put("security", if (isReality) "reality" else "tls")
            val tlsSettings = JSONObject()
            tls.optString("server_name", server).takeIf(String::isNotBlank)?.let { tlsSettings.put("serverName", it) }
            tls.optJSONObject("utls")?.optString("fingerprint", "")?.takeIf(String::isNotBlank)?.let { tlsSettings.put("fingerprint", it) }
            tls.optJSONObject("reality")?.let { r ->
                r.optString("public_key", "").takeIf(String::isNotBlank)?.let { tlsSettings.put("publicKey", it) }
                r.optString("short_id", "").takeIf(String::isNotBlank)?.let { tlsSettings.put("shortId", it) }
            }
            stream.put(if (isReality) "realitySettings" else "tlsSettings", tlsSettings)
        }
        return stream
    }

    private fun transportName(transport: JSONObject?): String = when (transport?.optString("type", "tcp")?.lowercase(Locale.ROOT) ?: "tcp") {
        "ws", "websocket" -> "ws"
        "grpc" -> "grpc"
        "httpupgrade" -> "httpupgrade"
        "http" -> "http"
        "h2" -> "h2"
        "xhttp", "splithttp" -> "xhttp"
        else -> "tcp"
    }

    private fun parseMihomoYaml(text: String): List<ConfigProfile>? {
        val proxies = mutableListOf<MutableMap<String, String>>()
        var inProxies = false
        var current: MutableMap<String, String>? = null
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEach
            if (trimmed == "proxies:") {
                inProxies = true
                return@forEach
            }
            if (!inProxies && (trimmed.startsWith("- {") || (trimmed.startsWith("-") && trimmed.contains("type:") && trimmed.contains("server:")))) {
                inProxies = true
            }
            if (!inProxies) return@forEach
            if (trimmed.endsWith(":") && !trimmed.startsWith("-")) {
                inProxies = false
                current = null
                return@forEach
            }
            if (trimmed.startsWith("- {") && trimmed.endsWith("}")) {
                proxies += parseInlineYamlMap(trimmed.removePrefix("- ")).toMutableMap()
                current = null
                return@forEach
            }
            if (trimmed.startsWith("- ")) {
                current = linkedMapOf<String, String>().also { proxies += it }
                parseYamlPair(trimmed.removePrefix("- "))?.let { (k, v) -> current?.put(k, v) }
            } else {
                parseYamlPair(trimmed)?.let { (k, v) -> current?.put(k, v) }
            }
        }
        val profiles = proxies.mapNotNull { mihomoProxyToProfile(it) }
        return profiles.takeIf { it.isNotEmpty() }
    }

    private fun parseInlineYamlMap(value: String): Map<String, String> {
        val body = value.trim().removePrefix("{").removeSuffix("}")
        val map = linkedMapOf<String, String>()
        body.splitRespectingQuotes(',').forEach { item ->
            parseYamlPair(item.trim())?.let { (k, v) -> map[k] = v }
        }
        return map
    }

    private fun parseYamlPair(value: String): Pair<String, String>? {
        val idx = value.indexOf(':')
        if (idx <= 0) return null
        val key = value.substring(0, idx).trim()
        var v = value.substring(idx + 1).trim()
        if ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\""))) {
            v = v.substring(1, v.length - 1)
        }
        return key to v
    }

    private fun mihomoProxyToProfile(map: Map<String, String>): ConfigProfile? {
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
        xray.put("streamSettings", mihomoStreamSettings(map))
        val name = map["name"]?.takeIf(String::isNotBlank) ?: "$server:$port"
        return ConfigProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = protocol,
            server = server,
            port = port,
            raw = map.toString(),
            outboundJson = xray.toString(),
            probeServer = server,
            probePort = port
        )
    }

    private fun mihomoStreamSettings(map: Map<String, String>): JSONObject {
        val network = map["network"] ?: "tcp"
        val stream = JSONObject().put("network", network)
        if (map["tls"] == "true" || map["reality-opts"].orEmpty().isNotBlank()) {
            stream.put("security", if (map["reality-opts"].orEmpty().isNotBlank()) "reality" else "tls")
            val tls = JSONObject()
            map["servername"]?.takeIf(String::isNotBlank)?.let { tls.put("serverName", it) }
            map["client-fingerprint"]?.takeIf(String::isNotBlank)?.let { tls.put("fingerprint", it) }
            stream.put(if (stream.optString("security") == "reality") "realitySettings" else "tlsSettings", tls)
        }
        return stream
    }

    private fun jsonToStringMap(obj: JSONObject): Map<String, String> {
        val result = linkedMapOf<String, String>()
        obj.keys().forEach { key -> result[key] = obj.opt(key)?.toString().orEmpty() }
        return result
    }

    private fun String.splitRespectingQuotes(delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        forEach { ch ->
            when {
                quote != null -> {
                    sb.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    sb.append(ch)
                }
                ch == delimiter -> {
                    out += sb.toString()
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        out += sb.toString()
        return out
    }

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
