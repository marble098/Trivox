package com.trivox.client.service

// TRIVOX_V20_SAFE_NATIVE_LIFECYCLE

import com.trivox.client.core.CoreManager
import com.trivox.client.util.Diagnostics
import java.net.InetSocketAddress
import java.net.ServerSocket
import android.os.SystemClock

internal object LocalProxyPortGuard {
    private val lock = Any()

    fun prepare(
        core: CoreManager,
        port: Int,
        timeoutMs: Long = START_RELEASE_TIMEOUT_MS
    ): Boolean = synchronized(lock) {
        if (isAvailable(port)) {
            return@synchronized true
        }

        if (!core.isRunning()) {
            Diagnostics.error(
                "Mixed proxy port $port is busy, but Trivox does not own a " +
                    "running Xray instance. Refusing an unsafe native stop."
            )
            return@synchronized false
        }

        Diagnostics.warning(
            "Mixed proxy port $port is busy; stopping the owned Xray instance"
        )
        val stop = runCatching { core.stop() }.getOrNull()
        if (stop != null && !stop.success) {
            Diagnostics.warning(
                "Owned Xray cleanup reported: ${stop.error}"
            )
        }

        val released = awaitReleased(port, timeoutMs)
        if (released) {
            Diagnostics.info(
                "Mixed proxy port $port became available after cleanup"
            )
        } else {
            Diagnostics.error(
                "Mixed proxy port $port remained busy for ${timeoutMs}ms"
            )
        }
        released
    }

    fun awaitReleased(
        port: Int,
        timeoutMs: Long = STOP_RELEASE_TIMEOUT_MS
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        do {
            if (isAvailable(port)) {
                return true
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return false
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        } while (true)
    }

    fun isAvailable(port: Int): Boolean =
        runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("127.0.0.1", port))
            }
            true
        }.getOrDefault(false)

    private const val RETRY_INTERVAL_MS = 70L
    private const val START_RELEASE_TIMEOUT_MS = 3_000L
    private const val STOP_RELEASE_TIMEOUT_MS = 2_500L
}
