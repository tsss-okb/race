package ru.racelab.phone.session

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.floor

enum class SessionExportFormat(val extension: String, val mime: String) {
    ZIP("zip", "application/zip"),
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
    VBO("vbo", "text/plain"),
    NMEA("nmea", "text/plain")
}

data class SessionExportResult(
    val uri: Uri,
    val displayName: String,
    val mime: String,
    val shareable: Boolean
)

object SessionExporter {
    fun export(
        context: Context,
        session: SessionSummary,
        format: SessionExportFormat
    ): SessionExportResult {
        val displayName = session.id + "_" + format.name.lowercase(Locale.US) + "." + format.extension
        val target = createTarget(context, displayName, format.mime)
        context.contentResolver.openOutputStream(target.uri, "w").use { out ->
            requireNotNull(out) { "Не удалось открыть экспорт" }
            when (format) {
                SessionExportFormat.ZIP -> writeZip(session, out)
                SessionExportFormat.CSV -> copyOrEmpty(File(session.directory, "gps.csv"), out)
                SessionExportFormat.JSON -> writeJson(session, out)
                SessionExportFormat.VBO -> writeVbo(session, out)
                SessionExportFormat.NMEA -> writeNmea(session, out)
            }
        }
        return SessionExportResult(target.uri, displayName, format.mime, target.shareable)
    }

    fun share(context: Context, result: SessionExportResult): Boolean {
        if (!result.shareable) return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = result.mime
            putExtra(Intent.EXTRA_STREAM, result.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться RaceLab"))
        return true
    }

    private data class Target(val uri: Uri, val shareable: Boolean)

    private fun createTarget(context: Context, name: String, mime: String): Target {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RaceLab")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore export failed")
            return Target(uri, true)
        }
        val root = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "RaceLab").apply { mkdirs() }
        return Target(Uri.fromFile(File(root, name)), false)
    }

    private fun copyOrEmpty(file: File, out: OutputStream) {
        if (file.exists()) file.inputStream().use { it.copyTo(out) }
        else out.write("no_data\n".toByteArray())
    }

    private fun writeZip(session: SessionSummary, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            session.directory.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(session.directory).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun writeJson(session: SessionSummary, out: OutputStream) {
        val obj = JSONObject()
            .put("format", "racelab-session-export-v2")
            .put("id", session.id)
            .put("startedAt", session.startedAtMs)
            .put("endedAt", session.endedAtMs)
            .put("trackName", session.trackName)
            .put("gpsSource", session.gpsSource)
            .put("bestLapMs", session.bestLapMs)
            .put("maxSpeedKmh", session.maxSpeedKmh)
            .put("sizeBytes", session.sizeBytes)
            .put("data", JSONObject()
                .put("gps", session.hasGps)
                .put("sensors", session.hasSensors)
                .put("obdCustom", session.hasObdCustom)
                .put("can", session.hasCan)
                .put("videos", session.videoRefs.size))
            .put("laps", JSONArray().apply {
                session.laps.forEach { lap ->
                    put(JSONObject()
                        .put("no", lap.no)
                        .put("timeMs", lap.timeMs)
                        .put("maxSpeedKmh", lap.maxSpeedKmh)
                        .put("distanceM", lap.distanceM)
                        .put("sectorsMs", JSONArray(lap.sectorsMs)))
                }
            })
            .put("videos", JSONArray().apply {
                session.videoRefs.forEach { put(JSONObject().put("name", it.name).put("uri", it.uri)) }
            })
        out.write(obj.toString(2).toByteArray(Charsets.UTF_8))
    }

    private fun writeVbo(session: SessionSummary, out: OutputStream) {
        val rows = SessionRepository.readGps(session)
        val fmt = SimpleDateFormat("HHmmss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.appendLine("[header]")
        sb.appendLine("generated by RaceLab 2.0")
        sb.appendLine("[column names]")
        sb.appendLine("time latitude longitude velocity heading height lap")
        sb.appendLine("[data]")
        rows.forEach { r ->
            sb.append(fmt.format(Date(r.ts))).append(' ')
                .append("%.8f".format(Locale.US, r.lat)).append(' ')
                .append("%.8f".format(Locale.US, r.lon)).append(' ')
                .append("%.3f".format(Locale.US, r.speedKmh)).append(' ')
                .append("%.2f".format(Locale.US, r.heading ?: 0.0)).append(' ')
                .append("%.2f".format(Locale.US, r.altitude ?: 0.0)).append(' ')
                .append(r.lapNo ?: 0)
                .appendLine()
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeNmea(session: SessionSummary, out: OutputStream) {
        val rows = SessionRepository.readGps(session)
        val meta = readMeta(session)
        val sats = meta.optInt("satellites", 0).coerceIn(0, 99)
        val hdop = meta.optDouble("hdop", 1.0).takeIf { !it.isNaN() } ?: 1.0
        val timeFmt = SimpleDateFormat("HHmmss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dateFmt = SimpleDateFormat("ddMMyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        rows.forEach { r ->
            val date = Date(r.ts)
            val lat = nmeaCoord(r.lat, true)
            val lon = nmeaCoord(r.lon, false)
            val ns = if (r.lat >= 0) "N" else "S"
            val ew = if (r.lon >= 0) "E" else "W"
            val knots = r.speedKmh / 1.852
            val rmc = "GPRMC," + timeFmt.format(date) + ",A," + lat + "," + ns + "," + lon + "," + ew + "," +
                "%.3f".format(Locale.US, knots) + "," + "%.2f".format(Locale.US, r.heading ?: 0.0) + "," +
                dateFmt.format(date) + ",,,A"
            val gga = "GPGGA," + timeFmt.format(date) + "," + lat + "," + ns + "," + lon + "," + ew +
                ",1," + "%02d".format(Locale.US, sats) + "," + "%.2f".format(Locale.US, hdop) + "," +
                "%.2f".format(Locale.US, r.altitude ?: 0.0) + ",M,0.0,M,,"
            sb.append('$').append(rmc).append('*').append(checksum(rmc)).append("\r\n")
            sb.append('$').append(gga).append('*').append(checksum(gga)).append("\r\n")
        }
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun nmeaCoord(value: Double, latitude: Boolean): String {
        val a = abs(value)
        val deg = floor(a).toInt()
        val minutes = (a - deg) * 60.0
        return if (latitude) {
            "%02d%08.5f".format(Locale.US, deg, minutes)
        } else {
            "%03d%08.5f".format(Locale.US, deg, minutes)
        }
    }

    private fun checksum(body: String): String {
        var cs = 0
        body.forEach { cs = cs xor it.code }
        return "%02X".format(Locale.US, cs and 0xFF)
    }

    private fun readMeta(session: SessionSummary): JSONObject =
        runCatching { JSONObject(File(session.directory, "meta.json").readText()) }.getOrDefault(JSONObject())
}
