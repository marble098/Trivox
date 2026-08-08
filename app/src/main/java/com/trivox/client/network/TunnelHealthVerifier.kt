package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.PingMethod
import com.trivox.client.data.PingResult
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlin.math.abs

/**
 * Fast end-to-end verification of the route actually exposed to the user.
 * Proxy mode first verifies that the localhost listener exists, then performs a
 * DNS-free SOCKS HTTPS proof before trying HTTP CONNECT. This prevents repeated
 * DNS timeouts from hiding a listener/lifecycle bug and avoids spending two full
 * timeouts on the less reliable route first.
 */
object TunnelHealthVerifier {
    fun measure(
        settings: AppSettings,
        mode: ConnectionMode? = null,
        attempts: Int = 3,
        budgetMs: Int = 12_000,
        perProbeTimeoutMs: Int = MAX_PROBE_TIMEOUT_MS,
        initialDelayMs: Int = 0,
        isCancelled: () -> Boolean = { false },
        hardFailure: () -> String? = { null },
        dnsFallbackTargets: Boolean = true
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        if (!waitCancellable(initialDelayMs.coerceIn(0, MAX_INITIAL_DELAY_MS), isCancelled)) {
            return cancelledResult(timestamp)
        }

        val budget = budgetMs.coerceIn(MIN_TOTAL_BUDGET_MS, MAX_TOTAL_BUDGET_MS)
        val timeout = perProbeTimeoutMs.coerceIn(MIN_PROBE_TIMEOUT_MS, MAX_PROBE_TIMEOUT_MS)
        val deadline = System.nanoTime() + budget * NANOS_PER_MILLISECOND

        if (mode != ConnectionMode.VPN && !waitForProxyListener(
                settings.socksPort,
                deadline,
                isCancelled
            )
        ) {
            return if (cancelled(isCancelled)) {
                cancelledResult(timestamp)
            } else {
                failureResult(timestamp, mode, "local_proxy_listener_unavailable")
            }
        }

        val routes = routesFor(mode)
        val samples = mutableListOf<Long>()
        var lastFailure = "tunnel_timeout"
        val targets = buildList {
            addAll(VerifiedHttpProbe.dnsFreeTraceTargets)
            if (dnsFallbackTargets) {
                VerifiedHttpProbe.targetForUserUrl(settings.testUrl)
                    ?.takeUnless { candidate ->
                        VerifiedHttpProbe.dnsFreeTraceTargets.any { it.url == candidate.url }
                    }
                    ?.let(::add)
                addAll(VerifiedHttpProbe.fallback204Targets)
            }
        }.distinctBy { it.url }
        val targetLimit = (attempts.coerceIn(2, 3) + 2).coerceAtMost(targets.size)

        for (target in targets.take(targetLimit)) {
            if (cancelled(isCancelled)) return cancelledResult(timestamp)
            hardFailure()?.let { return failureResult(timestamp, mode, it) }

            for (route in routes) {
                if (cancelled(isCancelled)) return cancelledResult(timestamp)
                val remaining = remainingMillis(deadline)
                if (remaining < MIN_PROBE_TIMEOUT_MS) {
                    return failureResult(timestamp, mode, lastFailure)
                }
                val probe = VerifiedHttpProbe.probe(
                    settings = settings,
                    route = route,
                    target = target,
                    timeoutMs = remaining.coerceAtMost(timeout.toLong()).toInt(),
                    nonce = timestamp + samples.size
                )
                if (probe.success && probe.latencyMs != null) {
                    samples += probe.latencyMs
                    if (target.proof == VerifiedHttpProbe.Proof.CLOUDFLARE_TRACE) {
                        return successResult(timestamp, mode, samples)
                    }
                    if (samples.size >= 2) {
                        return successResult(timestamp, mode, samples)
                    }
                    break
                }
                lastFailure = preferredFailure(lastFailure, probe.errorCategory)
            }
        }

        hardFailure()?.let { lastFailure = it }
        return failureResult(timestamp, mode, lastFailure)
    }

    private fun preferredFailure(current: String, candidate: String?): String {
        if (candidate.isNullOrBlank()) return current
        if (current == "tunnel_timeout") return candidate
        fun priority(value: String): Int {
            val v = value.lowercase()
            return when {
                v in setOf(
                    "reality_mismatch", "authentication_failed", "tls_certificate",
                    "connection_refused", "local_proxy_listener_unavailable"
                ) -> 100
                "ssl" in v || "tls" in v -> 90
                "connectexception" in v || "connectionrefused" in v -> 85
                "timeout" in v -> 75
                v.startsWith("http_") || v == "invalid_trace_body" -> 65
                v in setOf("gaiexception", "unknownhostexception") -> 40
                else -> 55
            }
        }
        return if (priority(candidate) >= priority(current)) candidate else current
    }

    private fun routesFor(mode: ConnectionMode?): List<VerifiedHttpProbe.Route> =
        if (mode == ConnectionMode.VPN) {
            listOf(VerifiedHttpProbe.Route.ACTIVE_NETWORK)
        } else {
            listOf(
                VerifiedHttpProbe.Route.SOCKS_PROXY,
                VerifiedHttpProbe.Route.HTTP_PROXY
            )
        }

    private fun waitForProxyListener(
        port: Int,
        deadlineNanos: Long,
        isCancelled: () -> Boolean
    ): Boolean {
        val listenerDeadline = minOf(
            deadlineNanos,
            System.nanoTime() + LISTENER_BUDGET_MS * NANOS_PER_MILLISECOND
        )
        while (System.nanoTime() < listenerDeadline) {
            if (cancelled(isCancelled)) return false
            val ready = runCatching {
                Socket().use {
                    it.connect(InetSocketAddress("127.0.0.1", port), LISTENER_CONNECT_MS)
                }
                true
            }.getOrDefault(false)
            if (ready) return true
            if (!waitCancellable(LISTENER_RETRY_MS, isCancelled)) return false
        }
        return false
    }

    private fun successResult(
        timestamp: Long,
        mode: ConnectionMode?,
        samples: List<Long>
    ): PingResult {
        val sorted = samples.sorted()
        val center = sorted[sorted.size / 2]
        val jitter = if (sorted.size > 1) {
            sorted.map { abs(it - center) }.sorted()[sorted.size / 2]
        } else null
        return PingResult(
            method = PingMethod.XRAY_HTTP.name,
            success = true,
            latencyMs = center.coerceAtLeast(1L),
            jitterMs = jitter,
            successRatio = 1.0,
            resolvedIp = if (mode == ConnectionMode.VPN) "vpn" else "127.0.0.1",
            timestamp = timestamp,
            errorCategory = null
        )
    }

    private fun failureResult(
        timestamp: Long,
        mode: ConnectionMode?,
        category: String
    ) = PingResult(
        method = PingMethod.XRAY_HTTP.name,
        success = false,
        latencyMs = null,
        jitterMs = null,
        successRatio = 0.0,
        resolvedIp = if (mode == ConnectionMode.VPN) "vpn" else "127.0.0.1",
        timestamp = timestamp,
        errorCategory = category
    )

    private fun cancelledResult(timestamp: Long) = PingResult(
        method = PingMethod.XRAY_HTTP.name,
        success = false,
        latencyMs = null,
        jitterMs = null,
        successRatio = 0.0,
        resolvedIp = null,
        timestamp = timestamp,
        errorCategory = "cancelled"
    )

    private fun cancelled(isCancelled: () -> Boolean): Boolean =
        Thread.currentThread().isInterrupted || isCancelled()

    private fun waitCancellable(delayMs: Int, isCancelled: () -> Boolean): Boolean {
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
        ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)

    internal fun acceptsHttpStatus(url: String, status: Int): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("").lowercase()
        return if ("generate_204" in path || "gen_204" in path) {
            status == HttpURLConnection.HTTP_NO_CONTENT
        } else status in 200..299
    }

    private const val MIN_PROBE_TIMEOUT_MS = 650
    private const val MAX_PROBE_TIMEOUT_MS = 5_000
    private const val MIN_TOTAL_BUDGET_MS = 2_500
    private const val MAX_TOTAL_BUDGET_MS = 28_000
    private const val MAX_INITIAL_DELAY_MS = 3_000
    private const val WAIT_SLICE_MS = 60
    private const val LISTENER_BUDGET_MS = 1_200
    private const val LISTENER_CONNECT_MS = 180
    private const val LISTENER_RETRY_MS = 55
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
