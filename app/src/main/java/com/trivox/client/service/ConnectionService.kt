package com.trivox.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.trivox.client.R
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.core.CoreManager
import com.trivox.client.core.CoreStartRequest
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.SettingsRepository
import com.trivox.client.ui.MainActivity
import com.trivox.client.util.Diagnostics
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val stopRequested = AtomicBoolean(false)
    private lateinit var core: CoreManager
    private var profileId: String? = null
    private var profileName: String = ""
    private var startedElapsed: Long = 0L
    private var sessionId: Long = 0L

    override fun onCreate() {
        super.onCreate()
        core = CoreManager(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: ConfigRepository(this).selectedId()
                startForeground(NOTIFICATION_ID, notification("Starting Xray"))
                stopRequested.set(false)
                executor.execute { startConnection() }
                return START_STICKY
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun startConnection() {
        val repository = ConfigRepository(this)
        val profile = repository.find(profileId)
        if (profile == null || !profile.enabled) {
            fail("Selected Xray profile is unavailable")
            return
        }
        val settings = SettingsRepository(this).load().normalize().also {
            it.coreId = com.trivox.client.data.CoreId.XRAY
            it.smartCoreSelection = false
            it.lastSmartCoreId = com.trivox.client.data.CoreId.XRAY
            it.preferredTestCore = com.trivox.client.data.CoreId.XRAY
        }
        if (!core.adapter.isAvailable()) {
            fail("Xray 26.7.28 Android core is missing or corrupted")
            return
        }
        if (!portAvailable(settings.socksPort)) {
            core.stop()
            Thread.sleep(250)
        }
        sessionId = ConnectionRuntime.nextSessionId()
        profileName = profile.name
        ConnectionRuntime.update(
            ConnectionRuntime.Snapshot(
                state = ConnectionState.CONNECTING,
                profileId = profile.id,
                profileName = profile.name,
                mode = ConnectionMode.PROXY,
                sessionId = sessionId
            )
        )
        val result = core.start(
            CoreStartRequest(profile, settings, ConnectionMode.PROXY),
            isCancelled = stopRequested::get
        )
        if (!result.success) {
            fail(result.error)
            return
        }
        startedElapsed = SystemClock.elapsedRealtime()
        ConnectionRuntime.updateSession(sessionId) {
            it.copy(state = ConnectionState.CONNECTED, startedElapsed = startedElapsed, error = "")
        }
        startForeground(NOTIFICATION_ID, notification("Xray connected: ${profile.name}"))
    }

    private fun requestStop() {
        stopRequested.set(true)
        runCatching { core.stop() }
        ConnectionRuntime.update(ConnectionRuntime.Snapshot())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        Diagnostics.error(message)
        runCatching { core.stop() }
        ConnectionRuntime.update(
            ConnectionRuntime.Snapshot(
                state = ConnectionState.ERROR,
                profileId = profileId,
                profileName = profileName,
                error = message,
                mode = ConnectionMode.PROXY,
                sessionId = sessionId
            )
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun portAvailable(port: Int): Boolean = runCatching {
        ServerSocket().use {
            it.reuseAddress = false
            it.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    }.getOrDefault(false)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Trivox Xray", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Trivox")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.trivox.client.PROXY_START"
        const val ACTION_STOP = "com.trivox.client.PROXY_STOP"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val CHANNEL_ID = "trivox_xray"
        private const val NOTIFICATION_ID = 260728
    }
}
