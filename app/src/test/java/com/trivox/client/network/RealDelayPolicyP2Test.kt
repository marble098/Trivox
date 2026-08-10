package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.RealDelayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDelayPolicyP2Test {
    @Test
    fun turboKeepsFastProofAndAddsFailureOnlyRescue() {
        val policy = RealDelayPolicy.from(AppSettings(realDelayProfile = RealDelayProfile.TURBO))
        assertEquals(1, policy.targets.size)
        assertEquals(1, policy.requiredProofs)
        assertTrue(policy.startGraceMs >= 80)
        assertTrue(policy.rescueTargets.size >= 2)
        assertTrue(policy.rescueProbeTimeoutMs > policy.probeTimeoutMs)
    }
}
