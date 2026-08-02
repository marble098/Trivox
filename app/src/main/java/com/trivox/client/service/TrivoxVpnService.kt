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
import com.trivox.client.core.CoreStartRequest
import com.trivox.client.data.AppRoutingMode
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.DnsMode
import com.trivox.client.data.SettingsRepository
import com.trivox.client.util.Diagnostics
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TrivoxVpnService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    private val sessionAccepted = AtomicBoolean(false)
    private val ownsCore = AtomicBoolean(false)
    private lateinit var core: CoreManager
    private var tun: ParcelFileDescriptor? = null
    private var profileId: String? = null
    private var startedElapsed = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() { super.onCreate(); core = CoreManager(this); NotificationSupport.createChannel(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (!sessionAccepted.get()) {
                stopSelf()
                return START_NOT_STICKY
            }
            stopConnection()
            return START_NOT_STICKY
        }
        if (ConnectionRuntime.current().state !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) return START_NOT_STICKY
        if (!sessionAccepted.compareAndSet(false, true)) return START_NOT_STICKY
        profileId = intent?.getStringExtra(EXTRA_PROFILE_ID) ?: ConfigRepository(this).selectedId()
        startForeground(NotificationSupport.ID, NotificationSupport.build(this, "Trivox VPN", 0,
            Intent(this, TrivoxVpnService::class.java).setAction(ACTION_STOP)))
        executor.execute(::startConnection)
        return START_NOT_STICKY
    }

    private fun startConnection() {
        val repo = ConfigRepository(this); val profile = repo.find(profileId)
        if (profile == null || !profile.enabled) return fail("Selected configuration is unavailable or disabled")
        if (!core.adapter.isAvailable()) return fail("Xray Android core is missing or corrupted")
        val settings = SettingsRepository(this).load()
        ConnectionRuntime.update(ConnectionRuntime.Snapshot(ConnectionState.PREPARING, profile.id, profile.name))
        val builder = Builder().setSession("Trivox • ${profile.name}").setMtu(settings.mtu)
            .addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0)
        if (settings.ipv6) builder.addAddress("fd00:7472:6976:6f78::1", 126).addRoute("::", 0)
        val dnsServers = when (settings.dnsMode) {
            DnsMode.CUSTOM -> settings.customDns.filter { it.matches(Regex("[0-9a-fA-F:.]+")) }
            DnsMode.SYSTEM -> emptyList()
            else -> listOf("1.1.1.1", "8.8.8.8")
        }
        dnsServers.forEach { runCatching { builder.addDnsServer(it) } }
        applyAppRouting(builder, settings.appRoutingMode, settings.routedPackages)
        if (Build.VERSION.SDK_INT >= 29) builder.setBlocking(settings.blocking)
        tun = builder.establish() ?: return fail("Android did not establish the VPN interface")
        val request = CoreStartRequest(profile, settings, ConnectionMode.VPN, tun!!.fd)
        val validation = runCatching {
            ParcelFileDescriptor.dup(tun!!.fileDescriptor).use { duplicate ->
                core.prepare(request.copy(tunFd = duplicate.fd)).second
            }
        }.getOrElse { com.trivox.client.core.CoreResult(false, "VPN configuration validation failed: ${it.message}") }
        if (!validation.success) return fail(validation.error)
        ConnectionRuntime.update(ConnectionRuntime.Snapshot(ConnectionState.CONNECTING, profile.id, profile.name))
        val result = core.startValidated(request) { fd -> protect(fd) }
        if (!result.success) return fail(result.error)
        ownsCore.set(true)
        startedElapsed = SystemClock.elapsedRealtime()
        ConnectionRuntime.update(ConnectionRuntime.Snapshot(ConnectionState.CONNECTED, profile.id, profile.name, startedElapsed))
        Diagnostics.info("VPN connection started for ${profile.name}")
        registerNetworkCallback(settings.reconnectOnNetworkChange, request)
        scheduleMonitor()
    }

    private fun applyAppRouting(builder: Builder, mode: AppRoutingMode, packages: Set<String>) {
        if (mode == AppRoutingMode.ALL) return
        packages.forEach { packageName ->
            try {
                if (mode == AppRoutingMode.ALLOW_SELECTED) builder.addAllowedApplication(packageName)
                else builder.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                Diagnostics.warning("Saved app routing package is no longer installed: $packageName")
            }
        }
    }

    private fun registerNetworkCallback(enabled: Boolean, request: CoreStartRequest) {
        if (!enabled) return
        var observedInitial = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!observedInitial) { observedInitial = true; return }
                if (ConnectionRuntime.current().state != ConnectionState.RECONNECTING) return
                executor.execute {
                    if (ownsCore.compareAndSet(true, false)) core.stop()
                    val result = core.startValidated(request) { fd -> protect(fd) }
                    if (result.success) ownsCore.set(true)
                    if (result.success) ConnectionRuntime.update(ConnectionRuntime.current().copy(state = ConnectionState.CONNECTED, error = ""))
                    else fail(result.error)
                }
            }
            override fun onLost(network: Network) {
                if (ConnectionRuntime.current().state == ConnectionState.CONNECTED)
                    ConnectionRuntime.update(ConnectionRuntime.current().copy(state = ConnectionState.RECONNECTING))
            }
        }
        networkCallback = callback
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
    }

    private fun scheduleMonitor() {
        handler.post(object : Runnable {
            override fun run() {
                val state = ConnectionRuntime.current().state
                if (state !in setOf(ConnectionState.CONNECTED, ConnectionState.RECONNECTING)) return
                if (state == ConnectionState.CONNECTED && !core.isRunning()) { fail("Xray stopped unexpectedly"); return }
                val current = ConnectionRuntime.current()
                NotificationSupport.notifyIfAllowed(
    this@TrivoxVpnService,
    NotificationSupport.build(
        this@TrivoxVpnService,
        current.profileName,
        startedElapsed,
        Intent(
            this@TrivoxVpnService,
            TrivoxVpnService::class.java
        ).setAction(ACTION_STOP)
    )
)
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun stopConnection() {
        if (!stopping.compareAndSet(false, true)) return
        ConnectionRuntime.update(ConnectionRuntime.current().copy(state = ConnectionState.STOPPING))
        executor.execute {
            unregisterNetworkCallback()
            if (ownsCore.compareAndSet(true, false)) core.stop()
            tun?.close()
            tun = null
            storeDuration()
            ConnectionRuntime.update(ConnectionRuntime.Snapshot())
            handler.removeCallbacksAndMessages(null)
            sessionAccepted.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun storeDuration() {
        if (startedElapsed <= 0) return
        val repo = ConfigRepository(this); val profile = repo.find(profileId) ?: return
        val duration = SystemClock.elapsedRealtime() - startedElapsed
        profile.lastSessionMs = duration; profile.cumulativeSessionMs += duration; repo.save(profile)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun fail(message: String) {
        Diagnostics.error(message)
        unregisterNetworkCallback()
        if (ownsCore.compareAndSet(true, false)) core.stop()
        runCatching { tun?.close() }
        tun = null
        ConnectionRuntime.update(ConnectionRuntime.Snapshot(ConnectionState.ERROR, profileId, error = message))
        handler.post {
            sessionAccepted.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() { stopConnection() }
    override fun onDestroy() { unregisterNetworkCallback(); handler.removeCallbacksAndMessages(null); executor.shutdownNow(); super.onDestroy() }

    companion object {
        const val ACTION_START = "com.trivox.client.VPN_START"
        const val ACTION_STOP = "com.trivox.client.VPN_STOP"
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
