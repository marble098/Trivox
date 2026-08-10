package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.RealDelayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDelayExtremeV38Test {
    @Test
    fun turboKeepsConfiguredFastTargetButRescuesThroughDnsFreeTrace() {
        val requested = "https://example.com/generate_204"
        val policy = RealDelayPolicy.from(
            AppSettings(
                realDelayProfile = RealDelayProfile.TURBO,
                testUrl = requested
            )
        )

        assertEquals(requested, policy.targets.first().url)
        assertEquals(1, policy.requiredProofs)
        assertTrue(policy.startGraceMs >= 180)
        assertTrue(policy.probeTimeoutMs >= 3_000)
        assertTrue(policy.rescueProbeTimeoutMs > policy.probeTimeoutMs)
        assertTrue(
            policy.rescueTargets.any {
                it.url == VerifiedHttpProbe.strongTraceTarget.url
            }
        )
    }
}
