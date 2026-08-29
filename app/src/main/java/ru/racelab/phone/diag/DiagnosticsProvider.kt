package ru.racelab.phone.diag

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import java.io.File

data class SystemDiagnostics(
    val batteryPct: Int = -1,
    val thermal: String = "UNKNOWN",
    val freeStorageGb: Double = 0.0,
    val cameraPermission: Boolean = false,
    val locationPermission: Boolean = false,
    val micPermission: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val hasCamera: Boolean = false,
    val timestampMs: Long = 0L
)

object DiagnosticsProvider {
    fun collect(context: Context): SystemDiagnostics {
        val app = context.applicationContext
        val battery = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val bt = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val dir: File = app.getExternalFilesDir(null) ?: app.filesDir
        val stat = StatFs(dir.absolutePath)

        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "NORMAL"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN"
            }
        } else "N/A"

        return SystemDiagnostics(
            batteryPct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            thermal = thermal,
            freeStorageGb = stat.availableBytes / 1_073_741_824.0,
            cameraPermission = ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            locationPermission = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            micPermission = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            bluetoothEnabled = bt.adapter?.isEnabled == true,
            hasCamera = app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            timestampMs = System.currentTimeMillis()
        )
    }
}
