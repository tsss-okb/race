package ru.racelab.phone.gnss

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.core.NmeaParser
import ru.racelab.phone.core.NmeaQuality
import ru.racelab.phone.core.NmeaQualityParser

data class UsbGnssDeviceItem(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val ports: Int
)

data class UsbGnssState(
    val status: String = "USB GNSS отключён",
    val devices: List<UsbGnssDeviceItem> = emptyList(),
    val connectedDeviceId: Int? = null,
    val baudRate: Int = 115200,
    val bytesReceived: Long = 0L
)

class UsbNmeaManager(
    context: Context,
    private val onPoint: (GeoPoint) -> Unit,
    private val onQuality: (NmeaQuality) -> Unit = {}
) : SerialInputOutputManager.Listener {

    private val app = context.applicationContext
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val lineBuffer = StringBuilder()
    private var pendingDeviceId: Int? = null
    private var pendingBaud = 115200
    private var bytesReceived = 0L
    private var receiverRegistered = false

    private val _state = MutableStateFlow(UsbGnssState())
    val state: StateFlow<UsbGnssState> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val device = getUsbDevice(intent)
                    if (device != null && usbManager.hasPermission(device)) {
                        open(device.deviceId, pendingBaud)
                    } else {
                        _state.value = _state.value.copy(status = "USB permission отклонён")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getUsbDevice(intent)
                    if (device?.deviceId == _state.value.connectedDeviceId) {
                        disconnect()
                        refresh()
                    }
                }
            }
        }
    }

    init {
        registerReceiver()
        refresh()
    }

    fun refresh() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val devices = drivers.map { driver ->
            UsbGnssDeviceItem(
                deviceId = driver.device.deviceId,
                name = driver.device.productName ?: driver.device.deviceName ?: "USB Serial",
                vendorId = driver.device.vendorId,
                productId = driver.device.productId,
                ports = driver.ports.size
            )
        }
        _state.value = _state.value.copy(
            devices = devices,
            status = when {
                _state.value.connectedDeviceId != null -> _state.value.status
                devices.isEmpty() -> "USB serial не найден"
                else -> "Найдено USB serial: " + devices.size
            }
        )
    }

    fun connect(deviceId: Int, baudRate: Int) {
        pendingDeviceId = deviceId
        pendingBaud = baudRate
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == deviceId }
        if (driver == null) {
            _state.value = _state.value.copy(status = "USB устройство исчезло")
            refresh()
            return
        }
        if (!usbManager.hasPermission(driver.device)) {
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(
                driver.device,
                PendingIntent.getBroadcast(app, deviceId, intent, flags)
            )
            _state.value = _state.value.copy(status = "Разреши доступ к USB GNSS")
            return
        }
        open(deviceId, baudRate)
    }

    private fun open(deviceId: Int, baudRate: Int) {
        disconnect()
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == deviceId }
            ?: run {
                _state.value = _state.value.copy(status = "USB driver не найден")
                return
            }
        val conn = usbManager.openDevice(driver.device)
            ?: run {
                _state.value = _state.value.copy(status = "Не удалось открыть USB")
                return
            }
        val serialPort = driver.ports.firstOrNull()
            ?: run {
                conn.close()
                _state.value = _state.value.copy(status = "Нет serial port")
                return
            }

        runCatching {
            serialPort.open(conn)
            serialPort.setParameters(
                baudRate,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            runCatching { serialPort.setDTR(true) }
            runCatching { serialPort.setRTS(true) }
            val manager = SerialInputOutputManager(serialPort, this)
            manager.start()
            connection = conn
            port = serialPort
            ioManager = manager
            bytesReceived = 0L
            _state.value = _state.value.copy(
                status = "USB NMEA активен • " + baudRate + " baud",
                connectedDeviceId = deviceId,
                baudRate = baudRate,
                bytesReceived = 0L
            )
        }.onFailure {
            runCatching { serialPort.close() }
            conn.close()
            _state.value = _state.value.copy(status = "USB open: " + (it.message ?: "ошибка"))
        }
    }

    override fun onNewData(data: ByteArray) {
        bytesReceived += data.size
        _state.value = _state.value.copy(bytesReceived = bytesReceived)
        consume(data)
    }

    override fun onRunError(e: Exception) {
        _state.value = _state.value.copy(status = "USB serial: " + (e.message ?: "ошибка"))
        disconnect()
    }

    private fun consume(bytes: ByteArray) {
        lineBuffer.append(bytes.toString(Charsets.US_ASCII))
        while (true) {
            val n = lineBuffer.indexOf("\n")
            val r = lineBuffer.indexOf("\r")
            val idx = listOf(n, r).filter { it >= 0 }.minOrNull() ?: break
            val line = lineBuffer.substring(0, idx).trim()
            lineBuffer.delete(0, idx + 1)
            if (line.startsWith("$")) {
                NmeaQualityParser.parse(line)?.let(onQuality)
                NmeaParser.parse(line, System.currentTimeMillis())
                    ?.copy(source = "usb_nmea")
                    ?.let(onPoint)
            }
        }
        if (lineBuffer.length > 8192) {
            lineBuffer.delete(0, lineBuffer.length - 2048)
        }
    }

    fun disconnect() {
        runCatching { ioManager?.stop() }
        ioManager = null
        runCatching { port?.close() }
        port = null
        runCatching { connection?.close() }
        connection = null
        lineBuffer.clear()
        _state.value = _state.value.copy(
            connectedDeviceId = null,
            status = "USB GNSS отключён"
        )
    }

    fun close() {
        disconnect()
        if (receiverRegistered) {
            runCatching { app.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            app.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    @Suppress("DEPRECATION")
    private fun getUsbDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val ACTION_USB_PERMISSION = "ru.racelab.phone.USB_PERMISSION"
        val COMMON_BAUD_RATES = listOf(9600, 38400, 57600, 115200, 230400, 460800)
    }
}
