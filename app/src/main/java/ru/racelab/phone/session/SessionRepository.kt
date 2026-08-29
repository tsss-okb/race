package ru.racelab.phone.session

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.core.LapResult

data class SessionLapSummary(
    val no: Int,
    val timeMs: Long,
    val maxSpeedKmh: Double,
    val distanceM: Double,
    val sectorsMs: List<Long>
)

data class VideoRef(val name: String, val uri: String)

data class SessionSummary(
    val id: String,
    val directory: File,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val trackName: String?,
    val gpsSource: String?,
    val laps: List<SessionLapSummary>,
    val bestLapMs: Long?,
    val maxSpeedKmh: Double,
    val hasGps: Boolean,
    val hasSensors: Boolean,
    val hasObdCustom: Boolean,
    val hasCan: Boolean,
    val videoRefs: List<VideoRef>,
    val sizeBytes: Long
)

data class GpsCsvRow(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val heading: Double?,
    val accuracy: Double?,
    val altitude: Double?,
    val source: String?,
    val lapNo: Int?
)

object SessionRepository {
    fun root(context: Context): File =
        File(context.applicationContext.getExternalFilesDir(null), "RaceLab/sessions").apply { mkdirs() }

    fun list(context: Context): List<SessionSummary> =
        root(context).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { parse(it) }
            ?.sortedByDescending { it.startedAtMs }
            ?: emptyList()

    fun parse(directory: File): SessionSummary? {
        if (!directory.isDirectory) return null
        val metaFile = File(directory, "meta.json")
        val meta = runCatching {
            if (metaFile.exists()) JSONObject(metaFile.readText()) else JSONObject()
        }.getOrDefault(JSONObject())

        val laps = buildList {
            val arr = meta.optJSONArray("laps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sectors = buildList {
                    val s = o.optJSONArray("sectorsMs") ?: JSONArray()
                    for (j in 0 until s.length()) add(s.optLong(j))
                }
                add(
                    SessionLapSummary(
                        no = o.optInt("no", i + 1),
                        timeMs = o.optLong("timeMs", 0L),
                        maxSpeedKmh = o.optDouble("maxSpeedKmh", 0.0),
                        distanceM = o.optDouble("distanceM", 0.0),
                        sectorsMs = sectors
                    )
                )
            }
        }

        val id = meta.optString("sessionId").ifBlank { directory.name }
        val started = meta.optLong("startedAt", inferStart(directory, id))
        val ended = meta.optLong("endedAt", directory.lastModified())
        val videos = readVideos(directory)
        return SessionSummary(
            id = id,
            directory = directory,
            startedAtMs = started,
            endedAtMs = ended,
            trackName = meta.optString("trackName").takeIf { it.isNotBlank() && it != "null" },
            gpsSource = meta.optString("gpsSource").takeIf { it.isNotBlank() },
            laps = laps,
            bestLapMs = laps.filter { it.timeMs > 0 }.minOfOrNull { it.timeMs },
            maxSpeedKmh = laps.maxOfOrNull { it.maxSpeedKmh } ?: 0.0,
            hasGps = File(directory, "gps.csv").exists(),
            hasSensors = File(directory, "sensors.csv").let { it.exists() && it.length() > 80 },
            hasObdCustom = File(directory, "obd_custom.csv").let { it.exists() && it.length() > 25 },
            hasCan = File(directory, "can.csv").let { it.exists() && it.length() > 45 },
            videoRefs = videos,
            sizeBytes = directorySize(directory)
        )
    }

    fun readGps(session: SessionSummary): List<GpsCsvRow> {
        val file = File(session.directory, "gps.csv")
        if (!file.exists()) return emptyList()
        return runCatching {
            file.bufferedReader().use { reader ->
                val header = reader.readLine()?.split(',') ?: return@use emptyList()
                val index = header.mapIndexed { i, name -> name.trim() to i }.toMap()
                fun cell(parts: List<String>, name: String): String? =
                    index[name]?.let { parts.getOrNull(it) }?.takeIf { it.isNotBlank() }

                buildList {
                    reader.lineSequence().forEach { line ->
                        val p = line.split(',')
                        val ts = cell(p, "ts")?.toLongOrNull() ?: return@forEach
                        val lat = cell(p, "lat")?.toDoubleOrNull() ?: return@forEach
                        val lon = cell(p, "lon")?.toDoubleOrNull() ?: return@forEach
                        add(
                            GpsCsvRow(
                                ts = ts,
                                lat = lat,
                                lon = lon,
                                speedKmh = cell(p, "speed_kmh")?.toDoubleOrNull() ?: 0.0,
                                heading = cell(p, "heading")?.toDoubleOrNull(),
                                accuracy = cell(p, "accuracy")?.toDoubleOrNull(),
                                altitude = cell(p, "altitude")?.toDoubleOrNull(),
                                source = cell(p, "source"),
                                lapNo = cell(p, "lap_no")?.toIntOrNull()
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun loadLapResults(session: SessionSummary): List<LapResult> {
        val rows = readGps(session)
        if (rows.none { it.lapNo != null }) return emptyList()
        return session.laps.mapNotNull { lap ->
            val traceRows = rows.filter { it.lapNo == lap.no }
            if (traceRows.size < 2) return@mapNotNull null
            val trace = traceRows.map { r ->
                GeoPoint(
                    lat = r.lat,
                    lon = r.lon,
                    ts = r.ts,
                    speedMps = r.speedKmh / 3.6,
                    headingDeg = r.heading,
                    accuracyM = r.accuracy,
                    altitudeM = r.altitude,
                    source = r.source ?: "archive"
                )
            }
            LapResult(
                no = lap.no,
                timeMs = lap.timeMs,
                sectorsMs = lap.sectorsMs,
                maxSpeedKmh = lap.maxSpeedKmh,
                distanceM = lap.distanceM,
                trace = trace
            )
        }
    }

    fun delete(session: SessionSummary): Boolean =
        runCatching { session.directory.deleteRecursively() }.getOrDefault(false)

    private fun readVideos(directory: File): List<VideoRef> {
        val file = File(directory, "videos.txt")
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size == 2) VideoRef(parts[0], parts[1]) else null
        }
    }

    private fun inferStart(directory: File, id: String): Long {
        val match = Regex("session_(\\d{8})_(\\d{6})").find(id) ?: return directory.lastModified()
        return runCatching {
            val fmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            fmt.parse(match.groupValues[1] + "_" + match.groupValues[2])?.time ?: directory.lastModified()
        }.getOrDefault(directory.lastModified())
    }

    private fun directorySize(file: File): Long =
        if (file.isFile) file.length()
        else file.listFiles()?.sumOf { directorySize(it) } ?: 0L
}
