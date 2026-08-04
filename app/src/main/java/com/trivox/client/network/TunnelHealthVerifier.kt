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
    private val targets = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204"
    )

    fun measure(
        settings: AppSettings,
        attempts: Int = 2,
        budgetMs: Int = 12_000
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val count = attempts.coerceIn(1, 3)
        val budget = budgetMs.coerceIn(2_000, 20_000)
        val deadline = System.nanoTime() + budget * 1_000_000L
        val samples = mutableListOf<Long>()
        var lastFailure = "tunnel_timeout"

        repeat(count) { sampleIndex ->
            if (Thread.currentThread().isInterrupted) return@repeat
            var accepted = false

            for (proxyType in listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
                if (accepted) break
                for (target in rotatedTargets(sampleIndex)) {
                    val remainingMs = (
                        (deadline - System.nanoTime()) / 1_000_000L
                    ).coerceAtMost(MAX_PROBE_TIMEOUT_MS.toLong()).toInt()
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

    private fun rotatedTargets(offset: Int): List<String> =
        if (offset % targets.size == 0) targets else targets.drop(1) + targets.first()

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
            connection.setRequestProperty("User-Agent", "Trivox-TunnelHealth/5")

            val started = System.nanoTime()
            val status = connection.responseCode
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            if (status == 204) {
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

    private const val MIN_PROBE_TIMEOUT_MS = 500
    private const val MAX_PROBE_TIMEOUT_MS = 4_500
}
