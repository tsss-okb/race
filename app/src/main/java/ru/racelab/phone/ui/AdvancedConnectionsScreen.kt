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
import ru.racelab.phone.ble.BleNmeaManager
import ru.racelab.phone.ble.BleUiState
import ru.racelab.phone.ble.Elm327Manager
import ru.racelab.phone.data.AppState
import ru.racelab.phone.obd.CustomPid
import ru.racelab.phone.obd.CustomPidRepository
import ru.racelab.phone.obd.EvChannel

private val ObdPanel = Color(0xFF131A15)
private val ObdGreen = Color(0xFF69FF72)
private val ObdAmber = Color(0xFFFFCA58)
private val ObdRed = Color(0xFFFF6B6B)
private val ObdMuted = Color(0xFF93A096)

@Composable
fun AdvancedConnectionsScreen(
    state: AppState,
    bleGps: BleNmeaManager,
    obd: Elm327Manager
) {
    val gps by bleGps.state.collectAsStateWithLifecycle()
    val obdUi by obd.state.collectAsStateWithLifecycle()
    var section by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth().background(ObdPanel, RoundedCornerShape(10.dp)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            ObdTab("LINK", section == 0, Modifier.weight(1f)) { section = 0 }
            ObdTab("ENGINE", section == 1, Modifier.weight(1f)) { section = 1 }
            ObdTab("EV", section == 2, Modifier.weight(1f)) { section = 2 }
            ObdTab("CUSTOM", section == 3, Modifier.weight(1f)) { section = 3 }
        }
        Spacer(Modifier.height(6.dp))
        when (section) {
            0 -> LinkSection(gps, obdUi, bleGps, obd)
            1 -> EngineSection(state)
            2 -> EvSection(state)
            else -> CustomPidSection(state, obd)
        }
    }
}

@Composable
private fun ObdTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) ObdGreen else Color.Transparent,
            contentColor = if (selected) Color.Black else ObdMuted
        ),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun LinkSection(
    gps: BleUiState,
    obdUi: BleUiState,
    bleGps: BleNmeaManager,
    obd: Elm327Manager
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompactDevicePanel(
            title = "BLE GPS / NMEA",
            ui = gps,
            scan = { bleGps.startScan() },
            connect = { bleGps.connect(it) },
            disconnect = { bleGps.disconnect() }
        )
        CompactDevicePanel(
            title = "BLE OBD-II / ELM327",
            ui = obdUi,
            scan = { obd.startScan() },
            connect = { obd.connect(it) },
            disconnect = { obd.disconnect() }
        )
    }
}

@Composable
private fun CompactDevicePanel(
    title: String,
    ui: BleUiState,
    scan: () -> Unit,
    connect: (String) -> Unit,
    disconnect: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().background(ObdPanel, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(title, color = ObdGreen, fontWeight = FontWeight.Black)
        Text(ui.status, color = ObdMuted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = scan, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text(if (ui.scanning) "SCAN…" else "SCAN", fontSize = 10.sp)
            }
            OutlinedButton(onClick = disconnect, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text("OFF", fontSize = 10.sp)
            }
        }
        ui.devices.take(8).forEach { d ->
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF0D120E), RoundedCornerShape(8.dp)).padding(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(d.address + " • " + d.rssi + " dBm", color = ObdMuted, fontSize = 9.sp)
                }
                TextButton(onClick = { connect(d.address) }) { Text("CONNECT", fontSize = 9.sp) }
            }
        }
    }
}

@Composable
private fun EngineSection(state: AppState) {
    val o = state.obd
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            "SUPPORTED PID: " + state.supportedObdPids.size,
            color = ObdMuted,
            fontSize = 11.sp
        )
        MetricGrid(
            listOf(
                Triple("RPM", o.rpm, "rpm"),
                Triple("SPEED", o.speedKmh, "km/h"),
                Triple("THROTTLE", o.throttlePct, "%"),
                Triple("LOAD", o.engineLoadPct, "%"),
                Triple("COOLANT", o.coolantC, "°C"),
                Triple("OIL", o.oilTempC, "°C"),
                Triple("INTAKE", o.intakeC, "°C"),
                Triple("MAP", o.mapKpa, "kPa"),
                Triple("FUEL P", o.fuelPressureKpa, "kPa"),
                Triple("TIMING", o.timingDeg, "°"),
                Triple("MAF", o.mafGps, "g/s"),
                Triple("VOLT", o.voltageV, "V"),
                Triple("STFT", o.shortTrimPct, "%"),
                Triple("LTFT", o.longTrimPct, "%")
            )
        )
    }
}

@Composable
private fun EvSection(state: AppState) {
    val e = state.ev
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "EV-каналы заполняются Custom PID профилем конкретного автомобиля.",
            color = ObdMuted,
            fontSize = 11.sp
        )
        MetricGrid(
            listOf(
                Triple("SOC", e.socPct, "%"),
                Triple("BATTERY", e.batteryPowerKw, "kW"),
                Triple("MOTOR", e.motorPowerKw, "kW"),
                Triple("REGEN", e.regenKw, "kW"),
                Triple("BAT TEMP", e.batteryTempC, "°C"),
                Triple("INV TEMP", e.inverterTempC, "°C")
            )
        )
        if (state.customObd.isNotEmpty()) {
            Text("CUSTOM LIVE", color = ObdMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            state.customObd.forEach { v ->
                Row(
                    Modifier.fillMaxWidth().background(ObdPanel, RoundedCornerShape(9.dp)).padding(9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(v.name, fontWeight = FontWeight.Bold)
                    Text("%.3f ".format(v.value) + v.unit, color = ObdGreen)
                }
            }
        }
    }
}

@Composable
private fun MetricGrid(metrics: List<Triple<String, Double?, String>>) {
    metrics.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            row.forEach { m ->
                Column(
                    Modifier.weight(1f).background(ObdPanel, RoundedCornerShape(11.dp)).padding(10.dp)
                ) {
                    Text(m.first, color = ObdMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(
                        m.second?.let { "%.2f".format(it) } ?: "—",
                        color = if (m.second == null) ObdMuted else ObdGreen,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(m.third, color = ObdMuted, fontSize = 9.sp)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CustomPidSection(state: AppState, obd: Elm327Manager) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(CustomPidRepository.list(context)) }
    var editing by remember { mutableStateOf<CustomPid?>(null) }
    var createNew by remember { mutableStateOf(false) }

    fun refresh() {
        profiles = CustomPidRepository.list(context)
        obd.reloadCustomPids()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Button(
            onClick = { createNew = true },
            colors = ButtonDefaults.buttonColors(containerColor = ObdGreen, contentColor = Color.Black)
        ) {
            Text("+ CUSTOM PID", fontWeight = FontWeight.Black)
        }

        profiles.forEach { pid ->
            val live = state.customObd.firstOrNull { it.id == pid.id }
            Column(
                Modifier.fillMaxWidth().background(ObdPanel, RoundedCornerShape(11.dp)).padding(9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pid.name, fontWeight = FontWeight.Bold)
                        Text(
                            pid.request + " → " + pid.responsePrefix +
                                " • x" + pid.scale + " " + (if (pid.offset >= 0) "+" else "") + pid.offset,
                            color = ObdMuted,
                            fontSize = 9.sp
                        )
                        Text(
                            pid.evChannel.name + (live?.let { " • " + "%.3f".format(it.value) + " " + it.unit } ?: ""),
                            color = if (live != null) ObdGreen else ObdAmber,
                            fontSize = 10.sp
                        )
                    }
                    TextButton(onClick = { editing = pid }) { Text("EDIT", fontSize = 9.sp) }
                    TextButton(
                        onClick = {
                            CustomPidRepository.delete(context, pid.id)
                            refresh()
                        }
                    ) { Text("×", color = ObdRed) }
                }
            }
        }
    }

    if (createNew) {
        CustomPidDialog(
            initial = CustomPid(
                name = "Custom PID",
                request = "22F40D",
                responsePrefix = "62F40D",
                unit = ""
            ),
            onDismiss = { createNew = false },
            onSave = {
                CustomPidRepository.save(context, it)
                createNew = false
                refresh()
            }
        )
    }

    editing?.let { pid ->
        CustomPidDialog(
            initial = pid,
            onDismiss = { editing = null },
            onSave = {
                CustomPidRepository.save(context, it)
                editing = null
                refresh()
            }
        )
    }
}

@Composable
private fun CustomPidDialog(
    initial: CustomPid,
    onDismiss: () -> Unit,
    onSave: (CustomPid) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var request by remember(initial) { mutableStateOf(initial.request) }
    var prefix by remember(initial) { mutableStateOf(initial.responsePrefix) }
    var byteCount by remember(initial) { mutableIntStateOf(initial.byteCount) }
    var scale by remember(initial) { mutableStateOf(initial.scale.toString()) }
    var offset by remember(initial) { mutableStateOf(initial.offset.toString()) }
    var unit by remember(initial) { mutableStateOf(initial.unit) }
    var signed by remember(initial) { mutableStateOf(initial.signed) }
    var ev by remember(initial) { mutableStateOf(initial.evChannel) }
    var evMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CUSTOM PID") },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { "Custom PID" },
                            request = request.trim().uppercase().replace(" ", ""),
                            responsePrefix = prefix.trim().uppercase().replace(" ", ""),
                            byteCount = byteCount.coerceIn(1, 4),
                            scale = scale.toDoubleOrNull() ?: 1.0,
                            offset = offset.toDoubleOrNull() ?: 0.0,
                            signed = signed,
                            unit = unit.trim(),
                            evChannel = ev
                        )
                    )
                }
            ) { Text("SAVE") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(request, { request = it }, label = { Text("Request hex") }, singleLine = true)
                OutlinedTextField(prefix, { prefix = it }, label = { Text("Response prefix hex") }, singleLine = true)
                Text("Bytes: " + byteCount, color = ObdMuted, fontSize = 11.sp)
                Slider(
                    value = byteCount.toFloat(),
                    onValueChange = { byteCount = it.toInt().coerceIn(1, 4) },
                    valueRange = 1f..4f,
                    steps = 2
                )
                OutlinedTextField(scale, { scale = it }, label = { Text("Scale") }, singleLine = true)
                OutlinedTextField(offset, { offset = it }, label = { Text("Offset") }, singleLine = true)
                OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Signed", Modifier.weight(1f))
                    Switch(signed, { signed = it })
                }
                Box {
                    OutlinedButton(onClick = { evMenu = true }) { Text("EV: " + ev.name) }
                    DropdownMenu(expanded = evMenu, onDismissRequest = { evMenu = false }) {
                        EvChannel.entries.forEach { ch ->
                            DropdownMenuItem(
                                text = { Text(ch.name) },
                                onClick = { ev = ch; evMenu = false }
                            )
                        }
                    }
                }
            }
        }
    )
}
