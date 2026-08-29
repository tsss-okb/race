package ru.racelab.phone.canbus

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

data class UsbCanDeviceItem(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val ports: Int
)

data class UsbCanState(
    val status: String = "USB-CAN отключён",
    val devices: List<UsbCanDeviceItem> = emptyList(),
    val connectedDeviceId: Int? = null,
    val serialBaud: Int = 115200,
    val canBitrate: CanBitrate = CanBitrate.KBPS_500,
    val listenOnly: Boolean = true,
    val framesRx: Long = 0L,
    val lastFrame: CanFrame? = null
)

class UsbCanManager(
    context: Context,
    private val onFrame: (CanFrame) -> Unit,
    private val onSignal: (CanSignalValue) -> Unit
) : SerialInputOutputManager.Listener {

    private val app = context.applicationContext
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val buffer = StringBuilder()
    private var pendingDeviceId: Int? = null
    private var pendingSerialBaud = 115200
    private var pendingCanBitrate = CanBitrate.KBPS_500
    private var pendingListenOnly = true
    private var framesRx = 0L
    private var signals: List<CanSignal> = emptyList()
    private var lastSignalRefresh = 0L
    private var receiverRegistered = false

    private val _state = MutableStateFlow(UsbCanState())
    val state: StateFlow<UsbCanState> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val device = getUsbDevice(intent)
                    if (device != null && usbManager.hasPermission(device)) {
                        open(device.deviceId, pendingSerialBaud, pendingCanBitrate, pendingListenOnly)
                    } else {
                        _state.value = _state.value.copy(status = "USB-CAN permission отклонён")
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
            UsbCanDeviceItem(
                deviceId = driver.device.deviceId,
                name = driver.device.productName ?: driver.device.deviceName ?: "USB CAN/SLCAN",
                vendorId = driver.device.vendorId,
                productId = driver.device.productId,
                ports = driver.ports.size
            )
        }
        _state.value = _state.value.copy(
            devices = devices,
            status = when {
                _state.value.connectedDeviceId != null -> _state.value.status
                devices.isEmpty() -> "USB serial CAN не найден"
                else -> "Найдено USB serial: " + devices.size
            }
        )
    }

    fun connect(
        deviceId: Int,
        serialBaud: Int,
        canBitrate: CanBitrate,
        listenOnly: Boolean = true
    ) {
        pendingDeviceId = deviceId
        pendingSerialBaud = serialBaud
        pendingCanBitrate = canBitrate
        pendingListenOnly = listenOnly

        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == deviceId }
        if (driver == null) {
            _state.value = _state.value.copy(status = "USB-CAN устройство исчезло")
            refresh()
            return
        }
        if (!usbManager.hasPermission(driver.device)) {
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(
                driver.device,
                PendingIntent.getBroadcast(app, deviceId + 10_000, intent, flags)
            )
            _state.value = _state.value.copy(status = "Разреши доступ к USB-CAN")
            return
        }
        open(deviceId, serialBaud, canBitrate, listenOnly)
    }

    private fun open(
        deviceId: Int,
        serialBaud: Int,
        canBitrate: CanBitrate,
        listenOnly: Boolean
    ) {
        disconnect()
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == deviceId }
            ?: run {
                _state.value = _state.value.copy(status = "SLCAN driver не найден")
                return
            }
        val conn = usbManager.openDevice(driver.device)
            ?: run {
                _state.value = _state.value.copy(status = "Не удалось открыть USB-CAN")
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
                serialBaud,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            runCatching { serialPort.setDTR(true) }
            runCatching { serialPort.setRTS(true) }

            val io = SerialInputOutputManager(serialPort, this)
            io.start()
            connection = conn
            port = serialPort
            ioManager = io
            framesRx = 0L
            signals = CanSignalRepository.list(app).filter { it.enabled }
            lastSignalRefresh = System.currentTimeMillis()

            writeAscii("C\r")
            writeAscii(canBitrate.slcanCommand + "\r")
            writeAscii(if (listenOnly) "L\r" else "O\r")

            _state.value = _state.value.copy(
                status = "SLCAN " + canBitrate.bitsPerSecond / 1000 + " kbit/s • " +
                    if (listenOnly) "LISTEN" else "OPEN read-only app",
                connectedDeviceId = deviceId,
                serialBaud = serialBaud,
                canBitrate = canBitrate,
                listenOnly = listenOnly,
                framesRx = 0L,
                lastFrame = null
            )
        }.onFailure {
            runCatching { serialPort.close() }
            conn.close()
            _state.value = _state.value.copy(status = "USB-CAN open: " + (it.message ?: "ошибка"))
        }
    }

    fun reloadSignals() {
        signals = CanSignalRepository.list(app).filter { it.enabled }
        lastSignalRefresh = System.currentTimeMillis()
    }

    override fun onNewData(data: ByteArray) {
        buffer.append(data.toString(Charsets.US_ASCII))
        while (true) {
            val idx = buffer.indexOf("\r")
            if (idx < 0) break
            val line = buffer.substring(0, idx).trim()
            buffer.delete(0, idx + 1)
            handleLine(line)
        }
        if (buffer.length > 8192) buffer.delete(0, buffer.length - 2048)
    }

    private fun handleLine(line: String) {
        val frame = SlcanParser.parse(line) ?: return
        framesRx++
        _state.value = _state.value.copy(framesRx = framesRx, lastFrame = frame)
        onFrame(frame)

        val now = System.currentTimeMillis()
        if (now - lastSignalRefresh > 2_000L) reloadSignals()
        signals.forEach { signal ->
            CanSignalDecoder.decode(frame, signal)?.let { value ->
                onSignal(CanSignalValue(signal, value, frame.timestampMs))
            }
        }
    }

    override fun onRunError(e: Exception) {
        _state.value = _state.value.copy(status = "SLCAN serial: " + (e.message ?: "ошибка"))
        disconnect()
    }

    private fun writeAscii(command: String) {
        port?.write(command.toByteArray(Charsets.US_ASCII), 1000)
    }

    fun disconnect() {
        runCatching { writeAscii("C\r") }
        runCatching { ioManager?.stop() }
        ioManager = null
        runCatching { port?.close() }
        port = null
        runCatching { connection?.close() }
        connection = null
        buffer.clear()
        _state.value = _state.value.copy(
            connectedDeviceId = null,
            status = "USB-CAN отключён",
            framesRx = 0L,
            lastFrame = null
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
        private const val ACTION_USB_PERMISSION = "ru.racelab.phone.CAN_USB_PERMISSION"
        val SERIAL_BAUD_RATES = listOf(115200, 230400, 460800, 921600, 1_000_000, 2_000_000)
    }
}
