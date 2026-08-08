package com.trivox.client.service

// TRIVOX_V23_1_MIXED_PORT_RECOVERY
// TRIVOX_V20_SAFE_NATIVE_LIFECYCLE

import android.os.SystemClock
import com.trivox.client.core.CoreManager
import com.trivox.client.util.Diagnostics
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

internal object LocalProxyPortGuard {
    private val lock = Any()

    fun prepare(
        core: CoreManager,
        port: Int,
        timeoutMs: Long = START_RELEASE_TIMEOUT_MS
    ): Boolean = synchronized(lock) {
        prepareLocked(core, port, timeoutMs)
    }

    fun prepareVpnPort(
        core: CoreManager,
        preferredPort: Int,
        timeoutMs: Long = START_RELEASE_TIMEOUT_MS
    ): Int? = synchronized(lock) {
        if (prepareLocked(core, preferredPort, timeoutMs)) {
            return@synchronized preferredPort
        }

        val fallback = findAvailablePortLocked(preferredPort)
        if (fallback != null) {
            Diagnostics.warning(
                "VPN local mixed proxy recovered from busy port " +
                    "$preferredPort to temporary port $fallback"
            )
        } else {
            Diagnostics.error("No safe fallback local mixed proxy port was available")
        }
        fallback
    }

    private fun prepareLocked(core: CoreManager, port: Int, timeoutMs: Long): Boolean {
        if (isAvailable(port)) return true

        if (!core.isRunning()) {
            if (awaitReleased(port, minOf(timeoutMs, PASSIVE_RELEASE_TIMEOUT_MS))) {
                Diagnostics.info("Mixed proxy port $port became available after passive cleanup wait")
                return true
            }
            Diagnostics.warning(
                "Mixed proxy port $port is busy while Trivox owns no running Xray instance"
            )
            return false
        }

        Diagnostics.warning("Mixed proxy port $port is busy; stopping the owned Xray instance")
        val stop = runCatching { core.stop() }.getOrNull()
        if (stop != null && !stop.success) {
            Diagnostics.warning("Owned Xray cleanup reported: ${stop.error}")
        }

        val released = awaitReleased(port, timeoutMs)
        if (released) {
            Diagnostics.info("Mixed proxy port $port became available after cleanup")
        } else {
            Diagnostics.warning("Mixed proxy port $port remained busy for ${timeoutMs}ms")
        }
        return released
    }

    fun awaitReleased(
        port: Int,
        timeoutMs: Long = STOP_RELEASE_TIMEOUT_MS
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        do {
            if (isAvailable(port)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            try {
                Thread.sleep(RETRY_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        } while (true)
    }

    fun isAvailable(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    }.getOrDefault(false)

    private fun findAvailablePortLocked(preferredPort: Int): Int? {
        for (distance in 1..FALLBACK_SCAN_DISTANCE) {
            val higher = preferredPort + distance
            if (higher in MIN_FALLBACK_PORT..65535 && isAvailable(higher)) return higher
            val lower = preferredPort - distance
            if (lower in MIN_FALLBACK_PORT..65535 && isAvailable(lower)) return lower
        }
        return runCatching {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.getOrNull()?.takeIf { it in MIN_FALLBACK_PORT..65535 }
    }

    private const val RETRY_INTERVAL_MS = 70L
    private const val START_RELEASE_TIMEOUT_MS = 3_000L
    private const val STOP_RELEASE_TIMEOUT_MS = 2_500L
    private const val PASSIVE_RELEASE_TIMEOUT_MS = 900L
    private const val FALLBACK_SCAN_DISTANCE = 64
    private const val MIN_FALLBACK_PORT = 1024
}
