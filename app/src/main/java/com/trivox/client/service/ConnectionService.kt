package com.trivox.client.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.core.CoreManager
import com.trivox.client.core.CoreStartRequest
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.SettingsRepository
import com.trivox.client.config.OpenSshProfileCodec
import com.trivox.client.ssh.OpenSshTunnelBridge
import com.trivox.client.util.Diagnostics
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionService : Service() {
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
    private var sshHandle: OpenSshTunnelBridge.Handle? = null

    private lateinit var core:
        CoreManager
    private var profileId:
        String? = null
    private var profileName = ""
    private var startedElapsed = 0L
    private var restoredStartedElapsed =
        0L
    private var sessionId = 0L

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
                        ConnectionMode.PROXY
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

        if (action == ACTION_STOP) {
            ConnectionSessionStore
                .clear(this)
            requestStop()
            return START_NOT_STICKY
        }

        if (action != ACTION_START) {
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
            current.state !in
            setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            )
        ) {
            val staleRestoredState =
                restored != null &&
                    current.mode ==
                    ConnectionMode.PROXY &&
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
                    ConnectionMode.PROXY,
                profileId =
                    requestedProfileId,
                profileName =
                    profileName,
                startedElapsed =
                    restoredStartedElapsed
            )

        startForeground(
            NotificationSupport.ID,
            buildNotification()
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

        profileName =
            profile.name
        ConnectionSessionStore
            .markDesired(
                context = this,
                mode =
                    ConnectionMode.PROXY,
                profileId =
                    profile.id,
                profileName =
                    profileName,
                startedElapsed =
                    restoredStartedElapsed
            )

        startForeground(
            NotificationSupport.ID,
            buildNotification()
        )

        if (!core.adapter.isAvailable()) {
            fail(
                "Selected core " + core.adapter.id +
                    " is missing or corrupted"
            )
            return
        }

        val settings =
            SettingsRepository(this)
                .load()

        runCatching { core.stop() }
            .onFailure {
                Diagnostics.warning("Pre-start core cleanup failed: " + it.message)
            }

        try {
            Thread.sleep(180L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (
            !portAvailable(
                settings.socksPort
            )
        ) {
            Diagnostics.warning(
                "Mixed proxy port was busy; " +
                    "stopping stale core processes"
            )
            core.stop()

            try {
                Thread.sleep(250L)
            } catch (
                _: InterruptedException
            ) {
                Thread.currentThread()
                    .interrupt()
            }

            if (
                !portAvailable(
                    settings.socksPort
                )
            ) {
                fail(
                    "The mixed proxy port " +
                        "is already in use by " +
                        "another application"
                )
                return
            }
        }

        ConnectionRuntime.update(
            ConnectionRuntime.Snapshot(
                state =
                    ConnectionState.PREPARING,
                profileId = profile.id,
                profileName =
                    profile.name,
                mode =
                    ConnectionMode.PROXY,
                sessionId = sessionId
            )
        )

        val effectiveProfile =
            if (OpenSshProfileCodec.isOpenSsh(profile)) {
                runCatching {
                    OpenSshTunnelBridge(this).start(profile)
                }.getOrElse {
                    fail("OpenSSH start failed: " + (it.message ?: "unknown"))
                    return
                }.also {
                    sshHandle = it
                }.proxyProfile
            } else {
                profile
            }

        val request =
            CoreStartRequest(
                effectiveProfile,
                settings,
                ConnectionMode.PROXY
            )

        ConnectionRuntime.updateSession(
            sessionId
        ) {
            it.copy(
                state =
                    ConnectionState
                        .CONNECTING
            )
        }

        if (stopRequested.get()) {
            finishSession(null)
            return
        }

        val result =
            core.start(
                request = request,
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

        ConnectionRuntime.updateSession(
            sessionId
        ) {
            it.copy(
                state =
                    ConnectionState
                        .CONNECTED,
                startedElapsed =
                    startedElapsed,
                error = ""
            )
        }

        ConnectionSessionStore
            .markConnected(
                context = this,
                mode =
                    ConnectionMode.PROXY,
                profileId =
                    profile.id,
                profileName =
                    profile.name,
                startedElapsed =
                    startedElapsed
            )

        QuickConnectStore.save(
            this,
            ConnectionMode.PROXY,
            profile.id,
            profile.name
        )
        QuickConnectTileService
            .requestUpdate(this)

        startForeground(
            NotificationSupport.ID,
            buildNotification()
        )

        Diagnostics.info(
            "Mixed proxy started for " +
                profile.name
        )

        handler.postDelayed(
            {
                val current =
                    ConnectionRuntime
                        .current()

                if (
                    current.sessionId ==
                    sessionId &&
                    current.state ==
                    ConnectionState
                        .CONNECTED
                ) {
                    Diagnostics
                        .markNativeSessionStable()
                }
            },
            NATIVE_STABILITY_WINDOW_MS
        )

        scheduleMonitor()
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
                        current.state !=
                        ConnectionState
                            .CONNECTED
                    ) {
                        return
                    }

                    executeSafely {
                        val ssh = sshHandle
                        if (!stopRequested.get() && ssh != null && !ssh.isAlive()) {
                            fail("OpenSSH tunnel stopped unexpectedly: " + ssh.failureText())
                        } else if (
                            !stopRequested.get() &&
                            !core.isRunning()
                        ) {
                            fail(
                                "Selected core stopped " +
                                    "unexpectedly"
                            )
                        }
                    }

                    NotificationSupport
                        .notifyIfAllowed(
                            this@ConnectionService,
                            buildNotification()
                        )

                    handler.postDelayed(
                        this,
                        MONITOR_INTERVAL_MS
                    )
                }
            }
        )
    }

    private fun buildNotification() =
        NotificationSupport.build(
            service = this,
            title =
                profileName.ifBlank {
                    "Trivox"
                },
            mode =
                ConnectionMode.PROXY,
            startedElapsed =
                startedElapsed
                    .takeIf {
                        it > 0L
                    }
                    ?: restoredStartedElapsed,
            stopIntent =
                Intent(
                    this,
                    ConnectionService::
                        class.java
                ).setAction(
                    ACTION_STOP
                )
        )

    private fun requestStop() {
        if (!sessionAccepted.get()) {
            ConnectionSessionStore
                .clear(this)
            stopSelf()
            return
        }

        stopRequested.set(true)

        ConnectionRuntime
            .updateSession(
                sessionId
            ) {
                it.copy(
                    state =
                        ConnectionState
                            .STOPPING
                )
            }

        finishSession(null)
    }

    private fun fail(
        message: String
    ) {
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

        ConnectionSessionStore
            .clear(this)

        cleanupExecutor.execute {
            handler
                .removeCallbacksAndMessages(
                    null
                )

            ownsCore.set(false)
            sshHandle?.close()
            sshHandle = null
            runCatching { core.stop() }
                .onFailure {
                    Diagnostics.warning(
                        "Immediate core stop failed: " + it.message
                    )
                }

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
                                    ConnectionState
                                        .ERROR,
                                profileId =
                                    profileId,
                                profileName =
                                    profileName,
                                error = error,
                                mode =
                                    ConnectionMode
                                        .PROXY
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
        val duration =
            (
                SystemClock
                    .elapsedRealtime() -
                    startedElapsed
                ).coerceAtLeast(0L)

        repository.update(
            profileId ?: return
        ) { profile ->
            profile.lastSessionMs =
                duration
            profile
                .cumulativeSessionMs +=
                duration
        }

        startedElapsed = 0L
    }

    private fun portAvailable(
        port: Int
    ): Boolean =
        runCatching {
            ServerSocket().use {
                it.reuseAddress = false
                it.bind(
                    InetSocketAddress(
                        "127.0.0.1",
                        port
                    )
                )
            }
            true
        }.getOrDefault(false)

    private fun executeSafely(
        block: () -> Unit
    ): Boolean =
        try {
            executor.execute {
                runCatching(block)
                    .onFailure {
                        Diagnostics
                            .recordThrowable(
                                "Connection service task",
                                it
                            )

                        if (
                            !stopRequested.get() &&
                            !cleanupStarted.get()
                        ) {
                            fail(
                                "Connection service " +
                                    "failure: " +
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
                "Connection service rejected " +
                    "a task after shutdown"
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
            current.state ==
            ConnectionState.CONNECTED
        ) {
            startForeground(
                NotificationSupport.ID,
                buildNotification()
            )
            Diagnostics.info(
                "App task removed; mixed proxy " +
                    "foreground service remains active"
            )
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler
            .removeCallbacksAndMessages(
                null
            )

        val restartExpected =
            sessionAccepted.get() &&
                !cleanupStarted.get() &&
                !stopRequested.get() &&
                ConnectionSessionStore
                    .load(this)
                    ?.mode ==
                ConnectionMode.PROXY

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
                        ConnectionMode.PROXY,
                    sessionId = sessionId
                )
            )
            Diagnostics.warning(
                "Mixed proxy service was destroyed; " +
                    "Android restart is expected"
            )
        }

        if (
            !restartExpected &&
            ownsCore.compareAndSet(
                true,
                false
            )
        ) {
            val stopped =
                core.stop()

            if (!stopped.success) {
                Diagnostics.error(
                    "Proxy destroy stop failed: " +
                        stopped.error
                )
            }
        } else if (restartExpected) {
            Diagnostics.info(
                "Proxy service destroy skipped core stop because Android restart is expected"
            )
        }

        // Proxy destroy OpenSSH cleanup
        sshHandle?.close()
        sshHandle = null
        cleanupExecutor.shutdownNow()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    companion object {
        const val ACTION_START =
            "com.trivox.client." +
                "PROXY_START"
        const val ACTION_STOP =
            "com.trivox.client." +
                "PROXY_STOP"
        const val EXTRA_PROFILE_ID =
            "profile_id"

        private const val
            MONITOR_INTERVAL_MS =
                5_000L
        private const val
            NATIVE_STABILITY_WINDOW_MS =
                30_000L
    }
}
