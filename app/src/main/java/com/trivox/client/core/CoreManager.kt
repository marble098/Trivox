package com.trivox.client.core

import android.content.Context
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.CoreId
import com.trivox.client.network.TunnelHealthVerifier
import com.trivox.client.util.Diagnostics
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object ConnectionRuntime {
    data class Snapshot(
        val state: ConnectionState = ConnectionState.DISCONNECTED,
        val profileId: String? = null,
        val profileName: String = "",
        val startedElapsed: Long = 0,
        val error: String = "",
        val mode: ConnectionMode? = null,
        val sessionId: Long = 0
    )

    private val snapshot = AtomicReference(Snapshot())
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val sessionCounter = AtomicLong(0)

    fun current(): Snapshot = snapshot.get()
    fun nextSessionId(): Long = sessionCounter.incrementAndGet()
    fun update(value: Snapshot) {
        snapshot.set(value)
        listeners.forEach { runCatching { it(value) } }
    }
    fun updateSession(sessionId: Long, transform: (Snapshot) -> Snapshot): Boolean {
        while (true) {
            val current = snapshot.get()
            if (current.sessionId != sessionId || sessionId == 0L) return false
            val next = transform(current)
            if (snapshot.compareAndSet(current, next)) {
                listeners.forEach { runCatching { it(next) } }
                return true
            }
        }
    }
    fun addListener(listener: (Snapshot) -> Unit) {
        if (!listeners.contains(listener)) listeners += listener
        runCatching { listener(snapshot.get()) }
    }
    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }
}

class CoreManager(context: Context) {
    private val appContext = context.applicationContext
    val adapter: CoreAdapter = XrayCoreAdapter(appContext)

    fun prepare(request: CoreStartRequest): Pair<String?, CoreResult> =
        runCatching {
            val config = buildXrayConfig(request)
            val validation = adapter.validate(config)
            if (validation.success) config to CoreResult(true) else null to validation
        }.getOrElse {
            Diagnostics.recordThrowable("Xray config preparation", it)
            null to CoreResult(false, "Xray config generation failed: " + (it.message ?: "unknown"))
        }

    fun start(
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)? = null,
        isCancelled: () -> Boolean = { false }
    ): CoreResult {
        if (isCancelled()) return CoreResult(false, "Connection start cancelled")
        val config = runCatching { buildXrayConfig(request) }.getOrElse {
            Diagnostics.recordThrowable("Xray start config", it)
            return CoreResult(false, "Xray config generation failed: " + (it.message ?: "unknown"))
        }
        val validation = adapter.validate(config)
        if (!validation.success) return CoreResult(false, "Xray rejected config: " + validation.error)
        stop()
        if (isCancelled()) return CoreResult(false, "Connection start cancelled")
        val started = adapter.start(config, protect)
        if (!started.success) return started
        if (isCancelled()) {
            stop()
            return CoreResult(false, "Connection start cancelled")
        }
        val proof = TunnelHealthVerifier.measure(
            settings = request.settings,
            mode = request.mode,
            attempts = request.settings.testAttempts.coerceIn(2, 3),
            budgetMs = if (request.mode == ConnectionMode.VPN) 9000 else 6500,
            perProbeTimeoutMs = 2800,
            initialDelayMs = if (request.mode == ConnectionMode.VPN) 120 else 50,
            isCancelled = isCancelled
        )
        if (!proof.success && isCancelled()) {
            stop()
            return CoreResult(false, "Connection start cancelled")
        }
        return started
    }

    fun isRunning(): Boolean =
        adapter.state().data?.optJSONObject("data")?.optBoolean("running") == true

    fun stop(): CoreResult =
        runCatching { adapter.stop() }.getOrElse { CoreResult(false, it.message ?: "unknown") }

    fun validateWith(coreId: CoreId, configJson: String): CoreResult = adapter.validate(configJson)

    fun requiresSelfBypassForVpn(profile: ConfigProfile, settings: com.trivox.client.data.AppSettings): Boolean = false

    fun switchCore(coreId: CoreId) {}

    fun smartSelect(request: CoreStartRequest): CoreId = CoreId.XRAY

    private fun buildXrayConfig(request: CoreStartRequest): String =
        XrayConfigBuilder.build(
            profile = request.profile,
            settings = request.settings,
            mode = request.mode,
            tunFd = request.tunFd,
            errorLogPath = Diagnostics.xrayErrorLogPath()
        )
}
