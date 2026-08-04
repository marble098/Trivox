package com.trivox.client.core

import android.content.Context
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
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
        notifyListeners(value)
    }

    fun updateSession(
        sessionId: Long,
        transform: (Snapshot) -> Snapshot
    ): Boolean {
        while (true) {
            val current = snapshot.get()
            if (current.sessionId != sessionId || sessionId == 0L) return false
            val next = transform(current)
            if (snapshot.compareAndSet(current, next)) {
                notifyListeners(next)
                return true
            }
        }
    }

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners += listener
        runCatching { listener(snapshot.get()) }
            .onFailure {
                Diagnostics.warning("Connection listener failed: " + it.message)
            }
    }

    private fun notifyListeners(value: Snapshot) {
        listeners.forEach { listener ->
            runCatching { listener(value) }
                .onFailure {
                    Diagnostics.warning("Connection listener failed: " + it.message)
                }
        }
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }
}

class CoreManager(context: Context) {
    private val appContext = context.applicationContext
    val adapter: CoreAdapter = XrayCoreAdapter(appContext)

    fun prepare(request: CoreStartRequest): Pair<String?, CoreResult> =
        runCatching {
            val json = buildJson(request)
            val validation = adapter.validate(json)
            if (!validation.success) null to validation
            else json to CoreResult(true)
        }.getOrElse {
            Diagnostics.recordThrowable("Core configuration preparation", it)
            null to CoreResult(
                false,
                "Configuration generation failed: " + it.message
            )
        }

    fun start(
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)? = null,
        isCancelled: () -> Boolean = { false }
    ): CoreResult {
        val (json, prepared) = prepare(request)
        if (!prepared.success || json == null) return prepared
        return startPrepared(json, request, protect, isCancelled)
    }

    fun startValidated(
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)? = null,
        isCancelled: () -> Boolean = { false }
    ): CoreResult {
        val json = runCatching { buildJson(request) }.getOrElse {
            Diagnostics.recordThrowable("Validated core start", it)
            return CoreResult(
                false,
                "Configuration generation failed: " + it.message
            )
        }
        return startPrepared(json, request, protect, isCancelled)
    }

    private fun startPrepared(
        json: String,
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)?,
        isCancelled: () -> Boolean
    ): CoreResult {
        if (isCancelled()) return cancelledStart()
        val started = adapter.start(json, protect)
        if (!started.success) {
            Diagnostics.error(started.error)
            return started
        }

        if (isCancelled()) {
            runCatching { adapter.stop() }
            return cancelledStart()
        }

        if (!request.profile.protocol.equals("wireguard", ignoreCase = true)) {
            return started
        }

        val health = TunnelHealthVerifier.measure(
            settings = request.settings,
            attempts = 1,
            budgetMs = WIREGUARD_START_VERIFY_BUDGET_MS,
            perProbeTimeoutMs = WIREGUARD_PROBE_TIMEOUT_MS,
            isCancelled = isCancelled
        )
        if (isCancelled() || health.errorCategory == "cancelled") {
            runCatching { adapter.stop() }
            return cancelledStart()
        }
        if (health.success) return started

        runCatching { adapter.stop() }
        val category = health.errorCategory
            ?.takeIf(String::isNotBlank)
            ?.let { " ($it)" }
            .orEmpty()
        val error =
            "WireGuard started locally, but no verified traffic crossed the tunnel$category. " +
                "The endpoint may be filtered, blocked, or incompatible with this network."
        Diagnostics.error(error)
        return CoreResult(false, error)
    }

    private fun cancelledStart(): CoreResult =
        CoreResult(false, "Connection start cancelled")

    fun isRunning(): Boolean = adapter.state()
        .data
        ?.optJSONObject("data")
        ?.optBoolean("running") == true

    fun stop(): CoreResult = adapter.stop()

    private fun buildJson(request: CoreStartRequest): String =
        XrayConfigBuilder.build(
            profile = request.profile,
            settings = request.settings,
            mode = request.mode,
            tunFd = request.tunFd,
            errorLogPath = Diagnostics.xrayErrorLogPath()
        )

    companion object {
        private const val WIREGUARD_START_VERIFY_BUDGET_MS = 7_000
        private const val WIREGUARD_PROBE_TIMEOUT_MS = 900
    }
}
