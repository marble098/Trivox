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
import kotlin.math.min

/**
 * Bounded end-to-end verification of the route that the user actually selected.
 *
 * Configuration-level TCP/Real Delay tests are useful ranking signals, but they
 * do not prove that Android's active VPN route or the live localhost proxy can
 * carry traffic after the core starts. This verifier is intentionally executed
 * against the running route and never treats an open port as a healthy tunnel.
 */
object TunnelHealthVerifier {
    private val fallbackTargets = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://www.gstatic.com/generate_204",
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://api.ipify.org/"
    )

    fun measure(
        settings: AppSettings,
        mode: ConnectionMode? = null,
        attempts: Int = 3,
        budgetMs: Int = 12_000,
        perProbeTimeoutMs: Int = MAX_PROBE_TIMEOUT_MS,
        initialDelayMs: Int = 0,
        isCancelled: () -> Boolean = { false }
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val count = attempts.coerceIn(2, 3)
        val budget = budgetMs.coerceIn(MIN_TOTAL_BUDGET_MS, MAX_TOTAL_BUDGET_MS)
        val probeTimeout = perProbeTimeoutMs.coerceIn(
            MIN_PROBE_TIMEOUT_MS,
            MAX_PROBE_TIMEOUT_MS
        )

        if (!waitCancellable(initialDelayMs.coerceIn(0, MAX_INITIAL_DELAY_MS), isCancelled)) {
            return cancelledResult(timestamp, 0, count)
        }

        val deadline = System.nanoTime() + budget * NANOS_PER_MILLISECOND
        val samplesNanos = mutableListOf<Long>()
        var lastFailure = "tunnel_timeout"

        for (sampleIndex in 0 until count) {
            if (cancelled(isCancelled)) {
                return cancelledResult(timestamp, samplesNanos.size, count)
            }

            val globalRemainingMs = remainingMillis(deadline)
            if (globalRemainingMs < MIN_PROBE_TIMEOUT_MS) break

            /*
             * Give every requested sample a bounded share of the global budget.
             * A blocked first endpoint must not consume the whole startup window
             * and prevent the second/third verification sample from running.
             */
            val samplesLeft = (count - sampleIndex).coerceAtLeast(1)
            val sampleBudgetMs = (globalRemainingMs / samplesLeft)
                .coerceAtLeast(MIN_PROBE_TIMEOUT_MS.toLong())
            val sampleDeadline = min(
                deadline,
                System.nanoTime() + sampleBudgetMs * NANOS_PER_MILLISECOND
            )

            var accepted = false
            for (route in probeRoutes(mode)) {
                if (accepted || cancelled(isCancelled)) break

                for (target in rotatedTargets(settings, sampleIndex)) {
                    if (cancelled(isCancelled)) {
                        return cancelledResult(timestamp, samplesNanos.size, count)
                    }

                    val remainingMs = min(
                        remainingMillis(deadline),
                        remainingMillis(sampleDeadline)
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

            if (
                sampleIndex + 1 < count &&
                !waitCancellable(INTER_SAMPLE_DELAY_MS, isCancelled)
            ) {
                return cancelledResult(timestamp, samplesNanos.size, count)
            }
        }

        val summary = PingStatistics.summarize(samplesNanos, count)
        return PingResult(
            method = PingMethod.XRAY_HTTP.name,
            success = summary.success,
            latencyMs = summary.latencyMs,
            jitterMs = summary.jitterMs,
            successRatio = summary.successRatio,
            resolvedIp = when (mode) {
                ConnectionMode.VPN -> "vpn"
                else -> "127.0.0.1"
            },
            timestamp = timestamp,
            errorCategory = if (summary.success) null else lastFailure
        )
    }

    /*
     * VPN verification must only use Android's active VPN route. Falling back to
     * the optional localhost proxy could otherwise mark a broken full-device VPN
     * as healthy merely because the side listener works.
     */
    private fun probeRoutes(mode: ConnectionMode?): List<ProbeRoute> =
        if (mode == ConnectionMode.VPN) {
            listOf(ProbeRoute.ACTIVE_VPN)
        } else {
            listOf(ProbeRoute.HTTP_PROXY, ProbeRoute.SOCKS_PROXY)
        }

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

    private fun waitCancellable(
        delayMs: Int,
        isCancelled: () -> Boolean
    ): Boolean {
        var remaining = delayMs
        while (remaining > 0) {
            if (cancelled(isCancelled)) return false
            val slice = remaining.coerceAtMost(WAIT_SLICE_MS)
            try {
                Thread.sleep(slice.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= slice
        }
        return !cancelled(isCancelled)
    }

    private fun remainingMillis(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND)
            .coerceAtLeast(0L)

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
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "Health-check URL must use HTTPS"
            }

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

                ProbeRoute.ACTIVE_VPN ->
                    uri.toURL().openConnection(Proxy.NO_PROXY)
            }

            connection = rawConnection as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "Trivox-TunnelHealth/9")

            val started = System.nanoTime()
            val status = connection.responseCode
            val elapsed = (
                (System.nanoTime() - started) / NANOS_PER_MILLISECOND
            ).coerceAtLeast(1L)

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

    private const val MIN_PROBE_TIMEOUT_MS = 650
    private const val MAX_PROBE_TIMEOUT_MS = 6_000
    private const val MIN_TOTAL_BUDGET_MS = 3_500
    private const val MAX_TOTAL_BUDGET_MS = 28_000
    private const val MAX_INITIAL_DELAY_MS = 3_000
    private const val INTER_SAMPLE_DELAY_MS = 120
    private const val WAIT_SLICE_MS = 100
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
