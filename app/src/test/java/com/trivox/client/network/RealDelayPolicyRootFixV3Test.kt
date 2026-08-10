package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.RealDelayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDelayPolicyRootFixV3Test {
    @Test
    fun configuredTargetStaysFirstAndDnsFreeTraceIsImmediateRescue() {
        val requested = "https://example.com/generate_204"
        val policy = RealDelayPolicy.from(
            AppSettings(
                realDelayProfile = RealDelayProfile.TURBO,
                testUrl = requested
            )
        )

        assertEquals(requested, policy.targets.first().url)
        assertEquals(VerifiedHttpProbe.strongTraceTarget.url, policy.rescueTargets.first().url)
        assertEquals(1, policy.requiredProofs)
        assertTrue(policy.rescueTargets.size >= 2)
        assertTrue(policy.rescueProbeTimeoutMs > policy.probeTimeoutMs)
    }

    @Test
    fun customModeKeepsExtraFailureOnlyRescueTargets() {
        val policy = RealDelayPolicy.from(
            AppSettings(
                realDelayProfile = RealDelayProfile.CUSTOM,
                realDelayTargetCount = 1,
                realDelayRequiredProofs = 1
            )
        )

        assertEquals(1, policy.targets.size)
        assertTrue(policy.rescueTargets.isNotEmpty())
    }
}
