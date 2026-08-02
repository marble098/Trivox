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
import com.trivox.client.util.Diagnostics
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionService : Service() {
    private val executor =
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

    private lateinit var core: CoreManager
    private var profileId: String? = null
    private var profileName = ""
    private var startedElapsed = 0L
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
        if (
            intent?.action ==
            ACTION_STOP
        ) {
            requestStop()
            return START_NOT_STICKY
        }

        if (
            intent?.action !=
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
            return START_NOT_STICKY
        }

        val current =
            ConnectionRuntime.current()

        if (
            current.state !in setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            )
        ) {
            sessionAccepted.set(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        stopRequested.set(false)
        cleanupStarted.set(false)
        ownsCore.set(false)
        startedElapsed = 0L
        sessionId =
            ConnectionRuntime
                .nextSessionId()

        profileId =
            intent.getStringExtra(
                EXTRA_PROFILE_ID
            ) ?: ConfigRepository(this)
                .selectedId()

        startForeground(
            NotificationSupport.ID,
            NotificationSupport.build(
                this,
                "Trivox",
                0,
                Intent(
                    this,
                    ConnectionService::class.java
                ).setAction(ACTION_STOP)
            )
        )

        executeSafely {
            startConnection()
        }

        return START_NOT_STICKY
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
                "Selected configuration is " +
                    "unavailable or disabled"
            )
            return
        }

        profileName = profile.name

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

        if (
            !portAvailable(
                settings.socksPort
            )
        ) {
            fail(
                "The mixed proxy port " +
                    "is already in use"
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
                    ConnectionMode.PROXY,
                sessionId = sessionId
            )
        )

        val request =
            CoreStartRequest(
                profile,
                settings,
                ConnectionMode.PROXY
            )

        ConnectionRuntime.updateSession(
            sessionId
        ) {
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
            core.start(request)

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
            SystemClock.elapsedRealtime()

        ConnectionRuntime.updateSession(
            sessionId
        ) {
            it.copy(
                state =
                    ConnectionState.CONNECTED,
                startedElapsed =
                    startedElapsed,
                error = ""
            )
        }

        Diagnostics.info(
            "Mixed proxy started for " +
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
                        ConnectionState.CONNECTED
                    ) {
                        return
                    }

                    executeSafely {
                        if (
                            !stopRequested.get() &&
                            !core.isRunning()
                        ) {
                            fail(
                                "Xray stopped " +
                                    "unexpectedly"
                            )
                        }
                    }

                    NotificationSupport
                        .notifyIfAllowed(
                            this@ConnectionService,
                            NotificationSupport
                                .build(
                                    this@ConnectionService,
                                    current
                                        .profileName,
                                    startedElapsed,
                                    Intent(
                                        this@ConnectionService,
                                        ConnectionService::
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

        executeSafely {
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
                core.stop()
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
                                    ConnectionState.ERROR,
                                profileId =
                                    profileId,
                                profileName =
                                    profileName,
                                error = error,
                                mode =
                                    ConnectionMode.PROXY
                            )
                    }
                )
            }

            sessionAccepted.set(false)
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )
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

    override fun onDestroy() {
        handler
            .removeCallbacksAndMessages(
                null
            )
        executor.shutdown()
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
            MONITOR_INTERVAL_MS = 1500L
        private const val
            NATIVE_STABILITY_WINDOW_MS =
                30_000L
    }
}
