package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.RealDelayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDelayPolicyV22Test {
    @Test
    fun hugeBatchesReduceNativePressureWithoutWeakeningProofs() {
        val base =
            RealDelayPolicy.from(
                AppSettings(
                    realDelayProfile =
                        RealDelayProfile.TURBO
                )
            )
        val bounded =
            base.forBatch(224)

        assertTrue(bounded.groupSize <= 6)
        assertTrue(bounded.workers <= 2)
        assertEquals(
            base.requiredProofs,
            bounded.requiredProofs
        )
        assertEquals(
            base.targets,
            bounded.targets
        )
    }

    @Test
    fun smallBatchesKeepSelectedPolicy() {
        val base =
            RealDelayPolicy.from(
                AppSettings(
                    realDelayProfile =
                        RealDelayProfile.BALANCED
                )
            )
        assertEquals(
            base,
            base.forBatch(17)
        )
    }
}
