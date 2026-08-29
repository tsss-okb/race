package ru.racelab.phone.data

import android.content.Context
import android.hardware.Sensor
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.core.ObdReading
import ru.racelab.phone.core.RaceEngine
import ru.racelab.phone.core.RaceGeometry
import ru.racelab.phone.sensor.MotionProcessor
import ru.racelab.phone.sensor.MountDirection
import kotlin.math.sqrt

object RaceRuntime {
    private val engine = RaceEngine()
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var writer: SessionFileWriter? = null
    private var latestPoint: GeoPoint? = null
    private var previousPoint: GeoPoint? = null
    private var lastGpsTs: Long? = null
    private var gpsHz = 0.0
    private val sensorMap = LinkedHashMap<String, SensorSnapshot>()
    private val sensorLastTs = HashMap<String, Long>()
    private var lastSensorUiPush = 0L
    private var availableSensors = 0
    private var gX = 0.0
    private var gY = 0.0
    private var gZ = 0.0
    private var gTotal = 0.0
    private var satellites = 0
    private var obd = ObdState()
    private val preview = ArrayDeque<GeoPoint>()

    @Synchronized
    fun setAvailableSensors(count: Int) {
        availableSensors = count
        _state.value = _state.value.copy(availableSensorCount = count)
    }

    @Synchronized
    fun startSession(context: Context) {
        if (_state.value.sessionActive) return
        engine.resetSession(keepTrack = true)
        latestPoint = null; previousPoint = null; lastGpsTs = null; gpsHz = 0.0
        preview.clear(); obd = ObdState()
        writer = SessionFileWriter(context.applicationContext)
        _state.value = _state.value.copy(
            sessionActive = true,
            armed = engine.startLine != null,
            sessionId = writer?.sessionId,
            sessionDir = writer?.directory?.absolutePath,
            laps = emptyList(), bestLapMs = null, deltaMs = null, predictedLapMs = null,
            lapElapsedMs = 0, trackPreview = emptyList(), lastMessage = "Сессия запущена"
        )
    }

    @Synchronized
    fun stopSession() {
        val w = writer
        writer = null
        w?.close(engine.laps, JSONObject().put("startConfigured", engine.startLine != null).put("sectorCount", engine.sectors.size))
        _state.value = _state.value.copy(sessionActive = false, armed = false, lastMessage = "Сессия сохранена")
    }

    @Synchronized
    fun setSatellites(count: Int) {
        satellites = count
        _state.value = _state.value.copy(satellites = count)
    }

    @Synchronized
    fun setStartLineHere(): Boolean {
        val cur = latestPoint ?: return false
        val prev = previousPoint ?: return false
        if (RaceGeometry.distance(prev, cur) < 2.0) return false
        engine.setStart(RaceGeometry.lineAt(cur, prev, 35.0))
        _state.value = _state.value.copy(
            startConfigured = true,
            armed = _state.value.sessionActive,
            sectorCount = engine.sectors.size,
            lastMessage = "START/FINISH установлен"
        )
        return true
    }

    @Synchronized
    fun addSectorHere(): Boolean {
        val cur = latestPoint ?: return false
        val prev = previousPoint ?: return false
        if (RaceGeometry.distance(prev, cur) < 2.0) return false
        val ok = engine.addSector(RaceGeometry.lineAt(cur, prev, 30.0))
        _state.value = _state.value.copy(sectorCount = engine.sectors.size, lastMessage = if (ok) "Сектор S${engine.sectors.size} установлен" else "Максимум 3 сектора")
        return ok
    }

    @Synchronized
    fun clearSectors() {
        engine.clearSectors()
        _state.value = _state.value.copy(sectorCount = 0, lastMessage = "Секторы очищены")
    }

    fun ingestLocation(location: Location) {
        val point = GeoPoint(
            lat = location.latitude,
            lon = location.longitude,
            ts = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            speedMps = location.speed.toDouble().takeIf { location.hasSpeed() },
            headingDeg = location.bearing.toDouble().takeIf { location.hasBearing() },
            accuracyM = location.accuracy.toDouble().takeIf { location.hasAccuracy() },
            altitudeM = location.altitude.takeIf { location.hasAltitude() },
            source = "phone_gnss"
        )
        ingestPoint(point)
    }

    @Synchronized
    fun ingestPoint(input: GeoPoint) {
        var point = input
        val prev = latestPoint
        if (prev != null && point.speedMps == null && point.ts > prev.ts) {
            val dt = (point.ts - prev.ts) / 1000.0
            if (dt > 0) point = point.copy(speedMps = RaceGeometry.distance(prev, point) / dt)
        }
        previousPoint = prev
        latestPoint = point

        lastGpsTs?.let { last ->
            val dt = point.ts - last
            if (dt in 1..5000) {
                val hz = 1000.0 / dt
                gpsHz = if (gpsHz == 0.0) hz else gpsHz * 0.8 + hz * 0.2
            }
        }
        lastGpsTs = point.ts

        val update = engine.onPoint(prev, point)
        preview += point
        while (preview.size > 1200) preview.removeFirst()

        val prediction = engine.prediction(point.ts, point)
        val lapElapsed = engine.currentLapStartMs?.let { (point.ts - it).coerceAtLeast(0) } ?: 0L
        writer?.writeGps(point, gX, gY, gZ, gTotal, obd)
        if (update.lapCompleted != null) writer?.flush()

        _state.value = _state.value.copy(
            latestPoint = point,
            previousPoint = prev,
            speedKmh = ((point.speedMps ?: 0.0) * 3.6).coerceAtLeast(0.0),
            gpsHz = gpsHz,
            satellites = satellites,
            accuracyM = point.accuracyM,
            lapElapsedMs = lapElapsed,
            bestLapMs = engine.bestLapMs,
            deltaMs = prediction?.deltaMs,
            predictedLapMs = prediction?.projectedMs,
            laps = engine.laps.toList(),
            armed = _state.value.sessionActive && engine.startLine != null && engine.currentLapStartMs == null,
            startConfigured = engine.startLine != null,
            sectorCount = engine.sectors.size,
            gpsSource = point.source,
            trackPreview = preview.toList(),
            lastMessage = when {
                update.lapCompleted != null -> "Круг ${update.lapCompleted.no}: ${formatLap(update.lapCompleted.timeMs)}"
                update.sectorCompleted != null -> "S${update.sectorCompleted.first}: ${formatLap(update.sectorCompleted.second)}"
                update.lapStarted -> "Круг начат"
                else -> _state.value.lastMessage
            }
        )
    }

    @Synchronized
    fun updateSensor(ts: Long, sensor: Sensor, accuracy: Int, values: FloatArray, persist: Boolean = true) {
        val id = "${sensor.type}:${sensor.name}"
        val prevTs = sensorLastTs[id]
        val hz = if (prevTs != null && ts > prevTs) 1000.0 / (ts - prevTs) else 0.0
        sensorLastTs[id] = ts
        val snapshot = SensorSnapshot(id, sensor.type, sensor.name, sensor.vendor, values.toList(), accuracy, hz, ts)
        sensorMap[id] = snapshot

        if (sensor.type == Sensor.TYPE_LINEAR_ACCELERATION && values.size >= 3) {
            gX = values[0] / 9.80665
            gY = values[1] / 9.80665
            gZ = values[2] / 9.80665
            gTotal = sqrt(gX * gX + gY * gY + gZ * gZ)
        }
        val motion = MotionProcessor.onSensor(sensor.type, values)
        if (persist) writer?.writeSensor(ts, sensor, accuracy, hz, values)

        if (ts - lastSensorUiPush >= 100) {
            lastSensorUiPush = ts
            _state.value = _state.value.copy(
                gX = gX, gY = gY, gZ = gZ, gTotal = gTotal,
                longitudinalG = motion.longitudinalG,
                lateralG = motion.lateralG,
                verticalG = motion.verticalG,
                yawDeg = motion.yawDeg,
                pitchDeg = motion.pitchDeg,
                rollDeg = motion.rollDeg,
                imuCalibrated = motion.calibrated,
                mountDirection = motion.mountDirection,
                sensors = sensorMap.values.sortedBy { it.type },
                availableSensorCount = availableSensors,
                activeSensorCount = sensorMap.size
            )
        }
    }

    @Synchronized
    fun calibrateImu() {
        val motion = MotionProcessor.calibrate()
        _state.value = _state.value.copy(
            imuCalibrated = motion.calibrated,
            yawDeg = motion.yawDeg,
            pitchDeg = motion.pitchDeg,
            rollDeg = motion.rollDeg,
            lastMessage = if (motion.calibrated) "IMU откалиброван" else "Нет rotation-vector для калибровки"
        )
    }

    @Synchronized
    fun resetImuCalibration() {
        val motion = MotionProcessor.resetCalibration()
        _state.value = _state.value.copy(
            imuCalibrated = false,
            yawDeg = motion.yawDeg,
            pitchDeg = motion.pitchDeg,
            rollDeg = motion.rollDeg,
            lastMessage = "Калибровка IMU сброшена"
        )
    }

    @Synchronized
    fun setMountDirection(direction: MountDirection) {
        val motion = MotionProcessor.setMountDirection(direction)
        _state.value = _state.value.copy(
            mountDirection = motion.mountDirection,
            lastMessage = "Направление крепления: ${direction.label}"
        )
    }

    @Synchronized
    fun updateObd(reading: ObdReading) {
        obd = obd.copy(
            rpm = reading.rpm ?: obd.rpm,
            speedKmh = reading.speedKmh ?: obd.speedKmh,
            throttlePct = reading.throttlePct ?: obd.throttlePct,
            coolantC = reading.coolantC ?: obd.coolantC
        )
        _state.value = _state.value.copy(obd = obd)
    }

    fun markMessage(message: String) {
        _state.value = _state.value.copy(lastMessage = message)
    }

    private fun formatLap(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val x = ms % 1000
        return "%02d:%02d.%03d".format(m, s, x)
    }
}
