package com.trivox.client.network

// TRIVOX_P2_TURBO_RESCUE
// TRIVOX_EXTREME_REAL_ALL_V38

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
                startGraceMs = maxOf(startGraceMs, 180)
            )
            total >= 64 -> copy(
                groupSize = minOf(groupSize, 8),
                workers = minOf(workers, 3),
                startGraceMs = maxOf(startGraceMs, 160)
            )
            else -> this
        }
    }

    companion object {
        fun from(settings: AppSettings): RealDelayPolicy {
            val userTarget = VerifiedHttpProbe.targetForUserUrl(settings.testUrl)
            val allTargets = buildList {
                userTarget?.let(::add)
                addAll(VerifiedHttpProbe.fallback204Targets)
                addAll(VerifiedHttpProbe.dnsFreeTraceTargets)
            }.distinctBy { it.url }

            /*
             * Turbo keeps the configured URL as its fast first proof for
             * compatibility, but its failure-only rescue starts with an
             * IP-literal Cloudflare trace target. That makes the rescue
             * independent from proxy-side DNS, which is a common reason a
             * perfectly healthy config looks dead in a large Real All batch.
             */
            val turboRescueTargets = buildList {
                add(VerifiedHttpProbe.strongTraceTarget)
                addAll(VerifiedHttpProbe.dnsFreeTraceTargets.drop(1))
                userTarget?.let(::add)
                addAll(VerifiedHttpProbe.fallback204Targets)
            }
                .distinctBy { it.url }
                .filterNot { candidate ->
                    candidate.url == allTargets.firstOrNull()?.url
                }

            return when (settings.realDelayProfile) {
                RealDelayProfile.TURBO -> RealDelayPolicy(
                    groupSize = 10,
                    workers = 3,
                    startGraceMs = 220,
                    probeTimeoutMs = 3_400,
                    targets = allTargets.take(1),
                    requiredProofs = 1,
                    rescueTargets = turboRescueTargets.take(4),
                    rescueProbeTimeoutMs = 4_800
                )
                RealDelayProfile.BALANCED -> RealDelayPolicy(
                    groupSize = 8,
                    workers = 3,
                    startGraceMs = 120,
                    probeTimeoutMs = 3_900,
                    targets = allTargets.take(2),
                    requiredProofs = 2,
                    rescueTargets = buildList {
                        add(VerifiedHttpProbe.strongTraceTarget)
                        addAll(allTargets.drop(2))
                    }.distinctBy { it.url }.take(2),
                    rescueProbeTimeoutMs = 4_600
                )
                RealDelayProfile.ACCURATE -> RealDelayPolicy(
                    groupSize = 6,
                    workers = 2,
                    startGraceMs = 180,
                    probeTimeoutMs = 5_400,
                    targets = allTargets.take(3),
                    requiredProofs = 2,
                    rescueTargets = buildList {
                        add(VerifiedHttpProbe.strongTraceTarget)
                        addAll(allTargets.drop(3))
                    }.distinctBy { it.url }.take(2),
                    rescueProbeTimeoutMs = 6_000
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
                        rescueTargets = emptyList(),
                        rescueProbeTimeoutMs = settings.realDelayProbeTimeoutMs.coerceIn(1_500, 10_000)
                    )
                }
            }
        }
    }
}
