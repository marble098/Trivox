package com.trivox.client.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PingStatisticsTest {
    @Test
    fun twoOfThreeSamplesMeetTwoThirdsThreshold() {
        val result =
            PingStatistics.summarize(
                successfulNanos =
                    listOf(
                        10_000_000L,
                        12_000_000L
                    ),
                attempts = 3
            )

        assertTrue(result.success)
        assertEquals(2, result.requiredSamples)
        assertEquals(2, result.successfulSamples)
        assertEquals(11L, result.latencyMs)
    }

    @Test
    fun threeOfFiveSamplesDoNotMeetTwoThirdsThreshold() {
        val result =
            PingStatistics.summarize(
                successfulNanos =
                    listOf(
                        10_000_000L,
                        12_000_000L,
                        14_000_000L
                    ),
                attempts = 5
            )

        assertFalse(result.success)
        assertEquals(4, result.requiredSamples)
        assertEquals(null, result.latencyMs)
    }

    @Test
    fun medianAndMadIgnoreOneLargeOutlier() {
        val result =
            PingStatistics.summarize(
                successfulNanos =
                    listOf(
                        10_000_000L,
                        11_000_000L,
                        12_000_000L,
                        900_000_000L
                    ),
                attempts = 4
            )

        assertTrue(result.success)
        assertEquals(12L, result.latencyMs)
        assertEquals(1L, result.jitterMs)
    }
}
