package ru.racelab.phone.data

import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.core.LapResult
import ru.racelab.phone.sensor.MountDirection
import ru.racelab.phone.gnss.GnssSourceMode
import ru.racelab.phone.canbus.CanSignalValue
import ru.racelab.phone.remote.RemoteAction

data class SensorSnapshot(
    val id: String,
    val type: Int,
    val name: String,
    val vendor: String,
    val values: List<Float>,
    val accuracy: Int,
    val hz: Double,
    val updatedAtMs: Long
)

data class ObdState(
    val rpm: Double? = null,
    val speedKmh: Double? = null,
    val throttlePct: Double? = null,
    val coolantC: Double? = null,
    val engineLoadPct: Double? = null,
    val intakeC: Double? = null,
    val mapKpa: Double? = null,
    val timingDeg: Double? = null,
    val mafGps: Double? = null,
    val voltageV: Double? = null,
    val oilTempC: Double? = null,
    val fuelPressureKpa: Double? = null,
    val shortTrimPct: Double? = null,
    val longTrimPct: Double? = null
)

data class EvState(
    val socPct: Double? = null,
    val batteryPowerKw: Double? = null,
    val motorPowerKw: Double? = null,
    val regenKw: Double? = null,
    val batteryTempC: Double? = null,
    val inverterTempC: Double? = null
)

data class CustomObdValue(
    val id: String,
    val name: String,
    val value: Double,
    val unit: String
)

data class VehicleCanState(
    val gear: Double? = null,
    val steeringDeg: Double? = null,
    val brakePressureBar: Double? = null,
    val wheelFlKmh: Double? = null,
    val wheelFrKmh: Double? = null,
    val wheelRlKmh: Double? = null,
    val wheelRrKmh: Double? = null
)

data class AppState(
    val sessionActive: Boolean = false,
    val armed: Boolean = false,
    val sessionId: String? = null,
    val sessionDir: String? = null,
    val latestPoint: GeoPoint? = null,
    val previousPoint: GeoPoint? = null,
    val speedKmh: Double = 0.0,
    val gpsHz: Double = 0.0,
    val satellites: Int = 0,
    val accuracyM: Double? = null,
    val lapElapsedMs: Long = 0,
    val bestLapMs: Long? = null,
    val deltaMs: Long? = null,
    val predictedLapMs: Long? = null,
    val miniSectorCount: Int = 10,
    val currentMiniSector: Int = 0,
    val miniSectorDeltaMs: Long? = null,
    val miniSectorDeltasMs: List<Long?> = List(10) { null },
    val laps: List<LapResult> = emptyList(),
    val startConfigured: Boolean = false,
    val sectorCount: Int = 0,
    val currentTrackId: String? = null,
    val currentTrackName: String? = null,
    val nearestTrackName: String? = null,
    val nearestTrackDistanceM: Double? = null,
    val autoStopEnabled: Boolean = true,
    val autoStopRequested: Boolean = false,
    val videoRecording: Boolean = false,
    val videoStatus: String = "Видео готово",
    val pitEntryConfigured: Boolean = false,
    val pitExitConfigured: Boolean = false,
    val pitEntryPoint: GeoPoint? = null,
    val pitExitPoint: GeoPoint? = null,
    val pitLaneActive: Boolean = false,
    val pitLaneElapsedMs: Long = 0L,
    val pitLaneLastMs: Long? = null,
    val pitTimerActive: Boolean = false,
    val pitStartedElapsedMs: Long? = null,
    val pitLastMs: Long? = null,
    val pitBestMs: Long? = null,
    val pitStopCount: Int = 0,
    val pitLastTrigger: String = "—",
    val gm204Enabled: Boolean = true,
    val remoteDeviceName: String = "—",
    val remoteLastKey: String = "—",
    val remoteAction: RemoteAction = RemoteAction.NONE,
    val remoteActionSeq: Long = 0L,
    val pitLaneServerRunning: Boolean = false,
    val pitLaneUrls: List<String> = emptyList(),
    val internetPitRelayEnabled: Boolean = false,
    val internetPitRelayConfigured: Boolean = false,
    val internetPitRelayStatus: String = "OFF",
    val internetPitViewerUrl: String = "",
    val internetPitLastSuccessMs: Long? = null,
    val gX: Double = 0.0,
    val gY: Double = 0.0,
    val gZ: Double = 0.0,
    val gTotal: Double = 0.0,
    val longitudinalG: Double = 0.0,
    val lateralG: Double = 0.0,
    val verticalG: Double = 0.0,
    val yawDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val rollDeg: Double = 0.0,
    val imuCalibrated: Boolean = false,
    val mountDirection: MountDirection = MountDirection.TOP,
    val sensors: List<SensorSnapshot> = emptyList(),
    val availableSensorCount: Int = 0,
    val activeSensorCount: Int = 0,
    val obd: ObdState = ObdState(),
    val ev: EvState = EvState(),
    val customObd: List<CustomObdValue> = emptyList(),
    val supportedObdPids: Set<String> = emptySet(),
    val vehicleCan: VehicleCanState = VehicleCanState(),
    val canSignals: List<CanSignalValue> = emptyList(),
    val canFrameCount: Long = 0L,
    val canHz: Double = 0.0,
    val gpsSource: String = "phone",
    val gnssSourceMode: GnssSourceMode = GnssSourceMode.AUTO,
    val hdop: Double? = null,
    val vdop: Double? = null,
    val pdop: Double? = null,
    val trackPreview: List<GeoPoint> = emptyList(),
    val referenceTrack: List<GeoPoint> = emptyList(),
    val lastMessage: String = "Готов"
)
