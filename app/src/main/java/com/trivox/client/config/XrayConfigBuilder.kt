package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.DnsMode
import org.json.JSONArray
import org.json.JSONObject

object Validators {
    fun validPort(port: Int) = port in 1..65535

    fun validateDns(value: String): Boolean {
        val text = value.trim()
        if (text.isBlank() || text.any(Char::isWhitespace)) return false
        if (
            listOf(
                "https://",
                "https+local://",
                "tcp://",
                "tcp+local://",
                "quic://",
                "quic+local://"
            ).any { text.startsWith(it, ignoreCase = true) }
        ) {
            return true
        }
        if (text.startsWith('[')) return text.contains(']')
        return text.matches(Regex("[A-Za-z0-9._:-]+"))
    }
}

object XrayConfigBuilder {
    private const val WIREGUARD_RELIABLE_MTU = 1360
    private const val WIREGUARD_KEEPALIVE_SECONDS = 25
    private const val MAX_CUSTOM_DNS_SERVERS = 8

    private val privateNetworks = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
        "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15",
        "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
        "::/128", "::1/128", "2001:db8::/32", "fc00::/7", "fe80::/10", "ff00::/8"
    )

    fun build(
        profile: ConfigProfile,
        settings: AppSettings,
        mode: ConnectionMode,
        tunFd: Int? = null,
        errorLogPath: String? = null
    ): String {
        settings.normalize()
        require(Validators.validPort(settings.socksPort)) { "Invalid mixed proxy port" }
        require(settings.mtu in 576..9000) { "MTU must be between 576 and 9000" }
        if (settings.dnsMode == DnsMode.CUSTOM) {
            require(
                settings.customDns.isNotEmpty() &&
                    settings.customDns.size <= MAX_CUSTOM_DNS_SERVERS &&
                    settings.customDns.all(Validators::validateDns)
            ) { "Invalid custom DNS endpoint" }
        }

        val log = JSONObject().put("loglevel", "warning")
        if (!errorLogPath.isNullOrBlank()) log.put("error", errorLogPath)

        val root = JSONObject().put("log", log)
        if (tunFd != null) {
            root.put("env", JSONObject().put("xray.tun.fd", tunFd.toString()))
        }
        root.put("inbounds", buildInbounds(profile, settings, mode))
        root.put("outbounds", buildOutbounds(profile, settings))
        root.put("dns", dns(profile, settings))
        root.put("routing", routing())
        return root.toString(2)
    }

    private fun buildInbounds(
        profile: ConfigProfile,
        settings: AppSettings,
        mode: ConnectionMode
    ): JSONArray {
        val result = JSONArray()
        if (mode == ConnectionMode.VPN) result.put(tunInbound(settings))

        // WireGuard is not considered connected until this localhost-only mixed
        // listener passes an end-to-end HTTP health probe.
        if (
            mode == ConnectionMode.PROXY ||
            settings.localProxyInVpn ||
            profile.protocol.equals("wireguard", ignoreCase = true)
        ) {
            result.put(mixedInbound(settings))
        }
        return result
    }

    private fun buildOutbounds(
        profile: ConfigProfile,
        settings: AppSettings
    ): JSONArray {
        val chain = ProxyChainCodec.decode(profile.outboundJson)
        val result = JSONArray()
        if (chain == null) {
            result.put(
                normalizeOutbound(JSONObject(profile.outboundJson), settings)
                    .put("tag", "proxy")
            )
        } else {
            val bridge = normalizeOutbound(JSONObject(chain.bridge.toString()), settings)
                .put("tag", "chain-bridge")
            val exit = normalizeOutbound(JSONObject(chain.exit.toString()), settings)
                .put("tag", "proxy")
                .put(
                    "proxySettings",
                    JSONObject().put("tag", "chain-bridge").put("transportLayer", true)
                )
            result.put(exit).put(bridge)
        }

        return result
            .put(dnsOutbound())
            .put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
            .put(JSONObject().put("protocol", "blackhole").put("tag", "block"))
    }

    private fun normalizeOutbound(
        outbound: JSONObject,
        settings: AppSettings
    ): JSONObject {
        when (outbound.optString("protocol").lowercase()) {
            "shadowsocks" -> normalizeShadowsocks(outbound)
            "wireguard" -> normalizeWireGuard(outbound, settings)
        }

        val stream = outbound.optJSONObject("streamSettings") ?: return outbound
        val network = stream.optString("network").trim().lowercase()
        if (network.isBlank() || network == "none" || network == "null") {
            stream.put("network", "tcp")
        }
        return outbound
    }

    private fun normalizeShadowsocks(outbound: JSONObject) {
        val servers = outbound.optJSONObject("settings")
            ?.optJSONArray("servers") ?: JSONArray()
        for (index in 0 until servers.length()) {
            val server = servers.optJSONObject(index) ?: continue
            server.put(
                "password",
                Shadowsocks2022.normalizePassword(
                    server.optString("method"),
                    server.optString("password")
                )
            )
        }
    }

    private fun normalizeWireGuard(
        outbound: JSONObject,
        settings: AppSettings
    ) {
        val wireGuard = outbound.optJSONObject("settings") ?: JSONObject().also {
            outbound.put("settings", it)
        }
        val currentMtu = wireGuard.optInt("mtu", 1420).takeIf { it > 0 } ?: 1420
        val minimum = minOf(settings.mtu, if (settings.ipv6) 1280 else 1200)
        val reliableMtu = minOf(currentMtu, settings.mtu, WIREGUARD_RELIABLE_MTU)
            .coerceAtLeast(minimum)

        wireGuard.put("mtu", reliableMtu)
        wireGuard.put("domainStrategy", "ForceIP")
        wireGuard.put("noKernelTun", true)

        val peers = wireGuard.optJSONArray("peers") ?: JSONArray()
        for (index in 0 until peers.length()) {
            val peer = peers.optJSONObject(index) ?: continue
            if (peer.optInt("keepAlive", 0) <= 0) {
                peer.put("keepAlive", WIREGUARD_KEEPALIVE_SECONDS)
            }
        }
    }

    private fun mixedInbound(settings: AppSettings) = JSONObject()
        .put("tag", "mixed-in")
        .put("listen", "127.0.0.1")
        .put("port", settings.socksPort)
        .put("protocol", "mixed")
        .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
        .put(
            "sniffing",
            JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                .put("routeOnly", false)
        )

    private fun tunInbound(settings: AppSettings) = JSONObject()
        .put("tag", "tun-in")
        .put("port", 0)
        .put("protocol", "tun")
        .put("settings", JSONObject().put("name", "trivox0").put("mtu", settings.mtu))

    private fun dnsOutbound() = JSONObject()
        .put("protocol", "dns")
        .put("tag", "dns-out")
        .put(
            "settings",
            JSONObject().put(
                "rules",
                JSONArray().put(
                    JSONObject()
                        .put("action", "hijack")
                        .put("qType", "1,28")
                )
            )
        )

    private fun dns(
        profile: ConfigProfile,
        settings: AppSettings
    ): JSONObject = when (settings.dnsMode) {
        DnsMode.IMPORTED -> profile.originalDnsJson
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: smartDns(remoteSecureDns(profile), settings)

        DnsMode.CUSTOM -> smartDns(
            JSONArray(
                settings.customDns
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_CUSTOM_DNS_SERVERS)
                    .toList()
            ),
            settings
        )

        DnsMode.SYSTEM -> JSONObject()
            .put("servers", JSONArray().put("localhost"))
            .put("queryStrategy", queryStrategy(settings))

        DnsMode.DIRECT -> smartDns(localSecureDns(), settings)
        DnsMode.THROUGH_PROXY,
        DnsMode.TRIVOX_DEFAULT -> smartDns(remoteSecureDns(profile), settings)
    }

    private fun smartDns(
        servers: JSONArray,
        settings: AppSettings
    ) = JSONObject()
        .put("servers", servers)
        .put("queryStrategy", queryStrategy(settings))
        .put("disableCache", false)
        .put("disableFallback", false)
        .put("disableFallbackIfMatch", false)
        .put("enableParallelQuery", true)
        .put("useSystemHosts", false)

    private fun queryStrategy(settings: AppSettings) =
        if (settings.ipv6) "UseIP" else "UseIPv4"

    private fun remoteSecureDns(profile: ConfigProfile): JSONArray =
        JSONArray().apply {
            if (
                profile.protocol.equals("wireguard", ignoreCase = true) &&
                !isIpLiteral(profile.server)
            ) {
                put(
                    JSONObject()
                        .put("address", "https+local://1.1.1.1/dns-query")
                        .put("domains", JSONArray().put("full:${profile.server}"))
                        .put("skipFallback", true)
                        .put("queryStrategy", "UseIPv4")
                )
            }
            put("https://1.1.1.1/dns-query")
            put("https://8.8.8.8/dns-query")
        }

    private fun localSecureDns() = JSONArray()
        .put("https+local://1.1.1.1/dns-query")
        .put("https+local://8.8.8.8/dns-query")

    private fun isIpLiteral(value: String): Boolean {
        val clean = value.trim().removePrefix("[").removeSuffix("]")
        return clean.matches(Regex("[0-9.]+")) ||
            (':' in clean && clean.matches(Regex("[0-9a-fA-F:]+")))
    }

    private fun routing(): JSONObject {
        val rules = JSONArray()
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("tun-in"))
                    .put("network", "udp,tcp")
                    .put("port", "53")
                    .put("outboundTag", "dns-out")
            )
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("ip", JSONArray(privateNetworks))
                    .put("outboundTag", "direct")
            )

        return JSONObject()
            .put("domainStrategy", "IPIfNonMatch")
            .put("rules", rules)
    }
}
