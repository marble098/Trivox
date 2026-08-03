package com.trivox.client.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PingStatisticsTest {
    @Test
    fun usesMedianAndRequiresMajority() {
        val result =
            PingStatistics.summarize(
                successfulNanos =
                    listOf(
                        50_000_000L,
                        52_000_000L,
                        2_000_000_000L
                    ),
                attempts = 5
            )

        assertTrue(result.success)
        assertEquals(52L, result.latencyMs)
        assertEquals(3, result.successfulSamples)
        assertEquals(3, result.requiredSamples)
    }

    @Test
    fun rejectsSingleLuckySample() {
        val result =
            PingStatistics.summarize(
                successfulNanos =
                    listOf(2_000_000L),
                attempts = 5
            )

        assertFalse(result.success)
        assertNull(result.latencyMs)
        assertEquals(0.2, result.successRatio, 0.0001)
    }

    @Test
    fun roundsSubMillisecondSamplesUp() {
        assertEquals(
            1L,
            PingStatistics.nanosToDisplayMs(
                100_000L
            )
        )
    }

    @Test
    fun computesRobustMedianJitter() {
        val result =
            PingStatistics.summarize(
                successfulNanos =
                    listOf(
                        40_000_000L,
                        43_000_000L,
                        47_000_000L
                    ),
                attempts = 3
            )

        assertTrue(result.success)
        assertEquals(43L, result.latencyMs)
        assertEquals(3L, result.jitterMs)
    }
}
