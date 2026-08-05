package com.trivox.client.config

import com.trivox.client.data.ConfigProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Parses native Mihomo YAML and sing-box JSON without feeding YAML internals to
 * the URI/Xray parser. Complete runtime documents remain complete; provider
 * lists can be expanded separately for cross-core conversion.
 */
object NativeConfigImporter {
    fun nativeInstallSubscriptionUrl(input: String): String? =
        NativeProfileDocument.remoteProfileUrl(input)

    fun parseTextOrNull(input: String): List<ConfigProfile>? {
        val text = input.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return emptyList()
        NativeProfileDocument.embeddedContent(text)?.let { embedded ->
            return parseTextOrNull(embedded)
        }
        NativeProfileDocument.parseExactDocumentOrNull(text)?.let { return listOf(it) }

        if (text.startsWith("{")) {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (root.has("type") && root.has("server")) {
                return singBoxOutboundToProfile(root)?.let(::listOf)
            }
            if (root.has("proxies")) {
                return parseMihomoJson(root).takeIf { it.isNotEmpty() }
            }
            return null
        }

        if (!looksLikeMihomoYaml(text)) return null
        val profiles = parseMihomoYamlProfiles(text)
        return profiles.takeIf { it.isNotEmpty() }
    }

    /** Returns individual portable proxies even when the input is a full document. */
    fun convertibleProfiles(input: String): List<ConfigProfile> {
        val text = input.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return emptyList()
        NativeProfileDocument.embeddedContent(text)?.let { return convertibleProfiles(it) }
        if (text.startsWith("{")) {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            if (root.has("outbounds")) {
                return NativeProfileDocument.singBoxOutboundItems(text)
                    .mapNotNull(::singBoxOutboundToProfile)
            }
            if (root.has("type") && root.has("server")) {
                return listOfNotNull(singBoxOutboundToProfile(root))
            }
            return parseMihomoJson(root)
        }
        return parseMihomoYamlProfiles(text)
    }

    private fun looksLikeMihomoYaml(text: String): Boolean =
        Regex("(?m)^\\s*(proxies|proxy-providers|proxy-groups|rules|dns|tun|mixed-port|socks-port)\\s*:")
            .containsMatchIn(text)

    private fun parseMihomoYamlProfiles(text: String): List<ConfigProfile> =
        NativeProfileDocument.directProxyItems(text).mapNotNull(::mihomoProxyToProfile)

    private fun parseMihomoJson(root: JSONObject): List<ConfigProfile> {
        val proxies = root.optJSONArray("proxies") ?: return emptyList()
        return (0 until proxies.length()).mapNotNull(proxies::optJSONObject)
            .mapNotNull { mihomoProxyToProfile(jsonObjectToMap(it)) }
    }

    private fun singBoxOutboundToProfile(out: JSONObject): ConfigProfile? {
        val type = out.optString("type").lowercase(Locale.ROOT)
        if (type in NON_PROXY_TYPES) return null
        val server = out.optString("server").trim()
        val port = out.optInt("server_port", out.optInt("port", 0))
        if (server.isBlank() || port !in 1..65535) return null

        val protocol = canonicalProtocol(type)
        val xray = JSONObject().put("tag", "proxy").put("protocol", protocol)
        val settings = JSONObject()
        when (protocol) {
            "vless", "vmess" -> {
                val user = JSONObject().put("id", out.optString("uuid"))
                if (protocol == "vless") user.put("encryption", "none")
                if (protocol == "vmess") {
                    user.put("security", out.optString("security", "auto"))
                    user.put("alterId", out.optInt("alter_id", 0))
                }
                out.optString("flow").takeIf(String::isNotBlank)?.let { user.put("flow", it) }
                settings.put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", server)
                            .put("port", port)
                            .put("users", JSONArray().put(user))
                    )
                )
            }
            "trojan" -> settings.put(
                "servers",
                JSONArray().put(
                    JSONObject().put("address", server).put("port", port)
                        .put("password", out.optString("password"))
                )
            )
            "shadowsocks" -> settings.put(
                "servers",
                JSONArray().put(
                    JSONObject().put("address", server).put("port", port)
                        .put("method", out.optString("method"))
                        .put("password", out.optString("password"))
                )
            )
            "socks" -> settings.put(
                "servers",
                JSONArray().put(
                    JSONObject().put("address", server).put("port", port)
                        .put("users", credentialUsers(out.optString("username"), out.optString("password")))
                )
            )
            "http" -> settings.put(
                "servers",
                JSONArray().put(
                    JSONObject().put("address", server).put("port", port)
                        .put("users", credentialUsers(out.optString("username"), out.optString("password")))
                )
            )
            else -> return null
        }
        xray.put("settings", settings)
        xray.put("streamSettings", singBoxStreamSettings(out, server))
        return ConfigProfile(
            id = UUID.randomUUID().toString(),
            name = out.optString("tag").ifBlank { "$server:$port" },
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
        val transport = out.optJSONObject("transport")
        val network = normalizeNetwork(transport?.optString("type") ?: "tcp")
        val stream = JSONObject().put("network", network)
        when (network) {
            "ws" -> stream.put(
                "wsSettings",
                JSONObject().apply {
                    transport?.optString("path")?.takeIf(String::isNotBlank)?.let { put("path", it) }
                    transport?.optJSONObject("headers")?.let { put("headers", it) }
                }
            )
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject().apply {
                    transport?.optString("service_name")?.takeIf(String::isNotBlank)
                        ?.let { put("serviceName", it) }
                }
            )
            "httpupgrade" -> stream.put(
                "httpupgradeSettings",
                JSONObject().apply {
                    transport?.optString("path")?.takeIf(String::isNotBlank)?.let { put("path", it) }
                    transport?.optJSONObject("headers")?.optString("Host")?.takeIf(String::isNotBlank)
                        ?.let { put("host", it) }
                }
            )
            "http", "h2", "xhttp" -> stream.put(
                if (network == "xhttp") "xhttpSettings" else "httpSettings",
                JSONObject().apply {
                    transport?.optString("path")?.takeIf(String::isNotBlank)?.let { put("path", it) }
                    transport?.optJSONObject("headers")?.optString("Host")?.takeIf(String::isNotBlank)
                        ?.let { put("host", JSONArray().put(it)) }
                }
            )
        }

        out.optJSONObject("tls")?.takeIf { it.optBoolean("enabled", true) }?.let { tls ->
            val reality = tls.optJSONObject("reality")?.takeIf { it.optBoolean("enabled", true) }
            stream.put("security", if (reality != null) "reality" else "tls")
            val settings = JSONObject()
                .put("serverName", tls.optString("server_name", server))
            tls.optBoolean("insecure", false).let { settings.put("allowInsecure", it) }
            tls.optJSONArray("alpn")?.let { settings.put("alpn", it) }
            tls.optJSONObject("utls")?.optString("fingerprint")?.takeIf(String::isNotBlank)
                ?.let { settings.put("fingerprint", it) }
            reality?.let {
                it.optString("public_key").takeIf(String::isNotBlank)
                    ?.let { value -> settings.put("publicKey", value) }
                it.optString("short_id").takeIf(String::isNotBlank)
                    ?.let { value -> settings.put("shortId", value) }
            }
            stream.put(if (reality != null) "realitySettings" else "tlsSettings", settings)
        }
        return stream
    }

    private fun mihomoProxyToProfile(map: Map<String, Any?>): ConfigProfile? {
        val type = text(map["type"]).lowercase(Locale.ROOT)
        val server = text(map["server"]).trim()
        val port = int(map["port"]) ?: return null
        if (type.isBlank() || server.isBlank() || port !in 1..65535) return null

        val protocol = canonicalProtocol(type)
        val xray = JSONObject().put("tag", "proxy").put("protocol", protocol)
        val settings = JSONObject()
        when (protocol) {
            "vless", "vmess" -> {
                val user = JSONObject().put("id", text(map["uuid"]))
                if (protocol == "vless") user.put("encryption", text(map["encryption"]).ifBlank { "none" })
                if (protocol == "vmess") {
                    user.put("security", text(map["cipher"]).ifBlank { "auto" })
                    user.put("alterId", int(map["alterId"] ?: map["alter-id"]) ?: 0)
                }
                text(map["flow"]).takeIf(String::isNotBlank)?.let { user.put("flow", it) }
                settings.put(
                    "vnext",
                    JSONArray().put(
                        JSONObject().put("address", server).put("port", port)
                            .put("users", JSONArray().put(user))
                    )
                )
            }
            "trojan" -> settings.put(
                "servers",
                JSONArray().put(
                    JSONObject().put("address", server).put("port", port)
                        .put("password", text(map["password"]))
                )
            )
            "shadowsocks" -> settings.put(
                "servers",
                JSONArray().put(
                    JSONObject().put("address", server).put("port", port)
                        .put("method", text(map["cipher"] ?: map["method"]))
                        .put("password", text(map["password"]))
                )
            )
            "socks", "http" -> settings.put(
                "servers",
                JSONArray().put(
                    JSONObject().put("address", server).put("port", port)
                        .put("users", credentialUsers(text(map["username"]), text(map["password"])))
                )
            )
            else -> return null
        }
        xray.put("settings", settings)
        xray.put("streamSettings", mihomoStreamSettings(map, server))
        val rawJson = JSONObject().also { target -> map.forEach { (key, value) -> target.put(key, jsonValue(value)) } }
        return ConfigProfile(
            id = UUID.randomUUID().toString(),
            name = text(map["name"]).ifBlank { "$server:$port" },
            protocol = protocol,
            server = server,
            port = port,
            raw = rawJson.toString(),
            outboundJson = xray.toString(),
            probeServer = server,
            probePort = port
        )
    }

    private fun mihomoStreamSettings(map: Map<String, Any?>, server: String): JSONObject {
        val network = normalizeNetwork(text(map["network"]))
        val stream = JSONObject().put("network", network)
        val ws = mapValue(map["ws-opts"])
        val grpc = mapValue(map["grpc-opts"])
        val h2 = mapValue(map["h2-opts"])
        val httpUpgrade = mapValue(map["http-upgrade-opts"] ?: map["httpupgrade-opts"])
        when (network) {
            "ws" -> {
                val headers = mapValue(ws["headers"] ?: map["ws-headers"])
                stream.put(
                    "wsSettings",
                    JSONObject().apply {
                        val path = text(ws["path"] ?: map["ws-path"])
                        if (path.isNotBlank()) put("path", path)
                        if (headers.isNotEmpty()) put("headers", mapToJson(headers))
                    }
                )
            }
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject().apply {
                    text(grpc["grpc-service-name"] ?: grpc["service-name"] ?: map["grpc-service-name"])
                        .takeIf(String::isNotBlank)?.let { put("serviceName", it) }
                }
            )
            "httpupgrade" -> stream.put(
                "httpupgradeSettings",
                JSONObject().apply {
                    text(httpUpgrade["path"] ?: map["http-upgrade-path"])
                        .takeIf(String::isNotBlank)?.let { put("path", it) }
                    val headers = mapValue(httpUpgrade["headers"])
                    text(headers["Host"] ?: headers["host"] ?: map["host"])
                        .takeIf(String::isNotBlank)?.let { put("host", it) }
                }
            )
            "http", "h2" -> stream.put(
                "httpSettings",
                JSONObject().apply {
                    val path = text(h2["path"] ?: map["http-path"])
                    if (path.isNotBlank()) put("path", path)
                    val host = firstText(h2["host"], map["host"], map["servername"], map["sni"])
                    if (host.isNotBlank()) put("host", JSONArray().put(host))
                }
            )
        }

        val reality = mapValue(map["reality-opts"])
        val tlsEnabled = bool(map["tls"]) == true || reality.isNotEmpty()
        if (tlsEnabled) {
            stream.put("security", if (reality.isNotEmpty()) "reality" else "tls")
            val tls = JSONObject()
            firstText(map["servername"], map["sni"], server)
                .takeIf(String::isNotBlank)?.let { tls.put("serverName", it) }
            bool(map["skip-cert-verify"])?.let { tls.put("allowInsecure", it) }
            firstText(map["client-fingerprint"], map["fingerprint"])
                .takeIf(String::isNotBlank)?.let { tls.put("fingerprint", it) }
            listValue(map["alpn"]).takeIf { it.isNotEmpty() }
                ?.let { tls.put("alpn", JSONArray(it.map(::text))) }
            if (reality.isNotEmpty()) {
                firstText(reality["public-key"], reality["public_key"])
                    .takeIf(String::isNotBlank)?.let { tls.put("publicKey", it) }
                firstText(reality["short-id"], reality["short_id"])
                    .takeIf(String::isNotBlank)?.let { tls.put("shortId", it) }
            }
            stream.put(if (reality.isNotEmpty()) "realitySettings" else "tlsSettings", tls)
        }
        return stream
    }

    private fun canonicalProtocol(type: String): String = when (type.lowercase(Locale.ROOT)) {
        "ss", "shadowsocks" -> "shadowsocks"
        "socks5" -> "socks"
        "https" -> "http"
        else -> type.lowercase(Locale.ROOT)
    }

    private fun normalizeNetwork(value: String): String = when (value.trim().lowercase(Locale.ROOT)) {
        "", "none", "raw", "tcp" -> "tcp"
        "websocket" -> "ws"
        "http-upgrade", "http_upgrade" -> "httpupgrade"
        "splithttp" -> "xhttp"
        else -> value.trim().lowercase(Locale.ROOT)
    }

    private fun credentialUsers(username: String, password: String): JSONArray =
        if (username.isBlank() && password.isBlank()) JSONArray()
        else JSONArray().put(JSONObject().put("user", username).put("pass", password))

    private fun jsonObjectToMap(value: JSONObject): Map<String, Any?> =
        value.keys().asSequence().associateWith { key -> jsonToKotlin(value.opt(key)) }

    private fun jsonToKotlin(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonToKotlin(value.opt(it)) }
        else -> value
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> mapToJson(value.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> JSONArray(value.map(::jsonValue))
        else -> value
    }

    private fun mapToJson(map: Map<String, Any?>): JSONObject =
        JSONObject().also { json -> map.forEach { (key, value) -> json.put(key, jsonValue(value)) } }

    private fun mapValue(value: Any?): Map<String, Any?> = MiniYaml.stringMap(value)
    private fun listValue(value: Any?): List<Any?> = MiniYaml.list(value)
    private fun text(value: Any?): String = MiniYaml.string(value)
    private fun int(value: Any?): Int? = MiniYaml.int(value)
    private fun bool(value: Any?): Boolean? = MiniYaml.bool(value)
    private fun firstText(vararg values: Any?): String = values.map(::text).firstOrNull(String::isNotBlank).orEmpty()

    private val NON_PROXY_TYPES = setOf(
        "direct", "block", "dns", "selector", "urltest", "url-test", "fallback"
    )
}
