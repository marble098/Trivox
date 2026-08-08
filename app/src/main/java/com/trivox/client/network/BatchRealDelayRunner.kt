package com.trivox.client.network

import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.core.CoreAdapter
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.PingMethod
import com.trivox.client.data.PingResult
import com.trivox.client.util.Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Starts one bounded Xray process for a group of profiles. Each profile receives
 * an isolated localhost SOCKS inbound and an inboundTag -> outboundTag route.
 * Policy presets tune parallelism and proof depth without changing what a valid
 * Real Delay means: at least one verified HTTPS response must cross that exact
 * profile outbound.
 */
internal class BatchRealDelayRunner(
    private val core: CoreAdapter
) {
    fun run(
        profiles: List<ConfigProfile>,
        settings: AppSettings,
        workDir: File,
        callback: (ConfigProfile, PingResult) -> Unit,
        fallback: (ConfigProfile) -> PingResult
    ) {
        val policy = RealDelayPolicy.from(settings)
        profiles.chunked(policy.groupSize).forEach { group ->
            if (Thread.currentThread().isInterrupted) return

            val compatible = group.filterNot {
                it.protocol.equals("chain", ignoreCase = true) ||
                    it.protocol.equals("openssh", ignoreCase = true) ||
                    it.protocol.equals("wireguard", ignoreCase = true)
            }
            val incompatible = group - compatible.toSet()

            incompatible.forEach { profile ->
                if (!Thread.currentThread().isInterrupted) {
                    callback(profile, fallback(profile))
                }
            }

            if (compatible.isEmpty()) return@forEach
            runGroup(
                profiles = compatible,
                settings = settings,
                policy = policy,
                workDir = workDir,
                callback = callback,
                fallback = fallback
            )
        }
    }

    private fun runGroup(
        profiles: List<ConfigProfile>,
        settings: AppSettings,
        policy: RealDelayPolicy,
        workDir: File,
        callback: (ConfigProfile, PingResult) -> Unit,
        fallback: (ConfigProfile) -> PingResult
    ) {
        val ports = runCatching { reservePorts(profiles.size) }.getOrElse {
            profiles.forEach { callback(it, fallback(it)) }
            return
        }
        val logFile = File(workDir, "trivox-batch-real-${System.nanoTime()}.log")
        var started = false
        val delivered = HashSet<String>()

        fun deliver(profile: ConfigProfile, result: PingResult) {
            synchronized(delivered) {
                if (!delivered.add(profile.id)) return
            }
            callback(profile, result)
        }

        try {
            val config = buildConfig(profiles, ports, settings, logFile)
            val validation = core.validate(config)
            if (!validation.success) {
                profiles.forEach { deliver(it, fallback(it)) }
                return
            }

            val start = core.start(config, null)
            if (!start.success) {
                profiles.forEach { deliver(it, fallback(it)) }
                return
            }
            started = true

            if (!waitCancellable(policy.startGraceMs)) return

            val executor = Executors.newFixedThreadPool(
                minOf(policy.workers, profiles.size)
            )
            try {
                val futures = profiles.indices.map { index ->
                    executor.submit(Callable {
                        val result = measurePort(
                            settings = settings,
                            port = ports[index],
                            policy = policy
                        )
                        deliver(profiles[index], result)
                    })
                }
                waitFor(futures)
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(1, TimeUnit.SECONDS)
            }
        } catch (throwable: Throwable) {
            Diagnostics.recordThrowable("Batch verified Real Delay", throwable)
            if (started) {
                runCatching { core.stop() }
                started = false
            }
            profiles.forEach { profile ->
                if (!Thread.currentThread().isInterrupted) {
                    deliver(profile, fallback(profile))
                }
            }
        } finally {
            if (started) {
                runCatching { core.stop() }
                    .onFailure {
                        Diagnostics.warning(
                            "Batch Real Delay cleanup failed: ${it.message}"
                        )
                    }
            }
            runCatching { logFile.delete() }
        }
    }

    private fun buildConfig(
        profiles: List<ConfigProfile>,
        ports: List<Int>,
        settings: AppSettings,
        logFile: File
    ): String {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray().put(
            JSONObject()
                .put("type", "field")
                .put("inboundTag", JSONArray().put("dns-in"))
                .put("outboundTag", "batch-direct")
        )
        var sharedDns: JSONObject? = null

        profiles.forEachIndexed { index, profile ->
            val built = JSONObject(
                XrayConfigBuilder.build(
                    profile = profile,
                    settings = settings.copy().normalize(),
                    mode = ConnectionMode.PROXY,
                    errorLogPath = logFile.absolutePath
                )
            )
            built.optJSONObject("dns")?.let { dns ->
                sharedDns = mergeDns(sharedDns, dns)
            }

            val outbound = findProxyOutbound(built)
            val outboundTag = "batch-proxy-$index"
            val inboundTag = "batch-in-$index"
            outbound.put("tag", outboundTag)
            outbounds.put(outbound)

            inbounds.put(
                JSONObject()
                    .put("tag", inboundTag)
                    .put("listen", "127.0.0.1")
                    .put("port", ports[index])
                    .put("protocol", "socks")
                    .put(
                        "settings",
                        JSONObject().put("auth", "noauth").put("udp", false)
                    )
            )
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put(inboundTag))
                    .put("outboundTag", outboundTag)
            )
        }

        outbounds.put(
            JSONObject()
                .put("protocol", "freedom")
                .put("tag", "batch-direct")
        )

        val root = JSONObject()
            .put(
                "log",
                JSONObject()
                    .put("loglevel", "warning")
                    .put("error", logFile.absolutePath)
            )
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put(
                "routing",
                JSONObject().put("domainStrategy", "AsIs").put("rules", rules)
            )
        sharedDns?.let { root.put("dns", it) }
        return root.toString()
    }

    private fun mergeDns(current: JSONObject?, incoming: JSONObject): JSONObject {
        if (current == null) return JSONObject(incoming.toString())
        val merged = JSONObject(current.toString())
        val servers = JSONArray()
        val seenServers = LinkedHashSet<String>()

        fun appendServers(source: JSONObject) {
            val values = source.optJSONArray("servers") ?: return
            for (index in 0 until values.length()) {
                val value = values.opt(index)
                val key = value?.toString().orEmpty()
                if (key.isNotBlank() && seenServers.add(key)) servers.put(value)
            }
        }
        appendServers(current)
        appendServers(incoming)
        if (servers.length() > 0) merged.put("servers", servers)

        val hosts = JSONObject()
        fun appendHosts(source: JSONObject) {
            val values = source.optJSONObject("hosts") ?: return
            for (key in values.keys()) {
                if (!hosts.has(key)) hosts.put(key, values.opt(key))
            }
        }
        appendHosts(current)
        appendHosts(incoming)
        if (hosts.length() > 0) merged.put("hosts", hosts)
        return merged
    }

    private fun findProxyOutbound(root: JSONObject): JSONObject {
        val values = root.optJSONArray("outbounds")
            ?: error("Generated Xray config has no outbounds")
        var fallback: JSONObject? = null
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: continue
            if (fallback == null) fallback = JSONObject(value.toString())
            if (value.optString("tag") == "proxy") {
                return JSONObject(value.toString())
            }
        }
        return fallback ?: error("Generated Xray config has no usable outbound")
    }

    private fun measurePort(
        settings: AppSettings,
        port: Int,
        policy: RealDelayPolicy
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val localSettings = settings.copy(socksPort = port, httpPort = port)
        val samples = mutableListOf<Long>()
        var lastError = "verified_https_failed"

        policy.targets.forEachIndexed { index, target ->
            if (Thread.currentThread().isInterrupted) {
                return failure(timestamp, "cancelled")
            }
            val probe = VerifiedHttpProbe.probe(
                settings = localSettings,
                route = VerifiedHttpProbe.Route.SOCKS_PROXY,
                target = target,
                timeoutMs = policy.probeTimeoutMs,
                nonce = timestamp + index
            )
            if (probe.success && probe.latencyMs != null) {
                samples += probe.latencyMs
                if (samples.size >= policy.requiredProofs) {
                    return success(timestamp, samples)
                }
            } else {
                lastError = probe.errorCategory ?: lastError
            }
            val remaining = policy.targets.size - index - 1
            if (samples.size + remaining < policy.requiredProofs) {
                return failure(timestamp, lastError)
            }
        }
        return if (samples.size >= policy.requiredProofs) {
            success(timestamp, samples)
        } else {
            failure(timestamp, lastError)
        }
    }

    private fun success(timestamp: Long, samples: List<Long>): PingResult {
        val sorted = samples.sorted()
        val latency = sorted[sorted.size / 2]
        val jitter = if (samples.size < 2) 0L else abs(samples.first() - samples.last())
        return PingResult(
            method = PingMethod.XRAY_HTTP.name,
            success = true,
            latencyMs = latency,
            jitterMs = jitter,
            successRatio = 1.0,
            resolvedIp = null,
            timestamp = timestamp,
            errorCategory = null
        )
    }

    private fun failure(timestamp: Long, category: String) = PingResult(
        method = PingMethod.XRAY_HTTP.name,
        success = false,
        latencyMs = null,
        jitterMs = null,
        successRatio = 0.0,
        resolvedIp = null,
        timestamp = timestamp,
        errorCategory = category
    )

    private fun reservePorts(count: Int): List<Int> {
        val sockets = ArrayList<ServerSocket>(count)
        return try {
            repeat(count) { sockets += ServerSocket(0, 1) }
            sockets.map { it.localPort }
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private fun waitFor(futures: List<Future<*>>) {
        futures.forEach { future ->
            while (!future.isDone) {
                if (Thread.currentThread().isInterrupted) {
                    futures.forEach { it.cancel(true) }
                    return
                }
                runCatching { future.get(100, TimeUnit.MILLISECONDS) }
            }
        }
    }

    private fun waitCancellable(delayMs: Int): Boolean {
        var remaining = delayMs
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted) return false
            val slice = minOf(remaining, 40)
            try {
                Thread.sleep(slice.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= slice
        }
        return true
    }
}
