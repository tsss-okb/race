package ru.racelab.phone.sensor

import android.hardware.Sensor
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.sqrt

enum class MountDirection(val label: String) {
    TOP("Верх телефона вперёд"),
    BOTTOM("Низ телефона вперёд"),
    LEFT("Левый край вперёд"),
    RIGHT("Правый край вперёд")
}

data class MotionState(
    val longitudinalG: Double = 0.0,
    val lateralG: Double = 0.0,
    val verticalG: Double = 0.0,
    val totalG: Double = 0.0,
    val yawDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val rollDeg: Double = 0.0,
    val calibrated: Boolean = false,
    val mountDirection: MountDirection = MountDirection.TOP
)

object MotionProcessor {
    private val currentR = FloatArray(9)
    private var hasRotation = false
    private var baselineR: FloatArray? = null
    private var direction = MountDirection.TOP
    private var lastLinear = floatArrayOf(0f, 0f, 0f)
    private var state = MotionState()

    @Synchronized
    fun setMountDirection(value: MountDirection): MotionState {
        direction = value
        state = state.copy(mountDirection = value)
        return state
    }

    @Synchronized
    fun calibrate(): MotionState {
        if (hasRotation) baselineR = currentR.copyOf()
        state = state.copy(calibrated = baselineR != null, yawDeg = 0.0, pitchDeg = 0.0, rollDeg = 0.0)
        return state
    }

    @Synchronized
    fun resetCalibration(): MotionState {
        baselineR = null
        state = state.copy(calibrated = false)
        return state
    }

    @Synchronized
    fun onSensor(type: Int, values: FloatArray): MotionState {
        when (type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                if (values.size >= 3) {
                    SensorManager.getRotationMatrixFromVector(currentR, values)
                    hasRotation = true
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (values.size >= 3) lastLinear = floatArrayOf(values[0], values[1], values[2])
            }
        }

        val vehicle = transformToBaseline(lastLinear)
        val rawX = vehicle[0] / 9.80665
        val rawY = vehicle[1] / 9.80665
        val rawZ = vehicle[2] / 9.80665

        val longitudinal: Double
        val lateral: Double
        when (direction) {
            MountDirection.TOP -> {
                longitudinal = rawY
                lateral = rawX
            }
            MountDirection.BOTTOM -> {
                longitudinal = -rawY
                lateral = -rawX
            }
            MountDirection.LEFT -> {
                longitudinal = -rawX
                lateral = rawY
            }
            MountDirection.RIGHT -> {
                longitudinal = rawX
                lateral = -rawY
            }
        }

        val angles = relativeAngles()
        val total = sqrt(longitudinal * longitudinal + lateral * lateral + rawZ * rawZ)
        state = MotionState(
            longitudinalG = longitudinal,
            lateralG = lateral,
            verticalG = rawZ,
            totalG = total,
            yawDeg = angles[0],
            pitchDeg = angles[1],
            rollDeg = angles[2],
            calibrated = baselineR != null,
            mountDirection = direction
        )
        return state
    }

    @Synchronized
    fun current(): MotionState = state

    private fun transformToBaseline(v: FloatArray): DoubleArray {
        if (!hasRotation) return doubleArrayOf(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
        val world = mul(currentR, v)
        val base = baselineR ?: return world
        return mulTranspose(base, world)
    }

    private fun relativeAngles(): DoubleArray {
        if (!hasRotation) return doubleArrayOf(0.0, 0.0, 0.0)
        val base = baselineR
        val matrix = if (base == null) currentR.copyOf() else relative(base, currentR)
        val out = FloatArray(3)
        SensorManager.getOrientation(matrix, out)
        return doubleArrayOf(
            out[0] * 180.0 / PI,
            out[1] * 180.0 / PI,
            out[2] * 180.0 / PI
        )
    }

    private fun mul(r: FloatArray, v: FloatArray): DoubleArray = doubleArrayOf(
        (r[0] * v[0] + r[1] * v[1] + r[2] * v[2]).toDouble(),
        (r[3] * v[0] + r[4] * v[1] + r[5] * v[2]).toDouble(),
        (r[6] * v[0] + r[7] * v[1] + r[8] * v[2]).toDouble()
    )

    private fun mulTranspose(r: FloatArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        r[0] * v[0] + r[3] * v[1] + r[6] * v[2],
        r[1] * v[0] + r[4] * v[1] + r[7] * v[2],
        r[2] * v[0] + r[5] * v[1] + r[8] * v[2]
    )

    private fun relative(base: FloatArray, current: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                var sum = 0f
                for (k in 0..2) sum += base[k * 3 + row] * current[k * 3 + col]
                out[row * 3 + col] = sum
            }
        }
        return out
    }
}
