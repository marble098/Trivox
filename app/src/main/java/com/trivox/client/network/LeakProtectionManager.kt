package com.trivox.client.network

// TRIVOX_P3_LEAK_GUARD_V2

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.trivox.client.data.AppRoutingMode
import com.trivox.client.data.AppSettings
import com.trivox.client.data.DnsMode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Leak Guard V2: route/DNS checks plus independent bound-network exit proofs. */
class LeakProtectionManager(private val context: Context) {
    data class Report(
        val ipLeak: Boolean,
        val dnsLeak: Boolean,
        val ipv6LeakRisk: Boolean,
        val vpnExitIp: String?,
        val underlyingIp: String?,
        val vpnDns: List<String>,
        val physicalDns: List<String>,
        val probeIncomplete: Boolean = false,
        val reasons: List<String> = emptyList(),
        val riskScore: Int = 0
    ) {
        val hasLeak: Boolean get() = ipLeak || dnsLeak || ipv6LeakRisk
        fun compactSummary(): String = buildString {
            append("Risk "); append(riskScore); append("/100 • IP ")
            append(if (ipLeak) "!" else if (probeIncomplete) "?" else "✓")
            append(" • DNS "); append(if (dnsLeak) "!" else "✓")
            append(" • IPv6 "); append(if (ipv6LeakRisk) "!" else "✓")
            vpnExitIp?.takeIf(String::isNotBlank)?.let { append(" • exit="); append(it) }
            if (reasons.isNotEmpty()) { append(" • "); append(reasons.joinToString(";")) }
        }
    }

    fun check(settings: AppSettings): Report {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("ConnectivityManager unavailable")
        val networks = cm.allNetworks.toList()
        val vpnNetwork = networks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        val physicalNetwork = networks.asSequence()
            .filter { it != vpnNetwork }
            .filter { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .sortedByDescending { network ->
                if (cm.getNetworkCapabilities(network)?.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    ) == true
                ) 1 else 0
            }
            .firstOrNull()
        val vpnLinks = vpnNetwork?.let(cm::getLinkProperties)
        val physicalLinks = physicalNetwork?.let(cm::getLinkProperties)
        val vpnV4Default = hasDefaultRoute(vpnLinks, ipv6 = false)
        val vpnV6Default = hasDefaultRoute(vpnLinks, ipv6 = true)
        val physicalV6Default = hasDefaultRoute(physicalLinks, ipv6 = true)
        val vpnDns = vpnLinks?.dnsServers?.mapNotNull { it.hostAddress }?.distinct().orEmpty()
        val physicalDns = physicalLinks?.dnsServers?.mapNotNull { it.hostAddress }?.distinct().orEmpty()
        val vpnExit = fetchTraceIp(null)
        val underlyingExit = physicalNetwork?.let(::fetchTraceIp)
        val reasons = mutableListOf<String>()
        val dnsUnsafeMode = settings.dnsMode in setOf(DnsMode.SYSTEM, DnsMode.DIRECT)
        val dnsOverlap = physicalDns.isNotEmpty() && vpnDns.any(physicalDns::contains)
        val dnsLeak = vpnDns.isEmpty() || dnsUnsafeMode || dnsOverlap
        if (vpnDns.isEmpty()) reasons += "vpn_dns_empty"
        if (dnsUnsafeMode) reasons += "dns_mode_bypasses_proxy"
        if (dnsOverlap) reasons += "vpn_dns_matches_physical"
        val sameExit = !vpnExit.isNullOrBlank() && !underlyingExit.isNullOrBlank() && vpnExit == underlyingExit
        val ipLeak = !vpnV4Default || sameExit
        if (!vpnV4Default) reasons += "vpn_ipv4_default_missing"
        if (sameExit) reasons += "vpn_and_direct_exit_match"
        val ipv6Leak = settings.ipv6 && physicalV6Default && !vpnV6Default
        if (ipv6Leak) reasons += "physical_ipv6_without_vpn_default"
        val incomplete = vpnExit.isNullOrBlank() || underlyingExit.isNullOrBlank()
        if (incomplete) reasons += "external_proof_incomplete"
        val risk = ((if (dnsLeak) 35 else 0) + (if (ipLeak) 45 else 0) + (if (ipv6Leak) 20 else 0)).coerceIn(0, 100)
        return Report(
            ipLeak, dnsLeak, ipv6Leak, vpnExit, underlyingExit, vpnDns, physicalDns,
            incomplete, reasons.distinct(), risk
        )
    }

    fun hardenedSettings(current: AppSettings, report: Report): AppSettings {
        val next = current.copy(autoLeakProtection = true, blocking = true)
        if (report.dnsLeak || next.dnsMode in setOf(DnsMode.SYSTEM, DnsMode.DIRECT)) {
            next.dnsMode = DnsMode.THROUGH_PROXY
        }
        if (report.ipLeak) {
            next.appRoutingMode = AppRoutingMode.ALL
            next.routedPackages = emptySet()
        }
        if (report.ipv6LeakRisk) next.ipv6 = false
        return next.normalize()
    }

    private fun hasDefaultRoute(links: LinkProperties?, ipv6: Boolean): Boolean =
        links?.routes?.any { route ->
            val destination = route.destination
            destination.prefixLength == 0 && if (ipv6) {
                destination.address is Inet6Address
            } else {
                destination.address is Inet4Address
            }
        } == true

    private fun fetchTraceIp(network: Network?): String? {
        for (endpoint in EXIT_PROBE_URLS) {
            val result = runCatching {
                val url = URL(endpoint)
                val connection = (if (network == null) url.openConnection() else network.openConnection(url)) as HttpsURLConnection
                connection.apply {
                    connectTimeout = 2_500; readTimeout = 2_500
                    instanceFollowRedirects = false; useCaches = false
                    setRequestProperty("Accept", "text/plain")
                    setRequestProperty("User-Agent", "Trivox-LeakGuard/2")
                }.useConnection {
                    if (responseCode !in 200..299) return@useConnection null
                    inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.take(64).firstOrNull { it.startsWith("ip=") }
                            ?.substringAfter('=')?.trim()?.takeIf(String::isNotBlank)
                    }
                }
            }.getOrNull()
            if (!result.isNullOrBlank()) return result
        }
        return null
    }

    private inline fun <T> HttpsURLConnection.useConnection(block: HttpsURLConnection.() -> T): T =
        try { block() } finally { disconnect() }

    companion object {
        private val EXIT_PROBE_URLS = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://1.0.0.1/cdn-cgi/trace"
        )
    }
}
