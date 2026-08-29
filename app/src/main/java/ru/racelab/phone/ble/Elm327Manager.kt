package ru.racelab.phone.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.racelab.phone.core.ObdParser
import ru.racelab.phone.core.ObdReading
import ru.racelab.phone.obd.CustomPid
import ru.racelab.phone.obd.CustomPidRepository
import java.util.UUID

@SuppressLint("MissingPermission")
class Elm327Manager(
    context: Context,
    private val onReading: (ObdReading) -> Unit,
    private val onCustom: (CustomPid, Double) -> Unit = { _, _ -> },
    private val onSupported: (Set<String>) -> Unit = {}
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = manager.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private val handler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, BleDeviceItem>()
    private val buffer = StringBuilder()
    private var gatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var pollIndex = 0
    private var ready = false
    private val supportedPids = linkedSetOf<String>()
    private var customPids: List<CustomPid> = emptyList()
    private var lastCustomRefresh = 0L

    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "BLE OBD"
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
                _state.value = _state.value.copy(
                    status = "Подключено, читаю сервисы…",
                    connectedAddress = gatt.device.address
                )
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                stopPolling()
                _state.value = _state.value.copy(status = "Отключено", connectedAddress = null)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val all = gatt.services.flatMap { it.characteristics }
            notifyChar = all.firstOrNull { it.uuid == FFE1 || it.uuid == NUS_TX }
                ?: all.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                }
            writeChar = all.firstOrNull { it.uuid == FFE1 || it.uuid == NUS_RX }
                ?: all.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
            notifyChar?.let { enableNotifications(gatt, it) }
            if (writeChar == null) {
                _state.value = _state.value.copy(status = "Не найдена write-характеристика ELM327")
                return
            }
            _state.value = _state.value.copy(status = "Инициализация ELM327…")
            initElm()
        }

        @Deprecated("Legacy callback for API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            consume(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            consume(value)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!ready || writeChar == null) return
            refreshCustomPidsIfNeeded()
            val commands = buildPollCommands()
            if (commands.isNotEmpty()) {
                write(commands[pollIndex % commands.size] + "\r")
                pollIndex++
            }
            handler.postDelayed(this, 140L)
        }
    }

    fun startScan() {
        devices.clear()
        _state.value = BleUiState(scanning = true, status = "Поиск BLE OBD-II…")
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
        ready = false
        stopPolling()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        notifyChar = null
        writeChar = null
        supportedPids.clear()
        _state.value = _state.value.copy(status = "Отключено", connectedAddress = null)
    }

    fun reloadCustomPids() {
        lastCustomRefresh = 0L
        refreshCustomPidsIfNeeded()
    }

    private fun initElm() {
        supportedPids.clear()
        val commands = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
        commands.forEachIndexed { i, cmd ->
            handler.postDelayed({ write(cmd + "\r") }, i * 320L)
        }
        val baseDelay = commands.size * 320L + 400L
        listOf("0100", "0120", "0140", "0160").forEachIndexed { i, cmd ->
            handler.postDelayed({ write(cmd + "\r") }, baseDelay + i * 360L)
        }
        handler.postDelayed({
            ready = true
            customPids = CustomPidRepository.list(app).filter { it.enabled }
            lastCustomRefresh = System.currentTimeMillis()
            _state.value = _state.value.copy(
                status = if (supportedPids.isEmpty()) "OBD-II активен" else "OBD-II активен • ${supportedPids.size} PID"
            )
            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)
        }, baseDelay + 4 * 360L + 400L)
    }

    private fun buildPollCommands(): List<String> {
        val standard = listOf(
            "0104", "0105", "0106", "0107", "010A", "010B", "010C",
            "010D", "010E", "010F", "0110", "0111", "0142", "015C"
        )
        val filtered = if (supportedPids.isEmpty()) standard else standard.filter { it in supportedPids }
        return (filtered + customPids.map { it.request.trim().uppercase().replace(" ", "") })
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun refreshCustomPidsIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCustomRefresh < 2_000L) return
        customPids = CustomPidRepository.list(app).filter { it.enabled }
        lastCustomRefresh = now
    }

    private fun stopPolling() = handler.removeCallbacks(pollRunnable)

    private fun write(text: String) {
        val g = gatt ?: return
        val ch = writeChar ?: return
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, bytes, type)
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = type
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    private fun consume(bytes: ByteArray) {
        buffer.append(bytes.toString(Charsets.US_ASCII))
        if (!buffer.contains('>') && buffer.length <= 512) return

        val raw = buffer.toString()
        buffer.clear()

        ObdParser.parse(raw)?.let(onReading)

        var supportChanged = false
        listOf(0x00, 0x20, 0x40, 0x60).forEach { base ->
            val found = ObdParser.parseSupportedPids(raw, base)
            if (found.isNotEmpty() && supportedPids.addAll(found)) supportChanged = true
        }
        if (supportChanged) {
            onSupported(supportedPids.toSet())
            _state.value = _state.value.copy(status = "OBD-II активен • ${supportedPids.size} PID")
        }

        customPids.forEach { pid ->
            CustomPidRepository.parse(raw, pid)?.let { onCustom(pid, it) }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(ch, true)
        val descriptor = ch.getDescriptor(CCCD) ?: return
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    companion object {
        private val FFE1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
