package ru.racelab.phone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.camera.view.PreviewView
import ru.racelab.phone.ble.BleNmeaManager
import ru.racelab.phone.ble.BleUiState
import ru.racelab.phone.ble.Elm327Manager
import ru.racelab.phone.camera.CameraRecorder
import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.data.AppState
import ru.racelab.phone.data.RaceRuntime
import ru.racelab.phone.data.SensorSnapshot
import kotlin.math.abs

private val Bg = Color(0xFF0B0F0C)
private val Panel = Color(0xFF131A15)
private val Green = Color(0xFF69FF72)
private val Amber = Color(0xFFFFCA58)
private val Red = Color(0xFFFF6B6B)
private val Muted = Color(0xFF93A096)

private enum class Tab(val label: String) { DRIVE("ЗАЕЗД"), VIDEO("ВИДЕО"), SENSORS("ДАТЧИКИ"), BLE("BLE/OBD") }

@Composable
fun RaceLabApp(
    bleGps: BleNmeaManager,
    obd: Elm327Manager,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val state by RaceRuntime.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.DRIVE) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green,
            secondary = Amber,
            background = Bg,
            surface = Panel,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                CompactTabBar(selected = tab, onSelect = { tab = it })
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    Tab.DRIVE -> DriveScreen(state, onStartSession, onStopSession)
                    Tab.VIDEO -> VideoScreen(state, onRequestPermissions)
                    Tab.SENSORS -> SensorsScreen(state)
                    Tab.BLE -> ConnectionsScreen(state, bleGps, obd)
                }
            }
        }
    }
}

@Composable
private fun CompactTabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Surface(color = Color(0xFF0E1410), tonalElevation = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(48.dp)
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Tab.entries.forEach { item ->
                val active = selected == item
                val glyph = when (item) {
                    Tab.DRIVE -> "▶"
                    Tab.VIDEO -> "●"
                    Tab.SENSORS -> "◉"
                    Tab.BLE -> "BT"
                }
                Surface(
                    onClick = { onSelect(item) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) Color(0xFF19351D) else Color.Transparent
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            glyph,
                            color = if (active) Green else Muted,
                            fontSize = if (item == Tab.BLE) 11.sp else 15.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            when (item) {
                                Tab.DRIVE -> "ЗАЕЗД"
                                Tab.VIDEO -> "ВИДЕО"
                                Tab.SENSORS -> "IMU"
                                Tab.BLE -> "OBD"
                            },
                            color = if (active) Color.White else Muted,
                            fontSize = 9.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveScreen(state: AppState, onStart: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("СКОРОСТЬ", state.speedKmh.toInt().toString(), "км/ч", Modifier.weight(1f), Green)
                MetricCard("КРУГ", fmt(state.lapElapsedMs), "", Modifier.weight(1.25f), Color.White)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("BEST", fmt(state.bestLapMs), "", Modifier.weight(1f), Green)
                MetricCard("DELTA", delta(state.deltaMs), "", Modifier.weight(1f), deltaColor(state.deltaMs))
                MetricCard("PRED", fmt(state.predictedLapMs), "", Modifier.weight(1f), Amber)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("G", "%.2f".format(state.gTotal), "g", Modifier.weight(1f), Amber)
                MetricCard("RPM", state.obd.rpm?.toInt()?.toString() ?: "—", "", Modifier.weight(1f), Color.White)
                MetricCard("GPS", "%.1f".format(state.gpsHz), "Hz", Modifier.weight(1f), Color.White)
            }
            TrackCanvas(state.trackPreview, Modifier.fillMaxWidth().weight(1f))
        }

        Column(Modifier.weight(.85f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelCard {
                Text(if (state.sessionActive) "● REC СЕССИЯ" else "ГОТОВ К ЗАЕЗДУ", color = if (state.sessionActive) Red else Green, fontWeight = FontWeight.Black)
                Text(state.lastMessage, color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text("GNSS: ${state.gpsSource} • SAT ${state.satellites} • ±${state.accuracyM?.let { "%.1f".format(it) } ?: "—"} м", fontSize = 12.sp)
                Text("Sensors: ${state.activeSensorCount}/${state.availableSensorCount} • Sectors: ${state.sectorCount}/3", fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = if (state.sessionActive) onStop else onStart,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.sessionActive) Red else Green, contentColor = Color.Black)
                ) { Text(if (state.sessionActive) "СТОП" else "СТАРТ", fontWeight = FontWeight.Black) }
                OutlinedButton(onClick = { RaceRuntime.setStartLineHere() }, modifier = Modifier.weight(1f)) { Text("SET START") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { RaceRuntime.addSectorHere() }, modifier = Modifier.weight(1f)) { Text("+ СЕКТОР") }
                OutlinedButton(onClick = { RaceRuntime.clearSectors() }, modifier = Modifier.weight(1f)) { Text("СБРОС S") }
            }
            Text("КРУГИ", color = Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(state.laps.asReversed()) { lap ->
                    Row(Modifier.fillMaxWidth().background(if (lap.timeMs == state.bestLapMs) Color(0xFF17361B) else Panel, RoundedCornerShape(10.dp)).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("#${lap.no}", fontWeight = FontWeight.Black)
                        Text(fmt(lap.timeMs), color = if (lap.timeMs == state.bestLapMs) Green else Color.White, fontWeight = FontWeight.Bold)
                        Text("${lap.maxSpeedKmh.toInt()} km/h", color = Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoScreen(state: AppState, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recorder = remember { CameraRecorder(context) }
    var message by remember { mutableStateOf("Камера не запущена") }
    var recording by remember { mutableStateOf(false) }
    var previewRef by remember { mutableStateOf<PreviewView?>(null) }
    var audio by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    view.scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewRef = view
                    recorder.bind(view, lifecycleOwner) { ok, err -> message = if (ok) "CameraX ready" else "Camera: $err" }
                }
            }
        )

        Column(Modifier.align(Alignment.TopStart).padding(16.dp).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("${state.speedKmh.toInt()} km/h", color = Green, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text("LAP ${fmt(state.lapElapsedMs)}", fontWeight = FontWeight.Bold)
            Text("${delta(state.deltaMs)}  PRED ${fmt(state.predictedLapMs)}", color = deltaColor(state.deltaMs))
            Text("G ${"%.2f".format(state.gTotal)}   RPM ${state.obd.rpm?.toInt() ?: 0}", color = Amber)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xB0000000)).padding(12.dp)) {
            Text(message, color = Muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        onRequestPermissions()
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            message = "Разреши камеру"
                            return@Button
                        }
                        if (!recording) {
                            recorder.start(audio) { event ->
                                when (event) {
                                    is VideoRecordEvent.Start -> { recording = true; message = "REC • Movies/RaceLab" }
                                    is VideoRecordEvent.Finalize -> {
                                        recording = false
                                        message = if (event.hasError()) "Ошибка записи ${event.error}" else "Сохранено: ${event.outputResults.outputUri}"
                                    }
                                    is VideoRecordEvent.Status -> Unit
                                    is VideoRecordEvent.Pause -> message = "PAUSE"
                                    is VideoRecordEvent.Resume -> message = "REC"
                                }
                            }
                        } else recorder.stop()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (recording) Red else Green, contentColor = Color.Black)
                ) { Text(if (recording) "СТОП REC" else "REC VIDEO", fontWeight = FontWeight.Black) }

                OutlinedButton(onClick = { previewRef?.let { recorder.switchCamera(it, lifecycleOwner) } }) { Text("КАМЕРА") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = audio, onCheckedChange = { audio = it })
                    Text(" MIC")
                }
            }
        }
    }
}

@Composable
private fun SensorsScreen(state: AppState) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("НАЙДЕНО", state.availableSensorCount.toString(), "датчиков", Modifier.weight(1f), Green)
            MetricCard("АКТИВНО", state.activeSensorCount.toString(), "потоков", Modifier.weight(1f), Green)
            MetricCard("G TOTAL", "%.3f".format(state.gTotal), "g", Modifier.weight(1f), Amber)
        }
        Spacer(Modifier.height(10.dp))
        Text("Android SensorManager: все непрерывные и on-change сенсоры", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.sensors, key = { it.id }) { SensorRow(it) }
        }
    }
}

@Composable
private fun SensorRow(s: SensorSnapshot) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.name, fontWeight = FontWeight.Bold)
            Text("type ${s.type} • ${s.vendor}", color = Muted, fontSize = 11.sp)
        }
        Text(s.values.take(4).joinToString("  ") { "%.3f".format(it) }, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End, fontSize = 12.sp)
        Text("%.0f Hz".format(s.hz), color = Green, modifier = Modifier.width(65.dp), textAlign = TextAlign.End, fontSize = 12.sp)
    }
}

@Composable
private fun ConnectionsScreen(state: AppState, bleGps: BleNmeaManager, obd: Elm327Manager) {
    val gps by bleGps.state.collectAsStateWithLifecycle()
    val obdState by obd.state.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionPanel("BLE GPS / NMEA", gps, Modifier.weight(1f), onScan = { bleGps.startScan() }, onConnect = { bleGps.connect(it) }, onDisconnect = { bleGps.disconnect() })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ConnectionPanel("BLE OBD-II / ELM327", obdState, Modifier.weight(1f), onScan = { obd.startScan() }, onConnect = { obd.connect(it) }, onDisconnect = { obd.disconnect() })
            PanelCard {
                Text("OBD LIVE", color = Muted, fontWeight = FontWeight.Bold)
                Text("RPM  ${state.obd.rpm?.let { "%.0f".format(it) } ?: "—"}", fontSize = 20.sp)
                Text("Speed  ${state.obd.speedKmh?.let { "%.1f km/h".format(it) } ?: "—"}")
                Text("Throttle  ${state.obd.throttlePct?.let { "%.1f %%".format(it) } ?: "—"}")
                Text("Coolant  ${state.obd.coolantC?.let { "%.0f °C".format(it) } ?: "—"}")
            }
        }
    }
}

@Composable
private fun ConnectionPanel(title: String, ui: BleUiState, modifier: Modifier, onScan: () -> Unit, onConnect: (String) -> Unit, onDisconnect: () -> Unit) {
    Column(modifier.fillMaxHeight().background(Panel, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(title, color = Green, fontWeight = FontWeight.Black)
        Text(ui.status, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan) { Text(if (ui.scanning) "SCAN…" else "SCAN") }
            OutlinedButton(onClick = onDisconnect) { Text("DISCONNECT") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(ui.devices, key = { it.address }) { d ->
                Row(Modifier.fillMaxWidth().background(Color(0xFF0D120E), RoundedCornerShape(8.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${d.address} • ${d.rssi} dBm", color = Muted, fontSize = 10.sp)
                    }
                    TextButton(onClick = { onConnect(d.address) }) { Text("CONNECT") }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, unit: String, modifier: Modifier, valueColor: Color) {
    Column(modifier.background(Panel, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(title, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = valueColor, fontSize = 24.sp, fontWeight = FontWeight.Black)
            if (unit.isNotBlank()) Text("  $unit", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
}

@Composable
private fun TrackCanvas(points: List<GeoPoint>, modifier: Modifier) {
    Canvas(modifier.background(Color(0xFF080B09), RoundedCornerShape(14.dp)).padding(8.dp)) {
        if (points.size < 2) return@Canvas
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
        val dx = (maxLon - minLon).takeIf { abs(it) > 1e-9 } ?: 1e-9
        val dy = (maxLat - minLat).takeIf { abs(it) > 1e-9 } ?: 1e-9
        val pad = 12f
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = pad + ((p.lon - minLon) / dx).toFloat() * (size.width - 2 * pad)
            val y = size.height - pad - ((p.lat - minLat) / dy).toFloat() * (size.height - 2 * pad)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Green, style = Stroke(width = 4f, cap = StrokeCap.Round))
        val last = points.last()
        val x = pad + ((last.lon - minLon) / dx).toFloat() * (size.width - 2 * pad)
        val y = size.height - pad - ((last.lat - minLat) / dy).toFloat() * (size.height - 2 * pad)
        drawCircle(Amber, 7f, Offset(x, y))
    }
}

private fun fmt(ms: Long?): String {
    if (ms == null) return "--:--.---"
    val m = ms / 60000
    val s = (ms % 60000) / 1000
    val x = ms % 1000
    return "%02d:%02d.%03d".format(m, s, x)
}

private fun delta(ms: Long?): String = when {
    ms == null -> "Δ ---.---"
    ms < 0 -> "Δ −%.3f".format(abs(ms) / 1000.0)
    else -> "Δ +%.3f".format(ms / 1000.0)
}

private fun deltaColor(ms: Long?): Color = when {
    ms == null -> Color.White
    ms <= 0 -> Green
    else -> Red
}
