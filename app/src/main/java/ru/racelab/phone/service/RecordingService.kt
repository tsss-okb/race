package ru.racelab.phone.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import ru.racelab.phone.MainActivity
import ru.racelab.phone.data.RaceRuntime

class RecordingService : LifecycleService(), SensorEventListener, LocationListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var started = false

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            RaceRuntime.setSatellites(used)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            else -> startRecording()
        }
        return Service.START_STICKY
    }

    private fun startRecording() {
        if (started) return
        started = true
        startForeground(NOTIFICATION_ID, buildNotification())
        RaceRuntime.startSession(applicationContext)
        registerSensors()
        requestGnss()
    }

    private fun stopRecording() {
        if (!started) {
            stopSelf()
            return
        }
        started = false
        sensorManager.unregisterListener(this)
        runCatching { locationManager.removeUpdates(this) }
        if (Build.VERSION.SDK_INT >= 24) runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        RaceRuntime.stopSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerSensors() {
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        RaceRuntime.setAvailableSensors(sensors.size)
        sensors.forEach { sensor ->
            if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) return@forEach
            val periodUs = when (sensor.type) {
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_GAME_ROTATION_VECTOR,
                Sensor.TYPE_ROTATION_VECTOR -> 10_000
                Sensor.TYPE_MAGNETIC_FIELD,
                Sensor.TYPE_GRAVITY -> 20_000
                else -> 50_000
            }
            runCatching { sensorManager.registerListener(this, sensor, periodUs, 0) }
        }
    }

    private fun requestGnss() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            RaceRuntime.markMessage("Нет разрешения GPS")
            return
        }
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 24) locationManager.registerGnssStatusCallback(gnssCallback, android.os.Handler(mainLooper))
        }.onFailure { RaceRuntime.markMessage("GNSS: ${it.message}") }
    }

    override fun onLocationChanged(location: Location) {
        RaceRuntime.ingestLocation(location)
        if (RaceRuntime.state.value.autoStopRequested) {
            RaceRuntime.markMessage("Автостоп: машина стоит после заезда")
            stopRecording()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        RaceRuntime.updateSensor(System.currentTimeMillis(), event.sensor, event.accuracy, event.values.copyOf())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        if (started) stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "RaceLab recording", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("RaceLab — запись сессии")
        .setContentText("GNSS и датчики телефона записываются")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(
            android.R.drawable.ic_media_pause,
            "Стоп",
            PendingIntent.getService(
                this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    companion object {
        const val ACTION_START = "ru.racelab.phone.START"
        const val ACTION_STOP = "ru.racelab.phone.STOP"
        private const val CHANNEL = "racelab_recording"
        private const val NOTIFICATION_ID = 1101
    }
}
