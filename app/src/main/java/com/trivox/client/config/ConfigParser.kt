package com.trivox.client.config

import com.trivox.client.data.ConfigProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

class ConfigParseException(message: String) : IllegalArgumentException(message)

object ConfigParser {
    private const val MAX_INPUT_CHARS = 768 * 1024
    private const val MAX_JSON_CHARS = 512 * 1024
    private const val MAX_LINE_CHARS = 96 * 1024
    private const val MAX_PROFILES = 2000
    private val uriLinePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val supportedSchemes = setOf(
        "vless", "vmess", "trojan", "ss", "shadowsocks",
        "socks", "socks5", "http", "https", "wg", "wireguard",
        "hy2", "hysteria2", "hysteria", "tuic"
    )
    private val nonProxyProtocols = setOf("freedom", "blackhole", "dns", "loopback")

    fun parseText(input: String): List<ConfigProfile> {
        if (input.length > MAX_INPUT_CHARS) {
            throw ConfigParseException("Config input exceeds ${MAX_INPUT_CHARS / 1024} KiB")
        }
        val text = input.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return emptyList()
        if (looksLikeWireGuardQuickConfig(text)) return listOf(parseWireGuardQuickConfig(text))
        if (text.startsWith("{")) return listOf(parseJson(text))

        val directCandidates = boundedLines(text).filter { line ->
            val candidate = line.trim()
            candidate.startsWith("{") || uriLinePattern.containsMatchIn(candidate)
        }
        val candidates = if (directCandidates.isNotEmpty()) {
            directCandidates
        } else {
            val decoded = decodeBase64OrNull(text) ?: throw ConfigParseException(
                "Input is neither a supported Xray URI, Xray JSON, WireGuard quick config, nor Base64 subscription"
            )
            if (looksLikeWireGuardQuickConfig(decoded)) return listOf(parseWireGuardQuickConfig(decoded))
            boundedLines(decoded).filter { line ->
                val candidate = line.trim()
                candidate.startsWith("{") || uriLinePattern.containsMatchIn(candidate)
            }
        }

        val unique = LinkedHashMap<String, ConfigProfile>()
        val errors = mutableListOf<String>()
        for (line in candidates) {
            val candidate = line.trim()
            if (candidate.isBlank()) continue
            if (candidate.startsWith("{") && candidate.endsWith("}")) {
                runCatching { parseJson(candidate) }
                    .onSuccess { unique.putIfAbsent(normalizeRaw(it.raw), it) }
                    .onFailure { addError(errors, it.message ?: "Invalid JSON") }
                continue
            }
            val scheme = candidate.substringBefore("://", "").lowercase(Locale.ROOT)
            if (scheme !in supportedSchemes) {
                addError(errors, "Unsupported scheme '$scheme' in Xray-only build")
                continue
            }
            runCatching { parseUri(candidate) }
                .onSuccess { unique.putIfAbsent(normalizeRaw(it.raw), it) }
                .onFailure { addError(errors, it.message ?: "Malformed $scheme URI") }
            if (unique.size > MAX_PROFILES) {
                throw ConfigParseException("Subscription contains more than $MAX_PROFILES unique profiles")
            }
        }
        if (unique.isEmpty()) {
            throw ConfigParseException(errors.joinToString("; ").ifBlank { "No supported configs found" })
        }
        return unique.values.toList()
    }

    fun decodeBase64OrNull(value: String): String? {
        if (value.length > MAX_INPUT_CHARS) return null
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.length < 4) return null
        val decoded = decodeAnyBase64(compact) ?: return null
        return decoded.takeIf {
            it.contains("://") || it.trimStart().startsWith("{") || looksLikeWireGuardQuickConfig(it)
        }
    }

    fun parseUri(raw: String): ConfigProfile {
        if (raw.length > MAX_LINE_CHARS) {
            throw ConfigParseException("Config URI exceeds ${MAX_LINE_CHARS / 1024} KiB")
        }
        return when (raw.substringBefore("://").lowercase(Locale.ROOT)) {
            "vmess" -> parseVmess(raw)
            "vless" -> parseVless(raw)
            "trojan" -> parseTrojan(raw)
            "ss", "shadowsocks" -> parseShadowsocks(raw)
            "socks", "socks5" -> parseSocks(raw)
            "http", "https" -> parseHttp(raw)
            "wg", "wireguard" -> parseWireGuardUri(raw)
            "hy2", "hysteria2", "hysteria" -> parseHysteria2(raw)
            "tuic" -> throw ConfigParseException("TUIC is not enabled in this Xray-only build; import an Xray JSON outbound if your core supports it")
            else -> throw ConfigParseException("Unsupported config scheme")
        }
    }

    fun parseJson(raw: String): ConfigProfile {
        if (raw.length > MAX_JSON_CHARS) {
            throw ConfigParseException("Xray JSON exceeds ${MAX_JSON_CHARS / 1024} KiB")
        }
        val root = runCatching { JSONObject(raw) }.getOrElse {
            throw ConfigParseException("Invalid Xray JSON: ${it.message}")
        }
        val outbounds = root.optJSONArray("outbounds") ?: throw ConfigParseException("Xray JSON field 'outbounds' is missing")
        val outbound = (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { canonicalProtocol(it.optString("protocol")) !in nonProxyProtocols }
            ?: throw ConfigParseException("Xray JSON has no usable proxy outbound")
        val normalized = JSONObject(outbound.toString()).put("tag", "proxy")
        val protocol = canonicalProtocol(normalized.optString("protocol", "unknown"))
        val endpoint = endpointFromOutbound(normalized)
        return ConfigProfile(
            name = normalized.optString("tag", "Xray JSON"),
            protocol = protocol,
            server = endpoint.first,
            port = endpoint.second,
            raw = raw,
            outboundJson = normalized.toString(),
            originalDnsJson = root.optJSONObject("dns")?.toString(),
            probeServer = endpoint.first,
            probePort = endpoint.second
        )
    }

    private fun parseVmess(raw: String): ConfigProfile {
        val encoded = raw.substringAfter("vmess://").substringBefore('#').substringBefore('?')
        val decoded = decodeAnyBase64(encoded) ?: throw ConfigParseException("VMess payload is not valid Base64")
        val json = runCatching { JSONObject(decoded) }.getOrElse {
            throw ConfigParseException("VMess payload is not valid JSON: ${it.message}")
        }
        val server = requiredText(json, "add", "VMess server")
        val port = parsePort(json.opt("port"), "VMess port")
        val user = JSONObject()
            .put("id", requiredText(json, "id", "VMess id"))
            .put("alterId", json.optInt("aid", 0))
            .put("security", json.optString("scy", "auto"))
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", server)
                .put("port", port)
                .put("users", JSONArray().put(user)))))
        val query = linkedMapOf<String, String>()
        listOf(
            "net", "type", "host", "path", "tls", "security", "sni", "alpn", "fp",
            "serviceName", "mode", "headerType", "seed", "quicSecurity", "key",
            "allowInsecure", "pbk", "sid", "spx", "packetEncoding"
        ).forEach { key -> json.optString(key).takeIf(String::isNotBlank)?.let { query[key] = it } }
        outbound.put("streamSettings", streamSettings(query, ""))
        return ConfigProfile(
            name = json.optString("ps", "$server:$port"),
            protocol = "vmess",
            server = server,
            port = port,
            raw = raw,
            outboundJson = outbound.toString()
        )
    }

    private fun parseVless(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "VLESS", 443)
        val id = decode(parsed.userInfo).substringBefore(':').ifBlank { throw ConfigParseException("VLESS user id is missing") }
        val user = JSONObject()
            .put("id", id)
            .put("encryption", parsed.query["encryption"] ?: "none")
        parsed.query["flow"]?.takeIf(String::isNotBlank)?.let { user.put("flow", it) }
        val settings = JSONObject().put("vnext", JSONArray().put(JSONObject()
            .put("address", parsed.host)
            .put("port", parsed.port)
            .put("users", JSONArray().put(user))))
        parsed.query["packetEncoding"]?.takeIf(String::isNotBlank)?.let { settings.put("packetEncoding", it) }
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", settings)
            .put("streamSettings", streamSettings(parsed.query, parsed.query["flow"].orEmpty()))
        return parsed.profile("vless", outbound, raw)
    }

    private fun parseTrojan(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "Trojan", 443)
        val password = decode(parsed.userInfo).ifBlank { throw ConfigParseException("Trojan password is missing") }
        val server = JSONObject().put("address", parsed.host).put("port", parsed.port).put("password", password)
        parsed.query["email"]?.takeIf(String::isNotBlank)?.let { server.put("email", it) }
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", streamSettings(parsed.query, ""))
        return parsed.profile("trojan", outbound, raw)
    }

    private fun parseShadowsocks(raw: String): ConfigProfile {
        val body = raw.substringAfter("://")
        val fragment = decode(body.substringAfter('#', ""))
        val clean = body.substringBefore('#').substringBefore('?')
        val decodedWhole = if ('@' !in clean) decodeAnyBase64(clean) ?: clean else clean
        val at = decodedWhole.lastIndexOf('@')
        if (at < 0) throw ConfigParseException("Shadowsocks endpoint is missing")
        val credential = decodeAnyBase64(decodedWhole.substring(0, at)) ?: decodedWhole.substring(0, at)
        val endpoint = parseHostPort(decodedWhole.substring(at + 1), "Shadowsocks", 8388)
        val colon = credential.indexOf(':')
        if (colon <= 0) throw ConfigParseException("Shadowsocks method or password is missing")
        val method = decode(credential.substring(0, colon))
        val password = decode(credential.substring(colon + 1))
        val server = JSONObject()
            .put("address", endpoint.first)
            .put("port", endpoint.second)
            .put("method", method)
            .put("password", Shadowsocks2022.normalizePassword(method, password))
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        return ConfigProfile(
            name = fragment.ifBlank { "${endpoint.first}:${endpoint.second}" },
            protocol = "shadowsocks",
            server = endpoint.first,
            port = endpoint.second,
            raw = raw,
            outboundJson = outbound.toString()
        )
    }

    private fun parseSocks(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "SOCKS", 1080)
        val server = JSONObject().put("address", parsed.host).put("port", parsed.port)
        addUserPassword(server, parsed.userInfo)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        return parsed.profile("socks", outbound, raw)
    }

    private fun parseHttp(raw: String): ConfigProfile {
        val secure = raw.startsWith("https://", ignoreCase = true)
        val parsed = parseStandardUri(raw, "HTTP", if (secure) 443 else 80)
        val server = JSONObject().put("address", parsed.host).put("port", parsed.port)
        addUserPassword(server, parsed.userInfo)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "http")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        if (secure) {
            val query = parsed.query.toMutableMap().apply {
                put("security", "tls")
                putIfAbsent("sni", parsed.host)
            }
            outbound.put("streamSettings", streamSettings(query, ""))
        }
        return parsed.profile("http", outbound, raw)
    }

    private fun parseHysteria2(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "Hysteria2", 443)
        val auth = decode(parsed.userInfo).substringBefore(':').ifBlank { throw ConfigParseException("Hysteria2 auth is missing") }
        val stream = JSONObject()
            .put("network", "hysteria")
            .put("security", "tls")
            .put("tlsSettings", tlsSettings(parsed.query, parsed.host))
            .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", auth).put("udpIdleTimeout", 60))
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "hysteria")
            .put("settings", JSONObject().put("version", 2).put("address", parsed.host).put("port", parsed.port))
            .put("streamSettings", stream)
        return parsed.profile("hysteria", outbound, raw)
    }

    private fun parseWireGuardUri(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "WireGuard", 51820)
        val privateKey = parsed.query["privateKey"] ?: parsed.query["secretKey"] ?: parsed.query["private_key"] ?: parsed.userInfo
        val publicKey = parsed.query["publicKey"] ?: parsed.query["public_key"] ?: ""
        if (privateKey.isBlank()) throw ConfigParseException("WireGuard private key is missing")
        if (publicKey.isBlank()) throw ConfigParseException("WireGuard public key is missing")
        val endpoint = "${parsed.host}:${parsed.port}"
        val peer = JSONObject()
            .put("endpoint", endpoint)
            .put("publicKey", publicKey)
            .put("allowedIPs", JSONArray((parsed.query["allowedIPs"] ?: "0.0.0.0/0,::/0").split(',').map(String::trim).filter(String::isNotBlank)))
        parsed.query["preSharedKey"]?.takeIf(String::isNotBlank)?.let { peer.put("preSharedKey", it) }
        val settings = JSONObject()
            .put("secretKey", privateKey)
            .put("address", JSONArray((parsed.query["address"] ?: "10.0.0.2/32").split(',').map(String::trim).filter(String::isNotBlank)))
            .put("noKernelTun", true)
            .put("mtu", parsed.query["mtu"]?.toIntOrNull()?.coerceIn(576, 9000) ?: 1420)
            .put("peers", JSONArray().put(peer))
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "wireguard").put("settings", settings)
        return parsed.profile("wireguard", outbound, raw)
    }

    private fun parseWireGuardQuickConfig(text: String): ConfigProfile {
        val map = linkedMapOf<String, MutableMap<String, String>>()
        var section = ""
        text.lineSequence().map(String::trim).forEach { line ->
            if (line.isBlank() || line.startsWith('#') || line.startsWith(';')) return@forEach
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.trim('[', ']').lowercase(Locale.ROOT)
                map.putIfAbsent(section, linkedMapOf())
                return@forEach
            }
            val key = line.substringBefore('=', "").trim()
            val value = line.substringAfter('=', "").trim()
            if (key.isNotBlank()) map.getOrPut(section) { linkedMapOf() }[key.lowercase(Locale.ROOT)] = value
        }
        val iface = map["interface"].orEmpty()
        val peerMap = map["peer"].orEmpty()
        val endpoint = peerMap["endpoint"].orEmpty()
        val endpointParts = parseHostPort(endpoint, "WireGuard endpoint", 51820)
        val privateKey = iface["privatekey"].orEmpty()
        val publicKey = peerMap["publickey"].orEmpty()
        if (privateKey.isBlank() || publicKey.isBlank()) throw ConfigParseException("WireGuard quick config is missing keys")
        val peer = JSONObject()
            .put("endpoint", "${endpointParts.first}:${endpointParts.second}")
            .put("publicKey", publicKey)
            .put("allowedIPs", JSONArray((peerMap["allowedips"] ?: "0.0.0.0/0,::/0").split(',').map(String::trim).filter(String::isNotBlank)))
        peerMap["presharedkey"]?.takeIf(String::isNotBlank)?.let { peer.put("preSharedKey", it) }
        val settings = JSONObject()
            .put("secretKey", privateKey)
            .put("address", JSONArray((iface["address"] ?: "10.0.0.2/32").split(',').map(String::trim).filter(String::isNotBlank)))
            .put("noKernelTun", true)
            .put("mtu", iface["mtu"]?.toIntOrNull()?.coerceIn(576, 9000) ?: 1420)
            .put("peers", JSONArray().put(peer))
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "wireguard").put("settings", settings)
        return ConfigProfile(
            name = endpoint.ifBlank { "WireGuard" },
            protocol = "wireguard",
            server = endpointParts.first,
            port = endpointParts.second,
            raw = text,
            outboundJson = outbound.toString()
        )
    }

    private fun looksLikeWireGuardQuickConfig(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("[interface]") && lower.contains("[peer]") && lower.contains("privatekey") && lower.contains("publickey")
    }

    private fun streamSettings(query: Map<String, String>, flow: String): JSONObject {
        val network = (query["type"] ?: query["net"] ?: query["network"] ?: "tcp").lowercase(Locale.ROOT)
            .let { if (it == "ws") "ws" else if (it == "httpupgrade") "httpupgrade" else it }
        val securityRaw = query["security"] ?: query["tls"].orEmpty()
        val security = when {
            securityRaw.equals("reality", true) -> "reality"
            securityRaw.equals("tls", true) || securityRaw.equals("1") || securityRaw.equals("true", true) -> "tls"
            else -> "none"
        }
        val stream = JSONObject().put("network", network)
        if (security != "none") {
            stream.put("security", security)
            if (security == "reality") stream.put("realitySettings", realitySettings(query))
            if (security == "tls") stream.put("tlsSettings", tlsSettings(query, query["host"].orEmpty()))
        }
        when (network) {
            "ws" -> stream.put("wsSettings", JSONObject()
                .put("path", query["path"] ?: "/")
                .put("headers", JSONObject().also { headers -> query["host"]?.takeIf(String::isNotBlank)?.let { headers.put("Host", it) } }))
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", query["serviceName"] ?: query["authority"] ?: ""))
            "xhttp", "splithttp" -> stream.put("xhttpSettings", JSONObject().put("path", query["path"] ?: "/").put("host", query["host"] ?: ""))
            "http" -> stream.put("httpSettings", JSONObject().put("path", JSONArray().put(query["path"] ?: "/")).put("host", JSONArray().put(query["host"] ?: "")))
            "kcp", "mkcp" -> stream.put("kcpSettings", JSONObject().put("header", JSONObject().put("type", query["headerType"] ?: "none")))
            "quic" -> stream.put("quicSettings", JSONObject().put("security", query["quicSecurity"] ?: "none").put("key", query["key"] ?: "").put("header", JSONObject().put("type", query["headerType"] ?: "none")))
        }
        query["spx"]?.takeIf(String::isNotBlank)?.let { stream.put("splider", it) }
        if (flow.contains("xtls", true) && security == "tls") stream.put("security", "tls")
        return stream
    }

    private fun tlsSettings(query: Map<String, String>, fallbackHost: String): JSONObject {
        val tls = JSONObject()
        (query["sni"] ?: query["peer"] ?: fallbackHost).takeIf(String::isNotBlank)?.let { tls.put("serverName", it) }
        query["fp"]?.takeIf(String::isNotBlank)?.let { tls.put("fingerprint", it) }
        query["allowInsecure"]?.toBooleanStrictOrNull()?.let { tls.put("allowInsecure", it) }
        query["alpn"]?.takeIf(String::isNotBlank)?.let { value ->
            tls.put("alpn", JSONArray(value.split(',', '|').map(String::trim).filter(String::isNotBlank)))
        }
        return tls
    }

    private fun realitySettings(query: Map<String, String>): JSONObject {
        val reality = JSONObject()
        query["sni"]?.takeIf(String::isNotBlank)?.let { reality.put("serverName", it) }
        query["fp"]?.takeIf(String::isNotBlank)?.let { reality.put("fingerprint", it) }
        query["pbk"]?.takeIf(String::isNotBlank)?.let { reality.put("publicKey", it) }
        query["sid"]?.takeIf(String::isNotBlank)?.let { reality.put("shortId", it) }
        query["spx"]?.takeIf(String::isNotBlank)?.let { reality.put("spiderX", it) }
        return reality
    }

    private fun parseStandardUri(raw: String, label: String, defaultPort: Int): ParsedUri {
        val body = raw.substringAfter("://")
        val fragment = decode(body.substringAfter('#', ""))
        val withoutFragment = body.substringBefore('#')
        val query = parseQuery(withoutFragment.substringAfter('?', ""))
        val authority = withoutFragment.substringBefore('?').substringBefore('/')
        val userInfo = if ('@' in authority) authority.substringBeforeLast('@') else ""
        val hostPort = if ('@' in authority) authority.substringAfterLast('@') else authority
        val endpoint = parseHostPort(hostPort, label, defaultPort)
        return ParsedUri(endpoint.first, endpoint.second, userInfo, query, fragment)
    }

    private data class ParsedUri(
        val host: String,
        val port: Int,
        val userInfo: String,
        val query: Map<String, String>,
        val fragment: String
    ) {
        fun profile(protocol: String, outbound: JSONObject, raw: String): ConfigProfile = ConfigProfile(
            name = fragment.ifBlank { "$host:$port" },
            protocol = protocol,
            server = host,
            port = port,
            raw = raw,
            outboundJson = outbound.toString()
        )
    }

    private fun endpointFromOutbound(outbound: JSONObject): Pair<String, Int> {
        val protocol = canonicalProtocol(outbound.optString("protocol"))
        val settings = outbound.optJSONObject("settings") ?: return "json" to 443
        return when (protocol) {
            "vless", "vmess" -> settings.optJSONArray("vnext")?.optJSONObject(0)?.let { it.optString("address", "json") to it.optInt("port", 443) } ?: ("json" to 443)
            "trojan", "shadowsocks", "socks", "http" -> settings.optJSONArray("servers")?.optJSONObject(0)?.let { it.optString("address", "json") to it.optInt("port", 443) } ?: ("json" to 443)
            "wireguard" -> settings.optJSONArray("peers")?.optJSONObject(0)?.optString("endpoint")?.takeIf(String::isNotBlank)?.let { parseHostPort(it, "WireGuard endpoint", 51820) } ?: ("wireguard" to 51820)
            "hysteria" -> settings.optString("address", "hysteria") to settings.optInt("port", 443)
            else -> "json" to 443
        }
    }

    private fun canonicalProtocol(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "ss" -> "shadowsocks"
        "socks5" -> "socks"
        "hy2", "hysteria2" -> "hysteria"
        "wg" -> "wireguard"
        else -> value.lowercase(Locale.ROOT)
    }

    private fun addUserPassword(server: JSONObject, userInfo: String) {
        if (userInfo.isBlank()) return
        val user = decode(userInfo).substringBefore(':')
        val pass = decode(userInfo).substringAfter(':', "")
        if (user.isNotBlank()) {
            server.put("users", JSONArray().put(JSONObject().put("user", user).put("pass", pass)))
        }
    }

    private fun parseHostPort(value: String, label: String, defaultPort: Int): Pair<String, Int> {
        val clean = value.trim().removePrefix("udp://").removePrefix("tcp://")
        if (clean.isBlank()) throw ConfigParseException("$label endpoint is missing")
        if (clean.startsWith("[")) {
            val close = clean.indexOf(']')
            if (close <= 0) throw ConfigParseException("$label IPv6 endpoint must use [address]:port")
            val host = clean.substring(1, close)
            val port = clean.substring(close + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return host to port.coercePort(label)
        }
        val lastColon = clean.lastIndexOf(':')
        val hasSingleColon = lastColon >= 0 && clean.indexOf(':') == lastColon
        val host = if (hasSingleColon) clean.substring(0, lastColon) else clean
        val port = if (hasSingleColon) clean.substring(lastColon + 1).toIntOrNull() ?: defaultPort else defaultPort
        if (host.isBlank()) throw ConfigParseException("$label host is missing")
        return host to port.coercePort(label)
    }

    private fun Int.coercePort(label: String): Int {
        if (this !in 1..65535) throw ConfigParseException("$label port must be between 1 and 65535")
        return this
    }

    private fun parsePort(value: Any?, label: String): Int {
        val port = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        } ?: throw ConfigParseException("$label is missing")
        return port.coercePort(label)
    }

    private fun requiredText(json: JSONObject, key: String, label: String): String =
        json.optString(key).trim().ifBlank { throw ConfigParseException("$label is missing") }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&')
            .mapNotNull { part ->
                if (part.isBlank()) null else {
                    val key = decode(part.substringBefore('=', part)).trim()
                    val value = decode(part.substringAfter('=', ""))
                    key.takeIf(String::isNotBlank)?.let { it to value }
                }
            }
            .toMap()
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun decodeAnyBase64(value: String): String? {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return listOf(Base64.getDecoder(), Base64.getUrlDecoder()).asSequence()
            .mapNotNull { decoder -> runCatching { String(decoder.decode(padded), StandardCharsets.UTF_8) }.getOrNull() }
            .firstOrNull { decoded -> decoded.isNotBlank() }
    }

    private fun boundedLines(text: String): List<String> = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .take(MAX_PROFILES + 50)
        .toList()

    private fun normalizeRaw(raw: String): String = raw.trim().removeSuffix("/")

    private fun addError(errors: MutableList<String>, message: String) {
        if (errors.size < 12 && message.isNotBlank()) errors.add(message)
    }
}
