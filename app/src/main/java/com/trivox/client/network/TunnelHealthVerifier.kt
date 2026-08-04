package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.PingMethod
import com.trivox.client.data.PingResult
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale

/**
 * Bounded end-to-end verification of the active Trivox route.
 * A single lucky response, redirect, captive portal, or open localhost port is
 * never enough to mark a tunnel healthy.
 */
object TunnelHealthVerifier {
    private val fallbackTargets = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://www.gstatic.com/generate_204"
    )

    fun measure(
        settings: AppSettings,
        mode: ConnectionMode? = null,
        attempts: Int = 3,
        budgetMs: Int = 12_000,
        perProbeTimeoutMs: Int = MAX_PROBE_TIMEOUT_MS,
        isCancelled: () -> Boolean = { false }
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val count = attempts.coerceIn(2, 3)
        val budget = budgetMs.coerceIn(2_500, 24_000)
        val probeTimeout = perProbeTimeoutMs.coerceIn(
            MIN_PROBE_TIMEOUT_MS,
            MAX_PROBE_TIMEOUT_MS
        )
        val deadline = System.nanoTime() + budget * 1_000_000L
        val samplesNanos = mutableListOf<Long>()
        var lastFailure = "tunnel_timeout"

        for (sampleIndex in 0 until count) {
            if (cancelled(isCancelled)) {
                return cancelledResult(timestamp, samplesNanos.size, count)
            }
            var accepted = false

            for (route in probeRoutes(settings, mode)) {
                if (accepted || cancelled(isCancelled)) break
                for (target in rotatedTargets(settings, sampleIndex)) {
                    if (cancelled(isCancelled)) {
                        return cancelledResult(timestamp, samplesNanos.size, count)
                    }
                    val remainingMs = (
                        (deadline - System.nanoTime()) / 1_000_000L
                    ).coerceAtMost(probeTimeout.toLong()).toInt()
                    if (remainingMs < MIN_PROBE_TIMEOUT_MS) break

                    val result = probe(
                        settings = settings,
                        route = route,
                        url = target,
                        timeoutMs = remainingMs,
                        nonce = timestamp + sampleIndex + samplesNanos.size
                    )
                    if (result.latencyMs != null) {
                        samplesNanos += result.latencyMs * NANOS_PER_MILLISECOND
                        accepted = true
                        break
                    }
                    lastFailure = result.errorCategory ?: lastFailure
                }
            }
        }

        val summary = PingStatistics.summarize(samplesNanos, count)
        return PingResult(
            method = PingMethod.XRAY_HTTP.name,
            success = summary.success,
            latencyMs = summary.latencyMs,
            jitterMs = summary.jitterMs,
            successRatio = summary.successRatio,
            resolvedIp = if (mode == ConnectionMode.VPN) "vpn" else "127.0.0.1",
            timestamp = timestamp,
            errorCategory = if (summary.success) null else lastFailure
        )
    }

    private fun probeRoutes(
        settings: AppSettings,
        mode: ConnectionMode?
    ): List<ProbeRoute> = buildList {
        if (mode != ConnectionMode.VPN || settings.localProxyInVpn) {
            add(ProbeRoute.HTTP_PROXY)
            add(ProbeRoute.SOCKS_PROXY)
        }
        if (mode == ConnectionMode.VPN) {
            add(ProbeRoute.ACTIVE_VPN)
        }
    }.distinct()

    private fun rotatedTargets(settings: AppSettings, offset: Int): List<String> {
        val targets = buildList {
            settings.testUrl.trim().takeIf(String::isNotBlank)?.let(::add)
            addAll(fallbackTargets)
        }.distinct()
        if (targets.isEmpty() || offset % targets.size == 0) return targets
        val shift = offset % targets.size
        return targets.drop(shift) + targets.take(shift)
    }

    private fun cancelled(isCancelled: () -> Boolean): Boolean =
        Thread.currentThread().isInterrupted || isCancelled()

    private fun cancelledResult(
        timestamp: Long,
        completed: Int,
        requested: Int
    ) = PingResult(
        method = PingMethod.XRAY_HTTP.name,
        success = false,
        latencyMs = null,
        jitterMs = null,
        successRatio = completed.toDouble() / requested.coerceAtLeast(1).toDouble(),
        resolvedIp = null,
        timestamp = timestamp,
        errorCategory = "cancelled"
    )

    private fun probe(
        settings: AppSettings,
        route: ProbeRoute,
        url: String,
        timeoutMs: Int,
        nonce: Long
    ): ProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            val separator = if ('?' in url) '&' else '?'
            val uri = URI("$url${separator}trivox_health=$nonce")
            val rawConnection = when (route) {
                ProbeRoute.HTTP_PROXY -> uri.toURL().openConnection(
                    Proxy(
                        Proxy.Type.HTTP,
                        InetSocketAddress("127.0.0.1", settings.socksPort)
                    )
                )
                ProbeRoute.SOCKS_PROXY -> uri.toURL().openConnection(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress("127.0.0.1", settings.socksPort)
                    )
                )
                ProbeRoute.ACTIVE_VPN -> uri.toURL().openConnection(Proxy.NO_PROXY)
            }
            connection = rawConnection as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("User-Agent", "Trivox-TunnelHealth/8")

            val started = System.nanoTime()
            val status = connection.responseCode
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            if (acceptsHttpStatus(uri.toString(), status)) {
                ProbeResult(latencyMs = elapsed)
            } else {
                ProbeResult(errorCategory = "http_$status")
            }
        } catch (throwable: Throwable) {
            ProbeResult(
                errorCategory = throwable.javaClass.simpleName
                    .ifBlank { "tunnel_probe_failed" }
                    .lowercase(Locale.ROOT)
            )
        } finally {
            runCatching { connection?.inputStream?.close() }
            runCatching { connection?.errorStream?.close() }
            connection?.disconnect()
        }
    }

    internal fun acceptsHttpStatus(url: String, status: Int): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }
            .getOrDefault("")
            .lowercase(Locale.ROOT)
        return if ("generate_204" in path || "gen_204" in path) {
            status == HttpURLConnection.HTTP_NO_CONTENT
        } else {
            status in 200..299
        }
    }

    private enum class ProbeRoute {
        HTTP_PROXY,
        SOCKS_PROXY,
        ACTIVE_VPN
    }

    private data class ProbeResult(
        val latencyMs: Long? = null,
        val errorCategory: String? = null
    )

    private const val MIN_PROBE_TIMEOUT_MS = 500
    private const val MAX_PROBE_TIMEOUT_MS = 5_000
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
