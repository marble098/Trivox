package com.trivox.client.core

import android.content.Context
import android.os.SystemClock
import com.trivox.client.config.NativeProfileDocument
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.CoreId
import com.trivox.client.data.SettingsRepository
import com.trivox.client.network.TunnelHealthVerifier
import com.trivox.client.util.Diagnostics
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Smart selection based on a real temporary proxy run, not fixed core order.
 * Every candidate must build, validate, start and expose a listener. Successful
 * HTTPS proof is preferred; listener-only candidates are degraded fallbacks.
 */
class SmartCoreSelector(
    context: Context,
    private val adapters: Map<CoreId, CoreAdapter>
) {
    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val lock = Any()
    private val cache = ConcurrentHashMap<String, Cached>()

    private data class Cached(val core: CoreId, val atElapsed: Long)

    private data class Candidate(
        val core: CoreId,
        val valid: Boolean,
        val listenerReady: Boolean,
        val proof: Boolean,
        val latencyMs: Long?,
        val startupMs: Long,
        val error: String
    ) {
        val score: Long
            get() = when {
                proof -> (latencyMs ?: 9_000L) + startupMs / 4
                listenerReady -> DEGRADED_BASE_SCORE + startupMs
                valid -> VALID_ONLY_BASE_SCORE + startupMs
                else -> Long.MAX_VALUE
            }
    }

    fun select(request: CoreStartRequest): CoreId = synchronized(lock) {
        val settings = settingsRepository.load()
        if (!settings.smartCoreSelection) return@synchronized settings.coreId

        NativeProfileDocument.affinity(request.profile)?.let { required ->
            val adapter = adapters[required]
            if (adapter?.isAvailable() == true) {
                settings.lastSmartCoreId = required
                settingsRepository.save(settings)
                Diagnostics.info("Smart core native affinity: ${required.label}")
                return@synchronized required
            }
            Diagnostics.warning("Native profile requires ${required.label}, but its binary is unavailable")
            return@synchronized CoreId.XRAY
        }

        val key = cacheKey(request)
        val now = SystemClock.elapsedRealtime()
        cache[key]?.takeIf { now - it.atElapsed <= CACHE_TTL_MS }?.let { cached ->
            if (adapters[cached.core]?.isAvailable() == true) {
                Diagnostics.info("Smart core cache hit: ${cached.core.label}")
                settings.lastSmartCoreId = cached.core
                settingsRepository.save(settings)
                return@synchronized cached.core
            }
        }

        if (ConnectionRuntime.current().state == ConnectionState.CONNECTED) {
            val retained = settings.lastSmartCoreId.takeIf { adapters[it]?.isAvailable() == true }
                ?: settings.coreId
            Diagnostics.info("Smart core retained while a session is connected: ${retained.label}")
            return@synchronized retained
        }

        val candidates = candidateOrder(request).filter { adapters[it]?.isAvailable() == true }
        if (candidates.isEmpty()) return@synchronized CoreId.XRAY

        val results = candidates.map { benchmark(request, it) }
        results.forEach { result ->
            Diagnostics.info(
                "Smart core candidate ${result.core.label}: valid=${result.valid}, " +
                    "listener=${result.listenerReady}, proof=${result.proof}, " +
                    "latency=${result.latencyMs ?: -1}, startup=${result.startupMs}, " +
                    "score=${if (result.score == Long.MAX_VALUE) -1 else result.score}, " +
                    "error=${result.error.ifBlank { "none" }}"
            )
        }

        val winner = results.filter { it.valid }.minWithOrNull(
            compareBy<Candidate> { it.score }
                .thenBy { preferenceIndex(request, it.core) }
        )?.core ?: CoreId.XRAY

        cache[key] = Cached(winner, now)
        settings.lastSmartCoreId = winner
        settingsRepository.save(settings)
        Diagnostics.info("Smart core winner: ${winner.label} for ${request.mode.name}")
        winner
    }

    private fun benchmark(request: CoreStartRequest, coreId: CoreId): Candidate {
        val adapter = adapters[coreId]
            ?: return Candidate(coreId, false, false, false, null, 0, "adapter missing")
        val port = freePort()
            ?: return Candidate(coreId, false, false, false, null, 0, "no free local port")
        val settings = request.settings.copy().also {
            it.mode = ConnectionMode.PROXY
            it.socksPort = port
            it.httpPort = port
            it.localProxyInVpn = false
            it.smartCoreSelection = false
            it.coreId = coreId
        }
        val candidateRequest = request.copy(settings = settings, mode = ConnectionMode.PROXY, tunFd = null)
        val config = runCatching { CoreConfigTranslator.build(candidateRequest, coreId) }
            .getOrElse {
                return Candidate(coreId, false, false, false, null, 0, it.message ?: "build failed")
            }
        val validation = runCatching { adapter.validate(config) }
            .getOrElse { CoreResult(false, it.message ?: "validation crashed") }
        if (!validation.success) {
            return Candidate(coreId, false, false, false, null, 0, validation.error)
        }

        val startedAt = SystemClock.elapsedRealtime()
        val start = runCatching { adapter.start(config, null) }
            .getOrElse { CoreResult(false, it.message ?: "start crashed") }
        if (!start.success) {
            runCatching { adapter.stop() }
            return Candidate(coreId, true, false, false, null, 0, start.error)
        }

        return try {
            val listener = waitForListener(port, LISTENER_WAIT_MS)
            val startup = SystemClock.elapsedRealtime() - startedAt
            if (!listener) {
                Candidate(coreId, true, false, false, null, startup, "local listener unavailable")
            } else {
                val result = TunnelHealthVerifier.measure(
                    settings = settings,
                    mode = ConnectionMode.PROXY,
                    attempts = 2,
                    budgetMs = BENCHMARK_BUDGET_MS,
                    perProbeTimeoutMs = BENCHMARK_PROBE_MS,
                    initialDelayMs = BENCHMARK_GRACE_MS,
                    isCancelled = { Thread.currentThread().isInterrupted }
                )
                Candidate(
                    core = coreId,
                    valid = true,
                    listenerReady = true,
                    proof = result.success,
                    latencyMs = result.latencyMs,
                    startupMs = startup,
                    error = result.errorCategory.orEmpty()
                )
            }
        } finally {
            runCatching { adapter.stop() }
        }
    }

    private fun candidateOrder(request: CoreStartRequest): List<CoreId> {
        val protocol = request.profile.protocol.lowercase()
        return when {
            protocol in setOf("wireguard", "hysteria2", "tuic") ->
                listOf(CoreId.SING_BOX, CoreId.MIHOMO, CoreId.XRAY)
            protocol == "vless" && request.profile.outboundJson.contains("xhttp", ignoreCase = true) ->
                listOf(CoreId.XRAY, CoreId.MIHOMO, CoreId.SING_BOX)
            else -> listOf(CoreId.XRAY, CoreId.SING_BOX, CoreId.MIHOMO)
        }
    }

    private fun preferenceIndex(request: CoreStartRequest, core: CoreId): Int =
        candidateOrder(request).indexOf(core).takeIf { it >= 0 } ?: 99

    private fun waitForListener(port: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted) return false
            val connected = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 160)
                }
                true
            }.getOrDefault(false)
            if (connected) return true
            Thread.sleep(60)
        }
        return false
    }

    private fun freePort(): Int? = runCatching {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }.getOrNull()

    private fun cacheKey(request: CoreStartRequest): String = buildString {
        append(request.profile.id)
        append(':')
        append(request.profile.outboundJson.hashCode())
        append(':')
        append(request.profile.raw.hashCode())
    }

    companion object {
        private const val CACHE_TTL_MS = 90_000L
        private const val LISTENER_WAIT_MS = 1_800L
        private const val BENCHMARK_BUDGET_MS = 4_800
        private const val BENCHMARK_PROBE_MS = 2_200
        private const val BENCHMARK_GRACE_MS = 120
        private const val DEGRADED_BASE_SCORE = 1_000_000L
        private const val VALID_ONLY_BASE_SCORE = 2_000_000L
    }
}
