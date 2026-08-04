package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.PingMethod
import com.trivox.client.data.PingResult
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.abs

/**
 * Fast end-to-end verification of the route that is actually exposed to the
 * user. The first probe is DNS-free and validates a bounded response body, so a
 * captive portal, redirect, TLS alert, or merely-open endpoint cannot become a
 * false Connected state.
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
        hardFailure: () -> String? = { null }
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        if (!waitCancellable(initialDelayMs.coerceIn(0, MAX_INITIAL_DELAY_MS), isCancelled)) {
            return cancelledResult(timestamp)
        }

        val budget = budgetMs.coerceIn(MIN_TOTAL_BUDGET_MS, MAX_TOTAL_BUDGET_MS)
        val timeout = perProbeTimeoutMs.coerceIn(
            MIN_PROBE_TIMEOUT_MS,
            MAX_PROBE_TIMEOUT_MS
        )
        val deadline = System.nanoTime() + budget * NANOS_PER_MILLISECOND
        val routes = routesFor(mode)
        val samples = mutableListOf<Long>()
        var lastFailure = "tunnel_timeout"

        val targets = buildList {
            add(VerifiedHttpProbe.strongTraceTarget)
            VerifiedHttpProbe.targetForUserUrl(settings.testUrl)
                ?.takeUnless { it.url == VerifiedHttpProbe.strongTraceTarget.url }
                ?.let(::add)
            addAll(VerifiedHttpProbe.fallback204Targets)
        }.distinctBy { it.url }

        val targetLimit = (attempts.coerceIn(2, 3) + 2)
            .coerceAtMost(targets.size)

        for (target in targets.take(targetLimit)) {
            if (cancelled(isCancelled)) return cancelledResult(timestamp)
            hardFailure()?.let { category ->
                return failureResult(timestamp, mode, category)
            }

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
                    /*
                     * A valid Cloudflare trace includes server-generated route
                     * data and is stronger than a plain status code. One such
                     * proof is enough to expose Connected immediately.
                     */
                    if (target.proof == VerifiedHttpProbe.Proof.CLOUDFLARE_TRACE) {
                        return successResult(timestamp, mode, samples)
                    }

                    /* Two independent exact/ordinary HTTPS proofs are accepted. */
                    if (samples.size >= 2) {
                        return successResult(timestamp, mode, samples)
                    }
                    break
                }
                lastFailure = probe.errorCategory ?: lastFailure
            }
        }

        hardFailure()?.let { lastFailure = it }
        return failureResult(timestamp, mode, lastFailure)
    }

    private fun routesFor(mode: ConnectionMode?): List<VerifiedHttpProbe.Route> =
        if (mode == ConnectionMode.VPN) {
            listOf(VerifiedHttpProbe.Route.ACTIVE_NETWORK)
        } else {
            listOf(
                VerifiedHttpProbe.Route.HTTP_PROXY,
                VerifiedHttpProbe.Route.SOCKS_PROXY
            )
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
        } else {
            null
        }
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

    internal fun acceptsHttpStatus(url: String, status: Int): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }
            .getOrDefault("")
            .lowercase()
        return if ("generate_204" in path || "gen_204" in path) {
            status == HttpURLConnection.HTTP_NO_CONTENT
        } else {
            status in 200..299
        }
    }

    private const val MIN_PROBE_TIMEOUT_MS = 650
    private const val MAX_PROBE_TIMEOUT_MS = 5_000
    private const val MIN_TOTAL_BUDGET_MS = 2_500
    private const val MAX_TOTAL_BUDGET_MS = 28_000
    private const val MAX_INITIAL_DELAY_MS = 3_000
    private const val WAIT_SLICE_MS = 75
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
