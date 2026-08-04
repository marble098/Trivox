package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.RealDelayProfile

internal data class RealDelayPolicy(
    val groupSize: Int,
    val workers: Int,
    val startGraceMs: Int,
    val probeTimeoutMs: Int,
    val targets: List<VerifiedHttpProbe.Target>,
    val requiredProofs: Int
) {
    companion object {
        fun from(settings: AppSettings): RealDelayPolicy {
            val allTargets = listOf(VerifiedHttpProbe.strongTraceTarget) +
                VerifiedHttpProbe.fallback204Targets
            return when (settings.realDelayProfile) {
                RealDelayProfile.TURBO -> RealDelayPolicy(
                    groupSize = 12,
                    workers = 4,
                    startGraceMs = 55,
                    probeTimeoutMs = 2_600,
                    targets = allTargets.take(1),
                    requiredProofs = 1
                )
                RealDelayProfile.BALANCED -> RealDelayPolicy(
                    groupSize = 8,
                    workers = 3,
                    startGraceMs = 80,
                    probeTimeoutMs = 3_600,
                    targets = allTargets.take(2),
                    requiredProofs = 2
                )
                RealDelayProfile.ACCURATE -> RealDelayPolicy(
                    groupSize = 6,
                    workers = 2,
                    startGraceMs = 130,
                    probeTimeoutMs = 5_000,
                    targets = allTargets.take(3),
                    requiredProofs = 2
                )
                RealDelayProfile.CUSTOM -> {
                    val count = settings.realDelayTargetCount.coerceIn(1, allTargets.size)
                    RealDelayPolicy(
                        groupSize = settings.realDelayGroupSize.coerceIn(2, 16),
                        workers = settings.realDelayWorkers.coerceIn(1, 6),
                        startGraceMs = settings.realDelayStartGraceMs.coerceIn(0, 1_000),
                        probeTimeoutMs = settings.realDelayProbeTimeoutMs.coerceIn(1_500, 10_000),
                        targets = allTargets.take(count),
                        requiredProofs = settings.realDelayRequiredProofs.coerceIn(1, count)
                    )
                }
            }
        }
    }
}
