package com.trivox.client.network

import kotlin.math.abs
import kotlin.math.ceil

internal data class PingSummary(
    val latencyMs: Long?,
    val jitterMs: Long?,
    val successRatio: Double,
    val successfulSamples: Int,
    val requiredSamples: Int,
    val success: Boolean
)

internal object PingStatistics {
    fun summarize(
        successfulNanos: List<Long>,
        attempts: Int
    ): PingSummary {
        val total =
            attempts.coerceAtLeast(1)
        val samples =
            successfulNanos
                .filter { it > 0L }
                .sorted()
        /*
         * Integer ceiling of 2/3. Using ceil(total * 0.67) accidentally
         * required 3/3 successes because 3 * 0.67 = 2.01.
         */
        val required =
            if (total == 1) {
                1
            } else {
                (
                    total * REQUIRED_SUCCESS_NUMERATOR +
                        REQUIRED_SUCCESS_DENOMINATOR -
                        1
                    ) /
                    REQUIRED_SUCCESS_DENOMINATOR
            }.coerceIn(
                1,
                total
            )
        val ratio =
            (
                samples.size.toDouble() /
                    total.toDouble()
                ).coerceIn(0.0, 1.0)
        val successful =
            samples.size >= required

        if (samples.isEmpty()) {
            return PingSummary(
                latencyMs = null,
                jitterMs = null,
                successRatio = ratio,
                successfulSamples = 0,
                requiredSamples = required,
                success = false
            )
        }

        val center =
            median(samples)
        val deviations =
            if (samples.size > 1) {
                samples
                    .map {
                        abs(it - center)
                    }
                    .sorted()
            } else {
                emptyList()
            }

        return PingSummary(
            latencyMs =
                if (successful) {
                    nanosToDisplayMs(center)
                } else {
                    null
                },
            jitterMs =
                deviations
                    .takeIf {
                        successful &&
                            it.isNotEmpty()
                    }
                    ?.let {
                        nanosToDisplayMs(
                            median(it)
                        )
                    },
            successRatio = ratio,
            successfulSamples = samples.size,
            requiredSamples = required,
            success = successful
        )
    }

    internal fun median(
        sortedValues: List<Long>
    ): Long {
        require(sortedValues.isNotEmpty()) {
            "Median requires samples"
        }

        val middle =
            sortedValues.size / 2

        return if (
            sortedValues.size % 2 == 1
        ) {
            sortedValues[middle]
        } else {
            val lower =
                sortedValues[middle - 1]
            val upper =
                sortedValues[middle]

            lower +
                (upper - lower) / 2L
        }
    }

    internal fun nanosToDisplayMs(
        nanos: Long
    ): Long =
        ceil(
            nanos
                .coerceAtLeast(1L)
                .toDouble() /
                NANOS_PER_MILLISECOND
        )
            .toLong()
            .coerceAtLeast(1L)

    private const val REQUIRED_SUCCESS_NUMERATOR = 2
    private const val REQUIRED_SUCCESS_DENOMINATOR = 3
    private const val NANOS_PER_MILLISECOND =
        1_000_000.0
}
