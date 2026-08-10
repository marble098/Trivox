package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Adaptive Connection Intelligence.
 *
 * Local deterministic learning only: no cloud model, telemetry, credentials,
 * or config material leave the device.
 */
internal object SmartConnectionOptimizer {
    data class VerificationPlan(
        val attempts: Int,
        val budgetMs: Int,
        val perProbeTimeoutMs: Int,
        val initialDelayMs: Long,
        val strategy: String
    )

    private data class OutcomeMemory(
        val successStreak: Int = 0,
        val failureStreak: Int = 0,
        val lastCategory: String? = null,
        val updatedAt: Long = 0L
    )

    private const val MAX_MEMORY_ENTRIES = 128
    private const val MEMORY_TTL_MS = 30L * 60L * 1_000L

    private val memoryLock = Any()
    private val outcomeMemory = LinkedHashMap<String, OutcomeMemory>(
        MAX_MEMORY_ENTRIES,
        0.75f,
        true
    )

    fun verificationPlan(
        profile: ConfigProfile,
        settings: AppSettings,
        mode: ConnectionMode
    ): VerificationPlan {
        val protocol = profile.protocol.lowercase(Locale.ROOT)
        val stream = runCatching { JSONObject(profile.outboundJson) }
            .getOrNull()
            ?.optJSONObject("streamSettings")
        val network = stream?.optString("network", "tcp")
            ?.lowercase(Locale.ROOT)
            ?: "tcp"
        val security = stream?.optString("security", "none")
            ?.lowercase(Locale.ROOT)
            ?: "none"

        val base = if (!settings.smartConnectionOptimizer) {
            val wireGuard = protocol == "wireguard"
            val adaptive = settings.adaptiveHandshake
            VerificationPlan(
                attempts = if (adaptive) 3 else 2,
                budgetMs = if (wireGuard) {
                    settings.wireGuardHandshakeTimeoutMs
                } else if (adaptive) {
                    7_000
                } else {
                    11_000
                },
                perProbeTimeoutMs = if (wireGuard) {
                    (settings.wireGuardHandshakeTimeoutMs / 3)
                        .coerceIn(2_000, 7_000)
                } else if (adaptive) {
                    2_800
                } else {
                    4_500
                },
                initialDelayMs = when {
                    wireGuard && adaptive -> 350L
                    wireGuard -> 850L
                    mode == ConnectionMode.VPN && adaptive -> 90L
                    mode == ConnectionMode.VPN -> 260L
                    adaptive -> 40L
                    else -> 120L
                },
                strategy = "manual:$protocol:$network:$security"
            )
        } else {
            when {
                protocol == "wireguard" -> VerificationPlan(
                    attempts = 3,
                    budgetMs = settings.wireGuardHandshakeTimeoutMs
                        .coerceIn(9_000, 24_000),
                    perProbeTimeoutMs = 4_500,
                    initialDelayMs = 450L,
                    strategy = "smart:wireguard"
                )

                network in setOf("hysteria", "mkcp", "kcp") ->
                    VerificationPlan(
                        attempts = 3,
                        budgetMs = 9_500,
                        perProbeTimeoutMs = 3_300,
                        initialDelayMs = 240L,
                        strategy = "smart:udp-first:$network"
                    )

                network in setOf(
                    "ws",
                    "websocket",
                    "xhttp",
                    "splithttp",
                    "httpupgrade",
                    "grpc"
                ) -> VerificationPlan(
                    attempts = 3,
                    budgetMs = 9_000,
                    perProbeTimeoutMs = 3_200,
                    initialDelayMs =
                        if (mode == ConnectionMode.VPN) 180L else 80L,
                    strategy = "smart:http-transport:$network:$security"
                )

                security in setOf("tls", "reality") ->
                    VerificationPlan(
                        attempts = 3,
                        budgetMs = 8_200,
                        perProbeTimeoutMs = 3_000,
                        initialDelayMs =
                            if (mode == ConnectionMode.VPN) 135L else 60L,
                        strategy = "smart:secure-raw:$security"
                    )

                else -> VerificationPlan(
                    attempts = 3,
                    budgetMs = 7_500,
                    perProbeTimeoutMs = 2_800,
                    initialDelayMs =
                        if (mode == ConnectionMode.VPN) 120L else 50L,
                    strategy = "smart:generic:$network"
                )
            }
        }

        if (!settings.smartConnectionOptimizer) {
            return base
        }

        val learned = memoryFor(profile) ?: return base
        val ageMs = System.currentTimeMillis() - learned.updatedAt
        if (ageMs !in 0..MEMORY_TTL_MS) {
            return base
        }

        val failurePressure = learned.failureStreak.coerceIn(0, 3)
        val successConfidence = learned.successStreak.coerceIn(0, 3)
        val transientBonus = when (learned.lastCategory) {
            "connection_reset",
            "websocket_handshake",
            "timeout",
            "sockettimeoutexception",
            "tunnel_timeout" -> 700

            "dns_timeout",
            "dns_failure",
            "dns_pipe_closed",
            "gaiexception",
            "unknownhostexception" -> 1_100

            else -> 0
        }

        return base.copy(
            attempts = (
                base.attempts +
                    if (failurePressure >= 2) 1 else 0
                ).coerceIn(2, 4),
            budgetMs = (
                base.budgetMs +
                    failurePressure * 1_050 +
                    transientBonus -
                    successConfidence * 180
                ).coerceIn(5_500, 24_000),
            perProbeTimeoutMs = (
                base.perProbeTimeoutMs +
                    failurePressure * 300 +
                    transientBonus / 4 -
                    successConfidence * 80
                ).coerceIn(1_800, 7_000),
            initialDelayMs = (
                base.initialDelayMs +
                    failurePressure * 70L +
                    transientBonus / 12L -
                    successConfidence * 8L
                ).coerceIn(30L, 1_200L),
            strategy = buildString {
                append(base.strategy)
                append(":learned:s")
                append(learned.successStreak)
                append(":f")
                append(learned.failureStreak)
                learned.lastCategory
                    ?.takeIf(String::isNotBlank)
                    ?.let {
                        append(':')
                        append(it)
                    }
            }
        )
    }

    fun recordOutcome(
        profile: ConfigProfile,
        success: Boolean,
        errorCategory: String?
    ) {
        val key = profileKey(profile)
        synchronized(memoryLock) {
            val old = outcomeMemory[key] ?: OutcomeMemory()
            outcomeMemory[key] = if (success) {
                OutcomeMemory(
                    successStreak = (old.successStreak + 1).coerceAtMost(6),
                    failureStreak = 0,
                    lastCategory = null,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                OutcomeMemory(
                    successStreak = 0,
                    failureStreak = (old.failureStreak + 1).coerceAtMost(6),
                    lastCategory = errorCategory
                        ?.lowercase(Locale.ROOT)
                        ?.take(64),
                    updatedAt = System.currentTimeMillis()
                )
            }

            while (outcomeMemory.size > MAX_MEMORY_ENTRIES) {
                val eldest = outcomeMemory.entries.iterator()
                if (!eldest.hasNext()) break
                eldest.next()
                eldest.remove()
            }
        }
    }

    internal fun resetForTests() {
        synchronized(memoryLock) {
            outcomeMemory.clear()
        }
    }

    private fun memoryFor(
        profile: ConfigProfile
    ): OutcomeMemory? =
        synchronized(memoryLock) {
            outcomeMemory[profileKey(profile)]
        }

    private fun profileKey(profile: ConfigProfile): String =
        profile.id.takeIf(String::isNotBlank)
            ?: buildString {
                append(profile.protocol.lowercase(Locale.ROOT))
                append('|')
                append(profile.server.lowercase(Locale.ROOT))
                append('|')
                append(profile.port)
            }

}
