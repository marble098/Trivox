package com.trivox.client.core

// TRIVOX_V20_SAFE_NATIVE_LIFECYCLE

import android.content.Context
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.network.TunnelHealthVerifier
import com.trivox.client.network.XrayProbeLogInspector
import com.trivox.client.util.Diagnostics
import java.io.File
import java.lang.ref.WeakReference
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
    private val listeners =
        CopyOnWriteArrayList<WeakReference<(Snapshot) -> Unit>>()
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
        pruneListeners()
        if (listeners.none { it.get() === listener }) {
            listeners += WeakReference(listener)
        }
        runCatching { listener(snapshot.get()) }
            .onFailure {
                Diagnostics.warning("Connection listener failed: " + it.message)
            }
    }

    private fun notifyListeners(value: Snapshot) {
        listeners.forEach { reference ->
            val listener = reference.get()
            if (listener == null) {
                listeners.remove(reference)
            } else {
                runCatching { listener(value) }
                    .onFailure {
                        Diagnostics.warning(
                            "Connection listener failed: " + it.message
                        )
                    }
            }
        }
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.forEach { reference ->
            val current = reference.get()
            if (current == null || current === listener) {
                listeners.remove(reference)
            }
        }
    }

    private fun pruneListeners() {
        listeners.forEach { reference ->
            if (reference.get() == null) listeners.remove(reference)
        }
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
            Diagnostics.recordThrowable("Core config preparation", it)
            null to CoreResult(
                false,
                "Config generation failed: " + it.message
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
                "Config generation failed: " + it.message
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

        val xrayLog = File(Diagnostics.xrayErrorLogPath())
        val logMark = XrayProbeLogInspector.mark(xrayLog)
        val started = adapter.start(json, protect)
        if (!started.success) return started

        if (isCancelled()) {
            stopAfterRejectedStart()
            return cancelledStart()
        }

        /*
         * Native process startup is not a connection result. TCP ping only proves
         * endpoint reachability, and libXray Real Delay validates an isolated
         * proxy-mode path. Before the service exposes CONNECTED, verify the live
         * route selected by the user: Android VPN for VPN mode, localhost mixed
         * listener for proxy mode.
         */
        val wireGuard = request.profile.protocol.equals(
            "wireguard",
            ignoreCase = true
        )
        val adaptive = request.settings.adaptiveHandshake
        val budgetMs = if (wireGuard) {
            request.settings.wireGuardHandshakeTimeoutMs
        } else if (adaptive) {
            GENERAL_ADAPTIVE_VERIFY_BUDGET_MS
        } else {
            GENERAL_CONSERVATIVE_VERIFY_BUDGET_MS
        }
        val health = TunnelHealthVerifier.measure(
            settings = request.settings,
            mode = request.mode,
            attempts = if (adaptive) 3 else 2,
            budgetMs = budgetMs,
            perProbeTimeoutMs = if (wireGuard) {
                (budgetMs / 3).coerceIn(2_000, 7_000)
            } else if (adaptive) {
                GENERAL_ADAPTIVE_PROBE_TIMEOUT_MS
            } else {
                GENERAL_CONSERVATIVE_PROBE_TIMEOUT_MS
            },
            initialDelayMs = when {
                wireGuard && adaptive -> WIREGUARD_ADAPTIVE_GRACE_MS
                wireGuard -> WIREGUARD_CONSERVATIVE_GRACE_MS
                request.mode == ConnectionMode.VPN && adaptive ->
                    VPN_ADAPTIVE_GRACE_MS
                request.mode == ConnectionMode.VPN ->
                    VPN_CONSERVATIVE_GRACE_MS
                adaptive -> PROXY_ADAPTIVE_GRACE_MS
                else -> PROXY_CONSERVATIVE_GRACE_MS
            },
            isCancelled = isCancelled,
            hardFailure = {
                XrayProbeLogInspector.classifySince(logMark)
                    ?.takeIf(::isImmediateTransportFailure)
            }
        )

        if (isCancelled() || health.errorCategory == "cancelled") {
            stopAfterRejectedStart()
            return cancelledStart()
        }
        if (health.success) return started

        stopAfterRejectedStart()

        val category = health.errorCategory
            ?.takeIf(String::isNotBlank)
            ?.let { " ($it)" }
            .orEmpty()
        val route = if (request.mode == ConnectionMode.VPN) {
            "Android VPN route"
        } else {
            "local proxy route"
        }
        val protocol = request.profile.protocol.uppercase()
        val error =
            "$protocol core started, but no verified HTTPS traffic crossed the " +
                "$route$category. TCP/Real Delay results are ranking tests and " +
                "cannot guarantee that the live route is usable."
        return CoreResult(false, error)
    }

    private fun isImmediateTransportFailure(category: String): Boolean =
        category in setOf(
            "reality_mismatch",
            "websocket_handshake",
            "authentication_failed",
            "tls_certificate",
            "invalid_xray_config",
            "connection_refused"
        )

    private fun stopAfterRejectedStart() {
        if (!isRunning()) {
            Diagnostics.debug(
                "Skipped rejected-start cleanup: no owned Xray is running"
            )
            return
        }
        runCatching { adapter.stop() }
            .onFailure {
                Diagnostics.warning(
                    "Core cleanup after rejected startup failed: " + it.message
                )
            }
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
        private const val GENERAL_ADAPTIVE_VERIFY_BUDGET_MS = 7_000
        private const val GENERAL_CONSERVATIVE_VERIFY_BUDGET_MS = 11_000
        private const val GENERAL_ADAPTIVE_PROBE_TIMEOUT_MS = 2_800
        private const val GENERAL_CONSERVATIVE_PROBE_TIMEOUT_MS = 4_500
        private const val PROXY_ADAPTIVE_GRACE_MS = 40
        private const val PROXY_CONSERVATIVE_GRACE_MS = 120
        private const val VPN_ADAPTIVE_GRACE_MS = 90
        private const val VPN_CONSERVATIVE_GRACE_MS = 260
        private const val WIREGUARD_ADAPTIVE_GRACE_MS = 350
        private const val WIREGUARD_CONSERVATIVE_GRACE_MS = 850
    }
}
