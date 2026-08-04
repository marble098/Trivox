package com.trivox.client.config

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Non-destructive compatibility pass for Xray outbound JSON.
 *
 * Share-link parsers should keep producing canonical JSON. Full JSON imports,
 * however, often contain historical aliases or fields copied from newer Xray
 * documentation. This pass normalizes only aliases with identical semantics and
 * preserves every unknown field for the pinned core validator to decide.
 */
object XrayCompatibility {
    fun normalizeOutbound(source: JSONObject): JSONObject {
        val outbound = JSONObject(source.toString())
        removeNullPlaceholders(outbound)

        val protocol = canonicalProtocol(outbound.optString("protocol"))
        if (protocol.isNotBlank()) outbound.put("protocol", protocol)

        val settings = outbound.optJSONObject("settings")
        if (protocol == "wireguard" && settings != null) {
            if (!settings.has("address") && settings.has("addresses")) {
                settings.put("address", settings.opt("addresses"))
            }
            val peers = settings.opt("peers")
            if (peers is JSONObject) {
                settings.put("peers", JSONArray().put(peers))
            }
        }

        if (protocol == "hysteria" && settings != null) {
            copyAlias(
                settings,
                "address",
                arrayOf("server", "host")
            )
            copyAlias(
                settings,
                "port",
                arrayOf("serverPort", "server_port")
            )
            settings.opt("port")
                ?.toString()
                ?.toIntOrNull()
                ?.takeIf { it in 1..65_535 }
                ?.let { settings.put("port", it) }
            settings.put("version", 2)
        }

        val stream = outbound.optJSONObject("streamSettings")
        if (protocol == "wireguard") {
            outbound.remove("streamSettings")
            return outbound
        }

        if (stream != null) {
            normalizeStream(stream)
        } else if (protocol == "hysteria") {
            outbound.put(
                "streamSettings",
                JSONObject()
                    .put("network", "hysteria")
                    .put("security", "tls")
                    .put("hysteriaSettings", JSONObject().put("version", 2))
            )
        }

        if (protocol == "hysteria") {
            settings?.put("version", 2)
            outbound.optJSONObject("streamSettings")?.let { hysteriaStream ->
                hysteriaStream.put("network", "hysteria")
                if (hysteriaStream.optString("security").isBlank()) {
                    hysteriaStream.put("security", "tls")
                }
                val hysteria = hysteriaStream.optJSONObject("hysteriaSettings")
                    ?: JSONObject().also {
                        hysteriaStream.put("hysteriaSettings", it)
                    }
                hysteria.put("version", 2)

                if (hysteria.optString("auth").isBlank()) {
                    firstText(settings, "auth", "password", "token")
                        ?.let { hysteria.put("auth", it) }
                }

                val sni = firstText(settings, "sni", "serverName")
                val insecure = firstBoolean(
                    settings,
                    "insecure",
                    "allowInsecure",
                    "skipCertVerify"
                )
                if (sni != null || insecure != null) {
                    val tls = hysteriaStream.optJSONObject("tlsSettings")
                        ?: JSONObject().also {
                            hysteriaStream.put("tlsSettings", it)
                        }
                    sni?.takeIf(String::isNotBlank)
                        ?.let { tls.put("serverName", it) }
                    insecure?.let { tls.put("allowInsecure", it) }
                }
            }
        }

        return outbound
    }

    private fun copyAlias(
        target: JSONObject,
        canonical: String,
        aliases: Array<String>
    ) {
        if (target.has(canonical)) return
        aliases.asSequence()
            .mapNotNull { key -> target.opt(key)?.takeUnless { it == JSONObject.NULL } }
            .firstOrNull()
            ?.let { target.put(canonical, it) }
    }

    private fun firstText(
        value: JSONObject?,
        vararg keys: String
    ): String? = value?.let { source ->
        keys.asSequence()
            .map(source::optString)
            .firstOrNull(String::isNotBlank)
    }

    private fun firstBoolean(
        value: JSONObject?,
        vararg keys: String
    ): Boolean? = value?.let { source ->
        keys.asSequence()
            .mapNotNull { key ->
                when (val raw = source.opt(key)) {
                    is Boolean -> raw
                    is Number -> raw.toInt() != 0
                    is String -> when (raw.trim().lowercase(Locale.ROOT)) {
                        "1", "true", "yes", "on" -> true
                        "0", "false", "no", "off" -> false
                        else -> null
                    }
                    else -> null
                }
            }
            .firstOrNull()
    }

    private fun normalizeStream(stream: JSONObject) {
        val method = stream.optString("method").trim()
        val network = stream.optString("network").trim()
        val canonical = canonicalTransport(
            network.ifBlank { method }
        )
        if (canonical.isNotBlank()) stream.put("network", canonical)
        stream.remove("method")

        if (!stream.has("rawSettings") && stream.has("tcpSettings")) {
            stream.put("rawSettings", stream.opt("tcpSettings"))
        }
        if (!stream.has("xhttpSettings") && stream.has("splithttpSettings")) {
            stream.put("xhttpSettings", stream.opt("splithttpSettings"))
        }

        val security = stream.optString("security").trim().lowercase(Locale.ROOT)
        if (security == "none" || security == "tls" || security == "reality") {
            stream.put("security", security)
        }
    }

    private fun canonicalProtocol(value: String): String =
        when (value.trim().lowercase(Locale.ROOT)) {
            "ss" -> "shadowsocks"
            "wg" -> "wireguard"
            "hy2", "hysteria2" -> "hysteria"
            else -> value.trim().lowercase(Locale.ROOT)
        }

    private fun canonicalTransport(value: String): String =
        when (value.trim().lowercase(Locale.ROOT)) {
            "", "none", "null" -> "tcp"
            "raw", "tcp" -> "tcp"
            "xhttp", "splithttp" -> "xhttp"
            "kcp", "mkcp" -> "mkcp"
            "ws", "websocket" -> "websocket"
            "grpc" -> "grpc"
            "httpupgrade", "http-upgrade" -> "httpupgrade"
            "hysteria", "hysteria2", "hy2" -> "hysteria"
            else -> value.trim().lowercase(Locale.ROOT)
        }

    private fun removeNullPlaceholders(value: Any?) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                keys.forEach { key ->
                    val child = value.opt(key)
                    if (child == null || child == JSONObject.NULL) {
                        value.remove(key)
                    } else {
                        removeNullPlaceholders(child)
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    removeNullPlaceholders(value.opt(index))
                }
            }
        }
    }
}
