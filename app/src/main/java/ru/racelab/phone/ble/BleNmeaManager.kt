package ru.racelab.phone.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.core.NmeaParser
import ru.racelab.phone.core.NmeaQuality
import ru.racelab.phone.core.NmeaQualityParser
import java.util.UUID

@SuppressLint("MissingPermission")
class BleNmeaManager(
    context: Context,
    private val onPoint: (GeoPoint) -> Unit,
    private val onQuality: (NmeaQuality) -> Unit = {}
) {
    private val app = context.applicationContext
    private val bluetoothManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private val devices = linkedMapOf<String, BleDeviceItem>()
    private val buffer = StringBuilder()

    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "BLE GPS"
            devices[result.device.address] = BleDeviceItem(result.device.address, name, result.rssi)
            _state.value = _state.value.copy(devices = devices.values.sortedByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = _state.value.copy(scanning = false, status = "Ошибка scan: $errorCode")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = _state.value.copy(status = "Подключено, читаю сервисы…", connectedAddress = gatt.device.address)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _state.value = _state.value.copy(status = "Отключено", connectedAddress = null)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val candidates = gatt.services.flatMap { it.characteristics }
            notifyCharacteristic = candidates.firstOrNull { it.uuid == NUS_TX || it.uuid == FFE1 }
                ?: candidates.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
                ?: candidates.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 }
            val ch = notifyCharacteristic
            if (ch == null) {
                _state.value = _state.value.copy(status = "Не найдена notify-характеристика")
                return
            }
            enableNotifications(gatt, ch)
            _state.value = _state.value.copy(status = "NMEA поток активен")
        }

        @Deprecated("Legacy callback for API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            consume(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            consume(value)
        }
    }

    fun startScan() {
        devices.clear()
        _state.value = BleUiState(scanning = true, status = "Поиск BLE GPS…")
        scanner?.startScan(scanCallback) ?: run {
            _state.value = _state.value.copy(scanning = false, status = "Bluetooth недоступен")
        }
    }

    fun stopScan() {
        runCatching { scanner?.stopScan(scanCallback) }
        _state.value = _state.value.copy(scanning = false)
    }

    fun connect(address: String) {
        stopScan()
        disconnect()
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        _state.value = _state.value.copy(status = "Подключение ${device.address}…")
        gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        notifyCharacteristic = null
        _state.value = _state.value.copy(status = "Отключено", connectedAddress = null)
    }

    private fun consume(bytes: ByteArray) {
        val text = bytes.toString(Charsets.US_ASCII)
        buffer.append(text)
        while (true) {
            val idxN = buffer.indexOf("\n")
            val idxR = buffer.indexOf("\r")
            val idx = listOf(idxN, idxR).filter { it >= 0 }.minOrNull() ?: break
            val line = buffer.substring(0, idx).trim()
            buffer.delete(0, idx + 1)
            if (line.startsWith("$")) {
                NmeaQualityParser.parse(line)?.let(onQuality)
                NmeaParser.parse(line)?.let(onPoint)
            }
        }
        if (buffer.length > 4096) buffer.delete(0, buffer.length - 1024)
    }

    private fun enableNotifications(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(ch, true)
        val descriptor = ch.getDescriptor(CCCD) ?: return
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(descriptor, value)
        else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    companion object {
        private val NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val FFE1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
