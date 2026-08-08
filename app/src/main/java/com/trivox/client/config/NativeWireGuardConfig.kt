package com.trivox.client.config

import com.trivox.client.data.AppRoutingMode
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.DnsMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * TRIVOX_V19_NATIVE_WIREGUARD_LEAK_GUARD
 *
 * Converts the standard subset of Xray WireGuard JSON into wg-quick text for
 * the official WireGuard Android tunnel backend. Xray-only reserved bytes and
 * AmneziaWG extensions intentionally stay on the Xray compatibility path.
 */
object NativeWireGuardConfig {
    data class Candidate(
        val text: String,
        val mtu: Int
    )

    fun supports(profile: ConfigProfile): Boolean {
        if (!profile.protocol.equals("wireguard", ignoreCase = true)) return false
        val outbound = parseOutbound(profile) ?: return false
        val settings = outbound.optJSONObject("settings") ?: return false
        if (hasUnsupportedFields(settings)) return false
        if (hasReserved(settings.opt("reserved"))) return false
        val peers = settings.optJSONArray("peers") ?: return false
        if (peers.length() == 0) return false
        for (index in 0 until peers.length()) {
            val peer = peers.optJSONObject(index) ?: return false
            if (hasUnsupportedFields(peer) || hasReserved(peer.opt("reserved"))) {
                return false
            }
        }
        return true
    }

    fun candidates(
        profile: ConfigProfile,
        appSettings: AppSettings,
        packageName: String
    ): List<Candidate> {
        val outbound = parseOutbound(profile)
            ?: throw IllegalArgumentException("WireGuard outbound is unavailable")
        val wireGuard = outbound.optJSONObject("settings")
            ?: throw IllegalArgumentException("WireGuard settings are unavailable")

        val privateKey = firstNonBlank(
            wireGuard,
            "secretKey",
            "privateKey",
            "private_key",
            "secret_key"
        ) ?: throw IllegalArgumentException("WireGuard private key is missing")

        val addresses = stringList(wireGuard.opt("address"))
            .ifEmpty {
                throw IllegalArgumentException("WireGuard interface address is missing")
            }

        val peers = wireGuard.optJSONArray("peers")
            ?: throw IllegalArgumentException("WireGuard peer is missing")
        if (peers.length() == 0) {
            throw IllegalArgumentException("WireGuard peer is missing")
        }

        val configuredMtu = wireGuard.optInt("mtu", appSettings.wireGuardMtu)
            .takeIf { it in 576..9000 }
            ?: appSettings.wireGuardMtu
        val requestedMtu = minOf(
            configuredMtu,
            appSettings.wireGuardMtu,
            appSettings.mtu
        ).coerceIn(1280, 1420)

        val mtus = buildList {
            add(requestedMtu)
            add(1280)
        }.distinct()

        return mtus.map { mtu ->
            Candidate(
                text = buildText(
                    profile = profile,
                    wireGuard = wireGuard,
                    privateKey = privateKey,
                    addresses = addresses,
                    peers = peers,
                    appSettings = appSettings,
                    packageName = packageName,
                    mtu = mtu
                ),
                mtu = mtu
            )
        }
    }

    private fun buildText(
        profile: ConfigProfile,
        wireGuard: JSONObject,
        privateKey: String,
        addresses: List<String>,
        peers: JSONArray,
        appSettings: AppSettings,
        packageName: String,
        mtu: Int
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = ${addresses.joinToString(", ")}")
        appendLine("MTU = $mtu")

        val dns = dnsServers(profile, appSettings)
        if (dns.isNotEmpty()) {
            appendLine("DNS = ${dns.joinToString(", ")}")
        }

        when (appSettings.appRoutingMode) {
            AppRoutingMode.ALLOW_SELECTED -> {
                val included = (appSettings.routedPackages + packageName)
                    .filter(String::isNotBlank)
                    .distinct()
                    .sorted()
                if (included.isNotEmpty()) {
                    appendLine("IncludedApplications = ${included.joinToString(", ")}")
                }
            }

            AppRoutingMode.BYPASS_SELECTED -> {
                val excluded = appSettings.routedPackages
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()
                    .sorted()
                if (excluded.isNotEmpty()) {
                    appendLine("ExcludedApplications = ${excluded.joinToString(", ")}")
                }
            }

            AppRoutingMode.ALL -> Unit
        }

        for (index in 0 until peers.length()) {
            val peer = peers.optJSONObject(index)
                ?: throw IllegalArgumentException("Invalid WireGuard peer")
            val publicKey = firstNonBlank(peer, "publicKey", "public_key")
                ?: throw IllegalArgumentException("WireGuard peer public key is missing")
            val endpoint = peer.optString("endpoint")
                .trim()
                .removePrefix("udp://")
                .removePrefix("UDP://")
                .ifBlank {
                    throw IllegalArgumentException("WireGuard peer endpoint is missing")
                }
            val preSharedKey = firstNonBlank(
                peer,
                "preSharedKey",
                "presharedKey",
                "preshared_key",
                "pre_shared_key"
            )
            val keepAlive = when {
                peer.has("keepAlive") -> peer.optInt("keepAlive", 0)
                peer.has("persistentKeepalive") -> peer.optInt("persistentKeepalive", 0)
                peer.has("persistent_keepalive") -> peer.optInt("persistent_keepalive", 0)
                else -> appSettings.wireGuardKeepAliveSeconds
            }.coerceIn(0, 65535)

            val allowed = wireGuardAllowedIps(peer, appSettings.autoLeakProtection)

            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $publicKey")
            if (!preSharedKey.isNullOrBlank()) {
                appendLine("PresharedKey = $preSharedKey")
            }
            appendLine("Endpoint = $endpoint")
            appendLine("AllowedIPs = ${allowed.joinToString(", ")}")
            if (keepAlive > 0) {
                appendLine("PersistentKeepalive = $keepAlive")
            }
        }
    }

    private fun wireGuardAllowedIps(
        peer: JSONObject,
        forceFullTunnel: Boolean
    ): List<String> {
        val raw = when {
            peer.has("allowedIPs") -> peer.opt("allowedIPs")
            peer.has("allowedIps") -> peer.opt("allowedIps")
            else -> peer.opt("allowed_ips")
        }
        val values = stringList(raw).toMutableList()
        if (values.isEmpty()) {
            values += "0.0.0.0/0"
        }
        if (
            forceFullTunnel &&
            values.none { it.trim() == "0.0.0.0/0" }
        ) {
            values += "0.0.0.0/0"
        }
        return values.map(String::trim).filter(String::isNotBlank).distinct()
    }

    private fun dnsServers(
        profile: ConfigProfile,
        settings: AppSettings
    ): List<String> {
        val values = when (settings.dnsMode) {
            DnsMode.CUSTOM -> settings.customDns
            DnsMode.SYSTEM -> if (settings.autoLeakProtection) {
                listOf("1.1.1.1", "8.8.8.8")
            } else {
                emptyList()
            }
            else -> if (profile.name.contains("NordVPN", ignoreCase = true)) {
                listOf("103.86.96.100", "103.86.99.100")
            } else {
                listOf("1.1.1.1", "8.8.8.8")
            }
        }
        return values
            .map(String::trim)
            .filter(::isIpLiteral)
            .distinct()
            .take(4)
    }

    private fun parseOutbound(profile: ConfigProfile): JSONObject? =
        runCatching { JSONObject(profile.outboundJson) }.getOrNull()

    private fun stringList(value: Any?): List<String> {
        val result = mutableListOf<String>()
        when (value) {
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    value.optString(index)
                        .split(',')
                        .map(String::trim)
                        .filterTo(result, String::isNotBlank)
                }
            }
            is String -> value
                .split(',')
                .map(String::trim)
                .filterTo(result, String::isNotBlank)
        }
        return result.distinct()
    }

    private fun firstNonBlank(
        value: JSONObject,
        vararg keys: String
    ): String? = keys.asSequence()
        .map(value::optString)
        .map(String::trim)
        .firstOrNull(String::isNotBlank)

    private fun hasReserved(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> false
        is JSONArray -> value.length() > 0
        is String -> value.trim().isNotEmpty() &&
            value.trim() !in setOf("0,0,0", "0 0 0", "[0,0,0]")
        else -> true
    }

    private fun hasUnsupportedFields(value: JSONObject): Boolean =
        listOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")
            .any(value::has)

    private fun isIpLiteral(value: String): Boolean {
        val clean = value.trim().removePrefix("[").removeSuffix("]")
        return clean.matches(Regex("[0-9.]+")) ||
            (':' in clean && clean.matches(Regex("[0-9a-fA-F:]+")))
    }
}
