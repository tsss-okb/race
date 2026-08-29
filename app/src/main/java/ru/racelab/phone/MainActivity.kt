package ru.racelab.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.KeyEvent
import android.view.InputDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import ru.racelab.phone.ble.BleNmeaManager
import ru.racelab.phone.ble.Elm327Manager
import ru.racelab.phone.data.RaceRuntime
import ru.racelab.phone.service.RecordingService
import ru.racelab.phone.gnss.PhoneGnssMonitor
import ru.racelab.phone.gnss.UsbNmeaManager
import ru.racelab.phone.canbus.UsbCanManager
import ru.racelab.phone.sensor.PhoneSensorMonitor
import ru.racelab.phone.ui.RaceLabApp
import ru.racelab.phone.remote.RemoteAction
import ru.racelab.phone.remote.RemoteControlSettingsRepository

class MainActivity : ComponentActivity() {
    private lateinit var bleGps: BleNmeaManager
    private lateinit var obd: Elm327Manager
    private lateinit var phoneSensors: PhoneSensorMonitor
    private lateinit var phoneGnss: PhoneGnssMonitor
    private lateinit var usbGnss: UsbNmeaManager
    private lateinit var usbCan: UsbCanManager

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isEmpty()) {
            RaceRuntime.markMessage("Разрешения выданы")
            phoneGnss.start()
        } else RaceRuntime.markMessage("Часть разрешений не выдана: ${denied.size}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RaceRuntime.setGm204Enabled(RemoteControlSettingsRepository.isGm204Enabled(this))

        bleGps = BleNmeaManager(
            context = this,
            onPoint = { RaceRuntime.ingestPoint(it) },
            onQuality = { RaceRuntime.updateGnssQuality(it, "ble_nmea") }
        )
        usbGnss = UsbNmeaManager(
            context = this,
            onPoint = { RaceRuntime.ingestPoint(it) },
            onQuality = { RaceRuntime.updateGnssQuality(it, "usb_nmea") }
        )
        usbCan = UsbCanManager(
            context = this,
            onFrame = { RaceRuntime.updateCanFrame(it) },
            onSignal = { RaceRuntime.updateCanSignal(it) }
        )
        obd = Elm327Manager(
            context = this,
            onReading = { RaceRuntime.updateObd(it) },
            onCustom = { pid, value -> RaceRuntime.updateCustomObd(pid, value) },
            onSupported = { RaceRuntime.setSupportedObdPids(it) }
        )
        phoneSensors = PhoneSensorMonitor(this)
        phoneGnss = PhoneGnssMonitor(this)
        requestCorePermissions()

        setContent {
            RaceLabApp(
                bleGps = bleGps,
                usbGnss = usbGnss,
                usbCan = usbCan,
                obd = obd,
                onStartSession = { startNativeSession() },
                onStopSession = { stopNativeSession() },
                onRequestPermissions = { requestCorePermissions() }
            )
        }
    }

    private fun startNativeSession() {
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!locationGranted) {
            requestCorePermissions()
            RaceRuntime.markMessage("Сначала разреши точное местоположение")
            return
        }
        phoneGnss.stop()
        ContextCompat.startForegroundService(
            this,
            Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
        )
    }

    private fun stopNativeSession() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        window.decorView.postDelayed({ phoneGnss.start() }, 800L)
    }

    private fun requestCorePermissions() {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        val missing = list.distinct().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        phoneSensors.start()
        phoneGnss.start()
    }

    override fun onPause() {
        phoneSensors.stop()
        phoneGnss.stop()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val device = InputDevice.getDevice(event.deviceId)
            val external = device?.isExternal == true
            val keyName = KeyEvent.keyCodeToString(event.keyCode)
            if (external) RaceRuntime.reportRemoteKey(device?.name, keyName)

            if (external && RemoteControlSettingsRepository.isGm204Enabled(this)) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_CAMERA,
                    KeyEvent.KEYCODE_F1,
                    KeyEvent.KEYCODE_BUTTON_1,
                    KeyEvent.KEYCODE_VOLUME_UP,
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        RaceRuntime.togglePitTimer("GM204:" + keyName)
                        return true
                    }

                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_CENTER -> {
                        if (RaceRuntime.state.value.pitTimerActive) {
                            RaceRuntime.markMessage("PIT ACTIVE: сначала STOP кнопкой CAMERA")
                        } else {
                            RaceRuntime.resetPitTimer()
                            RaceRuntime.markMessage("GM204 OK: PIT сброшен")
                        }
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        RaceRuntime.postRemoteAction(RemoteAction.PREVIOUS_TAB)
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        RaceRuntime.postRemoteAction(RemoteAction.NEXT_TAB)
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        RaceRuntime.postRemoteAction(RemoteAction.DASHBOARD)
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        RaceRuntime.postRemoteAction(RemoteAction.DATA)
                        return true
                    }
                }
            }

            val directPitKeys = setOf(
                KeyEvent.KEYCODE_F1,
                KeyEvent.KEYCODE_BUTTON_1,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_CAMERA
            )
            val externalVolumeButton =
                external && event.keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)

            if (event.keyCode in directPitKeys || externalVolumeButton) {
                val source = when {
                    externalVolumeButton -> "HID:EXT_VOLUME"
                    else -> "HID:" + keyName
                }
                RaceRuntime.togglePitTimer(source)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        bleGps.disconnect()
        usbGnss.close()
        usbCan.close()
        obd.disconnect()
        super.onDestroy()
    }
}
