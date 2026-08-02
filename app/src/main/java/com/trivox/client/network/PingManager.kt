package com.trivox.client.network

import com.trivox.client.core.CoreAdapter
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.PingResult
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

class PingManager(private val core: CoreAdapter? = null) : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(4)

    fun tcp(profile: ConfigProfile, attempts: Int = 3, timeoutMs: Int = 4000): PingResult {
        val count = attempts.coerceIn(1, 5)
        val values = mutableListOf<Long>()
        var resolved: String? = null
        repeat(count) {
            val start = System.nanoTime()
            runCatching {
                resolved = InetAddress.getByName(profile.server).hostAddress
                Socket().use { socket -> socket.connect(InetSocketAddress(profile.server, profile.port), timeoutMs) }
                values += (System.nanoTime() - start) / 1_000_000
            }
        }
        val average = values.takeIf { it.isNotEmpty() }?.average()?.toLong()
        val jitter = if (values.size > 1) values.zipWithNext { a, b -> abs(a - b) }.average().toLong() else null
        return PingResult("TCP", values.isNotEmpty(), average, jitter, values.size.toDouble() / count, resolved,
            errorCategory = if (values.isEmpty()) "timeout_or_connection_failure" else null)
    }

    fun realXray(configFile: File, url: String, timeoutSeconds: Int = 8): PingResult {
        val result = core?.realDelay(configFile.absolutePath, timeoutSeconds, url)
            ?: return PingResult("XRAY_HTTP", false, null, null, 0.0, null, errorCategory = "core_unavailable")
        val delay = result.data?.optJSONObject("data")?.optLong("delay")
        val ok = result.success && delay != null && delay < 10_000
        return PingResult("XRAY_HTTP", ok, delay?.takeIf { ok }, null, if (ok) 1.0 else 0.0, null,
            errorCategory = if (ok) null else result.error.ifBlank { "xray_test_failure" })
    }

    fun tlsHandshake(profile: ConfigProfile, serverName: String = profile.server, timeoutMs: Int = 5000): PingResult {
        val start = System.nanoTime()
        return runCatching {
            val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            socket.use {
                it.soTimeout = timeoutMs
                it.connect(InetSocketAddress(profile.server, profile.port), timeoutMs)
                it.sslParameters = SSLParameters().apply { serverNames = listOf(SNIHostName(serverName)) }
                it.startHandshake()
                val delay = (System.nanoTime() - start) / 1_000_000
                PingResult("TLS_HANDSHAKE", true, delay, null, 1.0, it.inetAddress?.hostAddress)
            }
        }.getOrElse { PingResult("TLS_HANDSHAKE", false, null, null, 0.0, null, errorCategory = it.javaClass.simpleName) }
    }

    fun icmp(host: String, timeoutSeconds: Int = 4): PingResult {
        val command = listOf("ping", "-c", "1", "-W", timeoutSeconds.coerceIn(1, 10).toString(), host)
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().take(8192) }
            val completed = process.waitFor(timeoutSeconds.toLong() + 2, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            val match = Regex("time[=<]([0-9.]+)\\s*ms").find(output)
            val latency = match?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
            PingResult("ICMP", completed && process.exitValue() == 0, latency, null,
                if (completed && process.exitValue() == 0) 1.0 else 0.0, null,
                errorCategory = if (completed && process.exitValue() == 0) null else "icmp_unavailable_or_blocked")
        }.getOrElse { PingResult("ICMP", false, null, null, 0.0, null, errorCategory = "icmp_unavailable") }
    }

    fun batchTcp(profiles: List<ConfigProfile>, attempts: Int, callback: (ConfigProfile, PingResult) -> Unit): List<Future<*>> =
        profiles.map { profile -> executor.submit(Callable { callback(profile, tcp(profile, attempts)) }) }

    fun cancel(tasks: Collection<Future<*>>) { tasks.forEach { it.cancel(true) } }

    override fun close() { executor.shutdownNow() }
}
