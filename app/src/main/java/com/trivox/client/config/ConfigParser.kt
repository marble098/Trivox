package com.trivox.client.config

import com.trivox.client.data.ConfigProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

class ConfigParseException(message: String) : IllegalArgumentException(message)

/**
 * Bounded parser for URI subscriptions, standard wg-quick files and complete
 * Xray JSON. URI aliases are converted to Xray 26.7.28 canonical objects while
 * unsupported removed transports fail explicitly instead of producing a profile
 * that appears valid but can never connect.
 */
object ConfigParser {
    private val supported = setOf(
        "vless", "vmess", "trojan", "ss", "shadowsocks",
        "socks", "socks5", "http", "https",
        "hy2", "hysteria2", "hysteria",
        "wg", "wireguard", "ssh", "openssh", "nordwhisper"
    )

    fun parseText(input: String): List<ConfigProfile> {
        if (input.length > MAX_INPUT_CHARS) {
            throw ConfigParseException(
                "Config input exceeds the ${MAX_INPUT_CHARS / 1024} KiB safety limit"
            )
        }
        val text = input.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return emptyList()
        NativeConfigImporter.parseTextOrNull(text)?.let { return it }
        NativeConfigImporter.parseTextOrNull(text)?.let { native ->
            if (native.isNotEmpty()) return native
        }
        if (looksLikeWireGuardQuickConfig(text)) {
            return listOf(parseWireGuardQuickConfig(text))
        }
        if (text.startsWith("{")) return listOf(parseJson(text))

        val directLines = boundedLines(text)
        val candidates = if (directLines.any { "://" in it || it.startsWith("{") }) {
            directLines
        } else {
            val decoded = decodeBase64OrNull(text) ?: throw ConfigParseException(
                "Input is neither a supported URI, Xray JSON, wg-quick, nor Base64 subscription content"
            )
            if (looksLikeWireGuardQuickConfig(decoded)) {
                return listOf(parseWireGuardQuickConfig(decoded))
            }
            boundedLines(decoded)
        }

        val unique = LinkedHashMap<String, ConfigProfile>()
        val errors = mutableListOf<String>()
        for (line in candidates) {
            if (line.startsWith("{") && line.endsWith("}")) {
                runCatching { parseJson(line) }
                    .onSuccess { unique.putIfAbsent(normalizeRaw(it.raw), it) }
                    .onFailure { addError(errors, it.message ?: "Invalid JSON") }
                continue
            }
            val scheme = line.substringBefore("://", "").lowercase(Locale.ROOT)
            if (scheme.isBlank()) continue
            if (scheme !in supported) {
                addError(errors, "Unsupported scheme '$scheme' for Xray 26.7.28")
                continue
            }
            runCatching { parseUri(line) }
                .onSuccess { unique.putIfAbsent(normalizeRaw(it.raw), it) }
                .onFailure { addError(errors, it.message ?: "Malformed $scheme URI") }
            if (unique.size > MAX_PROFILES) {
                throw ConfigParseException(
                    "Subscription contains more than $MAX_PROFILES unique profiles"
                )
            }
        }
        if (unique.isEmpty()) {
            throw ConfigParseException(
                errors.joinToString("; ").ifBlank { "No supported configs found" }
            )
        }
        return unique.values.toList()
    }

    fun decodeBase64OrNull(value: String): String? {
        if (value.length > MAX_INPUT_CHARS) return null
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.length < 4) return null
        val decoded = decodeAnyBase64(compact) ?: return null
        return decoded.takeIf {
            it.contains("://") ||
                it.trimStart().startsWith("{") ||
                looksLikeWireGuardQuickConfig(it)
        }
    }

    fun parseUri(raw: String): ConfigProfile {
        if (raw.length > MAX_LINE_CHARS) {
            throw ConfigParseException(
                "Config URI exceeds the ${MAX_LINE_CHARS / 1024} KiB safety limit"
            )
        }
        return when (raw.substringBefore("://").lowercase(Locale.ROOT)) {
            "vmess" -> parseVmess(raw)
            "vless" -> parseVless(raw)
            "trojan" -> parseTrojan(raw)
            "ss", "shadowsocks" -> parseShadowsocks(raw)
            "socks", "socks5" -> parseSocks(raw)
            "http", "https" -> parseHttp(raw)
            "hy2", "hysteria2", "hysteria" -> parseHysteria2(raw)
            "wg", "wireguard" -> parseWireGuardUri(raw)
            "ssh", "openssh" -> OpenSshProfileCodec.parse(raw)
            "nordwhisper" -> NordWhisperCompatibility.reject(raw)
            else -> throw ConfigParseException("Unsupported config scheme")
        }
    }

    fun parseJson(raw: String): ConfigProfile {
        if (raw.length > MAX_JSON_CHARS) {
            throw ConfigParseException(
                "Xray JSON exceeds the ${MAX_JSON_CHARS / 1024} KiB safety limit"
            )
        }
        val root = runCatching { JSONObject(raw) }.getOrElse {
            throw ConfigParseException("Invalid Xray JSON: ${it.message}")
        }
        val outbounds = root.optJSONArray("outbounds") ?: throw ConfigParseException(
            "Xray JSON field 'outbounds' is missing"
        )
        val outbound = (0 until outbounds.length())
            .mapNotNull(outbounds::optJSONObject)
            .firstOrNull {
                canonicalProtocol(it.optString("protocol")) !in NON_PROXY_PROTOCOLS
            } ?: throw ConfigParseException("Xray JSON has no usable proxy outbound")

        val normalized = runCatching {
            XrayCompatibility.normalizeOutbound(outbound)
        }.getOrElse {
            throw ConfigParseException("Unsupported Xray JSON: ${it.message}")
        }
        validateCoreCompatibility(normalized)
        val protocol = normalized.optString("protocol", "unknown")
        val endpoint = endpointFromOutbound(normalized)
        return ConfigProfile(
            name = normalized.optString("tag", "Xray JSON"),
            protocol = protocol,
            server = endpoint.first,
            port = endpoint.second,
            raw = raw,
            outboundJson = JSONObject(normalized.toString()).put("tag", "proxy").toString(),
            originalDnsJson = root.optJSONObject("dns")?.toString(),
            probeServer = endpoint.first,
            probePort = when (protocol) {
                "wireguard", "hysteria" -> 443
                else -> endpoint.second
            }
        )
    }

    private fun parseVmess(raw: String): ConfigProfile {
        val encoded = raw.substringAfter("vmess://").substringBefore('#')
        val decoded = decodeAnyBase64(encoded) ?: throw ConfigParseException(
            "VMess payload is not valid Base64"
        )
        val json = runCatching { JSONObject(decoded) }.getOrElse {
            throw ConfigParseException("VMess payload is not valid JSON: ${it.message}")
        }
        val server = requiredText(json, "add", "VMess field 'add'")
        val port = parsePort(json.opt("port"), "VMess port")
        val id = requiredText(json, "id", "VMess field 'id'")
        val user = JSONObject()
            .put("id", id)
            .put("alterId", json.optInt("aid", 0))
            .put("security", json.optString("scy", "auto"))
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", server)
                            .put("port", port)
                            .put("users", JSONArray().put(user))
                    )
                )
            )
        val query = linkedMapOf<String, String>()
        listOf(
            "net", "type", "host", "path", "tls", "security", "sni",
            "alpn", "fp", "serviceName", "mode", "extra", "headerType",
            "seed", "quicSecurity", "key", "allowInsecure", "pbk", "sid",
            "spx", "packetEncoding"
        ).forEach { key ->
            json.optString(key).takeIf(String::isNotBlank)?.let { query[key] = it }
        }
        outbound.put("streamSettings", streamSettings(query, flow = ""))
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
        val parsed = parseStandardUri(raw, "VLESS")
        val id = parsed.userInfo.substringBefore(':').ifBlank {
            throw ConfigParseException("VLESS user ID is missing")
        }
        val flow = parsed.query["flow"].orEmpty()
        val user = JSONObject()
            .put("id", id)
            .put("encryption", parsed.query["encryption"] ?: "none")
        flow.takeIf(String::isNotBlank)?.let { user.put("flow", it) }
        val settings = JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", parsed.host)
                    .put("port", parsed.port)
                    .put("users", JSONArray().put(user))
            )
        )
        parsed.query["packetEncoding"]?.takeIf(String::isNotBlank)
            ?.let { settings.put("packetEncoding", it) }
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", settings)
            .put("streamSettings", streamSettings(parsed.query, flow))
        return parsed.profile("vless", outbound, raw)
    }

    private fun parseTrojan(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "Trojan")
        val password = decode(parsed.userInfo).ifBlank {
            throw ConfigParseException("Trojan password is missing")
        }
        val server = JSONObject()
            .put("address", parsed.host)
            .put("port", parsed.port)
            .put("password", password)
        parsed.query["email"]?.takeIf(String::isNotBlank)?.let { server.put("email", it) }
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", streamSettings(parsed.query, flow = ""))
        return parsed.profile("trojan", outbound, raw)
    }

    private fun parseShadowsocks(raw: String): ConfigProfile {
        val body = raw.substringAfter("://")
        val fragment = decode(body.substringAfter('#', ""))
        val query = parseQuery(
            body.substringBefore('#').substringAfter('?', "")
        )
        query["plugin"]?.takeIf(String::isNotBlank)?.let {
            throw ConfigParseException(
                "Shadowsocks SIP003 plugin '$it' is not an Xray-core outbound feature"
            )
        }
        val noFragment = body.substringBefore('#').substringBefore('?')
        val decodedWhole = if ('@' !in noFragment) {
            decodeAnyBase64(noFragment) ?: noFragment
        } else {
            noFragment
        }
        val at = decodedWhole.lastIndexOf('@')
        val credentialPart: String
        val endpointPart: String
        if (at >= 0) {
            credentialPart = decodedWhole.substring(0, at)
            endpointPart = decodedWhole.substring(at + 1)
        } else {
            val rawAt = noFragment.lastIndexOf('@')
            if (rawAt < 0) throw ConfigParseException("Shadowsocks endpoint is missing")
            credentialPart = decodeAnyBase64(noFragment.substring(0, rawAt))
                ?: noFragment.substring(0, rawAt)
            endpointPart = noFragment.substring(rawAt + 1)
        }
        val credential = decodeAnyBase64(credentialPart) ?: credentialPart
        val colon = credential.indexOf(':')
        if (colon <= 0) {
            throw ConfigParseException("Shadowsocks method or password is missing")
        }
        val method = credential.substring(0, colon)
        val password = credential.substring(colon + 1)
        val endpoint = parseHostPort(endpointPart, "Shadowsocks")
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
        val parsed = parseStandardUri(raw, "SOCKS")
        val server = JSONObject()
            .put("address", parsed.host)
            .put("port", parsed.port)
        addUserPassword(server, parsed.userInfo)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        return parsed.profile("socks", outbound, raw)
    }

    private fun parseHttp(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "HTTP")
        val server = JSONObject()
            .put("address", parsed.host)
            .put("port", parsed.port)
        addUserPassword(server, parsed.userInfo)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "http")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        if (raw.startsWith("https://", ignoreCase = true)) {
            val query = parsed.query.toMutableMap().apply {
                put("security", "tls")
                putIfAbsent("sni", parsed.host)
            }
            outbound.put("streamSettings", streamSettings(query, flow = ""))
        }
        return parsed.profile("http", outbound, raw)
    }

    private fun parseHysteria2(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "Hysteria2")
        val auth = decode(parsed.userInfo).substringBefore(':').ifBlank {
            throw ConfigParseException("Hysteria2 authentication password is missing")
        }
        val obfs = parsed.query["obfs"].orEmpty()
        if (obfs.isNotBlank() && !obfs.equals("none", true)) {
            throw ConfigParseException(
                "Hysteria2 obfs '$obfs' cannot be represented by Xray 26.7.28 URI conversion; import complete Xray JSON instead"
            )
        }
        val hysteria = JSONObject()
            .put("version", 2)
            .put("auth", auth)
            .put(
                "udpIdleTimeout",
                parsed.query["udpIdleTimeout"]?.toIntOrNull()?.coerceIn(2, 600) ?: 60
            )
        val stream = JSONObject()
            .put("network", "hysteria")
            .put("method", "hysteria")
            .put("security", "tls")
            .put("hysteriaSettings", hysteria)
            .put("tlsSettings", tlsSettings(parsed.query, parsed.host))
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "hysteria")
            .put(
                "settings",
                JSONObject()
                    .put("version", 2)
                    .put("address", parsed.host)
                    .put("port", parsed.port)
            )
            .put("streamSettings", stream)
        return parsed.profile("hysteria", outbound, raw)
    }

    private fun parseWireGuardUri(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "WireGuard")
        val secretKey = decode(parsed.userInfo).substringBefore(':').ifBlank {
            parsed.query["privateKey"].orEmpty()
        }
        val publicKey = parsed.query["publicKey"]
            ?: parsed.query["public_key"]
            ?: throw ConfigParseException("WireGuard publicKey is missing")
        validateWireGuardKey(secretKey, "WireGuard privateKey")
        validateWireGuardKey(publicKey, "WireGuard publicKey")
        val addresses = splitCsv(
            parsed.query["address"] ?: parsed.query["addresses"] ?: "10.0.0.2/32"
        )
        val allowed = splitCsv(
            parsed.query["allowedIPs"] ?: parsed.query["allowedips"]
                ?: "0.0.0.0/0,::/0"
        )
        val peer = JSONObject()
            .put("endpoint", formatEndpoint(parsed.host, parsed.port))
            .put("publicKey", publicKey)
            .put("allowedIPs", JSONArray(allowed))
        parsed.query["preSharedKey"]
            ?.takeIf(String::isNotBlank)
            ?.also { validateWireGuardKey(it, "WireGuard preSharedKey") }
            ?.let { peer.put("preSharedKey", it) }
        parsed.query["keepAlive"]?.toIntOrNull()
            ?.takeIf { it in 0..65535 }
            ?.let { peer.put("keepAlive", it) }
        val settings = JSONObject()
            .put("secretKey", secretKey)
            .put("address", JSONArray(addresses))
            .put("peers", JSONArray().put(peer))
            .put("noKernelTun", true)
        parsed.query["mtu"]?.toIntOrNull()?.takeIf { it in 576..9000 }
            ?.let { settings.put("mtu", it) }
        parsed.query["reserved"]?.takeIf(String::isNotBlank)
            ?.let { settings.put("reserved", parseWireGuardReserved(it)) }
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "wireguard")
            .put("settings", settings)
        return parsed.profile("wireguard", outbound, raw).copy(
            probeServer = parsed.host,
            probePort = 443
        )
    }

    private fun streamSettings(
        query: Map<String, String>,
        flow: String
    ): JSONObject {
        val requested = (
            query["type"] ?: query["net"] ?: query["method"] ?: "tcp"
            ).trim().lowercase(Locale.ROOT)
        val network = when (requested) {
            "", "none", "raw", "tcp" -> "tcp"
            "ws", "websocket" -> "ws"
            "grpc" -> "grpc"
            "httpupgrade", "http-upgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            "kcp", "mkcp" -> "kcp"
            "hysteria", "hysteria2", "hy2" -> "hysteria"
            "h2", "h3", "http" -> throw ConfigParseException(
                "Legacy HTTP/H2/H3 transport was removed from Xray 26.7.28 and is not wire-compatible with XHTTP; obtain a new XHTTP profile"
            )
            "quic" -> throw ConfigParseException(
                "Legacy QUIC transport was removed from Xray 26.7.28 and is not wire-compatible with XHTTP; obtain a new XHTTP or Hysteria2 profile"
            )
            else -> throw ConfigParseException(
                "Unsupported Xray transport '$requested'"
            )
        }
        val stream = JSONObject().put("network", network)
        when (network) {
            "ws" -> stream.put(
                "wsSettings",
                JSONObject()
                    .put("path", query["path"] ?: "/")
                    .apply {
                        query["host"]?.takeIf(String::isNotBlank)?.let {
                            put("headers", JSONObject().put("Host", it))
                        }
                    }
            )
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject()
                    .put("serviceName", query["serviceName"] ?: query["path"] ?: "")
                    .put("multiMode", query["mode"].equals("multi", true))
            )
            "httpupgrade" -> stream.put(
                "httpupgradeSettings",
                JSONObject()
                    .put("path", query["path"] ?: "/")
                    .apply {
                        query["host"]?.takeIf(String::isNotBlank)?.let { put("host", it) }
                    }
            )
            "xhttp" -> stream.put(
                "xhttpSettings",
                JSONObject()
                    .put("path", query["path"] ?: "/")
                    .apply {
                        query["host"]?.takeIf(String::isNotBlank)?.let { put("host", it) }
                        query["mode"]?.takeIf(String::isNotBlank)
                            ?.let { put("mode", it) }
                        query["extra"]?.takeIf(String::isNotBlank)
                            ?.let { put("extra", parseXhttpExtra(it)) }
                    }
            )
            "kcp" -> stream.put(
                "kcpSettings",
                JSONObject()
                    .put(
                        "header",
                        JSONObject().put("type", query["headerType"] ?: "none")
                    )
                    .apply {
                        query["seed"]?.takeIf(String::isNotBlank)?.let { put("seed", it) }
                    }
            )
            "tcp" -> if (query["headerType"].equals("http", true)) {
                stream.put(
                    "tcpSettings",
                    JSONObject().put(
                        "header",
                        JSONObject()
                            .put("type", "http")
                            .put(
                                "request",
                                JSONObject().put(
                                    "path",
                                    JSONArray().put(query["path"] ?: "/")
                                )
                            )
                    )
                )
            }
        }

        var security = (query["security"] ?: query["tls"] ?: "none")
            .trim().lowercase(Locale.ROOT)
        if (security == "xtls") {
            if (flow.contains("vision", ignoreCase = true)) {
                security = "tls"
            } else {
                throw ConfigParseException(
                    "Legacy XTLS security is not available in Xray 26.7.28; use TLS or REALITY"
                )
            }
        }
        when (security) {
            "", "none" -> stream.put("security", "none")
            "tls" -> stream
                .put("security", "tls")
                .put("tlsSettings", tlsSettings(query, query["host"].orEmpty()))
            "reality" -> {
                if (network !in setOf("tcp", "xhttp", "grpc")) {
                    throw ConfigParseException(
                        "REALITY in Xray 26.7.28 supports only RAW/TCP, XHTTP and gRPC"
                    )
                }
                stream
                    .put("security", "reality")
                    .put(
                        "realitySettings",
                        JSONObject()
                            .put("serverName", query["sni"] ?: query["serverName"] ?: "")
                            .put("fingerprint", query["fp"] ?: "chrome")
                            .put("publicKey", query["pbk"] ?: query["publicKey"] ?: "")
                            .put("shortId", query["sid"] ?: query["shortId"] ?: "")
                            .put("spiderX", query["spx"] ?: query["spiderX"] ?: "")
                            .apply {
                                (query["pqv"] ?: query["mldsa65Verify"])
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { put("mldsa65Verify", it) }
                            }
                    )
            }
            else -> throw ConfigParseException("Unsupported transport security '$security'")
        }
        query["sockopt"]?.takeIf(String::isNotBlank)?.let {
            runCatching { JSONObject(it) }.getOrNull()?.let { value ->
                stream.put("sockopt", value)
            }
        }
        return stream
    }

    private fun tlsSettings(
        query: Map<String, String>,
        fallbackHost: String
    ): JSONObject {
        val insecure = parseBoolean(
            query["allowInsecure"] ?: query["insecure"]
                ?: query["skip-cert-verify"]
        ) ?: false
        val pinned = query["pinnedPeerCertSha256"] ?: query["pcs"]
        val verifyName = query["verifyPeerCertByName"] ?: query["vcn"]
        if (insecure && pinned.isNullOrBlank() && verifyName.isNullOrBlank()) {
            throw ConfigParseException(
                "allowInsecure/insecure was removed from Xray 26.7.28; the provider must supply pcs (pinnedPeerCertSha256) or vcn (verifyPeerCertByName)"
            )
        }
        return JSONObject()
            .put(
                "serverName",
                query["sni"] ?: query["serverName"] ?: query["host"] ?: fallbackHost
            )
            .apply {
                query["alpn"]?.split(',')?.map(String::trim)
                    ?.filter(String::isNotBlank)?.takeIf(List<String>::isNotEmpty)
                    ?.let { put("alpn", JSONArray(it)) }
                query["fp"]?.takeIf(String::isNotBlank)
                    ?.let { put("fingerprint", it) }
                pinned?.takeIf(String::isNotBlank)
                    ?.let { put("pinnedPeerCertSha256", it) }
                verifyName?.takeIf(String::isNotBlank)
                    ?.let { put("verifyPeerCertByName", it) }
                query["verifyPeerCertInNames"]
                    ?.split(',')?.map(String::trim)?.filter(String::isNotBlank)
                    ?.takeIf(List<String>::isNotEmpty)
                    ?.let { put("verifyPeerCertInNames", JSONArray(it)) }
                (query["ech"] ?: query["echConfigList"])
                    ?.takeIf(String::isNotBlank)
                    ?.let { put("echConfigList", it) }
                query["echForceQuery"]?.takeIf(String::isNotBlank)
                    ?.let { put("echForceQuery", it) }
            }
    }

    private fun validateCoreCompatibility(outbound: JSONObject) {
        val protocol = canonicalProtocol(outbound.optString("protocol"))
        if (protocol !in CORE_PROXY_PROTOCOLS) {
            throw ConfigParseException("Unsupported Xray outbound protocol '$protocol'")
        }
        val stream = outbound.optJSONObject("streamSettings") ?: return
        val network = (
            stream.optString("network").ifBlank { stream.optString("method") }
            ).lowercase(Locale.ROOT)
        if (network == "quic") {
            throw ConfigParseException(
                "Legacy QUIC transport was removed from Xray 26.7.28"
            )
        }
        if (network in setOf("h2", "h3", "http")) {
            throw ConfigParseException(
                "Legacy HTTP/H2/H3 transport was removed from Xray 26.7.28 and cannot be converted without server changes"
            )
        }
    }

    private data class Parsed(
        val host: String,
        val port: Int,
        val userInfo: String,
        val name: String,
        val query: Map<String, String>
    ) {
        fun profile(
            protocol: String,
            outbound: JSONObject,
            raw: String
        ) = ConfigProfile(
            name = name.ifBlank { "$host:$port" },
            protocol = protocol,
            server = host,
            port = port,
            raw = raw,
            outboundJson = outbound.toString()
        )
    }

    private fun parseStandardUri(raw: String, label: String): Parsed {
        val fragment = raw.substringAfter('#', "")
        val withoutFragment = raw.substringBefore('#')
        val uri = runCatching { URI(withoutFragment) }.getOrElse {
            throw ConfigParseException("Malformed $label URI: ${it.message}")
        }
        val authority = uri.rawAuthority?.substringAfter('@')
        val host = (uri.host ?: authority?.let {
            parseHostPort(it, label).first
        }).orEmpty().removeSurrounding("[", "]")
        if (host.isBlank()) throw ConfigParseException("$label server address is missing")
        val port = uri.port.takeIf { it in 1..65535 }
            ?: throw ConfigParseException("$label port is missing or invalid")
        return Parsed(
            host = host,
            port = port,
            userInfo = uri.rawUserInfo?.let(::decode).orEmpty(),
            name = decode(fragment),
            query = parseQuery(uri.rawQuery.orEmpty())
        )
    }

    private fun parseQuery(raw: String): Map<String, String> = raw
        .split('&')
        .filter(String::isNotBlank)
        .associate { part ->
            decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
        }

    private fun addUserPassword(server: JSONObject, userInfo: String) {
        if (userInfo.isBlank()) return
        server.put(
            "users",
            JSONArray().put(
                JSONObject()
                    .put("user", decode(userInfo.substringBefore(':')))
                    .put("pass", decode(userInfo.substringAfter(':', "")))
            )
        )
    }

    private fun parseXhttpExtra(value: String): JSONObject {
        val candidates = linkedSetOf(value.trim(), decode(value).trim())
        decodeAnyBase64(value.trim())?.trim()?.takeIf(String::isNotBlank)
            ?.let(candidates::add)
        return candidates.firstNotNullOfOrNull {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: throw ConfigParseException(
            "XHTTP parameter 'extra' must be a valid JSON object"
        )
    }

    private fun endpointFromOutbound(outbound: JSONObject): Pair<String, Int> {
        val settings = outbound.optJSONObject("settings") ?: return "" to 0
        return when (outbound.optString("protocol")) {
            "wireguard" -> settings.optJSONArray("peers")
                ?.optJSONObject(0)?.optString("endpoint")
                ?.takeIf(String::isNotBlank)
                ?.let { runCatching { parseHostPort(it, "WireGuard") }.getOrNull() }
                ?: ("" to 0)
            "hysteria" -> settings.optString("address") to settings.optInt("port")
            else -> {
                val array = settings.optJSONArray("vnext")
                    ?: settings.optJSONArray("servers")
                    ?: return "" to 0
                val server = array.optJSONObject(0) ?: return "" to 0
                server.optString("address") to server.optInt("port")
            }
        }
    }

    private fun looksLikeWireGuardQuickConfig(value: String): Boolean =
        Regex("(?im)^\\s*\\[interface]\\s*$").containsMatchIn(value)

    private fun parseWireGuardQuickConfig(raw: String): ConfigProfile {
        val interfaceValues = linkedMapOf<String, MutableList<String>>()
        val peers = mutableListOf<LinkedHashMap<String, MutableList<String>>>()
        var current: MutableMap<String, MutableList<String>>? = null
        raw.lineSequence().forEachIndexed { index, original ->
            val line = original.substringBefore('#').trim()
            if (line.isBlank() || line.startsWith(';')) return@forEachIndexed
            if (line.startsWith('[') && line.endsWith(']')) {
                current = when (line.removeSurrounding("[", "]").trim().lowercase()) {
                    "interface" -> interfaceValues
                    "peer" -> linkedMapOf<String, MutableList<String>>().also(peers::add)
                    else -> throw ConfigParseException(
                        "Unsupported WireGuard section at line ${index + 1}"
                    )
                }
                return@forEachIndexed
            }
            val section = current ?: throw ConfigParseException(
                "WireGuard key appears before [Interface] at line ${index + 1}"
            )
            val separator = line.indexOf('=')
            if (separator <= 0) throw ConfigParseException(
                "Malformed WireGuard line ${index + 1}"
            )
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            section.getOrPut(key) { mutableListOf() } += value
        }

        val amneziaKeys = setOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")
        val present = (interfaceValues.keys + peers.flatMap { it.keys })
            .filter(amneziaKeys::contains).distinct()
        if (present.isNotEmpty()) throw ConfigParseException(
            "AmneziaWG fields ${present.joinToString()} require an AmneziaWG-compatible core"
        )
        fun values(map: Map<String, List<String>>, key: String) = map[key.lowercase()].orEmpty()
        fun one(map: Map<String, List<String>>, key: String) =
            values(map, key).lastOrNull().orEmpty().trim()
        fun csv(map: Map<String, List<String>>, key: String) = values(map, key)
            .flatMap { it.split(',') }.map(String::trim)
            .filter(String::isNotBlank).distinct()

        val secretKey = one(interfaceValues, "privatekey")
        validateWireGuardKey(secretKey, "WireGuard PrivateKey")
        val addresses = csv(interfaceValues, "address")
        if (addresses.isEmpty()) throw ConfigParseException("WireGuard Address is missing")
        if (peers.isEmpty()) throw ConfigParseException("WireGuard [Peer] section is missing")
        val xrayPeers = JSONArray()
        var firstEndpoint = ""
        peers.forEachIndexed { index, peer ->
            val publicKey = one(peer, "publickey")
            validateWireGuardKey(publicKey, "WireGuard peer PublicKey")
            val endpoint = one(peer, "endpoint")
                .removePrefix("udp://").removePrefix("UDP://")
            if (endpoint.isBlank()) throw ConfigParseException(
                "WireGuard peer ${index + 1} Endpoint is missing"
            )
            if (firstEndpoint.isBlank()) firstEndpoint = endpoint
            val value = JSONObject()
                .put("publicKey", publicKey)
                .put("endpoint", endpoint)
            one(peer, "presharedkey").takeIf(String::isNotBlank)
                ?.also { validateWireGuardKey(it, "WireGuard PresharedKey") }
                ?.let { value.put("preSharedKey", it) }
            csv(peer, "allowedips").takeIf(List<String>::isNotEmpty)
                ?.let { value.put("allowedIPs", JSONArray(it)) }
            one(peer, "persistentkeepalive").toIntOrNull()
                ?.takeIf { it in 0..65535 }?.let { value.put("keepAlive", it) }
            xrayPeers.put(value)
        }
        val settings = JSONObject()
            .put("secretKey", secretKey)
            .put("address", JSONArray(addresses))
            .put("peers", xrayPeers)
            .put("noKernelTun", true)
        one(interfaceValues, "mtu").toIntOrNull()?.takeIf { it in 576..9000 }
            ?.let { settings.put("mtu", it) }
        one(interfaceValues, "reserved").takeIf(String::isNotBlank)
            ?.let { settings.put("reserved", parseWireGuardReserved(it)) }
        val endpoint = parseHostPort(firstEndpoint, "WireGuard")
        val originalDns = csv(interfaceValues, "dns")
            .takeIf(List<String>::isNotEmpty)
            ?.let { JSONObject().put("servers", JSONArray(it)).toString() }
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "wireguard")
            .put("settings", settings)
        return ConfigProfile(
            name = "WireGuard ${endpoint.first}",
            protocol = "wireguard",
            server = endpoint.first,
            port = endpoint.second,
            raw = raw,
            outboundJson = outbound.toString(),
            originalDnsJson = originalDns,
            probeServer = endpoint.first,
            probePort = 443
        )
    }

    private fun validateWireGuardKey(value: String, label: String) {
        if (value.isBlank()) throw ConfigParseException("$label is missing")
        val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
        if (decoded?.size != 32) throw ConfigParseException(
            "$label must be a 32-byte Base64 key"
        )
    }

    private fun parseWireGuardReserved(value: String): JSONArray {
        val bytes = value.split(',', ' ', ';').map(String::trim)
            .filter(String::isNotBlank).map {
                it.toIntOrNull()?.takeIf { byte -> byte in 0..255 }
                    ?: throw ConfigParseException(
                        "WireGuard Reserved must contain bytes from 0 to 255"
                    )
            }
        if (bytes.size != 3) throw ConfigParseException(
            "WireGuard Reserved must contain exactly three bytes"
        )
        return JSONArray(bytes)
    }

    private fun parseHostPort(value: String, label: String): Pair<String, Int> {
        val clean = value.substringBefore('?').trim()
        val host: String
        val portText: String
        if (clean.startsWith('[')) {
            val end = clean.indexOf(']')
            if (end < 0) throw ConfigParseException("Malformed $label IPv6 address")
            host = clean.substring(1, end)
            portText = clean.substring(end + 1).removePrefix(":")
        } else {
            host = clean.substringBeforeLast(':', "")
            portText = clean.substringAfterLast(':', "")
        }
        if (host.isBlank()) throw ConfigParseException("$label server address is missing")
        return host to parsePort(portText, "$label port")
    }

    private fun parsePort(value: Any?, field: String): Int {
        val port = value?.toString()?.toIntOrNull() ?: throw ConfigParseException(
            "$field is missing or invalid"
        )
        if (port !in 1..65535) throw ConfigParseException(
            "$field must be between 1 and 65535"
        )
        return port
    }

    private fun requiredText(json: JSONObject, key: String, label: String): String =
        json.optString(key).ifBlank { throw ConfigParseException("$label is missing") }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(
            value.replace("+", "%2B"),
            StandardCharsets.UTF_8.name()
        )
    }.getOrDefault(value)

    private fun decodeAnyBase64(value: String): String? {
        val compact = value.filterNot(Char::isWhitespace)
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return runCatching {
            Base64.getDecoder().decode(padded).toString(StandardCharsets.UTF_8)
        }.recoverCatching {
            Base64.getUrlDecoder().decode(padded).toString(StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun boundedLines(value: String): List<String> {
        val result = ArrayList<String>()
        value.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach
            if (line.length > MAX_LINE_CHARS) throw ConfigParseException(
                "A subscription line exceeds the ${MAX_LINE_CHARS / 1024} KiB safety limit"
            )
            result += line
            if (result.size > MAX_PROFILE_CANDIDATES) throw ConfigParseException(
                "Subscription contains more than $MAX_PROFILE_CANDIDATES candidate lines"
            )
        }
        return result
    }

    private fun addError(errors: MutableList<String>, message: String) {
        if (errors.size >= MAX_REPORTED_ERRORS) return
        errors += message
            .replace(
                Regex("(?i)(token|password|secret|uuid|privatekey)=([^&\\s]+)"),
                "$1=<redacted>"
            )
            .take(MAX_ERROR_CHARS)
    }

    private fun splitCsv(value: String): List<String> = value
        .split(',', '\n').map(String::trim).filter(String::isNotBlank).distinct()

    private fun parseBoolean(value: String?): Boolean? = when (
        value?.trim()?.lowercase(Locale.ROOT)
    ) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }

    private fun formatEndpoint(host: String, port: Int): String =
        if (':' in host) "[$host]:$port" else "$host:$port"

    private fun canonicalProtocol(value: String): String = when (
        value.trim().lowercase(Locale.ROOT)
    ) {
        "ss" -> "shadowsocks"
        "wg" -> "wireguard"
        "hy2", "hysteria2" -> "hysteria"
        else -> value.trim().lowercase(Locale.ROOT)
    }

    private fun normalizeRaw(raw: String): String = raw.trim()

    private val CORE_PROXY_PROTOCOLS = setOf(
        "vless", "vmess", "trojan", "shadowsocks", "socks", "http",
        "wireguard", "hysteria"
    )
    private val NON_PROXY_PROTOCOLS = setOf(
        "freedom", "direct", "blackhole", "block", "dns", "loopback"
    )
    private const val MAX_INPUT_CHARS = 4 * 1024 * 1024
    private const val MAX_JSON_CHARS = 2 * 1024 * 1024
    private const val MAX_LINE_CHARS = 64 * 1024
    private const val MAX_PROFILE_CANDIDATES = 10_000
    private const val MAX_PROFILES = 8_000
    private const val MAX_REPORTED_ERRORS = 20
    private const val MAX_ERROR_CHARS = 240
}
