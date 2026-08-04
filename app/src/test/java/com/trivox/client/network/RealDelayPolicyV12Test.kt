package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.RealDelayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDelayPolicyV12Test {
    @Test
    fun turboUsesOneStrongProofAndHigherBoundedParallelism() {
        val policy = RealDelayPolicy.from(
            AppSettings(realDelayProfile = RealDelayProfile.TURBO)
        )
        assertEquals(1, policy.requiredProofs)
        assertEquals(1, policy.targets.size)
        assertTrue(policy.workers in 1..4)
        assertTrue(policy.groupSize <= 12)
    }

    @Test
    fun customValuesAreClampedAndProofRequirementCannotExceedTargets() {
        val policy = RealDelayPolicy.from(
            AppSettings(
                realDelayProfile = RealDelayProfile.CUSTOM,
                realDelayGroupSize = 100,
                realDelayWorkers = 100,
                realDelayProbeTimeoutMs = 100,
                realDelayStartGraceMs = 5000,
                realDelayTargetCount = 2,
                realDelayRequiredProofs = 4
            )
        )
        assertEquals(16, policy.groupSize)
        assertEquals(6, policy.workers)
        assertEquals(1500, policy.probeTimeoutMs)
        assertEquals(1000, policy.startGraceMs)
        assertEquals(2, policy.targets.size)
        assertEquals(2, policy.requiredProofs)
    }
}
