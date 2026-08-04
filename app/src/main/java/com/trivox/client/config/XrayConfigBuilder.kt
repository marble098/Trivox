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
    private const val MAX_CUSTOM_DNS_SERVERS = 8
    private const val DNS_ROUTING_TAG = "dns-in"
    private const val HAPPY_EYEBALLS_DELAY_MS = 180
    private val TCP_DEFAULT_STREAM_PROTOCOLS = setOf(
        "vless", "vmess", "trojan", "http", "socks", "shadowsocks"
    )
    private val UDP_FIRST_TRANSPORTS = setOf("mkcp", "kcp", "hysteria")

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
        require(Validators.validPort(settings.socksPort)) {
            "Invalid mixed proxy port"
        }
        require(settings.mtu in 576..9000) {
            "MTU must be between 576 and 9000"
        }
        if (settings.dnsMode == DnsMode.CUSTOM) {
            require(
                settings.customDns.isNotEmpty() &&
                    settings.customDns.size <= MAX_CUSTOM_DNS_SERVERS &&
                    settings.customDns.all(Validators::validateDns)
            ) {
                "Invalid custom DNS endpoint"
            }
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
        root.put("routing", routing(settings, mode))
        return root.toString(2)
    }

    private fun buildInbounds(
        profile: ConfigProfile,
        settings: AppSettings,
        mode: ConnectionMode
    ): JSONArray {
        val result = JSONArray()
        if (mode == ConnectionMode.VPN) result.put(tunInbound(settings))

        /*
         * WireGuard keeps a localhost-only mixed listener even in VPN mode for
         * diagnostics and proxy-mode tests. Runtime VPN health is still verified
         * only through Android's active VPN route.
         */
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
                normalizeOutbound(
                    JSONObject(profile.outboundJson),
                    settings,
                    allowAutomaticDnsStrategy = true
                ).put("tag", "proxy")
            )
        } else {
            val bridge = normalizeOutbound(
                JSONObject(chain.bridge.toString()),
                settings,
                allowAutomaticDnsStrategy = false
            ).put("tag", "chain-bridge")
            val exit = normalizeOutbound(
                JSONObject(chain.exit.toString()),
                settings,
                allowAutomaticDnsStrategy = false
            )
                .put("tag", "proxy")
                .put(
                    "proxySettings",
                    JSONObject()
                        .put("tag", "chain-bridge")
                        .put("transportLayer", true)
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
        settings: AppSettings,
        allowAutomaticDnsStrategy: Boolean
    ): JSONObject {
        val normalized = XrayCompatibility.normalizeOutbound(outbound)
        when (normalized.optString("protocol").lowercase()) {
            "shadowsocks" -> normalizeShadowsocks(normalized)
            "wireguard" -> {
                normalizeWireGuard(normalized, settings)
                normalized.remove("streamSettings")
                return normalized
            }
        }

        applyNetworkTuning(
            normalized,
            settings,
            allowAutomaticDnsStrategy
        )
        return normalized
    }

    private fun applyNetworkTuning(
        outbound: JSONObject,
        settings: AppSettings,
        allowAutomaticDnsStrategy: Boolean
    ) {
        if (!settings.networkTuningEnabled) return

        val protocol = outbound.optString("protocol").lowercase()
        val stream = outbound.optJSONObject("streamSettings")
            ?: if (protocol in TCP_DEFAULT_STREAM_PROTOCOLS) {
                JSONObject().put("network", "tcp").also {
                    outbound.put("streamSettings", it)
                }
            } else {
                return
            }

        val transport = stream.optString("network", "tcp").lowercase()
        if (transport in UDP_FIRST_TRANSPORTS) return

        val sockopt = stream.optJSONObject("sockopt")
            ?: JSONObject().also { stream.put("sockopt", it) }

        if (settings.tcpFastOpen && !sockopt.has("tcpFastOpen")) {
            sockopt.put("tcpFastOpen", true)
        }
        if (
            settings.tcpKeepAliveIdleSeconds > 0 &&
            !sockopt.has("tcpKeepAliveIdle")
        ) {
            sockopt.put("tcpKeepAliveIdle", settings.tcpKeepAliveIdleSeconds)
        }
        if (
            settings.tcpKeepAliveIntervalSeconds > 0 &&
            !sockopt.has("tcpKeepAliveInterval")
        ) {
            sockopt.put(
                "tcpKeepAliveInterval",
                settings.tcpKeepAliveIntervalSeconds
            )
        }
        if (settings.tcpUserTimeoutMs > 0 && !sockopt.has("tcpUserTimeout")) {
            sockopt.put("tcpUserTimeout", settings.tcpUserTimeoutMs)
        }
        if (settings.adaptiveHandshake && !sockopt.has("happyEyeballs")) {
            val hasDialerProxy = sockopt.optString("dialerProxy").isNotBlank()
            val hasTargetStrategy = outbound.optString("targetStrategy").isNotBlank()
            var domainStrategy = sockopt.optString("domainStrategy").trim()

            /*
             * Happy Eyeballs only works when the proxy endpoint is resolved by
             * Sockopt. For a normal single profile we can safely select UseIP,
             * because dns() installs a narrowly-scoped local bootstrap for that
             * endpoint. Proxy chains are deliberately excluded from automatic
             * resolution: each hop can have a different hostname, and forcing
             * the wrong resolver path can create a DNS-via-proxy loop.
             */
            if (
                domainStrategy.isBlank() &&
                allowAutomaticDnsStrategy &&
                !hasDialerProxy &&
                !hasTargetStrategy
            ) {
                domainStrategy = "UseIP"
                sockopt.put("domainStrategy", domainStrategy)
            }

            if (
                domainStrategy.isNotBlank() &&
                !domainStrategy.equals("AsIs", ignoreCase = true) &&
                !hasDialerProxy &&
                !hasTargetStrategy
            ) {
                sockopt.put(
                    "happyEyeballs",
                    JSONObject()
                        .put("prioritizeIPv6", false)
                        .put("tryDelayMs", HAPPY_EYEBALLS_DELAY_MS)
                        .put("interleave", 1)
                        .put("maxConcurrentTry", 2)
                )
            }
        }
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

        rejectAmneziaOnlyFields(wireGuard)

        copyAlias(
            target = wireGuard,
            canonical = "secretKey",
            aliases = arrayOf("privateKey", "private_key", "secret_key")
        )
        wireGuard.remove("privateKey")
        wireGuard.remove("private_key")
        wireGuard.remove("secret_key")

        val currentMtu = wireGuard.optInt("mtu", 1420)
            .takeIf { it in 576..9000 }
            ?: 1420
        /*
         * Never raise a provider-supplied MTU. Lower values are commonly
         * deliberate on mobile, nested VPN, PPPoE and filtered paths.
         */
        wireGuard.put(
            "mtu",
            minOf(currentMtu, settings.mtu, settings.wireGuardMtu)
                .coerceAtLeast(576)
        )
        wireGuard.put("noKernelTun", true)
        if (wireGuard.optInt("workers", 0) <= 0) {
            wireGuard.put("workers", settings.wireGuardWorkers)
        }

        val addressValues = mutableListOf<String>()
        when (val rawAddress = wireGuard.opt("address")) {
            is JSONArray -> {
                for (index in 0 until rawAddress.length()) {
                    rawAddress.optString(index)
                        .split(',')
                        .map(String::trim)
                        .filterTo(addressValues, String::isNotBlank)
                }
            }

            is String -> rawAddress.split(',')
                .map(String::trim)
                .filterTo(addressValues, String::isNotBlank)
        }

        val normalizedAddresses = JSONArray()
        var hasIpv4Address = false
        var hasIpv6Address = false
        addressValues.distinct().forEach { raw ->
            hasIpv6Address = hasIpv6Address || ':' in raw
            hasIpv4Address = hasIpv4Address || ':' !in raw
            normalizedAddresses.put(
                when {
                    '/' in raw -> raw
                    ':' in raw -> "$raw/128"
                    else -> "$raw/32"
                }
            )
        }
        if (normalizedAddresses.length() > 0) {
            wireGuard.put("address", normalizedAddresses)
        }

        val validStrategies = setOf(
            "AsIs",
            "ForceIP",
            "ForceIPv4",
            "ForceIPv6",
            "ForceIPv4v6",
            "ForceIPv6v4"
        )
        val existingStrategy = wireGuard.optString("domainStrategy")
        if (existingStrategy !in validStrategies) {
            val configured = settings.wireGuardDomainStrategy
                .takeIf { it in validStrategies }
            wireGuard.put(
                "domainStrategy",
                configured ?: when {
                    hasIpv4Address && hasIpv6Address && settings.ipv6 ->
                        "ForceIPv4v6"
                    hasIpv6Address && settings.ipv6 -> "ForceIPv6"
                    else -> "ForceIPv4"
                }
            )
        }

        val peers = wireGuard.optJSONArray("peers") ?: JSONArray()
        var promotedReserved: Any? = wireGuard.opt("reserved")
        for (index in 0 until peers.length()) {
            val peer = peers.optJSONObject(index) ?: continue
            rejectAmneziaOnlyFields(peer)

            copyAlias(
                target = peer,
                canonical = "publicKey",
                aliases = arrayOf("public_key")
            )
            peer.remove("public_key")

            copyAlias(
                target = peer,
                canonical = "preSharedKey",
                aliases = arrayOf(
                    "presharedKey",
                    "preshared_key",
                    "pre_shared_key"
                )
            )
            peer.remove("presharedKey")
            peer.remove("preshared_key")
            peer.remove("pre_shared_key")

            val endpoint = peer.optString("endpoint").trim()
                .removePrefix("udp://")
                .removePrefix("UDP://")
            if (endpoint.isNotBlank()) peer.put("endpoint", endpoint)

            val keepAlive = when {
                peer.has("keepAlive") -> peer.optInt("keepAlive", 0)
                peer.has("persistentKeepalive") ->
                    peer.optInt("persistentKeepalive", 0)
                peer.has("persistent_keepalive") ->
                    peer.optInt("persistent_keepalive", 0)
                else -> settings.wireGuardKeepAliveSeconds
            }.coerceIn(0, 65_535)
            peer.remove("persistentKeepalive")
            peer.remove("persistent_keepalive")
            peer.put("keepAlive", keepAlive)

            normalizeAllowedIps(peer, settings)

            if (promotedReserved == null || promotedReserved == JSONObject.NULL) {
                promotedReserved = peer.opt("reserved")
            }
            peer.remove("reserved")
        }

        if (
            (wireGuard.opt("reserved") == null ||
                wireGuard.opt("reserved") == JSONObject.NULL) &&
            promotedReserved != null &&
            promotedReserved != JSONObject.NULL
        ) {
            wireGuard.put("reserved", promotedReserved)
        }
        normalizeWireGuardReserved(wireGuard)
    }

    private fun copyAlias(
        target: JSONObject,
        canonical: String,
        aliases: Array<String>
    ) {
        if (target.optString(canonical).isNotBlank()) return
        aliases.asSequence()
            .map(target::optString)
            .firstOrNull(String::isNotBlank)
            ?.let { target.put(canonical, it) }
    }

    private fun normalizeAllowedIps(
        peer: JSONObject,
        settings: AppSettings
    ) {
        val raw = when {
            peer.has("allowedIPs") -> peer.opt("allowedIPs")
            peer.has("allowedIps") -> peer.opt("allowedIps")
            else -> peer.opt("allowed_ips")
        }
        peer.remove("allowedIps")
        peer.remove("allowed_ips")

        val values = mutableListOf<String>()
        when (raw) {
            is JSONArray -> {
                for (index in 0 until raw.length()) {
                    raw.optString(index)
                        .split(',')
                        .map(String::trim)
                        .filterTo(values, String::isNotBlank)
                }
            }

            is String -> raw.split(',')
                .map(String::trim)
                .filterTo(values, String::isNotBlank)
        }
        val normalized = values.distinct().ifEmpty {
            buildList {
                add("0.0.0.0/0")
                if (settings.ipv6) add("::/0")
            }
        }
        peer.put("allowedIPs", JSONArray(normalized))
    }

    private fun rejectAmneziaOnlyFields(value: JSONObject) {
        val unsupported = listOf(
            "jc", "jmin", "jmax", "s1", "s2",
            "h1", "h2", "h3", "h4"
        ).filter(value::has)
        require(unsupported.isEmpty()) {
            "AmneziaWG-only fields ${unsupported.joinToString()} require an " +
                "AmneziaWG-compatible core"
        }
    }

    private fun normalizeWireGuardReserved(wireGuard: JSONObject) {
        val raw = wireGuard.opt("reserved") ?: return
        if (raw == JSONObject.NULL) {
            wireGuard.remove("reserved")
            return
        }
        val bytes = when (raw) {
            is JSONArray ->
                (0 until raw.length()).map { raw.optInt(it, -1) }

            is String -> raw.split(',', ' ', ';')
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { it.toIntOrNull() ?: -1 }

            else -> emptyList()
        }
        require(bytes.size == 3 && bytes.all { it in 0..255 }) {
            "WireGuard reserved must contain exactly three bytes from 0 to 255"
        }
        wireGuard.put("reserved", JSONArray(bytes))
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
                .put(
                    "destOverride",
                    JSONArray().put("http").put("tls").put("quic")
                )
                .put("routeOnly", true)
        )

    private fun tunInbound(settings: AppSettings) = JSONObject()
        .put("tag", "tun-in")
        .put("port", 0)
        .put("protocol", "tun")
        .put(
            "settings",
            JSONObject()
                .put("name", "trivox0")
                .put("mtu", settings.mtu)
        )

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
        DnsMode.IMPORTED ->
            importedDns(profile, settings)
                ?: smartDns(remoteSecureDns(profile), settings)

        DnsMode.CUSTOM -> smartDns(
            withBootstrap(
                profile,
                JSONArray(
                    settings.customDns
                        .asSequence()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinct()
                        .take(MAX_CUSTOM_DNS_SERVERS)
                        .toList()
                )
            ),
            settings
        )

        DnsMode.SYSTEM -> JSONObject()
            .put("servers", JSONArray().put("localhost"))
            .put("queryStrategy", queryStrategy(settings))
            .put("tag", DNS_ROUTING_TAG)

        DnsMode.DIRECT -> smartDns(localSecureDns(), settings)

        DnsMode.THROUGH_PROXY,
        DnsMode.TRIVOX_DEFAULT ->
            smartDns(remoteSecureDns(profile), settings)
    }

    private fun importedDns(
        profile: ConfigProfile,
        settings: AppSettings
    ): JSONObject? {
        val imported = profile.originalDnsJson
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null

        val serverValue: Any? = imported.opt("servers")
        val originalServers = when {
            serverValue is JSONArray -> serverValue
            serverValue is String -> JSONArray().put(serverValue)
            else -> JSONArray()
        }
        val servers = withBootstrap(profile, originalServers)
        if (servers.length() == 0) {
            val fallback = remoteSecureDns(profile)
            for (index in 0 until fallback.length()) {
                servers.put(fallback.opt(index))
            }
        }

        imported.put("servers", servers)
        if (imported.optString("queryStrategy").isBlank()) {
            imported.put("queryStrategy", queryStrategy(settings))
        }
        if (!imported.has("disableCache")) imported.put("disableCache", false)
        if (!imported.has("disableFallback")) imported.put("disableFallback", false)
        if (!imported.has("enableParallelQuery")) {
            imported.put("enableParallelQuery", true)
        }
        if (!imported.has("useSystemHosts")) {
            imported.put("useSystemHosts", false)
        }
        imported.put("tag", DNS_ROUTING_TAG)
        return imported
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
        .put("tag", DNS_ROUTING_TAG)

    private fun queryStrategy(settings: AppSettings) =
        if (settings.ipv6) "UseIP" else "UseIPv4"

    private fun remoteSecureDns(profile: ConfigProfile): JSONArray =
        withBootstrap(
            profile,
            JSONArray()
                .put("https://1.1.1.1/dns-query")
                .put("https://8.8.8.8/dns-query")
        )

    private fun withBootstrap(
        profile: ConfigProfile,
        servers: JSONArray
    ): JSONArray {
        val result = JSONArray()
        val seen = linkedSetOf<String>()

        fun append(value: Any?) {
            if (value == null || value == JSONObject.NULL) return
            val signature = value.toString()
            if (seen.add(signature)) result.put(value)
        }

        bootstrapDns(profile)?.let(::append)
        for (index in 0 until servers.length()) {
            append(servers.opt(index))
        }
        return result
    }

    /*
     * A domain-form proxy/WireGuard endpoint must be resolvable before that
     * outbound can carry DNS itself. The narrowly-scoped local bootstrap avoids
     * a DNS-via-proxy recursion while every normal DNS query still follows the
     * selected route.
     */
    private fun bootstrapDns(profile: ConfigProfile): JSONObject? =
        profile.server
            .trim()
            .takeIf {
                it.isNotBlank() && !isIpLiteral(it)
            }
            ?.let { host ->
                JSONObject()
                    .put("address", "https+local://1.1.1.1/dns-query")
                    .put("domains", JSONArray().put("full:$host"))
                    .put("skipFallback", true)
                    .put("queryStrategy", "UseIP")
            }

    private fun localSecureDns() = JSONArray()
        .put("https+local://1.1.1.1/dns-query")
        .put("https+local://8.8.8.8/dns-query")

    private fun isIpLiteral(value: String): Boolean {
        val clean = value.trim().removePrefix("[").removeSuffix("]")
        return clean.matches(Regex("[0-9.]+")) ||
            (':' in clean && clean.matches(Regex("[0-9a-fA-F:]+")))
    }

    private fun routing(
        settings: AppSettings,
        mode: ConnectionMode
    ): JSONObject {
        val dnsRouteTag = when {
            settings.dnsMode == DnsMode.SYSTEM ||
                settings.dnsMode == DnsMode.DIRECT -> "direct"
            mode == ConnectionMode.PROXY &&
                settings.dnsMode != DnsMode.THROUGH_PROXY &&
                settings.dnsMode != DnsMode.IMPORTED -> "direct"
            else -> "proxy"
        }
        val rules = JSONArray()
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("tun-in"))
                    .put("network", "udp,tcp")
                    .put("port", "53")
                    .put("outboundTag", "dns-out")
            )
            /*
             * Built-in DNS queries need an explicit higher-priority route.
             * Without it, imported private resolvers (10/8, 172.16/12, etc.)
             * are caught by the private-network direct rule and bypass the
             * selected WireGuard/proxy outbound.
             */
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put(DNS_ROUTING_TAG))
                    .put("outboundTag", dnsRouteTag)
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
