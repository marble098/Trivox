package com.trivox.client.service

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
import java.util.concurrent.atomic.AtomicLong

/**
 * Native WireGuard lifecycle controller.
 *
 * Startup verification is intentionally not protected by one long synchronized
 * block. Stop/new-start requests invalidate an epoch immediately, detach the
 * active candidate, and let stale workers observe cancellation without blocking
 * the next VPN session.
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

    private data class ActiveTunnel(
        val backend: GoBackend,
        val tunnel: ManagedTunnel
    )

    private val stateLock = Any()
    private val epoch = AtomicLong(0L)
    private var backend: GoBackend? = null
    private var tunnel: ManagedTunnel? = null

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
            val candidateTunnel = ManagedTunnel("trivoxwg")
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
            val up = runCatching {
                candidateBackend.setState(
                    candidateTunnel,
                    Tunnel.State.UP,
                    config
                )
            }
            if (up.isFailure || up.getOrNull() != Tunnel.State.UP) {
                lastCategory = "native_wireguard_activation"
                lastDetail = up.exceptionOrNull()?.message.orEmpty()
                bringDown(ActiveTunnel(candidateBackend, candidateTunnel))
                continue
            }

            activatedAny = true

            if (cancelled(token, isCancelled)) {
                bringDown(ActiveTunnel(candidateBackend, candidateTunnel))
                return cancelledResult(true, candidate.mtu)
            }

            val installed = synchronized(stateLock) {
                if (epoch.get() != token || isCancelled()) {
                    false
                } else {
                    backend = candidateBackend
                    tunnel = candidateTunnel
                    true
                }
            }
            if (!installed) {
                bringDown(ActiveTunnel(candidateBackend, candidateTunnel))
                return cancelledResult(true, candidate.mtu)
            }

            if (!waitCancellable(180L, token, isCancelled)) {
                detachIfCurrent(candidateBackend, candidateTunnel)
                bringDown(ActiveTunnel(candidateBackend, candidateTunnel))
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

            val stats = runCatching {
                candidateBackend.getStatistics(candidateTunnel)
            }.getOrNull()
            val rx = stats?.totalRx() ?: 0L
            val tx = stats?.totalTx() ?: 0L

            if (cancelled(token, isCancelled)) {
                detachIfCurrent(candidateBackend, candidateTunnel)
                bringDown(ActiveTunnel(candidateBackend, candidateTunnel))
                return cancelledResult(true, candidate.mtu)
            }

            if (proof.success) {
                val version = runCatching {
                    candidateBackend.version
                }.getOrDefault("")
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

            detachIfCurrent(candidateBackend, candidateTunnel)
            bringDown(ActiveTunnel(candidateBackend, candidateTunnel))

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
            val b = backend ?: return@synchronized null
            val t = tunnel ?: return@synchronized null
            ActiveTunnel(b, t)
        } ?: return false

        return runCatching {
            active.backend.getState(active.tunnel) == Tunnel.State.UP
        }.getOrDefault(false)
    }

    fun stop() {
        epoch.incrementAndGet()
        bringDown(detachCurrent())
    }

    private fun detachCurrent(): ActiveTunnel? = synchronized(stateLock) {
        val b = backend
        val t = tunnel
        backend = null
        tunnel = null
        if (b != null && t != null) ActiveTunnel(b, t) else null
    }

    private fun detachIfCurrent(
        expectedBackend: GoBackend,
        expectedTunnel: ManagedTunnel
    ) {
        synchronized(stateLock) {
            if (backend === expectedBackend && tunnel === expectedTunnel) {
                backend = null
                tunnel = null
            }
        }
    }

    private fun bringDown(active: ActiveTunnel?) {
        if (active == null) return
        runCatching {
            active.backend.setState(
                active.tunnel,
                Tunnel.State.DOWN,
                null
            )
        }.onFailure {
            Diagnostics.warning(
                "Native WireGuard cleanup failed: ${it.message}"
            )
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
}
