package ru.racelab.phone.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import ru.racelab.phone.data.RaceRuntime

/**
 * Lightweight live sensor monitor for the UI.
 * It runs only while MainActivity is visible and never writes to session CSV.
 * RecordingService owns persisted high-rate logging during a session.
 */
class PhoneSensorMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var running = false

    fun start() {
        if (running) return
        running = true
        val allowedTypes = setOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR
        )
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .filter { it.type in allowedTypes && it.reportingMode != Sensor.REPORTING_MODE_ONE_SHOT }
        RaceRuntime.setAvailableSensors(sensors.size)

        sensors.forEach { sensor ->
            runCatching {
                sensorManager.registerListener(this, sensor, 20_000, 0)
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        RaceRuntime.updateSensor(
            ts = System.currentTimeMillis(),
            sensor = event.sensor,
            accuracy = event.accuracy,
            values = event.values.copyOf(),
            persist = false
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
