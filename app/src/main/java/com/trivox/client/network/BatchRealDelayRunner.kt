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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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
        val policy = RealDelayPolicy.from(settings).forBatch(profiles.size)

        if (profiles.size >= 64) {
            Diagnostics.info(
                "Large Real Delay batch constrained; " +
                    "total=${profiles.size}, group=${policy.groupSize}, workers=${policy.workers}"
            )
        }

        profiles.chunked(policy.groupSize).forEach { group ->
            if (Thread.currentThread().isInterrupted) return

            val compatible = group.filterNot {
                it.protocol.equals("chain", ignoreCase = true) ||
                    it.protocol.equals("openssh", ignoreCase = true) ||
                    it.protocol.equals("wireguard", ignoreCase = true)
            }
            val isolated = group - compatible.toSet()

            isolated.forEach { profile ->
                if (!Thread.currentThread().isInterrupted) {
                    callback(profile, fallback(profile))
                }
            }

            if (compatible.isNotEmpty()) {
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
        val batchResults = arrayOfNulls<PingResult>(profiles.size)

        fun deliver(profile: ConfigProfile, result: PingResult) {
            synchronized(delivered) {
                if (!delivered.add(profile.id)) return
            }
            callback(profile, result)
        }

        fun stopBatchCore(): Boolean {
            if (!started) return true

            val stopped = runCatching { core.stop() }
                .onFailure {
                    Diagnostics.warning(
                        "Batch Real Delay cleanup failed: ${it.message}"
                    )
                }
                .getOrNull()

            started = false

            if (stopped?.success == true) return true

            Diagnostics.warning(
                "Batch Real Delay could not confirm Xray shutdown; " +
                    "isolated rescue skipped for native lifecycle safety"
            )
            return false
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

            val readyCount = awaitLocalListeners(
                ports = ports,
                timeoutMs = maxOf(policy.startGraceMs, LISTENER_READY_BUDGET_MS)
            )

            if (Thread.currentThread().isInterrupted) return

            if (readyCount < ports.size) {
                Diagnostics.warning(
                    "Batch Real Delay listeners partially ready; " +
                        "ready=$readyCount/${ports.size}; failures will be rescued"
                )
            }

            val executor = Executors.newFixedThreadPool(
                minOf(policy.workers, profiles.size)
            )

            try {
                val futures = profiles.indices.map { index ->
                    executor.submit(Callable {
                        batchResults[index] = measurePort(
                            settings = settings,
                            port = ports[index],
                            policy = policy
                        )
                    })
                }
                waitFor(futures)
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(1, TimeUnit.SECONDS)
            }

            if (Thread.currentThread().isInterrupted) return

            val safeToRescue = stopBatchCore()

            profiles.forEachIndexed { index, profile ->
                if (Thread.currentThread().isInterrupted) return

                val batchResult = batchResults[index]
                    ?: failure(
                        System.currentTimeMillis(),
                        "batch_measurement_incomplete"
                    )

                if (batchResult.success || !safeToRescue) {
                    deliver(profile, batchResult)
                } else {
                    Diagnostics.debug(
                        "Batch Real Delay rescue; profile=${profile.id}, " +
                            "batchError=${batchResult.errorCategory.orEmpty()}"
                    )

                    val rescued = runCatching { fallback(profile) }
                        .getOrElse { error ->
                            Diagnostics.recordThrowable(
                                "Batch Real Delay isolated rescue",
                                error
                            )
                            batchResult
                        }

                    deliver(
                        profile,
                        if (rescued.success) rescued
                        else preferFailure(rescued, batchResult)
                    )
                }
            }
        } catch (throwable: Throwable) {
            Diagnostics.recordThrowable("Batch verified Real Delay", throwable)
            val safeToRescue = stopBatchCore()

            profiles.forEachIndexed { index, profile ->
                if (!Thread.currentThread().isInterrupted) {
                    val batchResult = batchResults[index]
                        ?: failure(
                            System.currentTimeMillis(),
                            "batch_runner_failure"
                        )

                    deliver(
                        profile,
                        if (safeToRescue) {
                            runCatching { fallback(profile) }
                                .getOrDefault(batchResult)
                        } else {
                            batchResult
                        }
                    )
                }
            }
        } finally {
            stopBatchCore()
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

            val selection = BatchOutboundGraph.select(built, index)
            selection.outbounds.forEach(outbounds::put)

            val inboundTag = "batch-in-$index"

            inbounds.put(
                JSONObject()
                    .put("tag", inboundTag)
                    .put("listen", "127.0.0.1")
                    .put("port", ports[index])
                    .put("protocol", "socks")
                    .put(
                        "settings",
                        JSONObject()
                            .put("auth", "noauth")
                            .put("udp", false)
                    )
            )

            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put(inboundTag))
                    .put("outboundTag", selection.primaryTag)
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
                JSONObject()
                    .put("domainStrategy", "AsIs")
                    .put("rules", rules)
            )

        sharedDns?.let { root.put("dns", it) }

        return root.toString()
    }

    private fun mergeDns(
        current: JSONObject?,
        incoming: JSONObject
    ): JSONObject {
        if (current == null) return JSONObject(incoming.toString())

        val merged = JSONObject(current.toString())
        val servers = JSONArray()
        val seenServers = LinkedHashSet<String>()

        fun appendServers(source: JSONObject) {
            val values = source.optJSONArray("servers") ?: return
            for (index in 0 until values.length()) {
                val value = values.opt(index)
                val key = value?.toString().orEmpty()
                if (key.isNotBlank() && seenServers.add(key)) {
                    servers.put(value)
                }
            }
        }

        appendServers(current)
        appendServers(incoming)

        if (servers.length() > 0) {
            merged.put("servers", servers)
        }

        val hosts = JSONObject()

        fun appendHosts(source: JSONObject) {
            val values = source.optJSONObject("hosts") ?: return
            for (key in values.keys()) {
                if (!hosts.has(key)) {
                    hosts.put(key, values.opt(key))
                }
            }
        }

        appendHosts(current)
        appendHosts(incoming)

        if (hosts.length() > 0) {
            merged.put("hosts", hosts)
        }

        return merged
    }

    private fun measurePort(
        settings: AppSettings,
        port: Int,
        policy: RealDelayPolicy
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val localSettings = settings.copy(
            socksPort = port,
            httpPort = port
        )
        val samples = mutableListOf<Long>()
        var lastError = "verified_https_failed"

        fun tryTargets(
            targets: List<VerifiedHttpProbe.Target>,
            timeoutMs: Int
        ): Boolean {
            targets.forEachIndexed { index, target ->
                if (Thread.currentThread().isInterrupted) {
                    lastError = "cancelled"
                    return false
                }

                val probe = VerifiedHttpProbe.probe(
                    settings = localSettings,
                    route = VerifiedHttpProbe.Route.SOCKS_PROXY,
                    target = target,
                    timeoutMs = timeoutMs,
                    nonce = timestamp + samples.size * 31L + index
                )

                if (probe.success && probe.latencyMs != null) {
                    samples += probe.latencyMs

                    if (samples.size >= policy.requiredProofs) {
                        return true
                    }
                } else {
                    lastError = probe.errorCategory ?: lastError
                }
            }

            return samples.size >= policy.requiredProofs
        }

        if (tryTargets(policy.targets, policy.probeTimeoutMs)) {
            return success(timestamp, samples)
        }

        if (Thread.currentThread().isInterrupted) {
            return failure(timestamp, "cancelled")
        }

        if (
            policy.rescueTargets.isNotEmpty() &&
            tryTargets(
                policy.rescueTargets,
                policy.rescueProbeTimeoutMs
            )
        ) {
            return success(timestamp, samples)
        }

        return if (samples.size >= policy.requiredProofs) {
            success(timestamp, samples)
        } else {
            failure(timestamp, lastError)
        }
    }

    private fun success(
        timestamp: Long,
        samples: List<Long>
    ): PingResult {
        val sorted = samples.sorted()
        val latency = sorted[sorted.size / 2]
        val jitter = if (sorted.size < 2) {
            0L
        } else {
            sorted
                .map { abs(it - latency) }
                .sorted()[sorted.size / 2]
        }

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

    private fun preferFailure(
        primary: PingResult,
        secondary: PingResult
    ): PingResult {
        val generic = setOf(
            null,
            "",
            "verified_https_failed",
            "xray_test_failure",
            "batch_measurement_incomplete",
            "batch_runner_failure"
        )

        return if (
            primary.errorCategory in generic &&
            secondary.errorCategory !in generic
        ) {
            secondary
        } else {
            primary
        }
    }

    private fun failure(
        timestamp: Long,
        category: String
    ) = PingResult(
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
            repeat(count) {
                sockets += ServerSocket(0, 1)
            }
            sockets.map { it.localPort }
        } finally {
            sockets.forEach {
                runCatching { it.close() }
            }
        }
    }

    private fun awaitLocalListeners(
        ports: List<Int>,
        timeoutMs: Int
    ): Int {
        val pending = ports.toMutableSet()
        val deadline =
            System.nanoTime() +
                timeoutMs
                    .coerceIn(250, 2_500) *
                    NANOS_PER_MILLISECOND

        while (
            pending.isNotEmpty() &&
            System.nanoTime() < deadline
        ) {
            if (Thread.currentThread().isInterrupted) {
                break
            }

            val iterator = pending.iterator()

            while (iterator.hasNext()) {
                val port = iterator.next()
                val ready = runCatching {
                    Socket().use { socket ->
                        socket.connect(
                            InetSocketAddress(
                                "127.0.0.1",
                                port
                            ),
                            LISTENER_CONNECT_TIMEOUT_MS
                        )
                    }
                    true
                }.getOrDefault(false)

                if (ready) {
                    iterator.remove()
                }
            }

            if (
                pending.isNotEmpty() &&
                !waitCancellable(LISTENER_RETRY_MS)
            ) {
                break
            }
        }

        return ports.size - pending.size
    }

    private fun waitFor(futures: List<Future<*>>) {
        futures.forEach { future ->
            while (!future.isDone) {
                if (Thread.currentThread().isInterrupted) {
                    futures.forEach { it.cancel(true) }
                    return
                }

                runCatching {
                    future.get(
                        100,
                        TimeUnit.MILLISECONDS
                    )
                }
            }
        }
    }

    private fun waitCancellable(delayMs: Int): Boolean {
        var remaining = delayMs

        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted) {
                return false
            }

            val slice = minOf(
                remaining,
                40
            )

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

    companion object {
        private const val LISTENER_READY_BUDGET_MS = 1_250
        private const val LISTENER_CONNECT_TIMEOUT_MS = 90
        private const val LISTENER_RETRY_MS = 35
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal object BatchOutboundGraph {
    data class Selection(
        val outbounds: List<JSONObject>,
        val primaryTag: String
    )

    fun select(
        root: JSONObject,
        profileIndex: Int
    ): Selection {
        val values = root.optJSONArray("outbounds")
            ?: error("Generated Xray config has no outbounds")

        val ordered = buildList {
            for (index in 0 until values.length()) {
                values.optJSONObject(index)?.let {
                    add(JSONObject(it.toString()))
                }
            }
        }

        val primary =
            ordered.firstOrNull {
                it.optString("tag") == "proxy"
            }
                ?: ordered.firstOrNull()
                ?: error(
                    "Generated Xray config has no usable outbound"
                )

        val primaryOriginalTag =
            primary.optString("tag")
                .takeIf(String::isNotBlank)
                ?: error(
                    "Generated proxy outbound has no tag"
                )

        val byTag =
            ordered
                .mapNotNull { outbound ->
                    outbound.optString("tag")
                        .takeIf(String::isNotBlank)
                        ?.let { it to outbound }
                }
                .toMap()

        val required = LinkedHashSet<String>()

        fun visit(tag: String) {
            if (
                tag.isBlank() ||
                !required.add(tag)
            ) {
                return
            }

            val outbound = byTag[tag] ?: return

            dependencyTags(outbound)
                .forEach(::visit)
        }

        visit(primaryOriginalTag)

        val tagMap =
            LinkedHashMap<String, String>()

        tagMap[primaryOriginalTag] =
            "batch-proxy-$profileIndex"

        var dependencyIndex = 0

        required.forEach { tag ->
            if (tag != primaryOriginalTag) {
                tagMap[tag] =
                    "batch-dep-$profileIndex-${dependencyIndex++}"
            }
        }

        val copied =
            ordered.mapNotNull { outbound ->
                val originalTag =
                    outbound.optString("tag")
                val replacementTag =
                    tagMap[originalTag]
                        ?: return@mapNotNull null

                val copy =
                    JSONObject(outbound.toString())

                copy.put(
                    "tag",
                    replacementTag
                )

                rewriteReferences(
                    copy,
                    tagMap
                )

                copy
            }

        if (
            copied.none {
                it.optString("tag") ==
                    tagMap.getValue(primaryOriginalTag)
            }
        ) {
            error(
                "Generated Xray config lost its primary outbound"
            )
        }

        return Selection(
            outbounds = copied,
            primaryTag =
                tagMap.getValue(primaryOriginalTag)
        )
    }

    private fun dependencyTags(
        outbound: JSONObject
    ): List<String> = buildList {
        outbound.optJSONObject("proxySettings")
            ?.optString("tag")
            ?.takeIf(String::isNotBlank)
            ?.let(::add)

        outbound.optJSONObject("streamSettings")
            ?.optJSONObject("sockopt")
            ?.optString("dialerProxy")
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
    }

    private fun rewriteReferences(
        outbound: JSONObject,
        tagMap: Map<String, String>
    ) {
        outbound.optJSONObject("proxySettings")
            ?.let { proxySettings ->
                val original =
                    proxySettings.optString("tag")

                tagMap[original]
                    ?.let {
                        proxySettings.put(
                            "tag",
                            it
                        )
                    }
            }

        outbound.optJSONObject("streamSettings")
            ?.optJSONObject("sockopt")
            ?.let { sockopt ->
                val original =
                    sockopt.optString(
                        "dialerProxy"
                    )

                tagMap[original]
                    ?.let {
                        sockopt.put(
                            "dialerProxy",
                            it
                        )
                    }
            }
    }
}
