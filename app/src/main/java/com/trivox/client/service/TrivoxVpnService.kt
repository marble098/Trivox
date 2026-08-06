package com.trivox.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.trivox.client.R
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.core.CoreManager
import com.trivox.client.core.CoreStartRequest
import com.trivox.client.data.AppRoutingMode
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.SettingsRepository
import com.trivox.client.ui.MainActivity
import com.trivox.client.util.Diagnostics
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TrivoxVpnService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val stopRequested = AtomicBoolean(false)
    private lateinit var core: CoreManager
    private var tun: ParcelFileDescriptor? = null
    private var profileId: String? = null
    private var profileName = ""
    private var startedElapsed = 0L
    private var sessionId = 0L

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
                stopRequested.set(false)
                startForeground(NOTIFICATION_ID, notification("Starting Xray VPN"))
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
        val settings = SettingsRepository(this).load().normalize()
        if (!core.adapter.isAvailable()) {
            fail("Xray 26.7.28 Android core is missing or corrupted")
            return
        }
        val established = establishTun(settings) ?: run {
            fail("Android VPN interface could not be established")
            return
        }
        tun = established
        sessionId = ConnectionRuntime.nextSessionId()
        profileName = profile.name
        ConnectionRuntime.update(
            ConnectionRuntime.Snapshot(
                state = ConnectionState.CONNECTING,
                profileId = profile.id,
                profileName = profile.name,
                mode = ConnectionMode.VPN,
                sessionId = sessionId
            )
        )
        val result = core.start(
            CoreStartRequest(profile, settings, ConnectionMode.VPN, established.fd),
            protect = { fd -> protect(fd) },
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
        startForeground(NOTIFICATION_ID, notification("Xray VPN connected: ${profile.name}"))
    }

    private fun establishTun(settings: com.trivox.client.data.AppSettings): ParcelFileDescriptor? = runCatching {
        val builder = Builder()
            .setSession("Trivox Xray")
            .setMtu(settings.mtu)
            .addAddress("172.19.0.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
        if (settings.ipv6) {
            builder.addAddress("fdfe:dcba:9876::2", 126)
            builder.addRoute("::", 0)
        }
        applyAppRouting(builder, settings.appRoutingMode, settings.routedPackages)
        builder.establish()
    }.getOrElse {
        Diagnostics.recordThrowable("VPN establish", it)
        null
    }

    private fun applyAppRouting(builder: Builder, mode: AppRoutingMode, packages: Set<String>) {
        if (mode == AppRoutingMode.ALL || packages.isEmpty()) return
        packages.forEach { packageName ->
            runCatching {
                when (mode) {
                    AppRoutingMode.ALLOW_SELECTED -> builder.addAllowedApplication(packageName)
                    AppRoutingMode.BYPASS_SELECTED -> builder.addDisallowedApplication(packageName)
                    AppRoutingMode.ALL -> Unit
                }
            }.onFailure {
                if (it !is PackageManager.NameNotFoundException) {
                    Diagnostics.recordThrowable("VPN app routing $packageName", it)
                }
            }
        }
    }

    private fun requestStop() {
        stopRequested.set(true)
        runCatching { core.stop() }
        closeTun()
        ConnectionRuntime.update(ConnectionRuntime.Snapshot())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        Diagnostics.error(message)
        runCatching { core.stop() }
        closeTun()
        ConnectionRuntime.update(
            ConnectionRuntime.Snapshot(
                state = ConnectionState.ERROR,
                profileId = profileId,
                profileName = profileName,
                error = message,
                mode = ConnectionMode.VPN,
                sessionId = sessionId
            )
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Trivox Xray VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, TrivoxVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Trivox")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_launcher, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        requestStop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.trivox.client.VPN_START"
        const val ACTION_STOP = "com.trivox.client.VPN_STOP"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val CHANNEL_ID = "trivox_xray_vpn"
        private const val NOTIFICATION_ID = 260729
    }
}
