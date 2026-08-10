package com.trivox.client.core

// TRIVOX_V20_SAFE_NATIVE_LIFECYCLE

import android.content.Context
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.network.TunnelHealthVerifier
import com.trivox.client.network.SmartConnectionOptimizer
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
        val plan = SmartConnectionOptimizer.verificationPlan(
            profile = request.profile,
            settings = request.settings,
            mode = request.mode
        )
        /*
         * Compatibility-first startup: Android VPN verification is deliberately
         * bounded. A healthy Xray transport may need the first real application
         * request before a public health endpoint becomes reachable.
         */
        val verificationAttempts = if (request.mode == ConnectionMode.VPN) {
            minOf(plan.attempts, 2)
        } else {
            plan.attempts
        }
        val verificationBudgetMs = if (request.mode == ConnectionMode.VPN) {
            minOf(plan.budgetMs, VPN_VERIFICATION_BUDGET_MS)
        } else {
            plan.budgetMs
        }
        val verificationProbeTimeoutMs = if (request.mode == ConnectionMode.VPN) {
            minOf(plan.perProbeTimeoutMs, VPN_VERIFICATION_PROBE_TIMEOUT_MS)
        } else {
            plan.perProbeTimeoutMs
        }
        val verificationInitialDelayMs = if (request.mode == ConnectionMode.VPN) {
            minOf(plan.initialDelayMs, VPN_VERIFICATION_GRACE_MS)
        } else {
            plan.initialDelayMs
        }
        Diagnostics.debug(
            "Smart connection verification: ${plan.strategy}; " +
                "attempts=$verificationAttempts; budget=$verificationBudgetMs"
        )
        var health = TunnelHealthVerifier.measure(
            settings = request.settings,
            mode = request.mode,
            attempts = verificationAttempts,
            budgetMs = verificationBudgetMs,
            perProbeTimeoutMs = verificationProbeTimeoutMs,
            initialDelayMs = verificationInitialDelayMs,
            isCancelled = isCancelled,
            hardFailure = {
                XrayProbeLogInspector.classifySince(logMark)
                    ?.takeIf(::isDefinitiveTransportFailure)
            }
        )

        if (
            request.mode != ConnectionMode.VPN &&
            !health.success &&
            !isCancelled() &&
            isRunning() &&
            isTransientVerificationFailure(health.errorCategory)
        ) {
            Diagnostics.warning(
                "Initial live-route verification was inconclusive (" +
                    "${health.errorCategory.orEmpty()}); retrying DNS-free proof"
            )
            val retry = TunnelHealthVerifier.measure(
                settings = request.settings,
                mode = request.mode,
                attempts = 2,
                budgetMs = TRANSIENT_RETRY_BUDGET_MS,
                perProbeTimeoutMs = TRANSIENT_RETRY_PROBE_TIMEOUT_MS,
                initialDelayMs = TRANSIENT_RETRY_GRACE_MS,
                isCancelled = isCancelled,
                hardFailure = {
                    XrayProbeLogInspector.classifySince(logMark)
                        ?.takeIf(::isDefinitiveTransportFailure)
                },
                dnsFallbackTargets = false
            )
            if (retry.success) {
                Diagnostics.info("Live-route verification recovered on DNS-free retry")
                SmartConnectionOptimizer.recordOutcome(
                    profile = request.profile,
                    success = true,
                    errorCategory = null
                )
                return started
            }
            if (retry.errorCategory != null && retry.errorCategory != "tunnel_timeout") {
                health = retry
            }
        }

        if (isCancelled() || health.errorCategory == "cancelled") {
            stopAfterRejectedStart()
            return cancelledStart()
        }
        if (health.success) {
            SmartConnectionOptimizer.recordOutcome(
                profile = request.profile,
                success = true,
                errorCategory = null
            )
            return started
        }

        val logCategory = XrayProbeLogInspector.classifySince(logMark)
        val definitiveCategory = sequenceOf(logCategory, health.errorCategory)
            .filterNotNull()
            .map { it.lowercase() }
            .firstOrNull(::isDefinitiveTransportFailure)

        /*
         * VPN compatibility rule:
         * timeout/reset/WS probe races/DNS probe failures are not enough evidence
         * to destroy a running Xray process. Those failures can be specific to
         * the public verification target rather than the selected proxy itself.
         */
        if (
            request.mode == ConnectionMode.VPN &&
            definitiveCategory == null &&
            isRunning()
        ) {
            Diagnostics.warning(
                "Live-route verification was inconclusive (" +
                    "${health.errorCategory.orEmpty()}); Xray is still running, " +
                    "so the VPN session is accepted provisionally."
            )
            return started
        }

        stopAfterRejectedStart()

        val effectiveCategory = definitiveCategory
            ?: health.errorCategory
            ?: logCategory
            ?: "core_not_running"
        val category = " ($effectiveCategory)"
        val route = if (request.mode == ConnectionMode.VPN) {
            "Android VPN route"
        } else {
            "local proxy route"
        }
        val protocol = request.profile.protocol.uppercase()
        val error = if (definitiveCategory != null) {
            "$protocol startup rejected by a definitive transport/config error " +
                "on the $route$category."
        } else {
            "$protocol core stopped before the $route became usable$category."
        }
        SmartConnectionOptimizer.recordOutcome(
            profile = request.profile,
            success = false,
            errorCategory = effectiveCategory
        )
        return CoreResult(false, error)
    }

    private fun isTransientVerificationFailure(category: String?): Boolean =
        category?.lowercase() in setOf(
            "gaiexception", "unknownhostexception", "sockettimeoutexception",
            "interruptedioexception", "connectexception", "tunnel_timeout",
            "invalid_trace_body", "connection_reset", "dns_timeout",
            "dns_failure", "dns_pipe_closed", "timeout"
        )

    private fun isDefinitiveTransportFailure(category: String): Boolean =
        category.lowercase() in setOf(
            "reality_mismatch",
            "authentication_failed",
            "invalid_credentials",
            "tls_certificate",
            "invalid_xray_config"
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
            errorLogPath = Diagnostics.xrayErrorLogPath(),
            bootstrapIps = request.bootstrapIps
        )

    companion object {
        private const val VPN_VERIFICATION_BUDGET_MS = 3_200
        private const val VPN_VERIFICATION_PROBE_TIMEOUT_MS = 1_500
        private const val VPN_VERIFICATION_GRACE_MS = 160
        private const val TRANSIENT_RETRY_BUDGET_MS = 4_200
        private const val TRANSIENT_RETRY_PROBE_TIMEOUT_MS = 1_900
        private const val TRANSIENT_RETRY_GRACE_MS = 260
    }
}
