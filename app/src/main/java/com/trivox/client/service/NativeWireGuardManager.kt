package com.trivox.client.service

// TRIVOX_V22_NATIVE_CRASH_GUARD
// TRIVOX_V21_STABILITY_AUTO_LEAK_UI

import android.content.Context
import com.trivox.client.config.NativeWireGuardConfig
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.network.TunnelHealthVerifier
import com.trivox.client.util.Diagnostics
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Native WireGuard lifecycle controller.
 *
 * Every GoBackend call for one tunnel is serialized by that tunnel handle.
 * Cleanup is exactly-once even when UI stop, service cleanup and the startup
 * worker all observe cancellation at nearly the same time.
 */
object NativeWireGuardManager {
    data class StartResult(
        val success: Boolean,
        val activated: Boolean,
        val mtu: Int? = null,
        val backendVersion: String = "",
        val errorCategory: String = "",
        val detail: String = ""
    )

    private class ActiveTunnel(
        val backend: GoBackend,
        val tunnel: ManagedTunnel
    ) {
        val nativeLock = Any()
        val downStarted = AtomicBoolean(false)
    }

    private val stateLock = Any()
    private val epoch = AtomicLong(0L)
    private val nativeReadyAtNanos = AtomicLong(0L)
    private var activeTunnel: ActiveTunnel? = null

    fun supports(profile: ConfigProfile): Boolean =
        NativeWireGuardConfig.supports(profile)

    fun start(
        context: Context,
        profile: ConfigProfile,
        settings: AppSettings,
        isCancelled: () -> Boolean
    ): StartResult {
        val token = epoch.incrementAndGet()
        bringDown(detachCurrent())

        if (!waitForBackendReady(token, isCancelled)) {
            return cancelledResult(false, null)
        }

        val candidates = runCatching {
            NativeWireGuardConfig.candidates(
                profile = profile,
                appSettings = settings,
                packageName = context.packageName
            )
        }.getOrElse {
            return StartResult(
                success = false,
                activated = false,
                errorCategory = "invalid_wireguard_profile",
                detail = it.message.orEmpty()
            )
        }

        if (candidates.isEmpty()) {
            return StartResult(
                success = false,
                activated = false,
                errorCategory = "invalid_wireguard_profile",
                detail = "No native WireGuard candidates"
            )
        }

        val candidateBudgetMs =
            (settings.wireGuardHandshakeTimeoutMs / candidates.size)
                .coerceIn(2_500, 5_000)
        val probeTimeoutMs =
            (candidateBudgetMs - 650)
                .coerceIn(1_500, 2_500)

        var activatedAny = false
        var lastCategory = "wireguard_route_unverified"
        var lastDetail = ""
        var lastMtu: Int? = null

        for ((index, candidate) in candidates.withIndex()) {
            if (cancelled(token, isCancelled)) {
                return cancelledResult(activatedAny, lastMtu)
            }
            if (!waitForBackendReady(token, isCancelled)) {
                return cancelledResult(activatedAny, lastMtu)
            }

            val candidateBackend = runCatching {
                GoBackend(context.applicationContext)
            }.getOrElse {
                return StartResult(
                    success = false,
                    activated = activatedAny,
                    mtu = candidate.mtu,
                    errorCategory = "native_backend_unavailable",
                    detail = it.message.orEmpty()
                )
            }
            val handle =
                ActiveTunnel(
                    backend = candidateBackend,
                    tunnel = ManagedTunnel("trivoxwg")
                )
            val config = runCatching {
                Config.parse(
                    ByteArrayInputStream(
                        candidate.text.toByteArray(StandardCharsets.UTF_8)
                    )
                )
            }.getOrElse {
                return StartResult(
                    success = false,
                    activated = activatedAny,
                    mtu = candidate.mtu,
                    errorCategory = "invalid_wireguard_profile",
                    detail = it.message.orEmpty()
                )
            }

            lastMtu = candidate.mtu
            Diagnostics.nativeCheckpoint(
                "wireGuardSetStateUp",
                "begin",
                "candidate=${index + 1}/${candidates.size}; mtu=${candidate.mtu}"
            )
            val up = synchronized(handle.nativeLock) {
                runCatching {
                    handle.backend.setState(
                        handle.tunnel,
                        Tunnel.State.UP,
                        config
                    )
                }
            }
            Diagnostics.nativeCheckpoint(
                "wireGuardSetStateUp",
                if (up.isSuccess && up.getOrNull() == Tunnel.State.UP) {
                    "completed"
                } else {
                    "failed"
                },
                up.exceptionOrNull()?.message.orEmpty()
            )

            if (up.isFailure || up.getOrNull() != Tunnel.State.UP) {
                lastCategory = "native_wireguard_activation"
                lastDetail = up.exceptionOrNull()?.message.orEmpty()
                bringDown(handle)
                continue
            }

            activatedAny = true

            if (cancelled(token, isCancelled)) {
                bringDown(handle)
                return cancelledResult(true, candidate.mtu)
            }

            val installed = synchronized(stateLock) {
                if (epoch.get() != token || isCancelled()) {
                    false
                } else {
                    activeTunnel = handle
                    true
                }
            }
            if (!installed) {
                bringDown(handle)
                return cancelledResult(true, candidate.mtu)
            }

            if (!waitCancellable(180L, token, isCancelled)) {
                detachIfCurrent(handle)
                bringDown(handle)
                return cancelledResult(true, candidate.mtu)
            }

            val proof = TunnelHealthVerifier.measure(
                settings = settings,
                mode = ConnectionMode.VPN,
                attempts = 2,
                budgetMs = candidateBudgetMs,
                perProbeTimeoutMs = probeTimeoutMs,
                isCancelled = {
                    cancelled(token, isCancelled)
                }
            )

            val stats =
                if (!cancelled(token, isCancelled)) {
                    synchronized(handle.nativeLock) {
                        if (handle.downStarted.get()) {
                            null
                        } else {
                            Diagnostics.nativeCheckpoint(
                                "wireGuardGetStatistics",
                                "begin",
                                "candidate=${index + 1}/${candidates.size}"
                            )
                            runCatching {
                                handle.backend.getStatistics(handle.tunnel)
                            }.also {
                                Diagnostics.nativeCheckpoint(
                                    "wireGuardGetStatistics",
                                    if (it.isSuccess) "completed" else "failed",
                                    it.exceptionOrNull()?.message.orEmpty()
                                )
                            }.getOrNull()
                        }
                    }
                } else {
                    null
                }

            val rx = stats?.totalRx() ?: 0L
            val tx = stats?.totalTx() ?: 0L

            if (cancelled(token, isCancelled)) {
                detachIfCurrent(handle)
                bringDown(handle)
                return cancelledResult(true, candidate.mtu)
            }

            if (proof.success) {
                val stillOwned = synchronized(stateLock) {
                    activeTunnel === handle &&
                        epoch.get() == token &&
                        !isCancelled()
                }
                if (!stillOwned) {
                    detachIfCurrent(handle)
                    bringDown(handle)
                    return cancelledResult(true, candidate.mtu)
                }

                val version = synchronized(handle.nativeLock) {
                    if (handle.downStarted.get()) {
                        ""
                    } else {
                        runCatching { handle.backend.version }.getOrDefault("")
                    }
                }
                Diagnostics.info(
                    "Native WireGuard verified; mtu=${candidate.mtu}, rx=$rx, tx=$tx"
                )
                return StartResult(
                    success = true,
                    activated = true,
                    mtu = candidate.mtu,
                    backendVersion = version
                )
            }

            lastCategory = when {
                proof.errorCategory == "cancelled" -> "cancelled"
                tx > 0L && rx == 0L -> "wireguard_no_handshake_reply"
                rx > 0L -> "wireguard_route_or_dns_failure"
                else -> proof.errorCategory ?: "wireguard_route_unverified"
            }
            lastDetail =
                "proof=${proof.errorCategory.orEmpty()}, rx=$rx, tx=$tx, " +
                    "mtu=${candidate.mtu}, candidate=${index + 1}/${candidates.size}"
            Diagnostics.warning(
                "Native WireGuard candidate failed: $lastCategory ($lastDetail)"
            )

            detachIfCurrent(handle)
            bringDown(handle)

            if (lastCategory == "cancelled") {
                return cancelledResult(true, candidate.mtu)
            }

            if (index < candidates.lastIndex &&
                !waitCancellable(220L, token, isCancelled)
            ) {
                return cancelledResult(true, candidate.mtu)
            }
        }

        return StartResult(
            success = false,
            activated = activatedAny,
            mtu = lastMtu,
            errorCategory = lastCategory,
            detail = lastDetail
        )
    }

    fun cancelPending() {
        epoch.incrementAndGet()
        bringDown(detachCurrent())
    }

    fun isRunning(): Boolean {
        val active = synchronized(stateLock) {
            activeTunnel
        } ?: return false

        if (active.downStarted.get()) return false

        return synchronized(active.nativeLock) {
            if (active.downStarted.get()) {
                false
            } else {
                runCatching {
                    active.backend.getState(active.tunnel) == Tunnel.State.UP
                }.getOrDefault(false)
            }
        }
    }

    fun stop() {
        epoch.incrementAndGet()
        bringDown(detachCurrent())
    }

    private fun detachCurrent(): ActiveTunnel? =
        synchronized(stateLock) {
            activeTunnel.also {
                activeTunnel = null
            }
        }

    private fun detachIfCurrent(expected: ActiveTunnel) {
        synchronized(stateLock) {
            if (activeTunnel === expected) {
                activeTunnel = null
            }
        }
    }

    private fun bringDown(active: ActiveTunnel?) {
        if (active == null) return
        if (!active.downStarted.compareAndSet(false, true)) return

        Diagnostics.nativeCheckpoint(
            "wireGuardSetStateDown",
            "begin",
            "exactly-once cleanup"
        )
        val result = synchronized(active.nativeLock) {
            runCatching {
                active.backend.setState(
                    active.tunnel,
                    Tunnel.State.DOWN,
                    null
                )
            }
        }
        Diagnostics.nativeCheckpoint(
            "wireGuardSetStateDown",
            if (result.isSuccess) "completed" else "failed",
            result.exceptionOrNull()?.message.orEmpty()
        )
        result.exceptionOrNull()?.let {
            Diagnostics.warning(
                "Native WireGuard cleanup failed: ${it.message}"
            )
        }
        scheduleBackendSettle()
    }

    private fun scheduleBackendSettle() {
        val readyAt =
            System.nanoTime() +
                NATIVE_BACKEND_SETTLE_MS * 1_000_000L
        while (true) {
            val current = nativeReadyAtNanos.get()
            if (current >= readyAt) return
            if (nativeReadyAtNanos.compareAndSet(current, readyAt)) return
        }
    }

    private fun waitForBackendReady(
        token: Long,
        isCancelled: () -> Boolean
    ): Boolean {
        while (true) {
            if (cancelled(token, isCancelled)) return false
            val remaining =
                nativeReadyAtNanos.get() - System.nanoTime()
            if (remaining <= 0L) return true
            val sleepMs =
                ((remaining + 999_999L) / 1_000_000L)
                    .coerceIn(1L, 40L)
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun cancelled(
        token: Long,
        external: () -> Boolean
    ): Boolean =
        Thread.currentThread().isInterrupted ||
            epoch.get() != token ||
            external()

    private fun cancelledResult(
        activated: Boolean,
        mtu: Int?
    ) = StartResult(
        success = false,
        activated = activated,
        mtu = mtu,
        errorCategory = "cancelled",
        detail = "Connection start was cancelled"
    )

    private fun waitCancellable(
        delayMs: Long,
        token: Long,
        isCancelled: () -> Boolean
    ): Boolean {
        var remaining = delayMs
        while (remaining > 0L) {
            if (cancelled(token, isCancelled)) return false
            val slice = remaining.coerceAtMost(40L)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= slice
        }
        return !cancelled(token, isCancelled)
    }

    private class ManagedTunnel(
        private val tunnelName: String
    ) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    private const val NATIVE_BACKEND_SETTLE_MS = 140L
}
