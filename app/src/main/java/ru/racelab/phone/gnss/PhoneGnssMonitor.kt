package ru.racelab.phone.gnss

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import ru.racelab.phone.data.RaceRuntime
import ru.racelab.phone.track.TrackRepository

class PhoneGnssMonitor(context: Context) : LocationListener {
    private val app = context.applicationContext
    private val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false
    private var lastNearestCheck = 0L

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            if (RaceRuntime.state.value.sessionActive) return
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            RaceRuntime.setSatellites(used)
        }
    }

    fun start() {
        if (running || RaceRuntime.state.value.sessionActive) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        running = true
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, this, Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 24) {
                locationManager.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper()))
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { locationManager.removeUpdates(this) }
        if (Build.VERSION.SDK_INT >= 24) runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
    }

    override fun onLocationChanged(location: Location) {
        if (RaceRuntime.state.value.sessionActive) return
        RaceRuntime.ingestLocation(location)
        val now = System.currentTimeMillis()
        if (now - lastNearestCheck < 1500) return
        lastNearestCheck = now
        val point = RaceRuntime.state.value.latestPoint ?: return
        val nearest = TrackRepository.nearest(app, point)
        RaceRuntime.setNearestTrack(nearest?.profile?.name, nearest?.distanceM)
        if (nearest != null && nearest.distanceM <= 250.0 && RaceRuntime.state.value.currentTrackId == null) {
            RaceRuntime.loadTrack(nearest.profile, auto = true)
        }
    }
}
