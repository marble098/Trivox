package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.RealDelayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDelayPolicyRootFixV2Test {
    @Test
    fun turboUsesDnsFreePrimaryAndFailureOnlyRescueTargets() {
        val policy =
            RealDelayPolicy.from(
                AppSettings(
                    realDelayProfile =
                        RealDelayProfile.TURBO,
                    testUrl =
                        "https://example.com/generate_204"
                )
            )

        assertEquals(
            VerifiedHttpProbe.strongTraceTarget.url,
            policy.targets.first().url
        )

        assertEquals(
            1,
            policy.requiredProofs
        )

        assertTrue(
            policy.rescueTargets.size >= 2
        )

        assertTrue(
            policy.rescueProbeTimeoutMs >
                policy.probeTimeoutMs
        )
    }
}
