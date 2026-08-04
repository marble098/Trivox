package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.PingMethod
import com.trivox.client.data.PingResult
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale
import kotlin.math.abs

/**
 * A bounded end-to-end probe through Trivox's localhost mixed listener.
 * Native process liveness alone is intentionally not treated as connectivity.
 */
object TunnelHealthVerifier {
    private val fallbackTargets = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://www.gstatic.com/generate_204"
    )

    fun measure(
        settings: AppSettings,
        attempts: Int = 2,
        budgetMs: Int = 12_000,
        perProbeTimeoutMs: Int = MAX_PROBE_TIMEOUT_MS,
        isCancelled: () -> Boolean = { false }
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val count = attempts.coerceIn(1, 3)
        val budget = budgetMs.coerceIn(1_500, 20_000)
        val probeTimeout = perProbeTimeoutMs.coerceIn(
            MIN_PROBE_TIMEOUT_MS,
            MAX_PROBE_TIMEOUT_MS
        )
        val deadline = System.nanoTime() + budget * 1_000_000L
        val samples = mutableListOf<Long>()
        var lastFailure = "tunnel_timeout"

        for (sampleIndex in 0 until count) {
            if (cancelled(isCancelled)) {
                return cancelledResult(timestamp, samples.size, count)
            }
            var accepted = false

            for (proxyType in listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
                if (accepted || cancelled(isCancelled)) break
                for (target in rotatedTargets(settings, sampleIndex)) {
                    if (cancelled(isCancelled)) {
                        return cancelledResult(timestamp, samples.size, count)
                    }
                    val remainingMs = (
                        (deadline - System.nanoTime()) / 1_000_000L
                    ).coerceAtMost(probeTimeout.toLong()).toInt()
                    if (remainingMs < MIN_PROBE_TIMEOUT_MS) break

                    val result = probe(
                        settings = settings,
                        proxyType = proxyType,
                        url = target,
                        timeoutMs = remainingMs,
                        nonce = timestamp + sampleIndex + samples.size
                    )
                    if (result.latencyMs != null) {
                        samples += result.latencyMs
                        accepted = true
                        break
                    }
                    lastFailure = result.errorCategory ?: lastFailure
                }
            }
        }

        val sorted = samples.sorted()
        val latency = sorted.takeIf(List<Long>::isNotEmpty)
            ?.get(sorted.size / 2)
        val jitter = if (sorted.size > 1) {
            val deviations = sorted.map { abs(it - (latency ?: it)) }.sorted()
            deviations[deviations.size / 2]
        } else {
            null
        }

        return PingResult(
            method = PingMethod.XRAY_HTTP.name,
            success = latency != null,
            latencyMs = latency,
            jitterMs = jitter,
            successRatio = samples.size.toDouble() / count.toDouble(),
            resolvedIp = "127.0.0.1",
            timestamp = timestamp,
            errorCategory = if (latency == null) lastFailure else null
        )
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
        resolvedIp = "127.0.0.1",
        timestamp = timestamp,
        errorCategory = "cancelled"
    )

    private fun probe(
        settings: AppSettings,
        proxyType: Proxy.Type,
        url: String,
        timeoutMs: Int,
        nonce: Long
    ): ProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            val proxy = Proxy(
                proxyType,
                InetSocketAddress("127.0.0.1", settings.socksPort)
            )
            val separator = if ('?' in url) '&' else '?'
            val uri = URI("$url${separator}trivox_health=$nonce")
            connection = uri.toURL().openConnection(proxy) as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("User-Agent", "Trivox-TunnelHealth/6")

            val started = System.nanoTime()
            val status = connection.responseCode
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            if (status in 200..399) {
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

    private data class ProbeResult(
        val latencyMs: Long? = null,
        val errorCategory: String? = null
    )

    private const val MIN_PROBE_TIMEOUT_MS = 350
    private const val MAX_PROBE_TIMEOUT_MS = 4_500
}
