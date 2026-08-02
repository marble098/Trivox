package com.trivox.client.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
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
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val stopping = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var core: CoreManager
    private var profileId: String? = null
    private var startedElapsed = 0L

    override fun onCreate() {
        super.onCreate(); core = CoreManager(this); NotificationSupport.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopConnection(); return START_NOT_STICKY }
        if (ConnectionRuntime.current().state !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) return START_NOT_STICKY
        profileId = intent?.getStringExtra(EXTRA_PROFILE_ID) ?: ConfigRepository(this).selectedId()
        val initialNotification = NotificationSupport.build(this, "Trivox", 0, Intent(this, ConnectionService::class.java).setAction(ACTION_STOP))
        startForeground(NotificationSupport.ID, initialNotification)
        executor.execute(::startConnection)
        return START_NOT_STICKY
    }

    private fun startConnection() {
        val repo = ConfigRepository(this)
        val profile = repo.find(profileId)
        if (profile == null || !profile.enabled) return fail("Selected configuration is unavailable or disabled")
        if (!core.adapter.isAvailable()) return fail("Xray Android core is missing or corrupted")
        val settings = SettingsRepository(this).load()
        if (!portsAvailable(settings.socksPort, settings.httpPort)) return fail("A local proxy port is already in use")
        ConnectionRuntime.update(ConnectionRuntime.Snapshot(ConnectionState.PREPARING, profile.id, profile.name))
        val request = CoreStartRequest(profile, settings, ConnectionMode.PROXY)
        ConnectionRuntime.update(ConnectionRuntime.Snapshot(ConnectionState.CONNECTING, profile.id, profile.name))
        val result = core.start(request)
        if (!result.success) return fail(result.error)
        startedElapsed = SystemClock.elapsedRealtime()
        ConnectionRuntime.update(ConnectionRuntime.Snapshot(ConnectionState.CONNECTED, profile.id, profile.name, startedElapsed))
        Diagnostics.info("Local proxy connection started for ${profile.name}")
        scheduleMonitor()
    }

    private fun scheduleMonitor() {
        handler.post(object : Runnable {
            override fun run() {
                if (ConnectionRuntime.current().state != ConnectionState.CONNECTED) return
                if (!core.isRunning()) { fail("Xray stopped unexpectedly"); return }
                val current = ConnectionRuntime.current()
                NotificationManagerCompat.from(this@ConnectionService).notify(NotificationSupport.ID,
                    NotificationSupport.build(this@ConnectionService, current.profileName, startedElapsed,
                        Intent(this@ConnectionService, ConnectionService::class.java).setAction(ACTION_STOP)))
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun stopConnection() {
        if (!stopping.compareAndSet(false, true)) return
        ConnectionRuntime.update(ConnectionRuntime.current().copy(state = ConnectionState.STOPPING))
        executor.execute {
            core.stop(); storeDuration()
            ConnectionRuntime.update(ConnectionRuntime.Snapshot())
            handler.removeCallbacksAndMessages(null); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private fun storeDuration() {
        if (startedElapsed <= 0) return
        val repo = ConfigRepository(this); val profile = repo.find(profileId) ?: return
        val duration = SystemClock.elapsedRealtime() - startedElapsed
        profile.lastSessionMs = duration; profile.cumulativeSessionMs += duration; repo.save(profile)
    }

    private fun portsAvailable(vararg ports: Int): Boolean = ports.all { port ->
        runCatching { ServerSocket().use { it.reuseAddress = false; it.bind(InetSocketAddress("127.0.0.1", port)) }; true }.getOrDefault(false)
    }

    private fun fail(message: String) {
        Diagnostics.error(message); core.stop();
        ConnectionRuntime.update(ConnectionRuntime.Snapshot(ConnectionState.ERROR, profileId, error = message))
        handler.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); executor.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.trivox.client.PROXY_START"
        const val ACTION_STOP = "com.trivox.client.PROXY_STOP"
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
