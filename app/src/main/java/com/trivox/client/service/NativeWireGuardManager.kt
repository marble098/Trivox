package com.trivox.client.service

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

/**
 * TRIVOX_V19_NATIVE_WIREGUARD_LEAK_GUARD
 *
 * Preferred device-VPN backend for standard WireGuard profiles. It uses the
 * official WireGuard Android tunnel library and keeps Xray WireGuard only as a
 * compatibility fallback for non-standard fields.
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

    private val lock = Any()
    private var backend: GoBackend? = null
    private var tunnel: ManagedTunnel? = null

    fun supports(profile: ConfigProfile): Boolean =
        NativeWireGuardConfig.supports(profile)

    fun start(
        context: Context,
        profile: ConfigProfile,
        settings: AppSettings,
        isCancelled: () -> Boolean
    ): StartResult = synchronized(lock) {
        stopLocked()
        val candidates = runCatching {
            NativeWireGuardConfig.candidates(
                profile = profile,
                appSettings = settings,
                packageName = context.packageName
            )
        }.getOrElse {
            return@synchronized StartResult(
                success = false,
                activated = false,
                errorCategory = "invalid_wireguard_profile",
                detail = it.message.orEmpty()
            )
        }

        var activatedAny = false
        var lastCategory = "wireguard_route_unverified"
        var lastDetail = ""
        var lastMtu: Int? = null

        for ((index, candidate) in candidates.withIndex()) {
            if (isCancelled()) {
                return@synchronized StartResult(
                    success = false,
                    activated = activatedAny,
                    mtu = lastMtu,
                    errorCategory = "cancelled",
                    detail = "Connection start was cancelled"
                )
            }

            val candidateBackend = runCatching {
                GoBackend(context.applicationContext)
            }.getOrElse {
                return@synchronized StartResult(
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
                return@synchronized StartResult(
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
                runCatching {
                    candidateBackend.setState(
                        candidateTunnel,
                        Tunnel.State.DOWN,
                        null
                    )
                }
                continue
            }

            activatedAny = true
            backend = candidateBackend
            tunnel = candidateTunnel

            if (!waitCancellable(350L, isCancelled)) {
                stopLocked()
                return@synchronized StartResult(
                    success = false,
                    activated = true,
                    mtu = candidate.mtu,
                    errorCategory = "cancelled",
                    detail = "Connection start was cancelled"
                )
            }

            val proof = TunnelHealthVerifier.measure(
                settings = settings,
                mode = ConnectionMode.VPN,
                attempts = 2,
                budgetMs = 4_500,
                perProbeTimeoutMs = 3_000,
                isCancelled = isCancelled
            )
            val stats = runCatching {
                candidateBackend.getStatistics(candidateTunnel)
            }.getOrNull()
            val rx = stats?.totalRx() ?: 0L
            val tx = stats?.totalTx() ?: 0L

            if (proof.success) {
                val version = runCatching {
                    candidateBackend.version
                }.getOrDefault("")
                Diagnostics.info(
                    "Native WireGuard verified; mtu=${candidate.mtu}, rx=$rx, tx=$tx"
                )
                return@synchronized StartResult(
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
                "proof=${proof.errorCategory.orEmpty()}, rx=$rx, tx=$tx, mtu=${candidate.mtu}"
            Diagnostics.warning(
                "Native WireGuard candidate failed: $lastCategory ($lastDetail)"
            )

            stopLocked()
            if (index < candidates.lastIndex) {
                waitCancellable(650L, isCancelled)
            }
        }

        StartResult(
            success = false,
            activated = activatedAny,
            mtu = lastMtu,
            errorCategory = lastCategory,
            detail = lastDetail
        )
    }

    fun isRunning(): Boolean = synchronized(lock) {
        val currentBackend = backend ?: return@synchronized false
        val currentTunnel = tunnel ?: return@synchronized false
        runCatching {
            currentBackend.getState(currentTunnel) == Tunnel.State.UP
        }.getOrDefault(false)
    }

    fun stop() = synchronized(lock) {
        stopLocked()
    }

    private fun stopLocked() {
        val currentBackend = backend
        val currentTunnel = tunnel
        backend = null
        tunnel = null
        if (currentBackend != null && currentTunnel != null) {
            runCatching {
                currentBackend.setState(
                    currentTunnel,
                    Tunnel.State.DOWN,
                    null
                )
            }.onFailure {
                Diagnostics.warning(
                    "Native WireGuard cleanup failed: ${it.message}"
                )
            }
        }
    }

    private fun waitCancellable(
        delayMs: Long,
        isCancelled: () -> Boolean
    ): Boolean {
        var remaining = delayMs
        while (remaining > 0L) {
            if (Thread.currentThread().isInterrupted || isCancelled()) {
                return false
            }
            val slice = remaining.coerceAtMost(50L)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= slice
        }
        return !Thread.currentThread().isInterrupted && !isCancelled()
    }

    private class ManagedTunnel(
        private val tunnelName: String
    ) : Tunnel {
        override fun getName(): String = tunnelName

        override fun onStateChange(newState: Tunnel.State) = Unit
    }
}
