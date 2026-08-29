package ru.racelab.phone.analysis

import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.core.LapResult
import ru.racelab.phone.core.RaceGeometry
import kotlin.math.abs

data class LapSeriesPoint(
    val progress: Double,
    val distanceM: Double,
    val timeMs: Long,
    val speedKmh: Double,
    val accuracyM: Double?
)

data class DeltaPoint(val progress: Double, val deltaMs: Long)

data class Zone(
    val type: String,
    val progress: Double,
    val distanceM: Double,
    val strengthKmhPerS: Double
)

object LapAnalysis {
    fun series(lap: LapResult): List<LapSeriesPoint> {
        if (lap.trace.isEmpty()) return emptyList()
        val cumulative = DoubleArray(lap.trace.size)
        for (i in 1 until lap.trace.size) {
            cumulative[i] = cumulative[i - 1] + RaceGeometry.distance(lap.trace[i - 1], lap.trace[i])
        }
        val total = cumulative.last().coerceAtLeast(1.0)
        val firstTs = lap.trace.first().ts
        return lap.trace.mapIndexed { i, p ->
            LapSeriesPoint(
                progress = cumulative[i] / total,
                distanceM = cumulative[i],
                timeMs = (p.ts - firstTs).coerceAtLeast(0),
                speedKmh = (p.speedMps ?: 0.0) * 3.6,
                accuracyM = p.accuracyM
            )
        }
    }

    fun delta(reference: LapResult, compare: LapResult, steps: Int = 100): List<DeltaPoint> {
        val a = series(reference)
        val b = series(compare)
        if (a.size < 2 || b.size < 2) return emptyList()
        return (0..steps).map { idx ->
            val p = idx.toDouble() / steps
            DeltaPoint(p, interpolateTime(b, p) - interpolateTime(a, p))
        }
    }

    fun theoreticalBest(laps: List<LapResult>): Long? {
        if (laps.isEmpty()) return null
        val maxSectors = laps.maxOfOrNull { it.sectorsMs.size } ?: return null
        if (maxSectors == 0) return laps.minOfOrNull { it.timeMs }
        var total = 0L
        for (i in 0 until maxSectors) {
            val best = laps.mapNotNull { it.sectorsMs.getOrNull(i) }.minOrNull() ?: return null
            total += best
        }
        return total
    }

    fun zones(lap: LapResult, thresholdKmhPerS: Double = 4.0): List<Zone> {
        val s = series(lap)
        if (s.size < 3) return emptyList()
        val zones = mutableListOf<Zone>()
        for (i in 1 until s.size) {
            val dt = (s[i].timeMs - s[i - 1].timeMs) / 1000.0
            if (dt <= 0.03) continue
            val accel = (s[i].speedKmh - s[i - 1].speedKmh) / dt
            if (abs(accel) >= thresholdKmhPerS) {
                zones += Zone(
                    type = if (accel < 0) "BRAKE" else "ACCEL",
                    progress = s[i].progress,
                    distanceM = s[i].distanceM,
                    strengthKmhPerS = accel
                )
            }
        }
        return zones.sortedByDescending { abs(it.strengthKmhPerS) }.take(12)
    }

    fun averageAccuracy(lap: LapResult): Double? {
        val values = lap.trace.mapNotNull(GeoPoint::accuracyM)
        return if (values.isEmpty()) null else values.average()
    }

    private fun interpolateTime(series: List<LapSeriesPoint>, progress: Double): Long {
        if (progress <= 0.0) return series.first().timeMs
        if (progress >= 1.0) return series.last().timeMs
        val hi = series.indexOfFirst { it.progress >= progress }.coerceAtLeast(1)
        val a = series[hi - 1]
        val b = series[hi]
        val span = (b.progress - a.progress).coerceAtLeast(1e-9)
        val t = (progress - a.progress) / span
        return (a.timeMs + (b.timeMs - a.timeMs) * t).toLong()
    }
}
