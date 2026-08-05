package com.trivox.client.core

import android.content.Context
import com.trivox.client.config.NativeProfileDocument
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.CoreId
import com.trivox.client.data.SettingsRepository
import com.trivox.client.network.TunnelHealthVerifier
import com.trivox.client.network.XrayProbeLogInspector
import com.trivox.client.util.Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.Socket
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
    private val listeners = CopyOnWriteArrayList<WeakReference<(Snapshot) -> Unit>>()
    private val sessionCounter = AtomicLong(0)

    fun current(): Snapshot = snapshot.get()
    fun nextSessionId(): Long = sessionCounter.incrementAndGet()

    fun update(value: Snapshot) {
        snapshot.set(value)
        notifyListeners(value)
    }

    fun updateSession(sessionId: Long, transform: (Snapshot) -> Snapshot): Boolean {
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
        if (listeners.none { it.get() === listener }) listeners += WeakReference(listener)
        runCatching { listener(snapshot.get()) }
            .onFailure { Diagnostics.warning("Connection listener failed: " + it.message) }
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.forEach { reference ->
            val current = reference.get()
            if (current == null || current === listener) listeners.remove(reference)
        }
    }

    private fun notifyListeners(value: Snapshot) {
        listeners.forEach { reference ->
            val listener = reference.get()
            if (listener == null) listeners.remove(reference)
            else runCatching { listener(value) }
                .onFailure { Diagnostics.warning("Connection listener failed: " + it.message) }
        }
    }

    private fun pruneListeners() {
        listeners.forEach { reference -> if (reference.get() == null) listeners.remove(reference) }
    }
}

class CoreManager(context: Context) {
    private val appContext = context.applicationContext
    private val adapters: Map<CoreId, CoreAdapter> = mapOf(
        CoreId.XRAY to XrayCoreAdapter(appContext),
        CoreId.SING_BOX to SingBoxCoreAdapter(appContext),
        CoreId.MIHOMO to MihomoCoreAdapter(appContext)
    )
    private val smartCoreSelector = SmartCoreSelector(appContext, adapters)


    private data class StartPlan(
        val activeCore: CoreId,
        val primaryAdapter: CoreAdapter,
        val primaryConfig: String,
        val vpnBridgeConfig: String? = null,
        val needsXrayBridge: Boolean = false
    )

    val adapter: CoreAdapter
        get() = selectedAdapter()

    private fun selectedCoreId(): CoreId = SettingsRepository(appContext).load().let {
        if (it.smartCoreSelection) it.lastSmartCoreId else it.coreId
    }

    private fun selectedAdapter(): CoreAdapter = adapters[selectedCoreId()] ?: adapters.getValue(CoreId.XRAY)

    fun prepare(request: CoreStartRequest): Pair<String?, CoreResult> =
        runCatching {
            val plan = buildPlan(request)
            val validation = validatePlan(plan)
            if (!validation.success) null to validation
            else (plan.vpnBridgeConfig ?: plan.primaryConfig) to CoreResult(true)
        }.getOrElse {
            Diagnostics.recordThrowable("Core config preparation", it)
            null to CoreResult(false, "Config generation failed: " + (it.message ?: "unknown"))
        }

    fun start(
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)? = null,
        isCancelled: () -> Boolean = { false }
    ): CoreResult = startValidated(request, protect, isCancelled)

    fun startValidated(
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)? = null,
        isCancelled: () -> Boolean = { false }
    ): CoreResult {
        val plan = runCatching { buildPlan(request) }.getOrElse {
            Diagnostics.recordThrowable("Validated core start", it)
            return CoreResult(false, "Config generation failed: " + (it.message ?: "unknown"))
        }
        val validation = validatePlan(plan)
        if (!validation.success) return validation
        return startPlan(plan, request, protect, isCancelled)
    }

    private fun buildPlan(request: CoreStartRequest): StartPlan {
        val coreId = resolveCoreForRequest(request)
        val adapter = adapters[coreId] ?: adapters.getValue(CoreId.XRAY)

        if (request.mode == ConnectionMode.VPN && coreId != CoreId.XRAY) {
            val proxyRequest = request.copy(mode = ConnectionMode.PROXY, tunFd = null)
            val proxyConfig = CoreConfigTranslator.build(proxyRequest, coreId)
            val bridgeConfig = buildXrayVpnBridge(request, coreId)
            Diagnostics.info(
                "True multicore VPN plan: " + coreId.label +
                    " handles outbound; Xray only bridges Android TUN to 127.0.0.1:" +
                    request.settings.socksPort
            )
            return StartPlan(
                activeCore = coreId,
                primaryAdapter = adapter,
                primaryConfig = proxyConfig,
                vpnBridgeConfig = bridgeConfig,
                needsXrayBridge = true
            )
        }

        return StartPlan(
            activeCore = coreId,
            primaryAdapter = adapter,
            primaryConfig = CoreConfigTranslator.build(request, coreId)
        )
    }

    private fun validatePlan(plan: StartPlan): CoreResult {
        val primaryValidation = runCatching { plan.primaryAdapter.validate(plan.primaryConfig) }.getOrElse {
            CoreResult(false, plan.activeCore.label + " validation crashed: " + (it.message ?: "unknown"))
        }
        if (!primaryValidation.success) {
            return CoreResult(false, plan.activeCore.label + " rejected config: " + primaryValidation.error)
        }

        val bridgeConfig = plan.vpnBridgeConfig ?: return CoreResult(true)
        val bridgeValidation = runCatching { adapters.getValue(CoreId.XRAY).validate(bridgeConfig) }.getOrElse {
            CoreResult(false, "Xray VPN bridge validation crashed: " + (it.message ?: "unknown"))
        }
        if (!bridgeValidation.success) {
            return CoreResult(false, "Xray VPN bridge rejected config: " + bridgeValidation.error)
        }
        return CoreResult(true)
    }

    private fun buildXrayVpnBridge(request: CoreStartRequest, outboundCore: CoreId): String {
        val mixedPort = request.settings.socksPort
        val bridgeOutbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", "127.0.0.1")
                            .put("port", mixedPort)
                    )
                )
            )
        val bridgeProfile = ConfigProfile(
            name = "Trivox VPN bridge to " + outboundCore.label,
            protocol = "socks",
            server = "127.0.0.1",
            port = mixedPort,
            raw = "trivox-vpn-bridge://" + outboundCore.name.lowercase(),
            outboundJson = bridgeOutbound.toString(),
            probeServer = "127.0.0.1",
            probePort = mixedPort
        )
        val bridgeSettings = request.settings.copy().also {
            it.localProxyInVpn = false
        }
        return XrayConfigBuilder.build(
            profile = bridgeProfile,
            settings = bridgeSettings,
            mode = ConnectionMode.VPN,
            tunFd = request.tunFd,
            errorLogPath = Diagnostics.xrayErrorLogPath()
        )
    }

    private fun startPlan(
        plan: StartPlan,
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)?,
        isCancelled: () -> Boolean
    ): CoreResult {
        if (isCancelled()) return cancelledStart()

        val xrayLog = File(Diagnostics.xrayErrorLogPath())
        val logMark = XrayProbeLogInspector.mark(xrayLog)
        stopAllAdapters()

        val started = if (plan.needsXrayBridge) {
            Diagnostics.info("Starting real outbound core for VPN: " + plan.activeCore.label)
            val primary = plan.primaryAdapter.start(plan.primaryConfig, null)
            if (!primary.success) return primary

            if (isCancelled()) {
                stopAfterRejectedStart()
                return cancelledStart()
            }

            waitForLocalMixedProxy(
                port = request.settings.socksPort,
                isCancelled = isCancelled
            )?.let { error ->
                stopAfterRejectedStart()
                return CoreResult(
                    false,
                    plan.activeCore.label + " started, but its localhost mixed proxy was not ready before Android VPN bridge: " + error
                )
            }

            Diagnostics.info("Starting Xray Android TUN bridge for " + plan.activeCore.label)
            val bridge = adapters.getValue(CoreId.XRAY).start(plan.vpnBridgeConfig ?: return CoreResult(false, "Missing Xray VPN bridge config"), protect)
            if (!bridge.success) {
                stopAfterRejectedStart()
                return bridge
            }
            CoreResult(true)
        } else {
            Diagnostics.info("Starting selected runtime core: " + plan.activeCore.label)
            val primary = plan.primaryAdapter.start(plan.primaryConfig, protect)
            if (!primary.success) return primary
            primary
        }

        if (isCancelled()) {
            stopAfterRejectedStart()
            return cancelledStart()
        }

        val health = verifyLiveRoute(plan, request, logMark, isCancelled)
        if (isCancelled() || health.errorCategory == "cancelled") {
            stopAfterRejectedStart()
            return cancelledStart()
        }
        if (health.success) return started

        stopAfterRejectedStart()
        val category = health.errorCategory?.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
        val route = when {
            plan.needsXrayBridge -> "native local proxy before Android VPN bridge"
            request.mode == ConnectionMode.VPN -> "Android VPN route"
            else -> "local proxy route"
        }
        val protocol = request.profile.protocol.uppercase()
        val engine = plan.activeCore.label
        return CoreResult(
            false,
            "$protocol config started with $engine, but no verified HTTPS traffic crossed the $route$category. " +
                "TCP/Real Delay results are ranking tests and cannot guarantee that the live route is usable."
        )
    }

    private fun waitForLocalMixedProxy(
        port: Int,
        isCancelled: () -> Boolean
    ): String? {
        val deadline = System.currentTimeMillis() + NATIVE_LOCAL_LISTENER_BUDGET_MS
        var lastError = "not attempted"
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return "cancelled"
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress("127.0.0.1", port),
                        NATIVE_LOCAL_CONNECT_TIMEOUT_MS
                    )
                }
                Diagnostics.info("Native local mixed proxy is ready on 127.0.0.1:$port")
                return null
            } catch (error: Throwable) {
                lastError = error.javaClass.simpleName + ": " + (error.message ?: "not ready")
                try {
                    Thread.sleep(NATIVE_LOCAL_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return "interrupted"
                }
            }
        }
        return lastError
    }

    private fun verifyLiveRoute(
        plan: StartPlan,
        request: CoreStartRequest,
        logMark: XrayProbeLogInspector.Mark,
        isCancelled: () -> Boolean
    ): com.trivox.client.data.PingResult {
        if (plan.needsXrayBridge) {
            return verifyNativeBridgeRoute(plan, request, logMark, isCancelled)
        }
        val wireGuard = request.profile.protocol.equals("wireguard", ignoreCase = true)
        val adaptive = request.settings.adaptiveHandshake
        val budgetMs = when {
            wireGuard -> request.settings.wireGuardHandshakeTimeoutMs
            adaptive -> GENERAL_ADAPTIVE_VERIFY_BUDGET_MS
            else -> GENERAL_CONSERVATIVE_VERIFY_BUDGET_MS
        }
        return TunnelHealthVerifier.measure(
            settings = request.settings,
            mode = request.mode,
            attempts = if (adaptive) 3 else 2,
            budgetMs = budgetMs,
            perProbeTimeoutMs = when {
                wireGuard -> (budgetMs / 3).coerceIn(2_000, 7_000)
                adaptive -> GENERAL_ADAPTIVE_PROBE_TIMEOUT_MS
                else -> GENERAL_CONSERVATIVE_PROBE_TIMEOUT_MS
            },
            initialDelayMs = when {
                wireGuard && adaptive -> WIREGUARD_ADAPTIVE_GRACE_MS
                wireGuard -> WIREGUARD_CONSERVATIVE_GRACE_MS
                request.mode == ConnectionMode.VPN && adaptive -> VPN_ADAPTIVE_GRACE_MS
                request.mode == ConnectionMode.VPN -> VPN_CONSERVATIVE_GRACE_MS
                adaptive -> PROXY_ADAPTIVE_GRACE_MS
                else -> PROXY_CONSERVATIVE_GRACE_MS
            },
            isCancelled = isCancelled,
            hardFailure = {
                XrayProbeLogInspector.classifySince(logMark)?.takeIf(::isImmediateTransportFailure)
            }
        )
    }

    private fun verifyNativeBridgeRoute(
        plan: StartPlan,
        request: CoreStartRequest,
        logMark: XrayProbeLogInspector.Mark,
        isCancelled: () -> Boolean
    ): com.trivox.client.data.PingResult {
        Diagnostics.info(
            "Native VPN bridge proof: checking " + plan.activeCore.label +
                " local mixed proxy before accepting Android TUN bridge"
        )

        val proxySettings = request.settings.copy().also {
            it.mode = ConnectionMode.PROXY
            it.localProxyInVpn = false
        }

        val result = TunnelHealthVerifier.measure(
            settings = proxySettings,
            mode = ConnectionMode.PROXY,
            attempts = request.settings.testAttempts.coerceIn(2, 3),
            budgetMs = NATIVE_BRIDGE_VERIFY_BUDGET_MS,
            perProbeTimeoutMs = NATIVE_BRIDGE_PROBE_TIMEOUT_MS,
            initialDelayMs = NATIVE_BRIDGE_GRACE_MS,
            isCancelled = isCancelled,
            hardFailure = {
                XrayProbeLogInspector.classifySince(logMark)
                    ?.takeIf { it == "invalid_xray_config" }
            }
        )

        if (result.success) {
            Diagnostics.info(
                "Native VPN bridge proof passed: " + plan.activeCore.label +
                    " accepted verified HTTPS through 127.0.0.1:" +
                    request.settings.socksPort +
                    "; Xray owns Android TUN only as packet bridge"
            )
            return result.copy(
                resolvedIp = "vpn-bridge:" + plan.activeCore.label,
                errorCategory = null
            )
        }

        Diagnostics.warning(
            "Native VPN bridge proof failed for " + plan.activeCore.label +
                ": " + (result.errorCategory ?: "unknown")
        )
        return result.copy(
            resolvedIp = "vpn-bridge:" + plan.activeCore.label
        )
    }

    private fun isImmediateBridgeFailure(category: String): Boolean =
        category == "invalid_xray_config"

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
        runCatching { stopAllAdapters() }
            .onFailure { Diagnostics.warning("Core cleanup after rejected startup failed: " + it.message) }
    }

    private fun cancelledStart(): CoreResult = CoreResult(false, "Connection start cancelled")

    fun isRunning(): Boolean = adapters.values.any { adapter ->
        adapter.state().data?.optJSONObject("data")?.optBoolean("running") == true
    }

    fun stop(): CoreResult = stopAllAdapters()

    private fun stopAllAdapters(): CoreResult {
        var ok = true
        var error = ""
        adapters.values.forEach { adapter ->
            val result = runCatching { adapter.stop() }.getOrElse { CoreResult(false, it.message ?: "unknown") }
            if (!result.success) {
                ok = false
                if (error.isBlank()) error = result.error
            }
        }
        return CoreResult(ok, error)
    }

    fun validateWith(coreId: CoreId, configJson: String): CoreResult =
        (adapters[coreId] ?: adapters.getValue(CoreId.XRAY)).validate(configJson)

    fun requiresSelfBypassForVpn(
        profile: ConfigProfile,
        settings: com.trivox.client.data.AppSettings
    ): Boolean {
        val selected = NativeProfileDocument.affinity(profile) ?: if (settings.smartCoreSelection) {
            smartSelect(
                CoreStartRequest(
                    profile = profile,
                    settings = settings,
                    mode = ConnectionMode.PROXY,
                    tunFd = null
                )
            )
        } else {
            settings.coreId
        }
        val bypass = selected != CoreId.XRAY
        Diagnostics.info(
            "VPN self-bypass decision: selectedRuntime=" + selected.label +
                ", bypassTrivoxApp=" + bypass +
                ", smart=" + settings.smartCoreSelection
        )
        return bypass
    }

    fun switchCore(coreId: CoreId) {
        val settings = SettingsRepository(appContext).load()
        settings.coreId = coreId
        settings.smartCoreSelection = false
        settings.lastSmartCoreId = coreId
        SettingsRepository(appContext).save(settings)
        Diagnostics.info("Manual core selected: " + coreId.label)
    }

    private fun resolveCoreForRequest(request: CoreStartRequest): CoreId {
        NativeProfileDocument.affinity(request.profile)?.let { return it }
        val settings = SettingsRepository(appContext).load()
        return if (settings.smartCoreSelection) smartSelect(request) else settings.coreId
    }

    fun smartSelect(request: CoreStartRequest): CoreId {
        NativeProfileDocument.affinity(request.profile)?.let { required ->
            Diagnostics.info("Smart core native affinity: " + required.label)
            return required
        }
        return smartCoreSelector.select(request)
    }

    private fun buildPlanForCandidate(request: CoreStartRequest, coreId: CoreId): StartPlan {
        val adapter = adapters[coreId] ?: adapters.getValue(CoreId.XRAY)
        if (request.mode == ConnectionMode.VPN && coreId != CoreId.XRAY) {
            return StartPlan(
                activeCore = coreId,
                primaryAdapter = adapter,
                primaryConfig = CoreConfigTranslator.build(request.copy(mode = ConnectionMode.PROXY, tunFd = null), coreId),
                vpnBridgeConfig = buildXrayVpnBridge(request, coreId),
                needsXrayBridge = true
            )
        }
        return StartPlan(coreId, adapter, CoreConfigTranslator.build(request, coreId))
    }

    companion object {
        private const val GENERAL_ADAPTIVE_VERIFY_BUDGET_MS = 7_000
        private const val GENERAL_CONSERVATIVE_VERIFY_BUDGET_MS = 11_000
        private const val GENERAL_ADAPTIVE_PROBE_TIMEOUT_MS = 2_800
        private const val GENERAL_CONSERVATIVE_PROBE_TIMEOUT_MS = 4_500
        private const val NATIVE_BRIDGE_VERIFY_BUDGET_MS = 22_000
        private const val NATIVE_BRIDGE_PROBE_TIMEOUT_MS = 4_800
        private const val NATIVE_BRIDGE_GRACE_MS = 1_500
        private const val NATIVE_LOCAL_LISTENER_BUDGET_MS = 6_500
        private const val NATIVE_LOCAL_CONNECT_TIMEOUT_MS = 420
        private const val NATIVE_LOCAL_RETRY_DELAY_MS = 140L
        private const val PROXY_ADAPTIVE_GRACE_MS = 40
        private const val PROXY_CONSERVATIVE_GRACE_MS = 120
        private const val VPN_ADAPTIVE_GRACE_MS = 90
        private const val VPN_CONSERVATIVE_GRACE_MS = 260
        private const val WIREGUARD_ADAPTIVE_GRACE_MS = 350
        private const val WIREGUARD_CONSERVATIVE_GRACE_MS = 850
    }
}
