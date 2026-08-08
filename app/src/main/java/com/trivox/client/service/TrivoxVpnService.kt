package com.trivox.client.service

import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.core.CoreManager
import com.trivox.client.core.CoreResult
import com.trivox.client.core.CoreStartRequest
import com.trivox.client.data.AppRoutingMode
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.DnsMode
import com.trivox.client.data.SettingsRepository
import com.trivox.client.config.OpenSshProfileCodec
import com.trivox.client.ssh.OpenSshTunnelBridge
import com.trivox.client.util.Diagnostics
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class TrivoxVpnService : VpnService() {
    private val executor =
        Executors.newSingleThreadExecutor()
    private val cleanupExecutor =
        Executors.newSingleThreadExecutor()
    private val handler =
        Handler(Looper.getMainLooper())
    private val sessionAccepted =
        AtomicBoolean(false)
    private val stopRequested =
        AtomicBoolean(false)
    private val cleanupStarted =
        AtomicBoolean(false)
    private val ownsCore =
        AtomicBoolean(false)
    private val reconnectQueued =
        AtomicBoolean(false)
    private var sshHandle: OpenSshTunnelBridge.Handle? = null
    private var sshSourceProfile: com.trivox.client.data.ConfigProfile? = null

    private lateinit var core: CoreManager
    private var tun:
        ParcelFileDescriptor? = null
    private var profileId: String? = null
    private var profileName = ""
    private var startedElapsed = 0L
    private var restoredStartedElapsed = 0L
    private var sessionId = 0L
    private var mixedListenerPort: Int? = null
    private var networkCallback:
        ConnectivityManager
            .NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        core = CoreManager(this)
        NotificationSupport
            .createChannel(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val restored =
            ConnectionSessionStore
                .load(this)
                ?.takeIf {
                    it.mode ==
                        ConnectionMode.VPN
                }
        val action =
            intent?.action
                ?: if (
                    restored != null
                ) {
                    ACTION_START
                } else {
                    null
                }

        if (
            action ==
            ACTION_STOP
        ) {
            ConnectionSessionStore
                .clear(this)
            requestStop()
            return START_NOT_STICKY
        }

        if (
            action !=
            ACTION_START
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (
            !sessionAccepted
                .compareAndSet(
                    false,
                    true
                )
        ) {
            return START_REDELIVER_INTENT
        }

        val current =
            ConnectionRuntime.current()

        if (
            current.state !in setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            )
        ) {
            val staleRestoredState =
                restored != null &&
                    current.mode ==
                    ConnectionMode.VPN &&
                    current.profileId ==
                    restored.profileId

            if (staleRestoredState) {
                ConnectionRuntime.update(
                    ConnectionRuntime.Snapshot()
                )
            } else {
                sessionAccepted.set(false)
                stopSelf(startId)
                return START_REDELIVER_INTENT
            }
        }

        stopRequested.set(false)
        cleanupStarted.set(false)
        ownsCore.set(false)
        startedElapsed = 0L
        sessionId =
            ConnectionRuntime
                .nextSessionId()

        val explicitProfileId =
            intent
                ?.getStringExtra(
                    EXTRA_PROFILE_ID
                )
        profileId =
            explicitProfileId
                ?: restored?.profileId
                ?: ConfigRepository(this)
                    .selectedId()

        val requestedProfileId =
            profileId

        if (
            requestedProfileId
                .isNullOrBlank()
        ) {
            sessionAccepted.set(false)
            ConnectionSessionStore
                .clear(this)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val matchingRestored =
            restored
                ?.takeIf {
                    explicitProfileId == null ||
                        it.profileId ==
                        requestedProfileId
                }

        restoredStartedElapsed =
            matchingRestored
                ?.startedElapsed
                ?.takeIf {
                    it > 0L &&
                        it <=
                        SystemClock
                            .elapsedRealtime()
                }
                ?: 0L
        profileName =
            matchingRestored
                ?.profileName
                .orEmpty()

        ConnectionSessionStore
            .markDesired(
                context = this,
                mode =
                    ConnectionMode.VPN,
                profileId =
                    requestedProfileId,
                profileName =
                    profileName,
                startedElapsed =
                    restoredStartedElapsed
            )

        startForeground(
            NotificationSupport.ID,
            NotificationSupport.build(
                this,
                profileName.ifBlank {
                    "Trivox VPN"
                },
                ConnectionMode.VPN,
                restoredStartedElapsed,
                Intent(
                    this,
                    TrivoxVpnService::
                        class.java
                ).setAction(ACTION_STOP)
            )
        )

        executeSafely {
            startConnection()
        }

        return START_REDELIVER_INTENT
    }

    private fun startConnection() {
        if (stopRequested.get()) {
            finishSession(null)
            return
        }

        val repository =
            ConfigRepository(this)
        val profile =
            repository.find(profileId)

        if (
            profile == null ||
            !profile.enabled
        ) {
            fail(
                "Selected config is " +
                    "unavailable or disabled"
            )
            return
        }

        profileName = profile.name

        ConnectionSessionStore
            .markDesired(
                context = this,
                mode =
                    ConnectionMode.VPN,
                profileId =
                    profile.id,
                profileName =
                    profileName,
                startedElapsed =
                    restoredStartedElapsed
            )

        if (!core.adapter.isAvailable()) {
            fail(
                "Xray Android core is " +
                    "missing or corrupted"
            )
            return
        }

        val settings =
            SettingsRepository(this)
                .load()

        val needsMixedListener =
            settings.localProxyInVpn ||
                profile.protocol.equals("wireguard", ignoreCase = true)
        mixedListenerPort =
            settings.socksPort.takeIf { needsMixedListener }
        if (
            needsMixedListener &&
            !LocalProxyPortGuard.prepare(
                core = core,
                port = settings.socksPort
            )
        ) {
            fail(
                "The local mixed proxy port ${settings.socksPort} is still in use. " +
                    "Choose another local proxy port or disable the VPN local proxy."
            )
            return
        }

        ConnectionRuntime.update(
            ConnectionRuntime.Snapshot(
                state =
                    ConnectionState.PREPARING,
                profileId = profile.id,
                profileName = profile.name,
                mode =
                    ConnectionMode.VPN,
                sessionId = sessionId
            )
        )

        val builder = Builder()
            .setSession(
                "Trivox • ${profile.name}"
            )
            .setMtu(settings.mtu)
            .addAddress(
                "172.19.0.1",
                30
            )
            .addRoute(
                "0.0.0.0",
                0
            )

        if (settings.ipv6) {
            builder
                .addAddress(
                    "fd00:7472:6976:6f78::1",
                    126
                )
                .addRoute("::", 0)
        }

        val dnsServers =
            when (settings.dnsMode) {
                DnsMode.CUSTOM ->
                    settings.customDns
                        .filter {
                            it.matches(
                                Regex(
                                    "[0-9a-fA-F:.]+"
                                )
                            )
                        }

                DnsMode.SYSTEM ->
                    emptyList()

                else ->
                    listOf(
                        "1.1.1.1",
                        "8.8.8.8"
                    )
            }

        dnsServers.forEach {
            runCatching {
                builder.addDnsServer(it)
            }
        }

        applyAppRouting(
            builder,
            settings.appRoutingMode,
            settings.routedPackages
        )

        if (OpenSshProfileCodec.isOpenSsh(profile)) {
            if (settings.appRoutingMode == AppRoutingMode.ALLOW_SELECTED) {
                if (packageName in settings.routedPackages) {
                    fail("OpenSSH cannot tunnel the Trivox app itself; remove Trivox from selected VPN apps")
                    return
                }
            } else {
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure {
                        fail("Unable to exclude OpenSSH transport from its own VPN: " + it.message)
                        return
                    }
            }
        }

        if (
            Build.VERSION.SDK_INT >= 29
        ) {
            builder.setBlocking(
                settings.blocking
            )
        }

        if (stopRequested.get()) {
            finishSession(null)
            return
        }

        tun = builder.establish()

        if (tun == null) {
            fail(
                "Android did not establish " +
                    "the VPN interface"
            )
            return
        }

        val effectiveProfile =
            if (OpenSshProfileCodec.isOpenSsh(profile)) {
                runCatching {
                    OpenSshTunnelBridge(this).start(profile)
                }.getOrElse {
                    fail("OpenSSH start failed: " + (it.message ?: "unknown"))
                    return
                }.also {
                    sshSourceProfile = profile
                    sshHandle = it
                }.proxyProfile
            } else {
                profile
            }

        val request =
            CoreStartRequest(
                effectiveProfile,
                settings,
                ConnectionMode.VPN,
                tun!!.fd
            )

        val validation =
            runCatching {
                ParcelFileDescriptor
                    .dup(
                        tun!!.fileDescriptor
                    )
                    .use { duplicate ->
                        core.prepare(
                            request.copy(
                                tunFd =
                                    duplicate.fd
                            )
                        ).second
                    }
            }.getOrElse {
                CoreResult(
                    false,
                    "VPN config " +
                        "validation failed: " +
                        it.message
                )
            }

        if (!validation.success) {
            fail(validation.error)
            return
        }

        ConnectionRuntime
            .updateSession(sessionId) {
                it.copy(
                    state =
                        ConnectionState.CONNECTING
                )
            }

        if (stopRequested.get()) {
            finishSession(null)
            return
        }

        val result =
            core.startValidated(
                request = request,
                protect = { fd -> protect(fd) },
                isCancelled = stopRequested::get
            )

        if (!result.success) {
            fail(result.error)
            return
        }

        ownsCore.set(true)

        if (stopRequested.get()) {
            finishSession(null)
            return
        }

        startedElapsed =
            restoredStartedElapsed
                .takeIf {
                    it > 0L &&
                        it <=
                        SystemClock
                            .elapsedRealtime()
                }
                ?: SystemClock
                    .elapsedRealtime()

        ConnectionRuntime
            .updateSession(sessionId) {
                it.copy(
                    state =
                        ConnectionState.CONNECTED,
                    startedElapsed =
                        startedElapsed,
                    error = ""
                )
            }

        ConnectionSessionStore
            .markConnected(
                context = this,
                mode =
                    ConnectionMode.VPN,
                profileId =
                    profile.id,
                profileName =
                    profile.name,
                startedElapsed =
                    startedElapsed
            )

        QuickConnectStore.save(
            this,
            ConnectionMode.VPN,
            profile.id,
            profile.name
        )
        QuickConnectTileService
            .requestUpdate(this)

        startForeground(
            NotificationSupport.ID,
            NotificationSupport.build(
                this,
                profile.name,
                ConnectionMode.VPN,
                startedElapsed,
                Intent(
                    this,
                    TrivoxVpnService::
                        class.java
                ).setAction(
                    ACTION_STOP
                )
            )
        )

        Diagnostics.info(
            "VPN started for " +
                profile.name
        )

        handler.postDelayed(
            {
                val current =
                    ConnectionRuntime.current()

                if (
                    current.sessionId ==
                    sessionId &&
                    current.state ==
                    ConnectionState.CONNECTED
                ) {
                    Diagnostics
                        .markNativeSessionStable()
                }
            },
            NATIVE_STABILITY_WINDOW_MS
        )

        registerNetworkCallback(
            settings
                .reconnectOnNetworkChange,
            request
        )

        scheduleMonitor()
    }

    private fun applyAppRouting(
        builder: Builder,
        mode: AppRoutingMode,
        packages: Set<String>
    ) {
        if (
            mode ==
            AppRoutingMode.ALL
        ) {
            return
        }

        packages.forEach {
                packageName ->
            try {
                if (
                    mode ==
                    AppRoutingMode
                        .ALLOW_SELECTED
                ) {
                    builder
                        .addAllowedApplication(
                            packageName
                        )
                } else {
                    builder
                        .addDisallowedApplication(
                            packageName
                        )
                }
            } catch (
                _: PackageManager
                    .NameNotFoundException
            ) {
                Diagnostics.warning(
                    "Routing package is no " +
                        "longer installed: " +
                        packageName
                )
            }
        }
    }

    private fun registerNetworkCallback(
        enabled: Boolean,
        request: CoreStartRequest
    ) {
        if (
            !enabled ||
            stopRequested.get()
        ) {
            return
        }

        unregisterNetworkCallback()
        var observedInitial = false

        val callback =
            object :
                ConnectivityManager
                    .NetworkCallback() {
                override fun onAvailable(
                    network: Network
                ) {
                    if (!observedInitial) {
                        observedInitial = true
                        return
                    }

                    val current =
                        ConnectionRuntime
                            .current()

                    if (
                        stopRequested.get() ||
                        current.sessionId !=
                        sessionId ||
                        current.state !=
                        ConnectionState
                            .RECONNECTING
                    ) {
                        return
                    }

                    if (
                        !reconnectQueued
                            .compareAndSet(
                                false,
                                true
                            )
                    ) {
                        return
                    }

                    val accepted =
                        executeSafely {
                            try {
                                val latest =
                                    ConnectionRuntime
                                        .current()

                                if (
                                    !stopRequested
                                        .get() &&
                                    latest.sessionId ==
                                    sessionId &&
                                    latest.state ==
                                    ConnectionState
                                        .RECONNECTING
                                ) {
                                    reconnectCore(
                                        request
                                    )
                                }
                            } finally {
                                reconnectQueued
                                    .set(false)
                            }
                        }

                    if (!accepted) {
                        reconnectQueued.set(
                            false
                        )
                    }
                }

                override fun onLost(
                    network: Network
                ) {
                    if (
                        !stopRequested.get()
                    ) {
                        ConnectionRuntime
                            .updateSession(
                                sessionId
                            ) {
                                if (
                                    it.state ==
                                    ConnectionState
                                        .CONNECTED
                                ) {
                                    it.copy(
                                        state =
                                            ConnectionState
                                                .RECONNECTING
                                    )
                                } else {
                                    it
                                }
                            }
                    }
                }
            }

        networkCallback = callback

        getSystemService(
            ConnectivityManager::
                class.java
        ).registerDefaultNetworkCallback(
            callback
        )
    }

    private fun reconnectCore(
        request: CoreStartRequest
    ) {
        if (stopRequested.get()) {
            return
        }

        if (
            ownsCore.compareAndSet(
                true,
                false
            )
        ) {
            val stopped =
                core.stop()

            if (!stopped.success) {
                fail(stopped.error)
                return
            }
            mixedListenerPort?.let { port ->
                LocalProxyPortGuard.awaitReleased(port, 2_000L)
            }
        }

        if (stopRequested.get()) {
            return
        }

        var reconnectRequest = request
        sshSourceProfile?.let { source ->
            sshHandle?.close()
            val restarted = runCatching {
                OpenSshTunnelBridge(this).start(source)
            }.getOrElse {
                fail("OpenSSH reconnect failed: " + (it.message ?: "unknown"))
                return
            }
            sshHandle = restarted
            reconnectRequest = request.copy(profile = restarted.proxyProfile)
        }

        val result =
            core.startValidated(
                request = reconnectRequest,
                protect = { fd -> protect(fd) },
                isCancelled = stopRequested::get
            )

        if (result.success) {
            ownsCore.set(true)
            ConnectionRuntime
                .updateSession(sessionId) {
                    it.copy(
                        state =
                            ConnectionState
                                .CONNECTED,
                        error = ""
                    )
                }
        } else {
            fail(result.error)
        }
    }

    private fun scheduleMonitor() {
        handler.post(
            object : Runnable {
                override fun run() {
                    val current =
                        ConnectionRuntime
                            .current()

                    if (
                        stopRequested.get() ||
                        current.sessionId !=
                        sessionId ||
                        current.state !in
                        setOf(
                            ConnectionState
                                .CONNECTED,
                            ConnectionState
                                .RECONNECTING
                        )
                    ) {
                        return
                    }

                    if (
                        current.state ==
                        ConnectionState
                            .CONNECTED
                    ) {
                        executeSafely {
                            val ssh = sshHandle
                            if (!stopRequested.get() && ssh != null && !ssh.isAlive()) {
                                fail("OpenSSH tunnel stopped unexpectedly: " + ssh.failureText())
                            } else if (
                                !stopRequested
                                    .get() &&
                                !core.isRunning()
                            ) {
                                fail(
                                    "Xray stopped " +
                                        "unexpectedly"
                                )
                            }
                        }
                    }

                    NotificationSupport
                        .notifyIfAllowed(
                            this@TrivoxVpnService,
                            NotificationSupport
                                .build(
                                    this@TrivoxVpnService,
                                    current.profileName,
                                    ConnectionMode.VPN,
                                    startedElapsed,
                                    Intent(
                                        this@TrivoxVpnService,
                                        TrivoxVpnService::
                                            class.java
                                    ).setAction(
                                        ACTION_STOP
                                    )
                                )
                        )

                    handler.postDelayed(
                        this,
                        MONITOR_INTERVAL_MS
                    )
                }
            }
        )
    }

    private fun requestStop() {
        if (!sessionAccepted.get()) {
            ConnectionSessionStore
                .clear(this)
            stopSelf()
            return
        }

        stopRequested.set(true)

        ConnectionRuntime
            .updateSession(sessionId) {
                it.copy(
                    state =
                        ConnectionState.STOPPING
                )
            }

        finishSession(null)
    }

    private fun fail(message: String) {
        Diagnostics.error(message)
        stopRequested.set(true)
        finishSession(message)
    }

    private fun finishSession(
        error: String?
    ) {
        if (
            !cleanupStarted
                .compareAndSet(
                    false,
                    true
                )
        ) {
            return
        }

        reconnectQueued.set(false)
        ConnectionSessionStore
            .clear(this)

        cleanupExecutor.execute {
            handler
                .removeCallbacksAndMessages(
                    null
                )
            unregisterNetworkCallback()

            ownsCore.set(false)
            runCatching { core.stop() }
                .onFailure {
                    Diagnostics.warning(
                        "Immediate core stop failed: " + it.message
                    )
                }
            mixedListenerPort?.let { port ->
                if (!LocalProxyPortGuard.awaitReleased(port)) {
                    Diagnostics.warning(
                        "Local mixed proxy port $port was still busy after VPN cleanup"
                    )
                }
            }
            mixedListenerPort = null

            runCatching {
                tun?.close()
            }
            tun = null

            storeDuration()

            val current =
                ConnectionRuntime.current()

            if (
                current.sessionId ==
                sessionId
            ) {
                ConnectionRuntime.update(
                    if (error == null) {
                        ConnectionRuntime
                            .Snapshot()
                    } else {
                        ConnectionRuntime
                            .Snapshot(
                                state =
                                    ConnectionState.ERROR,
                                profileId =
                                    profileId,
                                profileName =
                                    profileName,
                                error = error,
                                mode =
                                    ConnectionMode.VPN
                            )
                    }
                )
            }

            sessionAccepted.set(false)
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )
            QuickConnectTileService
                .requestUpdate(this)
            stopSelf()
        }
    }

    private fun storeDuration() {
        if (startedElapsed <= 0) {
            return
        }

        val repository =
            ConfigRepository(this)
        val profile =
            repository.find(profileId)
                ?: return
        val duration =
            SystemClock
                .elapsedRealtime() -
                startedElapsed

        profile.lastSessionMs =
            duration
        profile.cumulativeSessionMs +=
            duration
        repository.save(profile)
        startedElapsed = 0L
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            callback ->
            runCatching {
                getSystemService(
                    ConnectivityManager::
                        class.java
                ).unregisterNetworkCallback(
                    callback
                )
            }
        }

        networkCallback = null
    }

    private fun executeSafely(
        block: () -> Unit
    ): Boolean =
        try {
            executor.execute {
                runCatching(block)
                    .onFailure {
                        Diagnostics
                            .recordThrowable(
                                "VPN service task",
                                it
                            )

                        if (
                            !stopRequested.get() &&
                            !cleanupStarted.get()
                        ) {
                            fail(
                                "VPN service failure: " +
                                    (
                                        it.message
                                            ?: "unknown"
                                        )
                            )
                        }
                    }
            }
            true
        } catch (
            _: RejectedExecutionException
        ) {
            Diagnostics.warning(
                "VPN service rejected a task " +
                    "after shutdown"
            )
            false
        }


    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {
        val current =
            ConnectionRuntime.current()

        if (
            current.sessionId ==
            sessionId &&
            current.state in
            setOf(
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING
            )
        ) {
            startForeground(
                NotificationSupport.ID,
                NotificationSupport.build(
                    this,
                    current.profileName,
                    ConnectionMode.VPN,
                    startedElapsed,
                    Intent(
                        this,
                        TrivoxVpnService::
                            class.java
                    ).setAction(
                        ACTION_STOP
                    )
                )
            )
            Diagnostics.info(
                "App task removed; VPN " +
                    "foreground service remains active"
            )
        }

        super.onTaskRemoved(
            rootIntent
        )
    }

    override fun onRevoke() {
        requestStop()
    }

    override fun onDestroy() {
        val restartExpected =
            sessionAccepted.get() &&
                !cleanupStarted.get() &&
                !stopRequested.get() &&
                ConnectionSessionStore
                    .load(this)
                    ?.mode ==
                ConnectionMode.VPN

        stopRequested.set(true)
        reconnectQueued.set(false)

        if (restartExpected) {
            ConnectionRuntime.update(
                ConnectionRuntime.Snapshot(
                    state =
                        ConnectionState.RECONNECTING,
                    profileId = profileId,
                    profileName = profileName,
                    startedElapsed =
                        startedElapsed
                            .takeIf { it > 0L }
                            ?: restoredStartedElapsed,
                    mode =
                        ConnectionMode.VPN,
                    sessionId = sessionId
                )
            )
            Diagnostics.warning(
                "VPN service was destroyed; " +
                    "Android restart is expected"
            )
        }
        unregisterNetworkCallback()
        handler
            .removeCallbacksAndMessages(
                null
            )

        if (
            ownsCore.compareAndSet(
                true,
                false
            )
        ) {
            val stopped =
                core.stop()

            if (!stopped.success) {
                Diagnostics.error(
                    "VPN destroy stop failed: " +
                        stopped.error
                )
            }
        }

        runCatching {
            tun?.close()
        }.onFailure {
            Diagnostics.warning(
                "Unable to close VPN TUN: " +
                    it.message
            )
        }
        tun = null
        // VPN destroy OpenSSH cleanup
        sshHandle?.close()
        sshHandle = null
        sshSourceProfile = null
        cleanupExecutor.shutdownNow()
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START =
            "com.trivox.client." +
                "VPN_START"
        const val ACTION_STOP =
            "com.trivox.client." +
                "VPN_STOP"
        const val EXTRA_PROFILE_ID =
            "profile_id"
        private const val
            MONITOR_INTERVAL_MS = 5_000L
        private const val
            NATIVE_STABILITY_WINDOW_MS =
                30_000L
    }
}
