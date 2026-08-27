package ru.racelab.phone.data

import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.core.LapResult

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
    val coolantC: Double? = null
)

data class AppState(
    val sessionActive: Boolean = false,
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
    val laps: List<LapResult> = emptyList(),
    val startConfigured: Boolean = false,
    val sectorCount: Int = 0,
    val gX: Double = 0.0,
    val gY: Double = 0.0,
    val gZ: Double = 0.0,
    val gTotal: Double = 0.0,
    val sensors: List<SensorSnapshot> = emptyList(),
    val availableSensorCount: Int = 0,
    val activeSensorCount: Int = 0,
    val obd: ObdState = ObdState(),
    val gpsSource: String = "phone",
    val trackPreview: List<GeoPoint> = emptyList(),
    val lastMessage: String = "Готов"
)
