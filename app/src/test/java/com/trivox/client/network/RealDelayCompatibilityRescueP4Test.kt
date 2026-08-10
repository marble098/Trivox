package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.RealDelayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDelayCompatibilityRescueP4Test {
    @Test
    fun configuredVerifiedUrlIsTriedBeforeIpOnlyTrace() {
        val requested = "https://example.com/generate_204"
        val policy = RealDelayPolicy.from(
            AppSettings(
                realDelayProfile = RealDelayProfile.TURBO,
                testUrl = requested
            )
        )

        assertEquals(requested, policy.targets.first().url)
        assertTrue(
            (policy.targets + policy.rescueTargets)
                .map { it.url }
                .distinct()
                .size >= 3
        )
    }
}
