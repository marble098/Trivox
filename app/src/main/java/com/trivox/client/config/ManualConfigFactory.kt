package com.trivox.client.config

import com.trivox.client.data.ConfigProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ManualConfigFactory {
    fun draft(protocol: String): ConfigProfile {
        val normalized = protocol.trim().lowercase()
        require(
            normalized in setOf(
                "vmess",
                "vless",
                "shadowsocks",
                "socks",
                "http",
                "trojan"
            )
        ) { "Unsupported manual protocol: $normalized" }

        val port = when (normalized) {
            "vless", "vmess", "trojan" -> 443
            "shadowsocks" -> 8388
            else -> 1080
        }
        val credential = when (normalized) {
            "vless", "vmess" -> UUID.randomUUID().toString()
            else -> ""
        }
        val encryption = when (normalized) {
            "vmess" -> "auto"
            "shadowsocks" -> "aes-256-gcm"
            else -> "none"
        }
        val security = if (normalized == "trojan") "tls" else "none"
        val fields = ConfigEditorFields(
            name = "New ${normalized.uppercase()}",
            protocol = normalized,
            address = "",
            port = port,
            credential = credential,
            encryption = encryption,
            security = security
        )
        val outbound = emptyOutbound(fields)

        return ConfigProfile(
            name = fields.name,
            protocol = normalized,
            server = "",
            port = port,
            raw = JSONObject()
                .put("outbounds", JSONArray().put(outbound))
                .toString(),
            outboundJson = outbound.toString(),
            group = "Manual"
        )
    }

    fun wireGuard(
        name: String,
        endpoint: String,
        privateKey: String,
        publicKey: String,
        address: String,
        allowed: String,
        preShared: String,
        mtu: Int
    ): ConfigProfile {
        val endpointValue = endpoint.trim()
        val endpointParts = parseEndpoint(endpointValue)
        require(privateKey.isNotBlank()) { "Private key is required" }
        require(publicKey.isNotBlank()) { "Public key is required" }

        val addresses = splitValues(address).ifEmpty {
            listOf("10.0.0.2/32")
        }
        val allowedIps = splitValues(allowed).ifEmpty {
            listOf("0.0.0.0/0", "::/0")
        }
        val peer = JSONObject()
            .put("endpoint", endpointValue)
            .put("publicKey", publicKey.trim())
            .put("allowedIPs", JSONArray(allowedIps))

        preShared.trim()
            .takeIf(String::isNotBlank)
            ?.let { peer.put("preSharedKey", it) }

        val outbound = JSONObject()
            .put("protocol", "wireguard")
            .put(
                "settings",
                JSONObject()
                    .put("secretKey", privateKey.trim())
                    .put("address", JSONArray(addresses))
                    .put("noKernelTun", true)
                    .put("mtu", mtu.coerceIn(576, 9000))
                    /*
                     * Domain strategy, workers and default peer keep-alive are
                     * intentionally supplied by XrayConfigBuilder from Settings.
                     * The generated profile therefore remains portable and does
                     * not silently override the user's global WireGuard policy.
                     */
                    .put("peers", JSONArray().put(peer))
            )

        return profile(
            name = name.ifBlank { "WireGuard" },
            protocol = "wireguard",
            server = endpointParts.first,
            port = endpointParts.second,
            outbound = outbound
        )
    }

    fun hysteria2(
        name: String,
        address: String,
        port: Int,
        auth: String,
        sni: String,
        insecure: Boolean
    ): ConfigProfile {
        val host = address.trim()
        require(host.isNotBlank()) { "Server address is required" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(auth.isNotBlank()) { "Authentication password is required" }

        val hysteriaSettings = JSONObject()
            .put("version", 2)
            .put("auth", auth.trim())
            .put("udpIdleTimeout", 60)

        val tlsSettings = JSONObject()
            .put("alpn", JSONArray().put("h3"))
            .put("allowInsecure", insecure)

        sni.trim()
            .takeIf(String::isNotBlank)
            ?.let { tlsSettings.put("serverName", it) }

        val outbound = JSONObject()
            .put("protocol", "hysteria")
            .put(
                "settings",
                JSONObject()
                    .put("version", 2)
                    .put("address", host)
                    .put("port", port)
            )
            .put(
                "streamSettings",
                JSONObject()
                    .put("network", "hysteria")
                    .put("security", "tls")
                    .put("tlsSettings", tlsSettings)
                    .put("hysteriaSettings", hysteriaSettings)
            )

        return profile(
            name = name.ifBlank { "Hysteria2" },
            protocol = "hysteria",
            server = host,
            port = port,
            outbound = outbound
        )
    }

    fun chain(
        name: String,
        exit: ConfigProfile,
        bridge: ConfigProfile
    ): ConfigProfile {
        require(exit.id != bridge.id) {
            "Exit and bridge profiles must be different"
        }
        require(exit.protocol != "chain" && bridge.protocol != "chain") {
            "Nested proxy chains are not supported"
        }

        val encoded = ProxyChainCodec.encode(exit, bridge)
        return ConfigProfile(
            name = name.trim().ifBlank {
                "${exit.name} via ${bridge.name}"
            },
            protocol = "chain",
            server = exit.server,
            port = exit.port,
            raw = encoded,
            outboundJson = encoded,
            group = "Proxy chains"
        )
    }

    private fun emptyOutbound(fields: ConfigEditorFields): JSONObject {
        val address = fields.address
        return when (fields.protocol) {
            "vless", "vmess" -> {
                val user = JSONObject().put("id", fields.credential)
                if (fields.protocol == "vless") {
                    user.put("encryption", "none")
                } else {
                    user.put("alterId", 0).put("security", "auto")
                }
                JSONObject()
                    .put("protocol", fields.protocol)
                    .put(
                        "settings",
                        JSONObject().put(
                            "vnext",
                            JSONArray().put(
                                JSONObject()
                                    .put("address", address)
                                    .put("port", fields.port)
                                    .put("users", JSONArray().put(user))
                            )
                        )
                    )
                    .put("streamSettings", JSONObject().put("network", "tcp"))
            }

            "trojan" -> JSONObject()
                .put("protocol", "trojan")
                .put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", address)
                                .put("port", fields.port)
                                .put("password", "")
                        )
                    )
                )
                .put(
                    "streamSettings",
                    JSONObject()
                        .put("network", "tcp")
                        .put("security", "tls")
                )

            "shadowsocks" -> JSONObject()
                .put("protocol", "shadowsocks")
                .put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", address)
                                .put("port", fields.port)
                                .put("method", "aes-256-gcm")
                                .put("password", "")
                        )
                    )
                )

            "socks", "http" -> JSONObject()
                .put("protocol", fields.protocol)
                .put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", address)
                                .put("port", fields.port)
                        )
                    )
                )

            else -> error("Unsupported protocol")
        }
    }

    private fun profile(
        name: String,
        protocol: String,
        server: String,
        port: Int,
        outbound: JSONObject
    ): ConfigProfile {
        val full = JSONObject()
            .put("outbounds", JSONArray().put(outbound))
            .toString(2)
        return ConfigProfile(
            name = name.trim(),
            protocol = protocol,
            server = server,
            port = port,
            raw = full,
            outboundJson = outbound.toString(),
            group = "Manual"
        )
    }

    private fun splitValues(value: String): List<String> =
        value.split(',', '\n')
            .map(String::trim)
            .filter(String::isNotBlank)

    private fun parseEndpoint(value: String): Pair<String, Int> {
        require(value.isNotBlank()) { "Endpoint is required" }
        val host: String
        val portText: String
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            require(close > 0 && close + 2 <= value.lastIndex) {
                "IPv6 endpoint must use [address]:port"
            }
            host = value.substring(1, close)
            portText = value.substring(close + 2)
        } else {
            host = value.substringBeforeLast(':', "")
            portText = value.substringAfterLast(':', "")
        }
        val port = portText.toIntOrNull()
        require(host.isNotBlank() && port in 1..65535) {
            "Endpoint must use host:port"
        }
        return host to port!!
    }
}
