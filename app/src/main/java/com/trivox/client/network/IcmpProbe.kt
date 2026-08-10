package com.trivox.client.network

// TRIVOX_P2_VERIFIED_ICMP

import com.trivox.client.data.PingResult
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Real platform ICMP echo. Reply source addresses must match the resolved target.
 * This is endpoint reachability telemetry, not proxy tunnel application latency.
 */
internal object IcmpProbe {
    internal data class Parsed(
        val samplesMs: List<Double>,
        val replyAddress: String?,
        val sawReply: Boolean,
        val sawUnexpectedReply: Boolean
    )

    fun measure(host: String, attempts: Int = 3, timeoutSeconds: Int = 2): PingResult {
        val timestamp = System.currentTimeMillis()
        val cleanHost = host.trim()
        val count = attempts.coerceIn(2, 5)
        val timeout = timeoutSeconds.coerceIn(1, 5)
        if (cleanHost.isBlank()) return failure(timestamp, "icmp_missing_host")

        val expected = runCatching {
            InetAddress.getAllByName(cleanHost)
                .mapNotNull { it.hostAddress }
                .map(::normalizeAddress)
                .filter(String::isNotBlank)
                .toSet()
        }.getOrElse { return failure(timestamp, "icmp_dns_failure") }
        if (expected.isEmpty()) return failure(timestamp, "icmp_dns_failure")

        val process = try {
            ProcessBuilder(
                "ping", "-n", "-c", count.toString(), "-W", timeout.toString(), cleanHost
            ).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return failure(
                timestamp,
                if (t is SecurityException) "icmp_operation_blocked" else "icmp_tool_unavailable"
            )
        }

        return try {
            val completed = process.waitFor((count * timeout + 3).toLong(), TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText().take(MAX_OUTPUT_CHARS) }
            val parsed = parseOutput(output, expected)
            val required = if (count <= 2) 1 else 2
            val success = completed && parsed.samplesMs.size >= required && parsed.replyAddress != null
            if (!success) {
                return failure(
                    timestamp,
                    when {
                        parsed.sawUnexpectedReply -> "icmp_reply_identity_mismatch"
                        parsed.sawReply -> "icmp_reply_unparseable"
                        !completed -> "icmp_timeout"
                        else -> "icmp_unavailable_or_blocked"
                    }
                )
            }
            val sorted = parsed.samplesMs.sorted()
            val median = sorted[sorted.size / 2]
            val jitter = if (parsed.samplesMs.size < 2) 0.0 else
                parsed.samplesMs.zipWithNext { a, b -> abs(a - b) }.average()
            PingResult(
                method = "ICMP",
                success = true,
                latencyMs = median.roundToLong().coerceAtLeast(0L),
                jitterMs = jitter.roundToLong().coerceAtLeast(0L),
                successRatio = parsed.samplesMs.size.toDouble() / count.toDouble(),
                resolvedIp = parsed.replyAddress,
                timestamp = timestamp,
                errorCategory = null
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failure(timestamp, "cancelled")
        } catch (_: Throwable) {
            failure(timestamp, "icmp_probe_failure")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    internal fun parseOutput(output: String, verifiedReplyAddresses: Set<String>): Parsed {
        val expected = verifiedReplyAddresses.map(::normalizeAddress).filter(String::isNotBlank).toSet()
        val samples = ArrayList<Double>()
        var acceptedAddress: String? = null
        var sawReply = false
        var sawUnexpected = false
        output.lineSequence().take(MAX_OUTPUT_LINES).forEach { line ->
            if (!line.contains("icmp_seq", ignoreCase = true)) return@forEach
            sawReply = true
            val match = REPLY_PATTERN.find(line) ?: return@forEach
            val address = normalizeAddress(
                match.groupValues[1].trim().removePrefix("(").removeSuffix(")").substringBefore('%')
            )
            if (address.isBlank() || address !in expected) {
                sawUnexpected = true
                return@forEach
            }
            val raw = match.groupValues[3].toDoubleOrNull() ?: return@forEach
            val value = if (match.groupValues[2] == "<") raw / 2.0 else raw
            if (!value.isFinite() || value < 0.0 || value > 120_000.0) return@forEach
            acceptedAddress = acceptedAddress ?: address
            samples += value
        }
        return Parsed(samples, acceptedAddress, sawReply, sawUnexpected)
    }

    private fun normalizeAddress(value: String): String = runCatching {
        InetAddress.getByName(
            value.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        ).hostAddress?.lowercase(Locale.ROOT).orEmpty().substringBefore('%')
    }.getOrDefault(value.trim().lowercase(Locale.ROOT).substringBefore('%'))

    private fun failure(timestamp: Long, category: String) = PingResult(
        method = "ICMP",
        success = false,
        latencyMs = null,
        jitterMs = null,
        successRatio = 0.0,
        resolvedIp = null,
        timestamp = timestamp,
        errorCategory = category
    )

    private val REPLY_PATTERN = Regex(
        "(?i)\bfrom\s+([0-9a-f:.%]+).*?\bicmp_seq[= ]\d+.*?\btime\s*([=<])\s*([0-9.]+)\s*ms"
    )
    private const val MAX_OUTPUT_CHARS = 16 * 1024
    private const val MAX_OUTPUT_LINES = 64
}
