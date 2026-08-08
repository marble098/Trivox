package com.trivox.client.network

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

/**
 * TRIVOX_V19_NATIVE_WIREGUARD_LEAK_GUARD
 *
 * Checks route coverage, DNS assignment and the public IP seen through the VPN
 * versus a deliberately bound physical-network probe.
 */
class LeakProtectionManager(
    private val context: Context
) {
    data class Report(
        val ipLeak: Boolean,
        val dnsLeak: Boolean,
        val ipv6LeakRisk: Boolean,
        val vpnExitIp: String?,
        val underlyingIp: String?,
        val vpnDns: List<String>,
        val probeIncomplete: Boolean = false
    ) {
        val hasLeak: Boolean
            get() = ipLeak || dnsLeak || ipv6LeakRisk || probeIncomplete

        fun compactSummary(): String = buildString {
            append("IP ")
            append(if (!ipLeak && !probeIncomplete) "✓" else "!")
            append(" • DNS ")
            append(if (!dnsLeak) "✓" else "!")
            append(" • IPv6 ")
            append(if (!ipv6LeakRisk) "✓" else "!")
            vpnExitIp?.takeIf(String::isNotBlank)?.let {
                append(" • exit=")
                append(it)
            }
        }
    }

    fun check(settings: AppSettings): Report {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("ConnectivityManager unavailable")

        val networks = cm.allNetworks.toList()
        val vpnNetwork = networks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        val physicalNetwork = networks
            .asSequence()
            .filter { it != vpnNetwork }
            .filter { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .sortedByDescending { network ->
                val caps = cm.getNetworkCapabilities(network)
                if (
                    caps?.hasCapability(
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

        val vpnDns = vpnLinks?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.distinct()
            .orEmpty()
        val physicalDns = physicalLinks?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.toSet()
            .orEmpty()

        val vpnExit = runCatching { fetchTraceIp(null) }.getOrNull()
        val underlyingExit = physicalNetwork?.let {
            runCatching { fetchTraceIp(it) }.getOrNull()
        }

        val sameExit = !vpnExit.isNullOrBlank() &&
            !underlyingExit.isNullOrBlank() &&
            vpnExit == underlyingExit
        val ipLeak = !vpnV4Default || sameExit
        val dnsLeak = vpnDns.isEmpty() ||
            (
                settings.dnsMode == DnsMode.SYSTEM &&
                    physicalDns.isNotEmpty() &&
                    vpnDns.any(physicalDns::contains)
                )
        val ipv6Leak = physicalV6Default && !vpnV6Default
        val incomplete = vpnExit.isNullOrBlank()

        return Report(
            ipLeak = ipLeak,
            dnsLeak = dnsLeak,
            ipv6LeakRisk = ipv6Leak,
            vpnExitIp = vpnExit,
            underlyingIp = underlyingExit,
            vpnDns = vpnDns,
            probeIncomplete = incomplete
        )
    }

    fun hardenedSettings(
        current: AppSettings,
        report: Report
    ): AppSettings {
        val next = current.copy()
        next.autoLeakProtection = true
        next.blocking = true

        if (
            report.dnsLeak ||
            next.dnsMode == DnsMode.SYSTEM ||
            next.dnsMode == DnsMode.DIRECT
        ) {
            next.dnsMode = DnsMode.THROUGH_PROXY
        }

        if (report.ipLeak && next.appRoutingMode != AppRoutingMode.ALL) {
            next.appRoutingMode = AppRoutingMode.ALL
            next.routedPackages = emptySet()
        }

        return next.normalize()
    }

    private fun hasDefaultRoute(
        links: LinkProperties?,
        ipv6: Boolean
    ): Boolean = links?.routes?.any { route ->
        val destination = route.destination
        destination.prefixLength == 0 &&
            if (ipv6) {
                destination.address is Inet6Address
            } else {
                destination.address is Inet4Address
            }
    } == true

    private fun fetchTraceIp(network: Network?): String? {
        val url = URL("https://1.1.1.1/cdn-cgi/trace")
        val connection = (
            if (network == null) {
                url.openConnection()
            } else {
                network.openConnection(url)
            } as HttpsURLConnection
            ).apply {
            connectTimeout = 3_500
            readTimeout = 3_500
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "Trivox-LeakGuard/1")
        }

        return connection.useConnection {
            if (responseCode !in 200..299) return@useConnection null
            inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines
                    .take(64)
                    .firstOrNull { it.startsWith("ip=") }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
        }
    }

    private inline fun <T> HttpsURLConnection.useConnection(
        block: HttpsURLConnection.() -> T
    ): T = try {
        block()
    } finally {
        disconnect()
    }
}
