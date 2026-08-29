package ru.racelab.phone.data

import android.content.Context
import android.hardware.Sensor
import org.json.JSONArray
import org.json.JSONObject
import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.core.LapResult
import ru.racelab.phone.obd.CustomPid
import ru.racelab.phone.canbus.CanFrame
import ru.racelab.phone.canbus.CanSignalValue
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
    private val customObdWriter: BufferedWriter
    private val canWriter: BufferedWriter
    private val canSignalWriter: BufferedWriter
    private var closed = false

    init {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionId = "session_$stamp"
        val root = File(context.getExternalFilesDir(null), "RaceLab/sessions")
        directory = File(root, sessionId).apply { mkdirs() }
        gpsWriter = BufferedWriter(FileWriter(File(directory, "gps.csv"), false), 64 * 1024)
        sensorWriter = BufferedWriter(FileWriter(File(directory, "sensors.csv"), false), 128 * 1024)
        customObdWriter = BufferedWriter(FileWriter(File(directory, "obd_custom.csv"), false), 64 * 1024)
        canWriter = BufferedWriter(FileWriter(File(directory, "can.csv"), false), 128 * 1024)
        canSignalWriter = BufferedWriter(FileWriter(File(directory, "can_signals.csv"), false), 64 * 1024)
        gpsWriter.write(
            "ts,lat,lon,speed_kmh,heading,accuracy,altitude,source,gx,gy,gz,g_total," +
                "rpm,obd_speed,throttle,coolant,engine_load,intake_c,map_kpa,timing_deg,maf_gps,voltage_v," +
                "oil_temp_c,fuel_pressure_kpa,short_trim,long_trim,ev_soc,ev_battery_kw,ev_motor_kw,ev_regen_kw," +
                "ev_battery_temp_c,ev_inverter_temp_c\n"
        )
        sensorWriter.write("ts,type,name,vendor,accuracy,hz,v0,v1,v2,v3,v4,v5\n")
        customObdWriter.write("ts,id,name,value,unit\n")
        canWriter.write("ts,can_id,extended,rtr,dlc,data_hex\n")
        canSignalWriter.write("ts,signal_id,name,value,unit,channel\n")
        gpsWriter.flush(); sensorWriter.flush(); customObdWriter.flush(); canWriter.flush(); canSignalWriter.flush()
    }

    @Synchronized
    fun writeGps(point: GeoPoint, gx: Double, gy: Double, gz: Double, gt: Double, obd: ObdState, ev: EvState) {
        if (closed) return
        gpsWriter.write(listOf(
            point.ts, point.lat, point.lon, (point.speedMps ?: 0.0) * 3.6,
            point.headingDeg ?: "", point.accuracyM ?: "", point.altitudeM ?: "", point.source,
            gx, gy, gz, gt,
            obd.rpm ?: "", obd.speedKmh ?: "", obd.throttlePct ?: "", obd.coolantC ?: "",
            obd.engineLoadPct ?: "", obd.intakeC ?: "", obd.mapKpa ?: "", obd.timingDeg ?: "",
            obd.mafGps ?: "", obd.voltageV ?: "", obd.oilTempC ?: "", obd.fuelPressureKpa ?: "",
            obd.shortTrimPct ?: "", obd.longTrimPct ?: "",
            ev.socPct ?: "", ev.batteryPowerKw ?: "", ev.motorPowerKw ?: "", ev.regenKw ?: "",
            ev.batteryTempC ?: "", ev.inverterTempC ?: ""
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
    fun writeCustomObd(ts: Long, pid: CustomPid, value: Double) {
        if (closed) return
        customObdWriter.write(
            listOf(ts, quote(pid.id), quote(pid.name), value, quote(pid.unit)).joinToString(",")
        )
        customObdWriter.newLine()
    }
    @Synchronized
    fun writeCanFrame(frame: CanFrame) {
        if (closed) return
        val hex = frame.data.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        canWriter.write(
            listOf(
                frame.timestampMs,
                "0x" + frame.id.toString(16).uppercase(),
                frame.extended,
                frame.rtr,
                frame.data.size,
                hex
            ).joinToString(",")
        )
        canWriter.newLine()
    }

    @Synchronized
    fun writeCanSignal(value: CanSignalValue) {
        if (closed) return
        canSignalWriter.write(
            listOf(
                value.timestampMs,
                quote(value.signal.id),
                quote(value.signal.name),
                value.value,
                quote(value.signal.unit),
                value.signal.channel.name
            ).joinToString(",")
        )
        canSignalWriter.newLine()
    }



    @Synchronized
    fun flush() {
        if (!closed) {
            gpsWriter.flush(); sensorWriter.flush(); customObdWriter.flush(); canWriter.flush(); canSignalWriter.flush()
        }
    }

    @Synchronized
    fun close(laps: List<LapResult>, extra: JSONObject = JSONObject()) {
        if (closed) return
        closed = true
        gpsWriter.flush(); sensorWriter.flush(); customObdWriter.flush(); canWriter.flush(); canSignalWriter.flush()
        gpsWriter.close(); sensorWriter.close(); customObdWriter.close(); canWriter.close(); canSignalWriter.close()

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

    private fun quote(s: String): String = "\\\"" + s.replace("\\\"", "\\\"\\\"") + "\\\""
}
