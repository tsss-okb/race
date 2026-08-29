package ru.racelab.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.racelab.phone.canbus.*
import ru.racelab.phone.data.AppState

private val CanPanel = Color(0xFF131A15)
private val CanGreen = Color(0xFF69FF72)
private val CanAmber = Color(0xFFFFCA58)
private val CanRed = Color(0xFFFF6B6B)
private val CanMuted = Color(0xFF93A096)

@Composable
fun CanSectionScreen(state: AppState, manager: UsbCanManager) {
    val context = LocalContext.current
    val ui by manager.state.collectAsStateWithLifecycle()
    var serialBaud by remember(ui.serialBaud) { mutableIntStateOf(ui.serialBaud) }
    var bitrate by remember(ui.canBitrate) { mutableStateOf(ui.canBitrate) }
    var listenOnly by remember(ui.listenOnly) { mutableStateOf(ui.listenOnly) }
    var serialMenu by remember { mutableStateOf(false) }
    var bitrateMenu by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(CanSignalRepository.list(context)) }
    var editing by remember { mutableStateOf<CanSignal?>(null) }
    var createNew by remember { mutableStateOf(false) }

    fun refreshProfiles() {
        profiles = CanSignalRepository.list(context)
        manager.reloadSignals()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().background(CanPanel, RoundedCornerShape(12.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("USB-CAN / SLCAN", color = CanGreen, fontWeight = FontWeight.Black)
            Text(ui.status, color = CanMuted, fontSize = 10.sp)
            Text(
                "RX " + ui.framesRx + " frames • " + "%.1f Hz".format(state.canHz),
                color = CanMuted,
                fontSize = 10.sp
            )
            ui.lastFrame?.let { f ->
                Text(
                    "0x" + f.id.toString(16).uppercase() + "  " +
                        f.data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                    color = CanAmber,
                    fontSize = 10.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { manager.refresh() },
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)
                ) { Text("REFRESH", fontSize = 8.sp) }
                Box {
                    OutlinedButton(
                        onClick = { serialMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                    ) { Text(serialBaud.toString(), fontSize = 8.sp) }
                    DropdownMenu(expanded = serialMenu, onDismissRequest = { serialMenu = false }) {
                        UsbCanManager.SERIAL_BAUD_RATES.forEach { b ->
                            DropdownMenuItem(
                                text = { Text(b.toString()) },
                                onClick = { serialBaud = b; serialMenu = false }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(
                        onClick = { bitrateMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                    ) { Text((bitrate.bitsPerSecond / 1000).toString() + "k", fontSize = 8.sp) }
                    DropdownMenu(expanded = bitrateMenu, onDismissRequest = { bitrateMenu = false }) {
                        CanBitrate.entries.forEach { b ->
                            DropdownMenuItem(
                                text = { Text((b.bitsPerSecond / 1000).toString() + " kbit/s") },
                                onClick = { bitrate = b; bitrateMenu = false }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = listenOnly, onCheckedChange = { listenOnly = it })
                    Text("LISTEN", fontSize = 8.sp)
                }
            }

            ui.devices.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF0D120E), RoundedCornerShape(8.dp)).padding(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text(
                            "VID " + "%04X".format(d.vendorId) + " PID " + "%04X".format(d.productId),
                            color = CanMuted,
                            fontSize = 8.sp
                        )
                    }
                    TextButton(
                        onClick = { manager.connect(d.deviceId, serialBaud, bitrate, listenOnly) }
                    ) {
                        Text(if (ui.connectedDeviceId == d.deviceId) "ON" else "CONNECT", fontSize = 8.sp)
                    }
                }
            }
            if (ui.connectedDeviceId != null) {
                OutlinedButton(onClick = { manager.disconnect() }) { Text("DISCONNECT") }
            }
        }

        VehicleCanMetrics(state)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CAN SIGNALS (" + profiles.size + ")",
                color = CanMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { createNew = true },
                colors = ButtonDefaults.buttonColors(containerColor = CanGreen, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) { Text("+ SIGNAL", fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }

        profiles.forEach { signal ->
            val live = state.canSignals.firstOrNull { it.signal.id == signal.id }
            Row(
                Modifier.fillMaxWidth().background(CanPanel, RoundedCornerShape(10.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(signal.name, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(
                        "0x" + signal.canId.toString(16).uppercase() +
                            " bit " + signal.startBit + ":" + signal.bitLength +
                            " • " + signal.byteOrder.name,
                        color = CanMuted,
                        fontSize = 8.sp
                    )
                    Text(
                        signal.channel.name +
                            (live?.let { " • " + "%.3f".format(it.value) + " " + signal.unit } ?: ""),
                        color = if (live != null) CanGreen else CanAmber,
                        fontSize = 9.sp
                    )
                }
                TextButton(onClick = { editing = signal }) { Text("EDIT", fontSize = 8.sp) }
                TextButton(
                    onClick = {
                        CanSignalRepository.delete(context, signal.id)
                        refreshProfiles()
                    }
                ) { Text("×", color = CanRed) }
            }
        }
    }

    if (createNew) {
        CanSignalDialog(
            initial = CanSignal(name = "CAN signal", canId = 0x123),
            onDismiss = { createNew = false },
            onSave = {
                CanSignalRepository.save(context, it)
                createNew = false
                refreshProfiles()
            }
        )
    }

    editing?.let { signal ->
        CanSignalDialog(
            initial = signal,
            onDismiss = { editing = null },
            onSave = {
                CanSignalRepository.save(context, it)
                editing = null
                refreshProfiles()
            }
        )
    }
}

@Composable
private fun VehicleCanMetrics(state: AppState) {
    val v = state.vehicleCan
    val rows = listOf(
        Triple("GEAR", v.gear, ""),
        Triple("STEERING", v.steeringDeg, "°"),
        Triple("BRAKE", v.brakePressureBar, "bar"),
        Triple("WHEEL FL", v.wheelFlKmh, "km/h"),
        Triple("WHEEL FR", v.wheelFrKmh, "km/h"),
        Triple("WHEEL RL", v.wheelRlKmh, "km/h"),
        Triple("WHEEL RR", v.wheelRrKmh, "km/h")
    )
    rows.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { m ->
                Column(
                    Modifier.weight(1f).background(CanPanel, RoundedCornerShape(10.dp)).padding(8.dp)
                ) {
                    Text(m.first, color = CanMuted, fontSize = 8.sp)
                    Text(
                        m.second?.let { "%.2f".format(it) } ?: "—",
                        color = if (m.second == null) CanMuted else CanGreen,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(m.third, color = CanMuted, fontSize = 8.sp)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CanSignalDialog(
    initial: CanSignal,
    onDismiss: () -> Unit,
    onSave: (CanSignal) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var canId by remember(initial) { mutableStateOf(initial.canId.toString(16).uppercase()) }
    var extended by remember(initial) { mutableStateOf(initial.extended) }
    var startBit by remember(initial) { mutableStateOf(initial.startBit.toString()) }
    var bitLength by remember(initial) { mutableStateOf(initial.bitLength.toString()) }
    var order by remember(initial) { mutableStateOf(initial.byteOrder) }
    var signed by remember(initial) { mutableStateOf(initial.signed) }
    var scale by remember(initial) { mutableStateOf(initial.scale.toString()) }
    var offset by remember(initial) { mutableStateOf(initial.offset.toString()) }
    var unit by remember(initial) { mutableStateOf(initial.unit) }
    var channel by remember(initial) { mutableStateOf(initial.channel) }
    var orderMenu by remember { mutableStateOf(false) }
    var channelMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CAN SIGNAL") },
        confirmButton = {
            Button(
                onClick = {
                    val parsedId = canId.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: initial.canId
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { "CAN signal" },
                            canId = parsedId,
                            extended = extended,
                            startBit = startBit.toIntOrNull()?.coerceIn(0, 63) ?: 0,
                            bitLength = bitLength.toIntOrNull()?.coerceIn(1, 32) ?: 8,
                            byteOrder = order,
                            signed = signed,
                            scale = scale.toDoubleOrNull() ?: 1.0,
                            offset = offset.toDoubleOrNull() ?: 0.0,
                            unit = unit.trim(),
                            channel = channel
                        )
                    )
                }
            ) { Text("SAVE") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(canId, { canId = it }, label = { Text("CAN ID hex") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("29-bit extended", Modifier.weight(1f))
                    Switch(extended, { extended = it })
                }
                OutlinedTextField(startBit, { startBit = it }, label = { Text("Start bit") }, singleLine = true)
                OutlinedTextField(bitLength, { bitLength = it }, label = { Text("Bit length 1..32") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { orderMenu = true }) { Text("Endian: " + order.name) }
                    DropdownMenu(expanded = orderMenu, onDismissRequest = { orderMenu = false }) {
                        CanByteOrder.entries.forEach { o ->
                            DropdownMenuItem(
                                text = { Text(o.name) },
                                onClick = { order = o; orderMenu = false }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Signed", Modifier.weight(1f))
                    Switch(signed, { signed = it })
                }
                OutlinedTextField(scale, { scale = it }, label = { Text("Scale") }, singleLine = true)
                OutlinedTextField(offset, { offset = it }, label = { Text("Offset") }, singleLine = true)
                OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { channelMenu = true }) { Text("Channel: " + channel.name) }
                    DropdownMenu(expanded = channelMenu, onDismissRequest = { channelMenu = false }) {
                        CanChannel.entries.forEach { ch ->
                            DropdownMenuItem(
                                text = { Text(ch.name) },
                                onClick = { channel = ch; channelMenu = false }
                            )
                        }
                    }
                }
                Text(
                    "BIG_ENDIAN использует сетевую нумерацию битов (bit 0 = MSB первого байта).",
                    color = CanAmber,
                    fontSize = 9.sp
                )
            }
        }
    )
}
