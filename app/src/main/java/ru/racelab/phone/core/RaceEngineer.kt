package ru.racelab.phone.core

import kotlin.math.roundToLong

data class RaceEngineerMetrics(
    val sampleCount: Int = 0,
    val averageLapMs: Long? = null,
    val degradationMsPerLap: Double? = null,
    val nextLapForecastMs: Long? = null,
    val paceLossMs: Long? = null,
    val pitWindow: String = "DATA"
)

object RaceEngineer {
    private const val MAX_ANALYSIS_LAPS = 6

    fun analyze(
        lapTimesMs: List<Long>,
        bestLapMs: Long?,
        pitActive: Boolean
    ): RaceEngineerMetrics {
        val recent = lapTimesMs
            .filter { it >= 5_000L }
            .takeLast(MAX_ANALYSIS_LAPS)

        if (pitActive) {
            return RaceEngineerMetrics(
                sampleCount = recent.size,
                averageLapMs = recent.averageOrNull(),
                degradationMsPerLap = trendSlope(recent),
                nextLapForecastMs = forecast(recent),
                paceLossMs = paceLoss(recent, bestLapMs),
                pitWindow = "NOW"
            )
        }

        if (recent.isEmpty()) return RaceEngineerMetrics()

        val average = recent.averageOrNull()
        val slope = trendSlope(recent)
        val next = forecast(recent)
        val loss = paceLoss(recent, bestLapMs)

        val window = when {
            recent.size < 3 -> "DATA"
            (slope ?: 0.0) >= 750.0 || (loss ?: 0L) >= 2_500L -> "NOW"
            (slope ?: 0.0) >= 450.0 || (loss ?: 0L) >= 1_800L -> "1 LAP"
            (slope ?: 0.0) >= 250.0 || (loss ?: 0L) >= 1_200L -> "2-3 LAPS"
            (slope ?: 0.0) >= 120.0 || (loss ?: 0L) >= 700L -> "3-5 LAPS"
            else -> "HOLD"
        }

        return RaceEngineerMetrics(
            sampleCount = recent.size,
            averageLapMs = average,
            degradationMsPerLap = slope,
            nextLapForecastMs = next,
            paceLossMs = loss,
            pitWindow = window
        )
    }

    private fun List<Long>.averageOrNull(): Long? =
        takeIf { it.isNotEmpty() }?.map(Long::toDouble)?.average()?.roundToLong()

    private fun trendSlope(values: List<Long>): Double? {
        if (values.size < 3) return null
        val meanX = (values.size - 1) / 2.0
        val meanY = values.map(Long::toDouble).average()
        var numerator = 0.0
        var denominator = 0.0
        values.forEachIndexed { index, value ->
            val dx = index - meanX
            numerator += dx * (value - meanY)
            denominator += dx * dx
        }
        return if (denominator > 0.0) numerator / denominator else null
    }

    private fun forecast(values: List<Long>): Long? {
        if (values.size < 2) return null
        val last = values.last().toDouble()
        val rawTrend = trendSlope(values)
            ?: (values.last() - values[values.lastIndex - 1]).toDouble()
        val boundedTrend = rawTrend.coerceIn(-3_000.0, 5_000.0)
        return (last + boundedTrend).roundToLong().coerceAtLeast(5_000L)
    }

    private fun paceLoss(values: List<Long>, bestLapMs: Long?): Long? {
        val best = bestLapMs ?: return null
        val average = values.takeLast(3).averageOrNull() ?: return null
        return (average - best).coerceAtLeast(0L)
    }
}
