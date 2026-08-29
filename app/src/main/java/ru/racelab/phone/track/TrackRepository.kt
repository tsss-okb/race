package ru.racelab.phone.track

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.core.RaceGeometry
import ru.racelab.phone.core.RaceLine
import java.util.UUID

data class TrackProfile(
    val id: String,
    val name: String,
    val start: RaceLine,
    val sectors: List<RaceLine>,
    val pitEntry: RaceLine? = null,
    val pitExit: RaceLine? = null,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    val center: GeoPoint
        get() = GeoPoint(start.centerLat, start.centerLon, createdAtMs)
}

data class NearestTrack(val profile: TrackProfile, val distanceM: Double)

object TrackRepository {
    private const val PREFS = "racelab_tracks"
    private const val KEY = "tracks_json"

    fun list(context: Context): List<TrackProfile> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(fromJsonObject(arr.getJSONObject(i)))
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, profile: TrackProfile): TrackProfile {
        val tracks = list(context).toMutableList()
        val idx = tracks.indexOfFirst { it.id == profile.id }
        if (idx >= 0) tracks[idx] = profile else tracks += profile
        persist(context, tracks)
        return profile
    }

    fun create(
        name: String,
        start: RaceLine,
        sectors: List<RaceLine>,
        pitEntry: RaceLine? = null,
        pitExit: RaceLine? = null
    ): TrackProfile =
        TrackProfile(
            UUID.randomUUID().toString(),
            name.trim().ifBlank { "Моя трасса" },
            start,
            sectors.toList(),
            pitEntry,
            pitExit
        )

    fun delete(context: Context, id: String) {
        persist(context, list(context).filterNot { it.id == id })
    }

    fun nearest(context: Context, point: GeoPoint, maxDistanceM: Double = 10_000.0): NearestTrack? {
        return list(context)
            .map { NearestTrack(it, RaceGeometry.distance(point, it.center)) }
            .filter { it.distanceM <= maxDistanceM }
            .minByOrNull { it.distanceM }
    }

    fun toJson(profile: TrackProfile): String = toJsonObject(profile).toString(2)

    fun fromJson(text: String): TrackProfile = fromJsonObject(JSONObject(text))

    fun toGpx(profile: TrackProfile): String {
        fun wpt(lat: Double, lon: Double, name: String): String =
            "  <wpt lat=\"" + lat + "\" lon=\"" + lon + "\"><name>" + name + "</name></wpt>"
        val points = buildList {
            add(wpt(profile.start.centerLat, profile.start.centerLon, "START_FINISH"))
            profile.sectors.forEachIndexed { i, line -> add(wpt(line.centerLat, line.centerLon, "S" + (i + 1))) }
        }.joinToString("\n")
        val json = toJson(profile).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"RaceLab\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
            "<metadata><name>" + profile.name + "</name><desc>" + json + "</desc></metadata>\n" +
            points + "\n</gpx>"
    }

    fun fromGpx(text: String): TrackProfile {
        val desc = Regex("<desc>(.*?)</desc>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(text)?.groupValues?.getOrNull(1)
            ?: error("GPX не содержит RaceLab metadata")
        val decoded = desc.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        return fromJson(decoded)
    }

    private fun persist(context: Context, tracks: List<TrackProfile>) {
        val arr = JSONArray()
        tracks.forEach { arr.put(toJsonObject(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJsonObject(profile: TrackProfile): JSONObject = JSONObject()
        .put("format", "racelab-track-v2")
        .put("id", profile.id)
        .put("name", profile.name)
        .put("createdAtMs", profile.createdAtMs)
        .put("start", lineToJson(profile.start))
        .put("sectors", JSONArray().apply { profile.sectors.forEach { put(lineToJson(it)) } })
        .put("pitEntry", profile.pitEntry?.let(::lineToJson) ?: JSONObject.NULL)
        .put("pitExit", profile.pitExit?.let(::lineToJson) ?: JSONObject.NULL)

    private fun fromJsonObject(o: JSONObject): TrackProfile {
        val sectors = buildList {
            val arr = o.optJSONArray("sectors") ?: JSONArray()
            for (i in 0 until arr.length()) add(lineFromJson(arr.getJSONObject(i)))
        }
        return TrackProfile(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name", "Импортированная трасса"),
            start = lineFromJson(o.getJSONObject("start")),
            sectors = sectors,
            pitEntry = o.optJSONObject("pitEntry")?.let(::lineFromJson),
            pitExit = o.optJSONObject("pitExit")?.let(::lineFromJson),
            createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis())
        )
    }

    private fun lineToJson(l: RaceLine): JSONObject = JSONObject()
        .put("aLat", l.aLat).put("aLon", l.aLon)
        .put("bLat", l.bLat).put("bLon", l.bLon)
        .put("centerLat", l.centerLat).put("centerLon", l.centerLon)
        .put("forwardX", l.forwardX).put("forwardY", l.forwardY)

    private fun lineFromJson(o: JSONObject): RaceLine = RaceLine(
        aLat = o.getDouble("aLat"), aLon = o.getDouble("aLon"),
        bLat = o.getDouble("bLat"), bLon = o.getDouble("bLon"),
        centerLat = o.getDouble("centerLat"), centerLon = o.getDouble("centerLon"),
        forwardX = o.getDouble("forwardX"), forwardY = o.getDouble("forwardY")
    )
}
