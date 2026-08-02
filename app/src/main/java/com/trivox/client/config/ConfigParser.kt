package com.trivox.client.config

import com.trivox.client.data.ConfigProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class ConfigParseException(message: String) : IllegalArgumentException(message)

object ConfigParser {
    private val supported = setOf("vless", "vmess", "trojan", "ss", "socks", "socks5", "http", "https")

    fun parseText(input: String): List<ConfigProfile> {
        val text = input.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return emptyList()
        if (text.startsWith("{")) return listOf(parseJson(text))

        val directLines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val candidates = if (directLines.any { "://" in it }) directLines else {
            val decoded = decodeBase64OrNull(text) ?: throw ConfigParseException("Input is neither a supported URI, Xray JSON, nor Base64 subscription content")
            decoded.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        }

        val unique = LinkedHashMap<String, ConfigProfile>()
        val errors = mutableListOf<String>()
        for (line in candidates) {
            if (line.startsWith("{") && line.endsWith("}")) {
                runCatching { parseJson(line) }.onSuccess { unique.putIfAbsent(normalizeRaw(it.raw), it) }
                    .onFailure { errors += it.message ?: "Invalid JSON" }
                continue
            }
            val scheme = line.substringBefore("://", "").lowercase()
            if (scheme.isBlank()) continue
            if (scheme !in supported) {
                errors += "Unsupported scheme '$scheme' for the Xray adapter: ${line.take(80)}"
                continue
            }
            runCatching { parseUri(line) }.onSuccess { unique.putIfAbsent(normalizeRaw(it.raw), it) }
                .onFailure { errors += (it.message ?: "Malformed $scheme URI") }
        }
        if (unique.isEmpty()) throw ConfigParseException(errors.joinToString("; ").ifBlank { "No supported configurations found" })
        return unique.values.toList()
    }

    fun decodeBase64OrNull(value: String): String? {
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.isBlank() || compact.length < 4) return null
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }
            .recoverCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull() ?: return null
        val decoded = bytes.toString(StandardCharsets.UTF_8)
        return decoded.takeIf { it.contains("://") || it.trimStart().startsWith("{") }
    }

    fun parseUri(raw: String): ConfigProfile = when (raw.substringBefore("://").lowercase()) {
        "vmess" -> parseVmess(raw)
        "vless" -> parseVless(raw)
        "trojan" -> parseTrojan(raw)
        "ss" -> parseShadowsocks(raw)
        "socks", "socks5" -> parseSocks(raw)
        "http", "https" -> parseHttp(raw)
        else -> throw ConfigParseException("Unsupported scheme in URI: ${raw.take(80)}")
    }

    fun parseJson(raw: String): ConfigProfile {
        val root = runCatching { JSONObject(raw) }.getOrElse { throw ConfigParseException("Invalid Xray JSON: ${it.message}") }
        val outbounds = root.optJSONArray("outbounds") ?: throw ConfigParseException("Xray JSON field 'outbounds' is missing")
        val outbound = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .firstOrNull { it.optString("protocol") !in setOf("freedom", "blackhole", "dns") }
            ?: throw ConfigParseException("Xray JSON has no usable proxy outbound")
        val protocol = outbound.optString("protocol", "unknown")
        val endpoint = endpointFromOutbound(outbound)
        return ConfigProfile(
            name = outbound.optString("tag", "Xray JSON"), protocol = protocol,
            server = endpoint.first, port = endpoint.second, raw = raw,
            outboundJson = JSONObject(outbound.toString()).put("tag", "proxy").toString(),
            originalDnsJson = root.optJSONObject("dns")?.toString()
        )
    }

    private fun parseVmess(raw: String): ConfigProfile {
        val encoded = raw.substringAfter("vmess://").substringBefore('#')
        val decoded = decodeAnyBase64(encoded) ?: throw ConfigParseException("VMess payload is not valid Base64")
        val json = runCatching { JSONObject(decoded) }.getOrElse { throw ConfigParseException("VMess payload is not valid JSON: ${it.message}") }
        val server = json.optString("add").ifBlank { throw ConfigParseException("VMess field 'add' is missing") }
        val port = parsePort(json.opt("port"), "VMess port")
        val id = json.optString("id").ifBlank { throw ConfigParseException("VMess field 'id' is missing") }
        val user = JSONObject().put("id", id).put("alterId", json.optInt("aid", 0)).put("security", json.optString("scy", "auto"))
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "vmess").put("settings",
            JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", server).put("port", port).put("users", JSONArray().put(user)))))
        val query = mutableMapOf<String, String>()
        listOf("net", "type", "host", "path", "tls", "sni", "alpn", "fp", "serviceName").forEach { key ->
            json.optString(key).takeIf(String::isNotBlank)?.let { query[key] = it }
        }
        outbound.put("streamSettings", streamSettings(query))
        return ConfigProfile(name = json.optString("ps", "$server:$port"), protocol = "vmess", server = server, port = port,
            raw = raw, outboundJson = outbound.toString())
    }

    private fun parseVless(raw: String): ConfigProfile {
        val p = parseStandardUri(raw, "VLESS")
        val id = p.userInfo.substringBefore(':').ifBlank { throw ConfigParseException("VLESS user ID is missing") }
        val user = JSONObject().put("id", id).put("encryption", p.query["encryption"] ?: "none")
        p.query["flow"]?.takeIf(String::isNotBlank)?.let { user.put("flow", it) }
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "vless").put("settings",
            JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", p.host).put("port", p.port).put("users", JSONArray().put(user)))))
            .put("streamSettings", streamSettings(p.query))
        p.query["packetEncoding"]?.let { outbound.getJSONObject("settings").put("packetEncoding", it) }
        return p.profile("vless", outbound, raw)
    }

    private fun parseTrojan(raw: String): ConfigProfile {
        val p = parseStandardUri(raw, "Trojan")
        val password = decode(p.userInfo).ifBlank { throw ConfigParseException("Trojan password is missing") }
        val server = JSONObject().put("address", p.host).put("port", p.port).put("password", password)
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", streamSettings(p.query))
        return p.profile("trojan", outbound, raw)
    }

    private fun parseShadowsocks(raw: String): ConfigProfile {
        val body = raw.substringAfter("ss://")
        val fragment = decode(body.substringAfter('#', ""))
        val noFragment = body.substringBefore('#').substringBefore('?')
        val decodedWhole = if ('@' !in noFragment) decodeAnyBase64(noFragment) ?: noFragment else noFragment
        val at = decodedWhole.lastIndexOf('@')
        val credentialPart: String
        val endpointPart: String
        if (at >= 0) {
            credentialPart = decodedWhole.substring(0, at)
            endpointPart = decodedWhole.substring(at + 1)
        } else {
            val rawAt = noFragment.lastIndexOf('@')
            if (rawAt < 0) throw ConfigParseException("Shadowsocks endpoint is missing")
            credentialPart = decodeAnyBase64(noFragment.substring(0, rawAt)) ?: noFragment.substring(0, rawAt)
            endpointPart = noFragment.substring(rawAt + 1)
        }
        val credential = decodeAnyBase64(credentialPart) ?: credentialPart
        val colon = credential.indexOf(':')
        if (colon <= 0) throw ConfigParseException("Shadowsocks method or password is missing")
        val method = credential.substring(0, colon)
        val password = credential.substring(colon + 1)
        val endpoint = parseHostPort(endpointPart, "Shadowsocks")
        val server = JSONObject().put("address", endpoint.first).put("port", endpoint.second)
            .put("method", method).put("password", password)
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        return ConfigProfile(name = fragment.ifBlank { "${endpoint.first}:${endpoint.second}" }, protocol = "shadowsocks",
            server = endpoint.first, port = endpoint.second, raw = raw, outboundJson = outbound.toString())
    }

    private fun parseSocks(raw: String): ConfigProfile {
        val p = parseStandardUri(raw, "SOCKS")
        val server = JSONObject().put("address", p.host).put("port", p.port)
        if (p.userInfo.isNotBlank()) {
            server.put("users", JSONArray().put(JSONObject().put("user", decode(p.userInfo.substringBefore(':')))
                .put("pass", decode(p.userInfo.substringAfter(':', "")))))
        }
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        return p.profile("socks", outbound, raw)
    }

    private fun parseHttp(raw: String): ConfigProfile {
        val p = parseStandardUri(raw, "HTTP")
        val server = JSONObject().put("address", p.host).put("port", p.port)
        if (p.userInfo.isNotBlank()) server.put("users", JSONArray().put(JSONObject()
            .put("user", decode(p.userInfo.substringBefore(':'))).put("pass", decode(p.userInfo.substringAfter(':', "")))))
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "http")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        if (raw.startsWith("https://")) outbound.put("streamSettings", JSONObject().put("security", "tls")
            .put("tlsSettings", JSONObject().put("serverName", p.host)))
        return p.profile("http", outbound, raw)
    }

    private fun streamSettings(query: Map<String, String>): JSONObject {
        val network = (query["type"] ?: query["net"] ?: "tcp").lowercase()
        val normalized = when (network) { "h2" -> "http"; "httpupgrade" -> "httpupgrade"; else -> network }
        val stream = JSONObject().put("network", normalized)
        when (normalized) {
            "ws" -> stream.put("wsSettings", JSONObject().put("path", query["path"] ?: "/").apply {
                query["host"]?.let { put("headers", JSONObject().put("Host", it)) }
            })
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", query["serviceName"] ?: query["path"] ?: "")
                .put("multiMode", query["mode"] == "multi"))
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().put("path", query["path"] ?: "/").apply {
                query["host"]?.let { put("host", it) }
            })
            "xhttp", "splithttp" -> stream.put("xhttpSettings", JSONObject().put("path", query["path"] ?: "/").apply {
                query["host"]?.let { put("host", it) }; query["mode"]?.let { put("mode", it) }; query["extra"]?.let { put("extra", decode(it)) }
            })
            "kcp", "mkcp" -> stream.put("kcpSettings", JSONObject().put("header", JSONObject().put("type", query["headerType"] ?: "none"))
                .apply { query["seed"]?.let { put("seed", it) } })
            "quic" -> stream.put("quicSettings", JSONObject().put("security", query["quicSecurity"] ?: "none")
                .put("key", query["key"] ?: "").put("header", JSONObject().put("type", query["headerType"] ?: "none")))
            "tcp" -> if (query["headerType"] == "http") stream.put("tcpSettings", JSONObject().put("header", JSONObject()
                .put("type", "http").put("request", JSONObject().put("path", JSONArray().put(query["path"] ?: "/")))))
        }
        val security = (query["security"] ?: query["tls"] ?: "none").lowercase()
        when (security) {
            "tls" -> stream.put("security", "tls").put("tlsSettings", tlsSettings(query))
            "reality" -> stream.put("security", "reality").put("realitySettings", JSONObject()
                .put("serverName", query["sni"] ?: query["serverName"] ?: "")
                .put("fingerprint", query["fp"] ?: "chrome")
                .put("publicKey", query["pbk"] ?: query["publicKey"] ?: "")
                .put("shortId", query["sid"] ?: query["shortId"] ?: "")
                .put("spiderX", query["spx"] ?: query["spiderX"] ?: ""))
        }
        query["sockopt"]?.let { runCatching { JSONObject(it) }.getOrNull()?.let { value -> stream.put("sockopt", value) } }
        return stream
    }

    private fun tlsSettings(query: Map<String, String>) = JSONObject()
        .put("serverName", query["sni"] ?: query["serverName"] ?: query["host"] ?: "")
        .put("allowInsecure", query["allowInsecure"]?.toBooleanStrictOrNull() ?: false)
        .apply {
            query["alpn"]?.split(',')?.filter(String::isNotBlank)?.let { put("alpn", JSONArray(it)) }
            query["fp"]?.let { put("fingerprint", it) }
        }

    private data class Parsed(val host: String, val port: Int, val userInfo: String, val name: String, val query: Map<String, String>) {
        fun profile(protocol: String, outbound: JSONObject, raw: String) = ConfigProfile(
            name = name.ifBlank { "$host:$port" }, protocol = protocol, server = host, port = port,
            raw = raw, outboundJson = outbound.toString()
        )
    }

    private fun parseStandardUri(raw: String, label: String): Parsed {
        val fragment = raw.substringAfter('#', "")
        val withoutFragment = raw.substringBefore('#')
        val uri = runCatching { URI(withoutFragment) }.getOrElse { throw ConfigParseException("Malformed $label URI: ${it.message}") }
        val host = (uri.host ?: run {
            val authority = uri.rawAuthority?.substringAfter('@') ?: throw ConfigParseException("$label server address is missing")
            parseHostPort(authority, label).first
        }).removeSurrounding("[", "]")
        val port = if (uri.port > 0) uri.port else throw ConfigParseException("$label port is missing or invalid")
        return Parsed(host, port, uri.rawUserInfo?.let(::decode) ?: "", decode(fragment), parseQuery(uri.rawQuery.orEmpty()))
    }

    private fun parseQuery(raw: String): Map<String, String> = raw.split('&').filter(String::isNotBlank).associate { part ->
        decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
    }

    private fun parseHostPort(value: String, label: String): Pair<String, Int> {
        val clean = value.substringBefore('?')
        val host: String
        val portText: String
        if (clean.startsWith('[')) {
            val end = clean.indexOf(']')
            if (end < 0) throw ConfigParseException("Malformed $label IPv6 address")
            host = clean.substring(1, end); portText = clean.substring(end + 1).removePrefix(":")
        } else {
            host = clean.substringBeforeLast(':', ""); portText = clean.substringAfterLast(':', "")
        }
        if (host.isBlank()) throw ConfigParseException("$label server address is missing")
        return host to parsePort(portText, "$label port")
    }

    private fun parsePort(value: Any?, field: String): Int {
        val port = value?.toString()?.toIntOrNull() ?: throw ConfigParseException("$field is missing or invalid")
        if (port !in 1..65535) throw ConfigParseException("$field must be between 1 and 65535")
        return port
    }

    private fun endpointFromOutbound(outbound: JSONObject): Pair<String, Int> {
        val settings = outbound.optJSONObject("settings") ?: return "" to 0
        val collection = settings.optJSONArray("vnext") ?: settings.optJSONArray("servers") ?: return "" to 0
        val server = collection.optJSONObject(0) ?: return "" to 0
        return server.optString("address") to server.optInt("port")
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
    private fun decodeAnyBase64(value: String): String? {
        val compact = value.filterNot(Char::isWhitespace)
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded).toString(StandardCharsets.UTF_8) }
            .recoverCatching { Base64.getUrlDecoder().decode(padded).toString(StandardCharsets.UTF_8) }.getOrNull()
    }
    private fun normalizeRaw(raw: String) = raw.trim()
}
