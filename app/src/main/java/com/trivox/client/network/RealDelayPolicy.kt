package com.trivox.client.network

// TRIVOX_REAL_DELAY_ROOTFIX_V3
// TRIVOX_P5_FAST_XRAY_RUNTIME

import com.trivox.client.data.AppSettings
import com.trivox.client.data.RealDelayProfile

internal data class RealDelayPolicy(
    val groupSize: Int,
    val workers: Int,
    val startGraceMs: Int,
    val probeTimeoutMs: Int,
    val targets: List<VerifiedHttpProbe.Target>,
    val requiredProofs: Int,
    val rescueTargets: List<VerifiedHttpProbe.Target> = emptyList(),
    val rescueProbeTimeoutMs: Int = probeTimeoutMs
) {
    fun forBatch(totalProfiles: Int): RealDelayPolicy {
        val total = totalProfiles.coerceAtLeast(0)
        return when {
            total >= 160 -> copy(
                groupSize = minOf(groupSize, 6),
                workers = minOf(workers, 2),
                startGraceMs = maxOf(startGraceMs, 120)
            )

            total >= 64 -> copy(
                groupSize = minOf(groupSize, 8),
                workers = minOf(workers, 3),
                startGraceMs = maxOf(startGraceMs, 100)
            )

            else -> this
        }
    }

    companion object {
        fun from(settings: AppSettings): RealDelayPolicy {
            /*
             * Respect the configured verified URL first. If DNS or that public
             * endpoint is unavailable, immediately rescue with Cloudflare's
             * DNS-free trace before spending time on additional hostname probes.
             */
            val allTargets = buildList {
                VerifiedHttpProbe
                    .targetForUserUrl(settings.testUrl)
                    ?.let(::add)
                add(VerifiedHttpProbe.strongTraceTarget)
                addAll(VerifiedHttpProbe.fallback204Targets)
                addAll(VerifiedHttpProbe.dnsFreeTraceTargets.drop(1))
            }.distinctBy { it.url }

            return when (settings.realDelayProfile) {
                RealDelayProfile.TURBO ->
                    RealDelayPolicy(
                        groupSize = 12,
                        workers = 4,
                        startGraceMs = 100,
                        probeTimeoutMs = 2_800,
                        targets = allTargets.take(1),
                        requiredProofs = 1,
                        rescueTargets = allTargets.drop(1).take(3),
                        rescueProbeTimeoutMs = 3_600
                    )

                RealDelayProfile.BALANCED ->
                    RealDelayPolicy(
                        groupSize = 8,
                        workers = 3,
                        startGraceMs = 120,
                        probeTimeoutMs = 3_800,
                        targets = allTargets.take(2),
                        requiredProofs = 2,
                        rescueTargets = allTargets.drop(2).take(2),
                        rescueProbeTimeoutMs = 4_400
                    )

                RealDelayProfile.ACCURATE ->
                    RealDelayPolicy(
                        groupSize = 6,
                        workers = 2,
                        startGraceMs = 160,
                        probeTimeoutMs = 5_200,
                        targets = allTargets.take(3),
                        requiredProofs = 2,
                        rescueTargets = allTargets.drop(3).take(2),
                        rescueProbeTimeoutMs = 5_800
                    )

                RealDelayProfile.CUSTOM -> {
                    val count = settings.realDelayTargetCount.coerceIn(1, allTargets.size)
                    RealDelayPolicy(
                        groupSize = settings.realDelayGroupSize.coerceIn(2, 16),
                        workers = settings.realDelayWorkers.coerceIn(1, 6),
                        startGraceMs = settings.realDelayStartGraceMs.coerceIn(0, 1_000),
                        probeTimeoutMs = settings.realDelayProbeTimeoutMs.coerceIn(1_500, 10_000),
                        targets = allTargets.take(count),
                        requiredProofs = settings.realDelayRequiredProofs.coerceIn(1, count),
                        rescueTargets = allTargets.drop(count).take(2),
                        rescueProbeTimeoutMs = settings.realDelayProbeTimeoutMs
                            .coerceIn(1_500, 10_000)
                    )
                }
            }
        }
    }
}
