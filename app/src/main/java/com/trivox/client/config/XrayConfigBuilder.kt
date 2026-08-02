package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.DnsMode
import org.json.JSONArray
import org.json.JSONObject

object Validators {
    fun validPort(port: Int): Boolean =
        port in 1..65535

    fun validateDns(value: String): Boolean {
        val text = value.trim()

        if (
            text.isBlank() ||
            text.any(Char::isWhitespace)
        ) {
            return false
        }

        if (
            text.startsWith("https://") ||
            text.startsWith("https+local://") ||
            text.startsWith("tcp://") ||
            text.startsWith("quic://")
        ) {
            return true
        }

        if (text.startsWith('[')) {
            return text.contains(']')
        }

        return text.matches(
            Regex("[A-Za-z0-9._:-]+")
        )
    }
}

object XrayConfigBuilder {
    private val privateNetworks = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.0.0.0/24",
        "192.0.2.0/24",
        "192.88.99.0/24",
        "192.168.0.0/16",
        "198.18.0.0/15",
        "198.51.100.0/24",
        "203.0.113.0/24",
        "224.0.0.0/4",
        "240.0.0.0/4",
        "::/128",
        "::1/128",
        "2001:db8::/32",
        "fc00::/7",
        "fe80::/10",
        "ff00::/8"
    )

    fun build(
        profile: ConfigProfile,
        settings: AppSettings,
        mode: ConnectionMode,
        tunFd: Int? = null
    ): String {
        settings.normalize()

        require(
            Validators.validPort(settings.socksPort)
        ) {
            "Invalid mixed proxy port"
        }

        require(settings.mtu in 576..9000) {
            "MTU must be between 576 and 9000"
        }

        if (settings.dnsMode == DnsMode.CUSTOM) {
            require(
                settings.customDns.isNotEmpty() &&
                    settings.customDns.all(
                        Validators::validateDns
                    )
            ) {
                "Invalid custom DNS endpoint"
            }
        }

        val root = JSONObject()
            .put(
                "log",
                JSONObject().put(
                    "loglevel",
                    "warning"
                )
            )

        if (tunFd != null) {
            root.put(
                "env",
                JSONObject().put(
                    "xray.tun.fd",
                    tunFd.toString()
                )
            )
        }

        root.put(
            "inbounds",
            if (mode == ConnectionMode.VPN) {
                vpnInbounds(settings)
            } else {
                mixedProxyInbound(settings)
            }
        )

        val proxy =
            normalizeOutbound(
                JSONObject(profile.outboundJson)
            ).put("tag", "proxy")

        root.put(
            "outbounds",
            JSONArray()
                .put(proxy)
                .put(
                    JSONObject()
                        .put(
                            "protocol",
                            "freedom"
                        )
                        .put("tag", "direct")
                )
                .put(
                    JSONObject()
                        .put(
                            "protocol",
                            "blackhole"
                        )
                        .put("tag", "block")
                )
        )

        root.put(
            "dns",
            dns(profile, settings)
        )

        root.put(
            "routing",
            JSONObject()
                .put(
                    "domainStrategy",
                    "IPIfNonMatch"
                )
                .put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "field")
                            .put(
                                "ip",
                                JSONArray(
                                    privateNetworks
                                )
                            )
                            .put(
                                "outboundTag",
                                "direct"
                            )
                    )
                )
        )

        return root.toString(2)
    }

    private fun normalizeOutbound(
        outbound: JSONObject
    ): JSONObject {
        val stream =
            outbound.optJSONObject(
                "streamSettings"
            ) ?: return outbound

        val network = stream
            .optString("network")
            .trim()
            .lowercase()

        if (
            network.isBlank() ||
            network == "none" ||
            network == "null"
        ) {
            stream.put("network", "tcp")
        }

        return outbound
    }

    private fun mixedProxyInbound(
        settings: AppSettings
    ): JSONArray = JSONArray().put(
        JSONObject()
            .put("tag", "mixed-in")
            .put("listen", "127.0.0.1")
            .put("port", settings.socksPort)
            .put("protocol", "mixed")
            .put(
                "settings",
                JSONObject()
                    .put("udp", true)
                    .put("auth", "noauth")
            )
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put(
                        "destOverride",
                        JSONArray()
                            .put("http")
                            .put("tls")
                            .put("quic")
                    )
                    .put(
                        "routeOnly",
                        false
                    )
            )
    )

    private fun vpnInbounds(
        settings: AppSettings
    ): JSONArray = JSONArray().put(
        JSONObject()
            .put("tag", "tun-in")
            .put("port", 0)
            .put("protocol", "tun")
            .put(
                "settings",
                JSONObject()
                    .put("name", "trivox0")
                    .put("mtu", settings.mtu)
            )
    )

    private fun dns(
        profile: ConfigProfile,
        settings: AppSettings
    ): JSONObject = when (settings.dnsMode) {
        DnsMode.IMPORTED ->
            profile.originalDnsJson
                ?.let(::JSONObject)
                ?: defaultDns()

        DnsMode.CUSTOM ->
            JSONObject().put(
                "servers",
                JSONArray(settings.customDns)
            )

        DnsMode.SYSTEM ->
            JSONObject().put(
                "servers",
                JSONArray().put("localhost")
            )

        DnsMode.DIRECT ->
            JSONObject().put(
                "servers",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "address",
                            "1.1.1.1"
                        )
                        .put(
                            "skipFallback",
                            true
                        )
                )
            )

        DnsMode.THROUGH_PROXY ->
            JSONObject()
                .put(
                    "servers",
                    JSONArray().put(
                        "https://1.1.1.1/dns-query"
                    )
                )
                .put(
                    "queryStrategy",
                    "UseIP"
                )

        DnsMode.TRIVOX_DEFAULT ->
            defaultDns()
    }

    private fun defaultDns(): JSONObject =
        JSONObject()
            .put(
                "servers",
                JSONArray()
                    .put(
                        "https://1.1.1.1/dns-query"
                    )
                    .put(
                        "https://8.8.8.8/dns-query"
                    )
            )
            .put("queryStrategy", "UseIP")
}
