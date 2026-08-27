package ru.racelab.phone.core

import kotlin.math.*

private const val EARTH_R = 6_371_000.0

data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val ts: Long,
    val speedMps: Double? = null,
    val headingDeg: Double? = null,
    val accuracyM: Double? = null,
    val altitudeM: Double? = null,
    val source: String = "phone"
)

data class RaceLine(
    val aLat: Double,
    val aLon: Double,
    val bLat: Double,
    val bLon: Double,
    val centerLat: Double,
    val centerLon: Double,
    val forwardX: Double,
    val forwardY: Double
)

data class LapResult(
    val no: Int,
    val timeMs: Long,
    val sectorsMs: List<Long>,
    val maxSpeedKmh: Double,
    val distanceM: Double,
    val trace: List<GeoPoint>
)

data class RaceUpdate(
    val lapCompleted: LapResult? = null,
    val sectorCompleted: Pair<Int, Long>? = null,
    val lapStarted: Boolean = false
)

data class Prediction(
    val elapsedMs: Long,
    val deltaMs: Long,
    val projectedMs: Long,
    val progress: Double,
    val referenceDistanceM: Double
)

object RaceGeometry {
    private fun rad(v: Double) = Math.toRadians(v)

    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val la1 = rad(a.lat)
        val la2 = rad(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(la1) * cos(la2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun xy(lat: Double, lon: Double, oLat: Double, oLon: Double): Pair<Double, Double> {
        val x = rad(lon - oLon) * cos(rad(oLat)) * EARTH_R
        val y = rad(lat - oLat) * EARTH_R
        return x to y
    }

    fun lineAt(point: GeoPoint, previous: GeoPoint, widthM: Double = 35.0): RaceLine {
        val (dx0, dy0) = xy(point.lat, point.lon, previous.lat, previous.lon)
        val n0 = hypot(dx0, dy0).coerceAtLeast(1e-9)
        val dirX = dx0 / n0
        val dirY = dy0 / n0
        val nx = -dirY
        val ny = dirX
        val half = widthM / 2.0

        fun metersToLat(m: Double) = m / EARTH_R * 180.0 / Math.PI
        fun metersToLon(m: Double) = m / (EARTH_R * cos(rad(point.lat))) * 180.0 / Math.PI

        return RaceLine(
            aLat = point.lat + metersToLat(ny * half),
            aLon = point.lon + metersToLon(nx * half),
            bLat = point.lat - metersToLat(ny * half),
            bLon = point.lon - metersToLon(nx * half),
            centerLat = point.lat,
            centerLon = point.lon,
            forwardX = dirX,
            forwardY = dirY
        )
    }

    fun crossing(prev: GeoPoint, cur: GeoPoint, line: RaceLine): Long? {
        val (ax, ay) = xy(line.aLat, line.aLon, line.centerLat, line.centerLon)
        val (bx, by) = xy(line.bLat, line.bLon, line.centerLat, line.centerLon)
        val (cx, cy) = xy(prev.lat, prev.lon, line.centerLat, line.centerLon)
        val (dx, dy) = xy(cur.lat, cur.lon, line.centerLat, line.centerLon)

        val rx = dx - cx
        val ry = dy - cy
        val sx = bx - ax
        val sy = by - ay
        val den = rx * sy - ry * sx
        if (abs(den) < 1e-9) return null

        val qx = ax - cx
        val qy = ay - cy
        val t = (qx * sy - qy * sx) / den
        val u = (qx * ry - qy * rx) / den
        if (t !in 0.0..1.0 || u !in 0.0..1.0) return null
        if (rx * line.forwardX + ry * line.forwardY <= 0.0) return null

        return (prev.ts + t * (cur.ts - prev.ts)).roundToLong()
    }
}

class RaceEngine {
    var startLine: RaceLine? = null
        private set
    val sectors = mutableListOf<RaceLine>()
    val laps = mutableListOf<LapResult>()

    var currentLapStartMs: Long? = null
        private set
    var bestLapMs: Long? = null
        private set
    var bestLapTrace: List<Pair<GeoPoint, Long>>? = null
        private set

    private var nextSector = 0
    private var currentSectorStartMs: Long? = null
    private val currentSectorTimes = mutableListOf<Long>()
    private val lapTrace = mutableListOf<GeoPoint>()

    fun resetSession(keepTrack: Boolean = true) {
        laps.clear()
        currentLapStartMs = null
        bestLapMs = null
        bestLapTrace = null
        nextSector = 0
        currentSectorStartMs = null
        currentSectorTimes.clear()
        lapTrace.clear()
        if (!keepTrack) {
            startLine = null
            sectors.clear()
        }
    }

    fun setStart(line: RaceLine) {
        startLine = line
        currentLapStartMs = null
        nextSector = 0
        currentSectorStartMs = null
        currentSectorTimes.clear()
        lapTrace.clear()
    }

    fun addSector(line: RaceLine): Boolean {
        if (sectors.size >= 3) return false
        sectors += line
        return true
    }

    fun clearSectors() = sectors.clear()

    fun onPoint(prev: GeoPoint?, point: GeoPoint): RaceUpdate {
        if (currentLapStartMs != null) lapTrace += point
        val start = startLine ?: return RaceUpdate()
        if (prev == null) return RaceUpdate()

        if (currentLapStartMs == null) {
            val hit = RaceGeometry.crossing(prev, point, start) ?: return RaceUpdate()
            currentLapStartMs = hit
            currentSectorStartMs = hit
            nextSector = 0
            currentSectorTimes.clear()
            lapTrace.clear()
            lapTrace += point
            return RaceUpdate(lapStarted = true)
        }

        if (nextSector < sectors.size) {
            val hit = RaceGeometry.crossing(prev, point, sectors[nextSector]) ?: return RaceUpdate()
            val sectorStart = currentSectorStartMs ?: hit
            val split = hit - sectorStart
            currentSectorTimes += split
            currentSectorStartMs = hit
            nextSector++
            return RaceUpdate(sectorCompleted = nextSector to split)
        }

        val hit = RaceGeometry.crossing(prev, point, start) ?: return RaceUpdate()
        val lapStart = currentLapStartMs ?: return RaceUpdate()
        if (hit - lapStart < 5_000) return RaceUpdate()

        val sectorStart = currentSectorStartMs ?: lapStart
        val splits = currentSectorTimes.toMutableList().apply { add(hit - sectorStart) }
        val trace = lapTrace.toList()
        val lapMs = hit - lapStart
        val lap = LapResult(
            no = laps.size + 1,
            timeMs = lapMs,
            sectorsMs = splits,
            maxSpeedKmh = trace.maxOfOrNull { (it.speedMps ?: 0.0) * 3.6 } ?: 0.0,
            distanceM = trace.zipWithNext().sumOf { (a, b) -> RaceGeometry.distance(a, b) },
            trace = trace
        )
        laps += lap

        if (bestLapMs == null || lapMs < bestLapMs!!) {
            bestLapMs = lapMs
            bestLapTrace = trace.map { it to (it.ts - lapStart) }
        }

        currentLapStartMs = hit
        currentSectorStartMs = hit
        nextSector = 0
        currentSectorTimes.clear()
        lapTrace.clear()
        lapTrace += point

        return RaceUpdate(lapCompleted = lap)
    }

    fun prediction(nowMs: Long, current: GeoPoint?): Prediction? {
        val lapStart = currentLapStartMs ?: return null
        val best = bestLapMs ?: return null
        val trace = bestLapTrace ?: return null
        if (trace.isEmpty() || current == null) return null

        var bestDistance = Double.MAX_VALUE
        var referenceRel = 0L
        var index = 0
        trace.forEachIndexed { i, (p, rel) ->
            val d = RaceGeometry.distance(current, p)
            if (d < bestDistance) {
                bestDistance = d
                referenceRel = rel
                index = i
            }
        }
        if (bestDistance > 80.0) return null

        val elapsed = (nowMs - lapStart).coerceAtLeast(0)
        val delta = elapsed - referenceRel
        val projected = elapsed + (best - referenceRel).coerceAtLeast(0)
        val progress = if (trace.size > 1) index.toDouble() / (trace.size - 1) else 0.0
        return Prediction(elapsed, delta, projected, progress, bestDistance)
    }
}
