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
    private val supported = setOf(
        "vless",
        "vmess",
        "trojan",
        "ss",
        "socks",
        "socks5",
        "http",
        "https"
    )

    fun parseText(input: String): List<ConfigProfile> {
        if (input.length > MAX_INPUT_CHARS) {
            throw ConfigParseException(
                "Configuration input exceeds the ${MAX_INPUT_CHARS / 1024} KiB safety limit"
            )
        }

        val text = input.trim().removePrefix("\uFEFF")

        if (text.isBlank()) {
            return emptyList()
        }

        if (looksLikeWireGuardQuickConfig(text)) {
            return listOf(parseWireGuardQuickConfig(text))
        }

        if (text.startsWith("{")) {
            return listOf(parseJson(text))
        }

        val directLines =
            boundedLines(text)

        val candidates = if (directLines.any { "://" in it }) {
            directLines
        } else {
            val decoded = decodeBase64OrNull(text)
                ?: throw ConfigParseException(
                    "Input is neither a supported URI, Xray JSON, nor Base64 subscription content"
                )

            boundedLines(decoded)
        }

        val unique = LinkedHashMap<String, ConfigProfile>()
        val errors = mutableListOf<String>()

        for (line in candidates) {
            if (line.startsWith("{") && line.endsWith("}")) {
                runCatching {
                    parseJson(line)
                }.onSuccess {
                    unique.putIfAbsent(normalizeRaw(it.raw), it)
                }.onFailure {
                    addError(
                        errors,
                        it.message ?: "Invalid JSON"
                    )
                }

                continue
            }

            val scheme = line
                .substringBefore("://", "")
                .lowercase()

            if (scheme.isBlank()) {
                continue
            }

            if (scheme !in supported) {
                addError(
                    errors,
                    "Unsupported scheme '$scheme' for the Xray adapter"
                )
                continue
            }

            runCatching {
                parseUri(line)
            }.onSuccess {
                unique.putIfAbsent(normalizeRaw(it.raw), it)
            }.onFailure {
                addError(
                    errors,
                    it.message ?: "Malformed $scheme URI"
                )
            }

            if (unique.size > MAX_PROFILES) {
                throw ConfigParseException(
                    "Subscription contains more than $MAX_PROFILES unique profiles"
                )
            }
        }

        if (unique.isEmpty()) {
            throw ConfigParseException(
                errors
                    .joinToString("; ")
                    .ifBlank { "No supported configurations found" }
            )
        }

        return unique.values.toList()
    }

    fun decodeBase64OrNull(value: String): String? {
        if (value.length > MAX_INPUT_CHARS) {
            return null
        }

        val compact = value.filterNot(Char::isWhitespace)

        if (compact.isBlank() || compact.length < 4) {
            return null
        }

        val padded = compact +
            "=".repeat((4 - compact.length % 4) % 4)

        val bytes = runCatching {
            Base64.getDecoder().decode(padded)
        }.recoverCatching {
            Base64.getUrlDecoder().decode(padded)
        }.getOrNull() ?: return null

        val decoded = bytes.toString(StandardCharsets.UTF_8)

        return decoded.takeIf {
            it.contains("://") ||
                it.trimStart().startsWith("{")
        }
    }

    fun parseUri(raw: String): ConfigProfile {
        if (raw.length > MAX_LINE_CHARS) {
            throw ConfigParseException(
                "Configuration URI exceeds the ${MAX_LINE_CHARS / 1024} KiB safety limit"
            )
        }

        return when (
            raw.substringBefore("://").lowercase()
        ) {
            "vmess" -> parseVmess(raw)
            "vless" -> parseVless(raw)
            "trojan" -> parseTrojan(raw)
            "ss" -> parseShadowsocks(raw)
            "socks", "socks5" -> parseSocks(raw)
            "http", "https" -> parseHttp(raw)

            else -> throw ConfigParseException(
                "Unsupported configuration scheme"
            )
        }
    }

    fun parseJson(raw: String): ConfigProfile {
        if (raw.length > MAX_JSON_CHARS) {
            throw ConfigParseException(
                "Xray JSON exceeds the ${MAX_JSON_CHARS / 1024} KiB safety limit"
            )
        }

        val root = runCatching {
            JSONObject(raw)
        }.getOrElse {
            throw ConfigParseException(
                "Invalid Xray JSON: ${it.message}"
            )
        }

        val outbounds = root.optJSONArray("outbounds")
            ?: throw ConfigParseException(
                "Xray JSON field 'outbounds' is missing"
            )

        val outbound = (0 until outbounds.length())
            .map { outbounds.getJSONObject(it) }
            .firstOrNull {
                it.optString("protocol") !in setOf(
                    "freedom",
                    "blackhole",
                    "dns"
                )
            }
            ?: throw ConfigParseException(
                "Xray JSON has no usable proxy outbound"
            )

        val protocol = outbound.optString(
            "protocol",
            "unknown"
        )

        val endpoint = endpointFromOutbound(outbound)

        return ConfigProfile(
            name = outbound.optString(
                "tag",
                "Xray JSON"
            ),
            protocol = protocol,
            server = endpoint.first,
            port = endpoint.second,
            raw = raw,
            outboundJson = JSONObject(outbound.toString())
                .put("tag", "proxy")
                .toString(),
            probeServer =
                endpoint.first,
            probePort =
                if (protocol == "wireguard") {
                    443
                } else {
                    endpoint.second
                },
            originalDnsJson = root
                .optJSONObject("dns")
                ?.toString()
        )
    }

    /** TRIVOX_V7_IMPORT_WIREGUARD
     * Parses standard wg-quick files without executing platform-specific shell
     * directives. AmneziaWG-only fields fail explicitly because silently passing
     * them to standard Xray WireGuard would create a misleading broken profile.
     */
    private fun looksLikeWireGuardQuickConfig(value: String): Boolean =
        Regex("(?im)^\\s*\\[interface]\\s*$")
            .containsMatchIn(value)

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

        val amneziaKeys = setOf(
            "jc", "jmin", "jmax", "s1", "s2",
            "h1", "h2", "h3", "h4"
        )
        val presentAmnezia = (interfaceValues.keys + peers.flatMap { it.keys })
            .filter { it in amneziaKeys }
            .distinct()
        if (presentAmnezia.isNotEmpty()) {
            throw ConfigParseException(
                "AmneziaWG fields ${presentAmnezia.joinToString()} require an " +
                    "AmneziaWG-compatible core; the Xray WireGuard outbound " +
                    "cannot emulate that obfuscation safely"
            )
        }

        fun values(
            map: Map<String, List<String>>,
            key: String
        ): List<String> = map[key.lowercase()].orEmpty()

        fun one(
            map: Map<String, List<String>>,
            key: String
        ): String = values(map, key).lastOrNull().orEmpty().trim()

        fun csv(
            map: Map<String, List<String>>,
            key: String
        ): List<String> = values(map, key)
            .flatMap { it.split(',') }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

        val secretKey = one(interfaceValues, "privatekey")
        validateWireGuardKey(secretKey, "WireGuard PrivateKey")
        val addresses = csv(interfaceValues, "address")
        if (addresses.isEmpty()) {
            throw ConfigParseException("WireGuard Address is missing")
        }
        if (peers.isEmpty()) {
            throw ConfigParseException("WireGuard [Peer] section is missing")
        }

        val xrayPeers = JSONArray()
        var firstEndpoint = ""
        peers.forEachIndexed { index, peer ->
            val publicKey = one(peer, "publickey")
            validateWireGuardKey(publicKey, "WireGuard peer PublicKey")
            val endpoint = one(peer, "endpoint")
                .removePrefix("udp://")
                .removePrefix("UDP://")
            if (endpoint.isBlank()) {
                throw ConfigParseException(
                    "WireGuard peer ${index + 1} Endpoint is missing"
                )
            }
            if (firstEndpoint.isBlank()) firstEndpoint = endpoint

            val xrayPeer = JSONObject()
                .put("publicKey", publicKey)
                .put("endpoint", endpoint)

            one(peer, "presharedkey")
                .takeIf(String::isNotBlank)
                ?.also { validateWireGuardKey(it, "WireGuard PresharedKey") }
                ?.let { xrayPeer.put("preSharedKey", it) }

            csv(peer, "allowedips")
                .takeIf(List<String>::isNotEmpty)
                ?.let { xrayPeer.put("allowedIPs", JSONArray(it)) }

            one(peer, "persistentkeepalive")
                .takeIf(String::isNotBlank)
                ?.toIntOrNull()
                ?.takeIf { it in 0..65535 }
                ?.let { xrayPeer.put("keepAlive", it) }

            xrayPeers.put(xrayPeer)
        }

        val settings = JSONObject()
            .put("secretKey", secretKey)
            .put("address", JSONArray(addresses))
            .put("peers", xrayPeers)
            .put("noKernelTun", true)

        one(interfaceValues, "mtu")
            .takeIf(String::isNotBlank)
            ?.toIntOrNull()
            ?.takeIf { it in 576..9000 }
            ?.let { settings.put("mtu", it) }

        one(interfaceValues, "reserved")
            .takeIf(String::isNotBlank)
            ?.let { settings.put("reserved", parseWireGuardReserved(it)) }

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "wireguard")
            .put("settings", settings)
        val endpoint = parseHostPort(
            firstEndpoint,
            "WireGuard"
        )
        val dnsServers = csv(interfaceValues, "dns")
        val originalDns = dnsServers
            .takeIf(List<String>::isNotEmpty)
            ?.let { JSONObject().put("servers", JSONArray(it)).toString() }

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
        val decoded = runCatching {
            Base64.getDecoder().decode(value)
        }.getOrNull()
        if (decoded?.size != 32) {
            throw ConfigParseException("$label must be a 32-byte Base64 key")
        }
    }

    private fun parseWireGuardReserved(value: String): JSONArray {
        val bytes = value
            .split(',', ' ', ';')
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { item ->
                item.toIntOrNull()
                    ?.takeIf { it in 0..255 }
                    ?: throw ConfigParseException(
                        "WireGuard Reserved must contain three bytes from 0 to 255"
                    )
            }
        if (bytes.size != 3) {
            throw ConfigParseException(
                "WireGuard Reserved must contain exactly three bytes"
            )
        }
        return JSONArray(bytes)
    }

    private fun parseVmess(raw: String): ConfigProfile {
        val encoded = raw
            .substringAfter("vmess://")
            .substringBefore('#')

        val decoded = decodeAnyBase64(encoded)
            ?: throw ConfigParseException(
                "VMess payload is not valid Base64"
            )

        val json = runCatching {
            JSONObject(decoded)
        }.getOrElse {
            throw ConfigParseException(
                "VMess payload is not valid JSON: ${it.message}"
            )
        }

        val server = json
            .optString("add")
            .ifBlank {
                throw ConfigParseException(
                    "VMess field 'add' is missing"
                )
            }

        val port = parsePort(
            json.opt("port"),
            "VMess port"
        )

        val id = json
            .optString("id")
            .ifBlank {
                throw ConfigParseException(
                    "VMess field 'id' is missing"
                )
            }

        val user = JSONObject()
            .put("id", id)
            .put("alterId", json.optInt("aid", 0))
            .put(
                "security",
                json.optString("scy", "auto")
            )

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
                            .put(
                                "users",
                                JSONArray().put(user)
                            )
                    )
                )
            )

        val query = mutableMapOf<String, String>()

        listOf(
            "net",
            "type",
            "host",
            "path",
            "tls",
            "sni",
            "alpn",
            "fp",
            "serviceName",
            "mode",
            "extra"
        ).forEach { key ->
            json.optString(key)
                .takeIf(String::isNotBlank)
                ?.let {
                    query[key] = it
                }
        }

        outbound.put(
            "streamSettings",
            streamSettings(query)
        )

        return ConfigProfile(
            name = json.optString(
                "ps",
                "$server:$port"
            ),
            protocol = "vmess",
            server = server,
            port = port,
            raw = raw,
            outboundJson = outbound.toString()
        )
    }

    private fun parseVless(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "VLESS")

        val id = parsed.userInfo
            .substringBefore(':')
            .ifBlank {
                throw ConfigParseException(
                    "VLESS user ID is missing"
                )
            }

        val user = JSONObject()
            .put("id", id)
            .put(
                "encryption",
                parsed.query["encryption"] ?: "none"
            )

        parsed.query["flow"]
            ?.takeIf(String::isNotBlank)
            ?.let {
                user.put("flow", it)
            }

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", parsed.host)
                            .put("port", parsed.port)
                            .put(
                                "users",
                                JSONArray().put(user)
                            )
                    )
                )
            )
            .put(
                "streamSettings",
                streamSettings(parsed.query)
            )

        parsed.query["packetEncoding"]?.let {
            outbound
                .getJSONObject("settings")
                .put("packetEncoding", it)
        }

        return parsed.profile(
            protocol = "vless",
            outbound = outbound,
            raw = raw
        )
    }

    private fun parseTrojan(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "Trojan")

        val password = decode(parsed.userInfo)
            .ifBlank {
                throw ConfigParseException(
                    "Trojan password is missing"
                )
            }

        val server = JSONObject()
            .put("address", parsed.host)
            .put("port", parsed.port)
            .put("password", password)

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "trojan")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(server)
                )
            )
            .put(
                "streamSettings",
                streamSettings(parsed.query)
            )

        return parsed.profile(
            protocol = "trojan",
            outbound = outbound,
            raw = raw
        )
    }

    private fun parseShadowsocks(
        raw: String
    ): ConfigProfile {
        val body = raw.substringAfter("ss://")

        val fragment = decode(
            body.substringAfter('#', "")
        )

        val noFragment = body
            .substringBefore('#')
            .substringBefore('?')

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

            if (rawAt < 0) {
                throw ConfigParseException(
                    "Shadowsocks endpoint is missing"
                )
            }

            credentialPart =
                decodeAnyBase64(
                    noFragment.substring(0, rawAt)
                ) ?: noFragment.substring(0, rawAt)

            endpointPart = noFragment.substring(
                rawAt + 1
            )
        }

        val credential =
            decodeAnyBase64(credentialPart)
                ?: credentialPart

        val colon = credential.indexOf(':')

        if (colon <= 0) {
            throw ConfigParseException(
                "Shadowsocks method or password is missing"
            )
        }

        val method = credential.substring(0, colon)
        val password = credential.substring(colon + 1)
        val normalizedPassword =
            Shadowsocks2022.normalizePassword(
                method,
                password
            )

        val endpoint = parseHostPort(
            endpointPart,
            "Shadowsocks"
        )

        val server = JSONObject()
            .put("address", endpoint.first)
            .put("port", endpoint.second)
            .put("method", method)
            .put("password", normalizedPassword)

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "shadowsocks")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(server)
                )
            )

        return ConfigProfile(
            name = fragment.ifBlank {
                "${endpoint.first}:${endpoint.second}"
            },
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

        if (parsed.userInfo.isNotBlank()) {
            server.put(
                "users",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "user",
                            decode(
                                parsed.userInfo
                                    .substringBefore(':')
                            )
                        )
                        .put(
                            "pass",
                            decode(
                                parsed.userInfo
                                    .substringAfter(':', "")
                            )
                        )
                )
            )
        }

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(server)
                )
            )

        return parsed.profile(
            protocol = "socks",
            outbound = outbound,
            raw = raw
        )
    }

    private fun parseHttp(raw: String): ConfigProfile {
        val parsed = parseStandardUri(raw, "HTTP")

        val server = JSONObject()
            .put("address", parsed.host)
            .put("port", parsed.port)

        if (parsed.userInfo.isNotBlank()) {
            server.put(
                "users",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "user",
                            decode(
                                parsed.userInfo
                                    .substringBefore(':')
                            )
                        )
                        .put(
                            "pass",
                            decode(
                                parsed.userInfo
                                    .substringAfter(':', "")
                            )
                        )
                )
            )
        }

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "http")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(server)
                )
            )

        if (raw.startsWith("https://")) {
            outbound.put(
                "streamSettings",
                JSONObject()
                    .put("security", "tls")
                    .put(
                        "tlsSettings",
                        JSONObject().put(
                            "serverName",
                            parsed.host
                        )
                    )
            )
        }

        return parsed.profile(
            protocol = "http",
            outbound = outbound,
            raw = raw
        )
    }

    private fun streamSettings(
        query: Map<String, String>
    ): JSONObject {
        val network = (
            query["type"]
                ?: query["net"]
                ?: "tcp"
            ).lowercase()

        val normalized = when (network) {
            "h2" -> "http"
            "httpupgrade" -> "httpupgrade"
            else -> network
        }

        val stream = JSONObject()
            .put("network", normalized)

        when (normalized) {
            "ws" -> {
                stream.put(
                    "wsSettings",
                    JSONObject()
                        .put(
                            "path",
                            query["path"] ?: "/"
                        )
                        .apply {
                            query["host"]?.let {
                                put(
                                    "headers",
                                    JSONObject().put(
                                        "Host",
                                        it
                                    )
                                )
                            }
                        }
                )
            }

            "grpc" -> {
                stream.put(
                    "grpcSettings",
                    JSONObject()
                        .put(
                            "serviceName",
                            query["serviceName"]
                                ?: query["path"]
                                ?: ""
                        )
                        .put(
                            "multiMode",
                            query["mode"] == "multi"
                        )
                )
            }

            "httpupgrade" -> {
                stream.put(
                    "httpupgradeSettings",
                    JSONObject()
                        .put(
                            "path",
                            query["path"] ?: "/"
                        )
                        .apply {
                            query["host"]?.let {
                                put("host", it)
                            }
                        }
                )
            }

            "xhttp", "splithttp" -> {
                stream.put(
                    "xhttpSettings",
                    JSONObject()
                        .put(
                            "path",
                            query["path"] ?: "/"
                        )
                        .apply {
                            query["host"]?.let {
                                put("host", it)
                            }

                            query["mode"]?.let {
                                put("mode", it)
                            }

                            query["extra"]
                                ?.takeIf(String::isNotBlank)
                                ?.let {
                                    put(
                                        "extra",
                                        parseXhttpExtra(it)
                                    )
                                }
                        }
                )
            }

            "kcp", "mkcp" -> {
                stream.put(
                    "kcpSettings",
                    JSONObject()
                        .put(
                            "header",
                            JSONObject().put(
                                "type",
                                query["headerType"] ?: "none"
                            )
                        )
                        .apply {
                            query["seed"]?.let {
                                put("seed", it)
                            }
                        }
                )
            }

            "quic" -> {
                stream.put(
                    "quicSettings",
                    JSONObject()
                        .put(
                            "security",
                            query["quicSecurity"] ?: "none"
                        )
                        .put(
                            "key",
                            query["key"] ?: ""
                        )
                        .put(
                            "header",
                            JSONObject().put(
                                "type",
                                query["headerType"] ?: "none"
                            )
                        )
                )
            }

            "tcp" -> {
                if (query["headerType"] == "http") {
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
                                        JSONArray().put(
                                            query["path"] ?: "/"
                                        )
                                    )
                                )
                        )
                    )
                }
            }
        }

        val security = (
            query["security"]
                ?: query["tls"]
                ?: "none"
            ).lowercase()

        when (security) {
            "tls" -> {
                stream
                    .put("security", "tls")
                    .put(
                        "tlsSettings",
                        tlsSettings(query)
                    )
            }

            "reality" -> {
                stream
                    .put("security", "reality")
                    .put(
                        "realitySettings",
                        JSONObject()
                            .put(
                                "serverName",
                                query["sni"]
                                    ?: query["serverName"]
                                    ?: ""
                            )
                            .put(
                                "fingerprint",
                                query["fp"] ?: "chrome"
                            )
                            .put(
                                "publicKey",
                                query["pbk"]
                                    ?: query["publicKey"]
                                    ?: ""
                            )
                            .put(
                                "shortId",
                                query["sid"]
                                    ?: query["shortId"]
                                    ?: ""
                            )
                            .put(
                                "spiderX",
                                query["spx"]
                                    ?: query["spiderX"]
                                    ?: ""
                            )
                    )
            }
        }

        query["sockopt"]?.let {
            runCatching {
                JSONObject(it)
            }.getOrNull()?.let { value ->
                stream.put("sockopt", value)
            }
        }

        return stream
    }

    private fun tlsSettings(
        query: Map<String, String>
    ): JSONObject {
        return JSONObject()
            .put(
                "serverName",
                query["sni"]
                    ?: query["serverName"]
                    ?: query["host"]
                    ?: ""
            )
            .put(
                "allowInsecure",
                query["allowInsecure"]
                    ?.toBooleanStrictOrNull()
                    ?: false
            )
            .apply {
                query["alpn"]
                    ?.split(',')
                    ?.filter(String::isNotBlank)
                    ?.let {
                        put("alpn", JSONArray(it))
                    }

                query["fp"]?.let {
                    put("fingerprint", it)
                }
            }
    }

    private fun parseXhttpExtra(
        value: String
    ): JSONObject {
        val candidates = linkedSetOf(
            value.trim(),
            decode(value).trim()
        )

        decodeAnyBase64(value.trim())
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(candidates::add)

        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching {
                JSONObject(candidate)
            }.getOrNull()
        } ?: throw ConfigParseException(
            "XHTTP parameter 'extra' must be a valid JSON object"
        )
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
        ): ConfigProfile {
            return ConfigProfile(
                name = name.ifBlank {
                    "$host:$port"
                },
                protocol = protocol,
                server = host,
                port = port,
                raw = raw,
                outboundJson = outbound.toString()
            )
        }
    }

    private fun parseStandardUri(
        raw: String,
        label: String
    ): Parsed {
        val fragment = raw.substringAfter('#', "")
        val withoutFragment = raw.substringBefore('#')

        val uri = runCatching {
            URI(withoutFragment)
        }.getOrElse {
            throw ConfigParseException(
                "Malformed $label URI: ${it.message}"
            )
        }

        val host = (
            uri.host ?: run {
                val authority = uri.rawAuthority
                    ?.substringAfter('@')
                    ?: throw ConfigParseException(
                        "$label server address is missing"
                    )

                parseHostPort(
                    authority,
                    label
                ).first
            }
            ).removeSurrounding("[", "]")

        val port = if (uri.port > 0) {
            uri.port
        } else {
            throw ConfigParseException(
                "$label port is missing or invalid"
            )
        }

        return Parsed(
            host = host,
            port = port,
            userInfo = uri.rawUserInfo
                ?.let(::decode)
                ?: "",
            name = decode(fragment),
            query = parseQuery(
                uri.rawQuery.orEmpty()
            )
        )
    }

    private fun parseQuery(
        raw: String
    ): Map<String, String> {
        return raw
            .split('&')
            .filter(String::isNotBlank)
            .associate { part ->
                decode(
                    part.substringBefore('=')
                ) to decode(
                    part.substringAfter('=', "")
                )
            }
    }

    private fun parseHostPort(
        value: String,
        label: String
    ): Pair<String, Int> {
        val clean = value.substringBefore('?')

        val host: String
        val portText: String

        if (clean.startsWith('[')) {
            val end = clean.indexOf(']')

            if (end < 0) {
                throw ConfigParseException(
                    "Malformed $label IPv6 address"
                )
            }

            host = clean.substring(1, end)
            portText = clean
                .substring(end + 1)
                .removePrefix(":")
        } else {
            host = clean.substringBeforeLast(':', "")
            portText = clean.substringAfterLast(':', "")
        }

        if (host.isBlank()) {
            throw ConfigParseException(
                "$label server address is missing"
            )
        }

        return host to parsePort(
            portText,
            "$label port"
        )
    }

    private fun parsePort(
        value: Any?,
        field: String
    ): Int {
        val port = value
            ?.toString()
            ?.toIntOrNull()
            ?: throw ConfigParseException(
                "$field is missing or invalid"
            )

        if (port !in 1..65535) {
            throw ConfigParseException(
                "$field must be between 1 and 65535"
            )
        }

        return port
    }

    private fun endpointFromOutbound(
        outbound: JSONObject
    ): Pair<String, Int> {
        val settings = outbound
            .optJSONObject("settings")
            ?: return "" to 0

        if (
            outbound.optString("protocol") ==
            "wireguard"
        ) {
            val endpoint =
                settings
                    .optJSONArray("peers")
                    ?.optJSONObject(0)
                    ?.optString("endpoint")
                    .orEmpty()

            if (endpoint.isNotBlank()) {
                return runCatching {
                    parseHostPort(
                        endpoint,
                        "WireGuard"
                    )
                }.getOrElse {
                    parseEndpointWithDefaultPort(
                        endpoint,
                        51820
                    )
                }
            }
        }

        val collection =
            settings.optJSONArray("vnext")
                ?: settings.optJSONArray("servers")
                ?: return "" to 0

        val server = collection.optJSONObject(0)
            ?: return "" to 0

        return server.optString("address") to
            server.optInt("port")
    }

    private fun decode(value: String): String {
        /*
         * Proxy URIs use RFC 3986 components. A literal '+' is data, not a
         * form-encoded space; URLDecoder would otherwise corrupt passwords,
         * UUID material and fragments.
         */
        return runCatching {
            URLDecoder.decode(
                value.replace(
                    "+",
                    "%2B"
                ),
                StandardCharsets.UTF_8.name()
            )
        }.getOrDefault(value)
    }

    private fun boundedLines(
        value: String
    ): List<String> {
        val result =
            ArrayList<String>()

        value.lineSequence()
            .forEach {
                    rawLine ->
                val line =
                    rawLine.trim()

                if (line.isBlank()) {
                    return@forEach
                }

                if (line.length > MAX_LINE_CHARS) {
                    throw ConfigParseException(
                        "A subscription line exceeds the ${MAX_LINE_CHARS / 1024} KiB safety limit"
                    )
                }

                result += line

                if (
                    result.size >
                    MAX_PROFILE_CANDIDATES
                ) {
                    throw ConfigParseException(
                        "Subscription contains more than $MAX_PROFILE_CANDIDATES candidate lines"
                    )
                }
            }

        return result
    }

    private fun addError(
        errors: MutableList<String>,
        message: String
    ) {
        if (errors.size < MAX_REPORTED_ERRORS) {
            errors +=
                message
                    .replace(
                        Regex(
                            "(?i)(token|password|secret|uuid)=([^&\\s]+)"
                        ),
                        "$1=<redacted>"
                    )
                    .take(MAX_ERROR_CHARS)
        }
    }

    private fun parseEndpointWithDefaultPort(
        value: String,
        defaultPort: Int
    ): Pair<String, Int> {
        val clean =
            value.trim()
                .substringBefore('?')

        if (clean.startsWith('[')) {
            val end =
                clean.indexOf(']')

            if (end > 1) {
                return clean
                    .substring(
                        1,
                        end
                    ) to defaultPort
            }
        }

        if (
            clean.count {
                it == ':'
            } > 1
        ) {
            return clean to defaultPort
        }

        val host =
            clean.substringBeforeLast(
                ':',
                clean
            ).trim()

        if (host.isBlank()) {
            throw ConfigParseException(
                "WireGuard endpoint is missing"
            )
        }

        return host to defaultPort
    }

    private fun decodeAnyBase64(
        value: String
    ): String? {
        val compact = value.filterNot(
            Char::isWhitespace
        )

        val padded = compact +
            "=".repeat(
                (4 - compact.length % 4) % 4
            )

        return runCatching {
            Base64
                .getDecoder()
                .decode(padded)
                .toString(StandardCharsets.UTF_8)
        }.recoverCatching {
            Base64
                .getUrlDecoder()
                .decode(padded)
                .toString(StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun normalizeRaw(raw: String): String {
        return raw.trim()
    }

    private const val MAX_INPUT_CHARS =
        4 * 1024 * 1024
    private const val MAX_JSON_CHARS =
        2 * 1024 * 1024
    private const val MAX_LINE_CHARS =
        64 * 1024
    private const val MAX_PROFILE_CANDIDATES =
        10_000
    private const val MAX_PROFILES =
        8_000
    private const val MAX_REPORTED_ERRORS =
        20
    private const val MAX_ERROR_CHARS =
        240
}
