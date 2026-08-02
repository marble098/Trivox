package com.trivox.client.config

import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.TestStatus
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class ConfigEditorFields(
    var name: String = "",
    var protocol: String = "vless",
    var address: String = "",
    var port: Int = 443,
    var credential: String = "",
    var encryption: String = "none",
    var flow: String = "",
    var transport: String = "tcp",
    var headerType: String = "none",
    var host: String = "",
    var path: String = "",
    var security: String = "none",
    var sni: String = "",
    var fingerprint: String = "",
    var publicKey: String = "",
    var shortId: String = "",
    var serviceName: String = ""
)

object ConfigEditorCodec {
    fun read(profile: ConfigProfile): ConfigEditorFields =
        readOutbound(
            profile.name,
            normalizeOutbound(JSONObject(profile.outboundJson))
        )

    fun fieldsFromJson(
        existing: ConfigProfile,
        jsonText: String
    ): ConfigEditorFields {
        val outbound = extractOutbound(JSONObject(jsonText))
        return readOutbound(existing.name, normalizeOutbound(outbound))
    }

    fun jsonFromFields(fields: ConfigEditorFields): String =
        buildOutbound(fields).toString(2)

    fun updateFromFields(
        existing: ConfigProfile,
        fields: ConfigEditorFields
    ): ConfigProfile {
        validate(fields)
        val outbound = buildOutbound(fields)
        val raw = buildShareUri(fields)
            ?: JSONObject()
                .put("outbounds", JSONArray().put(outbound))
                .toString(2)

        return replacement(
            existing = existing,
            name = fields.name.trim(),
            protocol = normalizedProtocol(fields.protocol),
            address = fields.address.trim(),
            port = fields.port,
            raw = raw,
            outbound = outbound,
            originalDnsJson = existing.originalDnsJson
        )
    }

    fun updateFromJson(
        existing: ConfigProfile,
        jsonText: String
    ): ConfigProfile {
        val root = JSONObject(jsonText)
        val outbound = normalizeOutbound(extractOutbound(root))
        val protocol = normalizedProtocol(outbound.optString("protocol"))
        val endpoint = endpoint(outbound)
        val fullRaw =
            if (root.has("outbounds")) {
                root.toString(2)
            } else {
                JSONObject()
                    .put("outbounds", JSONArray().put(outbound))
                    .toString(2)
            }

        return replacement(
            existing = existing,
            name = existing.name,
            protocol = protocol,
            address = endpoint.first,
            port = endpoint.second,
            raw = fullRaw,
            outbound = outbound,
            originalDnsJson = root.optJSONObject("dns")?.toString()
                ?: existing.originalDnsJson
        )
    }

    private fun readOutbound(
        profileName: String,
        outbound: JSONObject
    ): ConfigEditorFields {
        val protocol = normalizedProtocol(outbound.optString("protocol"))
        val endpoint = endpoint(outbound)
        val settings = outbound.optJSONObject("settings") ?: JSONObject()
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
        val network = normalizeNetwork(stream.optString("network"))

        var credential = ""
        var encryption = "none"
        var flow = ""

        when (protocol) {
            "vless", "vmess" -> {
                val user = settings
                    .optJSONArray("vnext")
                    ?.optJSONObject(0)
                    ?.optJSONArray("users")
                    ?.optJSONObject(0)
                    ?: JSONObject()
                credential = user.optString("id")
                encryption =
                    if (protocol == "vless") {
                        user.optString("encryption", "none")
                    } else {
                        user.optString("security", "auto")
                    }
                flow = user.optString("flow")
            }

            "trojan" -> {
                credential = settings
                    .optJSONArray("servers")
                    ?.optJSONObject(0)
                    ?.optString("password")
                    .orEmpty()
            }

            "shadowsocks" -> {
                val server = settings
                    .optJSONArray("servers")
                    ?.optJSONObject(0)
                    ?: JSONObject()
                credential = server.optString("password")
                encryption = server.optString("method", "aes-256-gcm")
            }

            "socks", "http" -> {
                val user = settings
                    .optJSONArray("servers")
                    ?.optJSONObject(0)
                    ?.optJSONArray("users")
                    ?.optJSONObject(0)
                    ?: JSONObject()
                val username = user.optString("user")
                val password = user.optString("pass")
                credential = listOf(username, password)
                    .filter(String::isNotBlank)
                    .joinToString(":")
            }
        }

        val security = stream.optString("security", "none")
            .ifBlank { "none" }
            .lowercase()

        val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
        val reality = stream.optJSONObject("realitySettings") ?: JSONObject()

        val hostAndPath = transportValues(stream, network)
        val header = when (network) {
            "tcp" -> stream.optJSONObject("tcpSettings")
                ?.optJSONObject("header")
                ?.optString("type", "none")
                ?: "none"

            "kcp" -> stream.optJSONObject("kcpSettings")
                ?.optJSONObject("header")
                ?.optString("type", "none")
                ?: "none"

            "quic" -> stream.optJSONObject("quicSettings")
                ?.optJSONObject("header")
                ?.optString("type", "none")
                ?: "none"

            else -> "none"
        }

        return ConfigEditorFields(
            name = profileName,
            protocol = protocol,
            address = endpoint.first,
            port = endpoint.second,
            credential = credential,
            encryption = encryption,
            flow = flow,
            transport = network,
            headerType = header,
            host = hostAndPath.first,
            path = hostAndPath.second,
            security = security,
            sni = reality.optString("serverName")
                .ifBlank { tls.optString("serverName") },
            fingerprint = reality.optString("fingerprint")
                .ifBlank { tls.optString("fingerprint") },
            publicKey = reality.optString("publicKey"),
            shortId = reality.optString("shortId"),
            serviceName = stream.optJSONObject("grpcSettings")
                ?.optString("serviceName")
                .orEmpty()
        )
    }

    private fun buildOutbound(fields: ConfigEditorFields): JSONObject {
        validate(fields)

        val protocol = normalizedProtocol(fields.protocol)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", protocol)

        when (protocol) {
            "vless", "vmess" -> {
                val user = JSONObject().put("id", fields.credential.trim())
                if (protocol == "vless") {
                    user.put(
                        "encryption",
                        fields.encryption.trim().ifBlank { "none" }
                    )
                    fields.flow.trim().takeIf(String::isNotBlank)?.let {
                        user.put("flow", it)
                    }
                } else {
                    user.put(
                        "alterId",
                        0
                    ).put(
                        "security",
                        fields.encryption.trim().ifBlank { "auto" }
                    )
                }

                outbound.put(
                    "settings",
                    JSONObject().put(
                        "vnext",
                        JSONArray().put(
                            JSONObject()
                                .put("address", fields.address.trim())
                                .put("port", fields.port)
                                .put("users", JSONArray().put(user))
                        )
                    )
                )
            }

            "trojan" -> {
                outbound.put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", fields.address.trim())
                                .put("port", fields.port)
                                .put("password", fields.credential)
                        )
                    )
                )
            }

            "shadowsocks" -> {
                outbound.put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", fields.address.trim())
                                .put("port", fields.port)
                                .put(
                                    "method",
                                    fields.encryption.trim()
                                        .ifBlank { "aes-256-gcm" }
                                )
                                .put("password", fields.credential)
                        )
                    )
                )
            }

            "socks", "http" -> {
                val server = JSONObject()
                    .put("address", fields.address.trim())
                    .put("port", fields.port)
                val user = fields.credential.substringBefore(':').trim()
                val pass = fields.credential.substringAfter(':', "").trim()
                if (user.isNotBlank() || pass.isNotBlank()) {
                    server.put(
                        "users",
                        JSONArray().put(
                            JSONObject()
                                .put("user", user)
                                .put("pass", pass)
                        )
                    )
                }
                outbound.put(
                    "settings",
                    JSONObject().put("servers", JSONArray().put(server))
                )
            }

            else -> throw IllegalArgumentException("Unsupported protocol: $protocol")
        }

        if (protocol in setOf("vless", "vmess", "trojan")) {
            outbound.put("streamSettings", buildStream(fields))
        }

        return normalizeOutbound(outbound)
    }

    private fun buildStream(fields: ConfigEditorFields): JSONObject {
        val network = normalizeNetwork(fields.transport)
        val stream = JSONObject().put("network", network)

        when (network) {
            "tcp" -> {
                val type = fields.headerType.trim().ifBlank { "none" }
                stream.put(
                    "tcpSettings",
                    JSONObject().put(
                        "header",
                        JSONObject().put("type", type)
                    )
                )
            }

            "ws" -> {
                val settings = JSONObject()
                    .put("path", fields.path.trim().ifBlank { "/" })
                fields.host.trim().takeIf(String::isNotBlank)?.let {
                    settings.put(
                        "headers",
                        JSONObject().put("Host", it)
                    )
                }
                stream.put("wsSettings", settings)
            }

            "grpc" -> {
                stream.put(
                    "grpcSettings",
                    JSONObject().put(
                        "serviceName",
                        fields.serviceName.trim()
                    )
                )
            }

            "xhttp" -> {
                stream.put(
                    "xhttpSettings",
                    JSONObject()
                        .put("path", fields.path.trim().ifBlank { "/" })
                        .apply {
                            fields.host.trim().takeIf(String::isNotBlank)?.let {
                                put("host", it)
                            }
                        }
                )
            }

            "httpupgrade" -> {
                stream.put(
                    "httpupgradeSettings",
                    JSONObject()
                        .put("path", fields.path.trim().ifBlank { "/" })
                        .apply {
                            fields.host.trim().takeIf(String::isNotBlank)?.let {
                                put("host", it)
                            }
                        }
                )
            }

            "http" -> {
                val settings = JSONObject()
                    .put(
                        "path",
                        fields.path.trim().ifBlank { "/" }
                    )
                fields.host.trim().takeIf(String::isNotBlank)?.let {
                    settings.put("host", JSONArray().put(it))
                }
                stream.put("httpSettings", settings)
            }

            "kcp" -> {
                stream.put(
                    "kcpSettings",
                    JSONObject().put(
                        "header",
                        JSONObject().put(
                            "type",
                            fields.headerType.trim().ifBlank { "none" }
                        )
                    )
                )
            }

            "quic" -> {
                stream.put(
                    "quicSettings",
                    JSONObject().put(
                        "header",
                        JSONObject().put(
                            "type",
                            fields.headerType.trim().ifBlank { "none" }
                        )
                    )
                )
            }
        }

        val security = fields.security.trim().lowercase().ifBlank { "none" }
        if (security != "none") {
            stream.put("security", security)
        }

        when (security) {
            "tls" -> {
                val tls = JSONObject()
                fields.sni.trim().takeIf(String::isNotBlank)?.let {
                    tls.put("serverName", it)
                }
                fields.fingerprint.trim().takeIf(String::isNotBlank)?.let {
                    tls.put("fingerprint", it)
                }
                stream.put("tlsSettings", tls)
            }

            "reality" -> {
                val reality = JSONObject()
                fields.sni.trim().takeIf(String::isNotBlank)?.let {
                    reality.put("serverName", it)
                }
                fields.fingerprint.trim().takeIf(String::isNotBlank)?.let {
                    reality.put("fingerprint", it)
                }
                fields.publicKey.trim().takeIf(String::isNotBlank)?.let {
                    reality.put("publicKey", it)
                }
                fields.shortId.trim().takeIf(String::isNotBlank)?.let {
                    reality.put("shortId", it)
                }
                stream.put("realitySettings", reality)
            }
        }

        return stream
    }

    private fun buildShareUri(
        fields: ConfigEditorFields
    ): String? {
        val protocol = normalizedProtocol(fields.protocol)
        val host = formatHost(fields.address.trim())
        val name = encode(fields.name.trim())

        return when (protocol) {
            "vless" -> {
                val query = linkedMapOf(
                    "encryption" to fields.encryption.trim().ifBlank { "none" },
                    "type" to normalizeNetwork(fields.transport),
                    "security" to fields.security.trim().ifBlank { "none" },
                    "flow" to fields.flow.trim(),
                    "host" to fields.host.trim(),
                    "path" to fields.path.trim(),
                    "serviceName" to fields.serviceName.trim(),
                    "headerType" to fields.headerType.trim(),
                    "sni" to fields.sni.trim(),
                    "fp" to fields.fingerprint.trim(),
                    "pbk" to fields.publicKey.trim(),
                    "sid" to fields.shortId.trim()
                )
                "vless://${encode(fields.credential)}@$host:${fields.port}?${queryString(query)}#$name"
            }

            "trojan" -> {
                val query = linkedMapOf(
                    "type" to normalizeNetwork(fields.transport),
                    "security" to fields.security.trim().ifBlank { "tls" },
                    "host" to fields.host.trim(),
                    "path" to fields.path.trim(),
                    "serviceName" to fields.serviceName.trim(),
                    "sni" to fields.sni.trim(),
                    "fp" to fields.fingerprint.trim(),
                    "pbk" to fields.publicKey.trim(),
                    "sid" to fields.shortId.trim()
                )
                "trojan://${encode(fields.credential)}@$host:${fields.port}?${queryString(query)}#$name"
            }

            "vmess" -> {
                val json = JSONObject()
                    .put("v", "2")
                    .put("ps", fields.name.trim())
                    .put("add", fields.address.trim())
                    .put("port", fields.port.toString())
                    .put("id", fields.credential.trim())
                    .put("aid", "0")
                    .put("scy", fields.encryption.trim().ifBlank { "auto" })
                    .put("net", normalizeNetwork(fields.transport))
                    .put("type", fields.headerType.trim().ifBlank { "none" })
                    .put("host", fields.host.trim())
                    .put("path", fields.path.trim())
                    .put(
                        "tls",
                        fields.security.trim().lowercase()
                            .takeIf { it != "none" }
                            .orEmpty()
                    )
                    .put("sni", fields.sni.trim())
                    .put("fp", fields.fingerprint.trim())
                    .put("serviceName", fields.serviceName.trim())
                    .put("pbk", fields.publicKey.trim())
                    .put("sid", fields.shortId.trim())
                val encoded = Base64.getEncoder()
                    .withoutPadding()
                    .encodeToString(
                        json.toString().toByteArray(StandardCharsets.UTF_8)
                    )
                "vmess://$encoded"
            }

            "shadowsocks" -> {
                val credential = "${fields.encryption.trim()}:${fields.credential}"
                val encoded = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                        credential.toByteArray(StandardCharsets.UTF_8)
                    )
                "ss://$encoded@$host:${fields.port}#$name"
            }

            else -> null
        }
    }

    private fun queryString(values: Map<String, String>): String =
        values.entries
            .filter { it.value.isNotBlank() }
            .joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }

    private fun transportValues(
        stream: JSONObject,
        network: String
    ): Pair<String, String> =
        when (network) {
            "ws" -> {
                val settings = stream.optJSONObject("wsSettings") ?: JSONObject()
                settings.optJSONObject("headers")
                    ?.optString("Host")
                    .orEmpty() to settings.optString("path")
            }

            "grpc" -> "" to ""

            "xhttp" -> {
                val settings = stream.optJSONObject("xhttpSettings") ?: JSONObject()
                settings.optString("host") to settings.optString("path")
            }

            "httpupgrade" -> {
                val settings = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                settings.optString("host") to settings.optString("path")
            }

            "http" -> {
                val settings = stream.optJSONObject("httpSettings") ?: JSONObject()
                settings.optJSONArray("host")?.optString(0).orEmpty() to
                    settings.optString("path")
            }

            else -> "" to ""
        }

    private fun extractOutbound(root: JSONObject): JSONObject {
        if (!root.has("outbounds")) return JSONObject(root.toString())

        val array = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("JSON field 'outbounds' is invalid")

        return (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .firstOrNull {
                it.optString("protocol") !in setOf(
                    "freedom",
                    "blackhole",
                    "dns"
                )
            }
            ?.let { JSONObject(it.toString()) }
            ?: throw IllegalArgumentException("No usable proxy outbound was found")
    }

    private fun endpoint(outbound: JSONObject): Pair<String, Int> {
        val protocol = normalizedProtocol(outbound.optString("protocol"))
        val settings = outbound.optJSONObject("settings") ?: JSONObject()

        return when (protocol) {
            "vless", "vmess" -> {
                val endpoint = settings.optJSONArray("vnext")?.optJSONObject(0)
                    ?: throw IllegalArgumentException("The vnext endpoint is missing")
                endpoint.optString("address") to endpoint.optInt("port")
            }

            "trojan", "shadowsocks", "socks", "http" -> {
                val endpoint = settings.optJSONArray("servers")?.optJSONObject(0)
                    ?: throw IllegalArgumentException("The server endpoint is missing")
                endpoint.optString("address") to endpoint.optInt("port")
            }

            else -> throw IllegalArgumentException("Unsupported protocol: $protocol")
        }.also {
            require(it.first.isNotBlank()) { "Server address is empty" }
            require(Validators.validPort(it.second)) { "Server port is invalid" }
        }
    }

    private fun replacement(
        existing: ConfigProfile,
        name: String,
        protocol: String,
        address: String,
        port: Int,
        raw: String,
        outbound: JSONObject,
        originalDnsJson: String?
    ) = ConfigProfile(
        id = existing.id,
        name = name.ifBlank { existing.name },
        protocol = protocol,
        server = address,
        port = port,
        raw = raw,
        outboundJson = normalizeOutbound(outbound).toString(),
        originalDnsJson = originalDnsJson,
        enabled = existing.enabled,
        favorite = existing.favorite,
        group = existing.group,
        subscriptionId = null,
        latencyMs = null,
        testStatus = TestStatus.UNTESTED,
        lastTestAt = 0,
        lastSessionMs = existing.lastSessionMs,
        cumulativeSessionMs = existing.cumulativeSessionMs,
        exitIp = "",
        exitCountry = "",
        exitCountryCode = "",
        exitFlag = "",
        exitIsp = "",
        lastExitCheckAt = 0
    )

    private fun validate(fields: ConfigEditorFields) {
        require(fields.name.trim().isNotBlank()) { "Remarks cannot be empty" }
        require(fields.address.trim().isNotBlank()) { "Address cannot be empty" }
        require(Validators.validPort(fields.port)) { "Port must be between 1 and 65535" }
        require(fields.credential.isNotBlank()) { "User ID or password cannot be empty" }
        require(normalizedProtocol(fields.protocol) in SUPPORTED_PROTOCOLS) {
            "Unsupported protocol: ${fields.protocol}"
        }
    }

    private fun normalizeOutbound(outbound: JSONObject): JSONObject {
        val stream = outbound.optJSONObject("streamSettings")
        if (stream != null) {
            stream.put(
                "network",
                normalizeNetwork(stream.optString("network"))
            )
        }
        return outbound
    }

    fun normalizeNetwork(value: String?): String {
        val network = value.orEmpty().trim().lowercase()
        return when (network) {
            "", "none", "null" -> "tcp"
            "h2" -> "http"
            else -> network
        }
    }

    private fun normalizedProtocol(value: String): String =
        when (value.trim().lowercase()) {
            "ss" -> "shadowsocks"
            "socks5" -> "socks"
            else -> value.trim().lowercase()
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

    private fun formatHost(host: String): String =
        if (':' in host && !host.startsWith('[')) "[$host]" else host

    private val SUPPORTED_PROTOCOLS = setOf(
        "vless",
        "vmess",
        "trojan",
        "shadowsocks",
        "socks",
        "http"
    )
}
