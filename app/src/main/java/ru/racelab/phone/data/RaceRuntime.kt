package ru.racelab.phone.data

import android.content.Context
import android.hardware.Sensor
import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.core.ObdReading
import ru.racelab.phone.core.RaceEngine
import ru.racelab.phone.core.RaceGeometry
import ru.racelab.phone.core.PitTimerEngine
import ru.racelab.phone.sensor.MotionProcessor
import ru.racelab.phone.sensor.MountDirection
import ru.racelab.phone.track.TrackProfile
import ru.racelab.phone.track.TrackRepository
import ru.racelab.phone.obd.CustomPid
import ru.racelab.phone.obd.EvChannel
import ru.racelab.phone.core.NmeaQuality
import ru.racelab.phone.gnss.GnssSourceMode
import ru.racelab.phone.canbus.CanChannel
import ru.racelab.phone.canbus.CanFrame
import ru.racelab.phone.canbus.CanSignalValue
import ru.racelab.phone.remote.RemoteAction
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
    private var ev = EvState()
    private val customObd = LinkedHashMap<String, CustomObdValue>()
    private val supportedObdPids = linkedSetOf<String>()
    private val preview = ArrayDeque<GeoPoint>()
    private var lowSpeedSinceMs: Long? = null
    private val gnssLastSeen = mutableMapOf<String, Long>()
    private var acceptedGnssSource: String? = null
    private var vehicleCan = VehicleCanState()
    private val canSignals = LinkedHashMap<String, CanSignalValue>()
    private var canFrameCount = 0L
    private var lastCanTs: Long? = null
    private var canHz = 0.0
    private val pitTimer = PitTimerEngine()
    private val pitCanPressed = HashMap<String, Boolean>()

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
        preview.clear(); obd = ObdState(); ev = EvState(); customObd.clear(); canSignals.clear(); canFrameCount = 0L; lastCanTs = null; canHz = 0.0
        pitTimer.reset(); pitCanPressed.clear()
        writer = SessionFileWriter(context.applicationContext)
        _state.value = _state.value.copy(
            sessionActive = true,
            armed = engine.startLine != null,
            sessionId = writer?.sessionId,
            sessionDir = writer?.directory?.absolutePath,
            laps = emptyList(), bestLapMs = null, deltaMs = null, predictedLapMs = null,
            lapElapsedMs = 0, trackPreview = emptyList(), autoStopRequested = false,
            pitTimerActive = false,
            pitStartedElapsedMs = null,
            pitLastMs = null,
            pitBestMs = null,
            pitStopCount = 0,
            pitLastTrigger = "—",
            lastMessage = if (engine.startLine != null) "ARM: ожидаю пересечение START" else "Сессия запущена"
        )
    }

    @Synchronized
    fun stopSession() {
        if (_state.value.pitTimerActive) togglePitTimer("SESSION_STOP")
        val w = writer
        writer = null
        w?.close(
            engine.laps,
            JSONObject()
                .put("startConfigured", engine.startLine != null)
                .put("sectorCount", engine.sectors.size)
                .put("trackId", _state.value.currentTrackId)
                .put("trackName", _state.value.currentTrackName)
                .put("gpsSource", _state.value.gpsSource)
                .put("gnssMode", _state.value.gnssSourceMode.name)
                .put("hdop", _state.value.hdop)
                .put("vdop", _state.value.vdop)
                .put("satellites", _state.value.satellites)
                .put("sensorCount", _state.value.activeSensorCount)
                .put("canFrameCount", _state.value.canFrameCount)
                .put("videoStatus", _state.value.videoStatus)
                .put("pitStopCount", _state.value.pitStopCount)
                .put("pitBestMs", _state.value.pitBestMs)
                .put("pitLastMs", _state.value.pitLastMs)
        )
        lowSpeedSinceMs = null
        _state.value = _state.value.copy(sessionActive = false, armed = false, autoStopRequested = false, lastMessage = "Сессия сохранена")
    }

    @Synchronized
    fun setSatellites(count: Int) {
        if (!sourceAllowed("phone_gnss", updateSeen = false)) return
        satellites = count
        _state.value = _state.value.copy(satellites = count)
    }

    @Synchronized
    fun setGnssSourceMode(mode: GnssSourceMode) {
        _state.value = _state.value.copy(
            gnssSourceMode = mode,
            lastMessage = "GNSS source: " + mode.label
        )
        acceptedGnssSource = null
        lastGpsTs = null
        gpsHz = 0.0
    }

    @Synchronized
    fun updateGnssQuality(quality: NmeaQuality, source: String) {
        val key = sourceKey(source)
        gnssLastSeen[key] = System.currentTimeMillis()
        if (!sourceAllowed(source, updateSeen = false)) return
        quality.satellites?.let { satellites = it }
        _state.value = _state.value.copy(
            satellites = quality.satellites ?: _state.value.satellites,
            pdop = quality.pdop ?: _state.value.pdop,
            hdop = quality.hdop ?: _state.value.hdop,
            vdop = quality.vdop ?: _state.value.vdop
        )
    }

    @Synchronized
    fun setStartLineHere(): Boolean {
        val cur = latestPoint ?: return false
        val prev = previousPoint ?: return false
        if (RaceGeometry.distance(prev, cur) < 2.0) return false
        engine.setStart(RaceGeometry.lineAt(cur, prev, 35.0))
        _state.value = _state.value.copy(
            startConfigured = true,
            currentTrackId = null,
            currentTrackName = null,
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

    @Synchronized
    fun loadTrack(profile: TrackProfile, auto: Boolean = false) {
        engine.setStart(profile.start)
        engine.clearSectors()
        profile.sectors.take(3).forEach { engine.addSector(it) }
        _state.value = _state.value.copy(
            startConfigured = true,
            sectorCount = engine.sectors.size,
            currentTrackId = profile.id,
            currentTrackName = profile.name,
            lastMessage = if (auto) "Автовыбор трассы: " + profile.name else "Трасса загружена: " + profile.name
        )
    }

    @Synchronized
    fun currentTrackProfile(name: String): TrackProfile? {
        val start = engine.startLine ?: return null
        return TrackRepository.create(name, start, engine.sectors)
    }

    @Synchronized
    fun setNearestTrack(name: String?, distanceM: Double?) {
        _state.value = _state.value.copy(nearestTrackName = name, nearestTrackDistanceM = distanceM)
    }

    @Synchronized
    fun clearLoadedTrack() {
        engine.resetSession(keepTrack = false)
        _state.value = _state.value.copy(
            startConfigured = false,
            sectorCount = 0,
            currentTrackId = null,
            currentTrackName = null,
            armed = false,
            lastMessage = "Трасса выгружена"
        )
    }

    @Synchronized
    fun setAutoStopEnabled(enabled: Boolean) {
        if (!enabled) lowSpeedSinceMs = null
        _state.value = _state.value.copy(autoStopEnabled = enabled, autoStopRequested = false)
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
        if (!sourceAllowed(input.source, updateSeen = true)) return
        var point = input
        if (acceptedGnssSource != sourceKey(input.source)) {
            acceptedGnssSource = sourceKey(input.source)
            lastGpsTs = null
            gpsHz = 0.0
        }
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
        val activeLapNo = engine.currentLapStartMs?.let { engine.laps.size + 1 }
        writer?.writeGps(point, activeLapNo, gX, gY, gZ, gTotal, obd, ev)
        if (update.lapCompleted != null) writer?.flush()

        val speedKmhNow = ((point.speedMps ?: 0.0) * 3.6).coerceAtLeast(0.0)
        var autoStop = false
        if (_state.value.sessionActive && _state.value.autoStopEnabled && engine.laps.isNotEmpty()) {
            if (speedKmhNow < 3.0) {
                if (lowSpeedSinceMs == null) lowSpeedSinceMs = point.ts
                autoStop = point.ts - (lowSpeedSinceMs ?: point.ts) >= 90_000L
            } else {
                lowSpeedSinceMs = null
            }
        } else {
            lowSpeedSinceMs = null
        }

        _state.value = _state.value.copy(
            latestPoint = point,
            previousPoint = prev,
            speedKmh = speedKmhNow,
            autoStopRequested = autoStop,
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
            coolantC = reading.coolantC ?: obd.coolantC,
            engineLoadPct = reading.engineLoadPct ?: obd.engineLoadPct,
            intakeC = reading.intakeC ?: obd.intakeC,
            mapKpa = reading.mapKpa ?: obd.mapKpa,
            timingDeg = reading.timingDeg ?: obd.timingDeg,
            mafGps = reading.mafGps ?: obd.mafGps,
            voltageV = reading.voltageV ?: obd.voltageV,
            oilTempC = reading.oilTempC ?: obd.oilTempC,
            fuelPressureKpa = reading.fuelPressureKpa ?: obd.fuelPressureKpa,
            shortTrimPct = reading.shortTrimPct ?: obd.shortTrimPct,
            longTrimPct = reading.longTrimPct ?: obd.longTrimPct
        )
        _state.value = _state.value.copy(obd = obd)
    }

    @Synchronized
    fun updateCustomObd(pid: CustomPid, value: Double) {
        customObd[pid.id] = CustomObdValue(pid.id, pid.name, value, pid.unit)
        ev = when (pid.evChannel) {
            EvChannel.SOC -> ev.copy(socPct = value)
            EvChannel.BATTERY_POWER_KW -> ev.copy(batteryPowerKw = value)
            EvChannel.MOTOR_POWER_KW -> ev.copy(motorPowerKw = value)
            EvChannel.REGEN_KW -> ev.copy(regenKw = value)
            EvChannel.BATTERY_TEMP_C -> ev.copy(batteryTempC = value)
            EvChannel.INVERTER_TEMP_C -> ev.copy(inverterTempC = value)
            EvChannel.NONE -> ev
        }
        writer?.writeCustomObd(System.currentTimeMillis(), pid, value)
        _state.value = _state.value.copy(
            customObd = customObd.values.toList(),
            ev = ev
        )
    }

    @Synchronized
    fun setSupportedObdPids(pids: Set<String>) {
        supportedObdPids += pids
        _state.value = _state.value.copy(supportedObdPids = supportedObdPids.toSet())
    }

    private fun sourceKey(source: String): String = when {
        source.startsWith("usb", ignoreCase = true) -> "usb"
        source.startsWith("ble", ignoreCase = true) -> "ble"
        else -> "phone"
    }

    private fun sourceAllowed(source: String, updateSeen: Boolean): Boolean {
        val key = sourceKey(source)
        val now = System.currentTimeMillis()
        if (updateSeen) gnssLastSeen[key] = now
        return when (_state.value.gnssSourceMode) {
            GnssSourceMode.PHONE -> key == "phone"
            GnssSourceMode.BLE -> key == "ble"
            GnssSourceMode.USB -> key == "usb"
            GnssSourceMode.AUTO -> {
                val usbFresh = now - (gnssLastSeen["usb"] ?: 0L) < 2_500L
                val bleFresh = now - (gnssLastSeen["ble"] ?: 0L) < 2_500L
                when (key) {
                    "usb" -> true
                    "ble" -> !usbFresh
                    else -> !usbFresh && !bleFresh
                }
            }
        }
    }

    @Synchronized
    fun updateCanFrame(frame: CanFrame) {
        canFrameCount++
        lastCanTs?.let { last ->
            val dt = frame.timestampMs - last
            if (dt in 1..5000) {
                val hz = 1000.0 / dt
                canHz = if (canHz == 0.0) hz else canHz * 0.85 + hz * 0.15
            }
        }
        lastCanTs = frame.timestampMs
        writer?.writeCanFrame(frame)
        _state.value = _state.value.copy(
            canFrameCount = canFrameCount,
            canHz = canHz
        )
    }

    @Synchronized
    fun updateCanSignal(decoded: CanSignalValue) {
        canSignals[decoded.signal.id] = decoded
        when (decoded.signal.channel) {
            CanChannel.RPM -> obd = obd.copy(rpm = decoded.value)
            CanChannel.VEHICLE_SPEED_KMH -> obd = obd.copy(speedKmh = decoded.value)
            CanChannel.THROTTLE_PCT -> obd = obd.copy(throttlePct = decoded.value)
            CanChannel.COOLANT_C -> obd = obd.copy(coolantC = decoded.value)
            CanChannel.OIL_TEMP_C -> obd = obd.copy(oilTempC = decoded.value)
            CanChannel.GEAR -> vehicleCan = vehicleCan.copy(gear = decoded.value)
            CanChannel.STEERING_DEG -> vehicleCan = vehicleCan.copy(steeringDeg = decoded.value)
            CanChannel.BRAKE_PRESSURE_BAR -> vehicleCan = vehicleCan.copy(brakePressureBar = decoded.value)
            CanChannel.WHEEL_FL_KMH -> vehicleCan = vehicleCan.copy(wheelFlKmh = decoded.value)
            CanChannel.WHEEL_FR_KMH -> vehicleCan = vehicleCan.copy(wheelFrKmh = decoded.value)
            CanChannel.WHEEL_RL_KMH -> vehicleCan = vehicleCan.copy(wheelRlKmh = decoded.value)
            CanChannel.WHEEL_RR_KMH -> vehicleCan = vehicleCan.copy(wheelRrKmh = decoded.value)
            CanChannel.EV_SOC -> ev = ev.copy(socPct = decoded.value)
            CanChannel.EV_BATTERY_KW -> ev = ev.copy(batteryPowerKw = decoded.value)
            CanChannel.EV_MOTOR_KW -> ev = ev.copy(motorPowerKw = decoded.value)
            CanChannel.EV_REGEN_KW -> ev = ev.copy(regenKw = decoded.value)
            CanChannel.EV_BATTERY_TEMP_C -> ev = ev.copy(batteryTempC = decoded.value)
            CanChannel.EV_INVERTER_TEMP_C -> ev = ev.copy(inverterTempC = decoded.value)
            CanChannel.PIT_BUTTON -> {
                val pressed = decoded.value > 0.5
                val wasPressed = pitCanPressed[decoded.signal.id] ?: false
                if (pressed && !wasPressed) {
                    togglePitTimer("CAN:" + decoded.signal.name)
                }
                pitCanPressed[decoded.signal.id] = pressed
            }
            CanChannel.NONE -> Unit
        }
        writer?.writeCanSignal(decoded)
        _state.value = _state.value.copy(
            obd = obd,
            ev = ev,
            vehicleCan = vehicleCan,
            canSignals = canSignals.values.toList()
        )
    }

    @Synchronized
    fun togglePitTimer(trigger: String = "SCREEN") {
        val event = pitTimer.toggle(SystemClock.elapsedRealtime(), trigger) ?: return
        val snap = event.snapshot
        writer?.writePitEvent(System.currentTimeMillis(), event.type, event.elapsedMs, trigger)
        _state.value = _state.value.copy(
            pitTimerActive = snap.active,
            pitStartedElapsedMs = snap.startedElapsedMs,
            pitLastMs = snap.lastMs,
            pitBestMs = snap.bestMs,
            pitStopCount = snap.count,
            pitLastTrigger = snap.lastTrigger,
            lastMessage = if (snap.active) {
                "PIT TIMER START • " + trigger
            } else {
                "PIT TIMER STOP • " + formatPit(event.elapsedMs ?: 0L)
            }
        )
    }

    @Synchronized
    fun resetPitTimer() {
        pitTimer.reset()
        pitCanPressed.clear()
        writer?.writePitEvent(System.currentTimeMillis(), "RESET", null, "SCREEN")
        _state.value = _state.value.copy(
            pitTimerActive = false,
            pitStartedElapsedMs = null,
            pitLastMs = null,
            pitBestMs = null,
            pitStopCount = 0,
            pitLastTrigger = "—",
            lastMessage = "PIT TIMER сброшен"
        )
    }

    fun pitElapsedMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long =
        pitTimer.elapsed(nowElapsedMs)

    @Synchronized
    fun setGm204Enabled(enabled: Boolean) {
        _state.value = _state.value.copy(
            gm204Enabled = enabled,
            lastMessage = if (enabled) "HOCO GM204 профиль включён" else "HOCO GM204 профиль выключен"
        )
    }

    @Synchronized
    fun reportRemoteKey(deviceName: String?, keyCodeName: String) {
        _state.value = _state.value.copy(
            remoteDeviceName = deviceName?.takeIf { it.isNotBlank() } ?: "Bluetooth HID",
            remoteLastKey = keyCodeName
        )
    }

    @Synchronized
    fun postRemoteAction(action: RemoteAction) {
        _state.value = _state.value.copy(
            remoteAction = action,
            remoteActionSeq = _state.value.remoteActionSeq + 1L
        )
    }

    @Synchronized
    fun setPitLaneServerStatus(running: Boolean, urls: List<String>) {
        _state.value = _state.value.copy(
            pitLaneServerRunning = running,
            pitLaneUrls = urls.distinct(),
            lastMessage = when {
                running && urls.isNotEmpty() -> "PIT LANE: " + urls.first()
                running -> "PIT LANE сервер запущен"
                else -> _state.value.lastMessage
            }
        )
    }

    fun updateVideoState(recording: Boolean, status: String) {
        _state.value = _state.value.copy(videoRecording = recording, videoStatus = status)
    }

    fun markMessage(message: String) {
        _state.value = _state.value.copy(lastMessage = message)
    }

    private fun formatPit(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val x = ms % 1000
        return "%02d:%02d.%03d".format(m, s, x)
    }

    private fun formatLap(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val x = ms % 1000
        return "%02d:%02d.%03d".format(m, s, x)
    }
}
