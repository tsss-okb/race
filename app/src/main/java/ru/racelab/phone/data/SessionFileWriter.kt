package ru.racelab.phone.data

import android.content.Context
import android.hardware.Sensor
import org.json.JSONArray
import org.json.JSONObject
import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.core.LapResult
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionFileWriter(context: Context) {
    val sessionId: String
    val directory: File
    private val gpsWriter: BufferedWriter
    private val sensorWriter: BufferedWriter
    private var closed = false

    init {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionId = "session_$stamp"
        val root = File(context.getExternalFilesDir(null), "RaceLab/sessions")
        directory = File(root, sessionId).apply { mkdirs() }
        gpsWriter = BufferedWriter(FileWriter(File(directory, "gps.csv"), false), 64 * 1024)
        sensorWriter = BufferedWriter(FileWriter(File(directory, "sensors.csv"), false), 128 * 1024)
        gpsWriter.write("ts,lat,lon,speed_kmh,heading,accuracy,altitude,source,gx,gy,gz,g_total,rpm,obd_speed,throttle,coolant\n")
        sensorWriter.write("ts,type,name,vendor,accuracy,hz,v0,v1,v2,v3,v4,v5\n")
        gpsWriter.flush(); sensorWriter.flush()
    }

    @Synchronized
    fun writeGps(point: GeoPoint, gx: Double, gy: Double, gz: Double, gt: Double, obd: ObdState) {
        if (closed) return
        gpsWriter.write(listOf(
            point.ts, point.lat, point.lon, (point.speedMps ?: 0.0) * 3.6,
            point.headingDeg ?: "", point.accuracyM ?: "", point.altitudeM ?: "", point.source,
            gx, gy, gz, gt,
            obd.rpm ?: "", obd.speedKmh ?: "", obd.throttlePct ?: "", obd.coolantC ?: ""
        ).joinToString(","))
        gpsWriter.newLine()
    }

    @Synchronized
    fun writeSensor(ts: Long, sensor: Sensor, accuracy: Int, hz: Double, values: FloatArray) {
        if (closed) return
        val row = mutableListOf<Any?>(ts, sensor.type, quote(sensor.name), quote(sensor.vendor), accuracy, hz)
        for (i in 0..5) row += values.getOrNull(i) ?: ""
        sensorWriter.write(row.joinToString(","))
        sensorWriter.newLine()
    }

    @Synchronized
    fun flush() {
        if (!closed) {
            gpsWriter.flush(); sensorWriter.flush()
        }
    }

    @Synchronized
    fun close(laps: List<LapResult>, extra: JSONObject = JSONObject()) {
        if (closed) return
        closed = true
        gpsWriter.flush(); sensorWriter.flush()
        gpsWriter.close(); sensorWriter.close()

        val meta = JSONObject()
            .put("format", "racelab-native-session-v1")
            .put("sessionId", sessionId)
            .put("endedAt", System.currentTimeMillis())
            .put("laps", JSONArray().apply {
                laps.forEach { lap ->
                    put(JSONObject()
                        .put("no", lap.no)
                        .put("timeMs", lap.timeMs)
                        .put("maxSpeedKmh", lap.maxSpeedKmh)
                        .put("distanceM", lap.distanceM)
                        .put("sectorsMs", JSONArray(lap.sectorsMs)))
                }
            })
        extra.keys().forEach { meta.put(it, extra.get(it)) }
        File(directory, "meta.json").writeText(meta.toString(2))
    }

    private fun quote(s: String): String = "\"\${s.replace("\"", "\"\"")}\""
}
