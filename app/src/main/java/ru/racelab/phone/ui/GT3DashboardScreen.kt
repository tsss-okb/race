package ru.racelab.phone.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.data.AppState
import ru.racelab.phone.data.RaceRuntime
import ru.racelab.phone.diag.DiagnosticsProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val GtBg = Color(0xFF050607)
private val GtPanelTop = Color(0xFF171A1D)
private val GtPanelBottom = Color(0xFF0B0D0F)
private val GtBorder = Color(0xFF2B2F33)
private val GtYellow = Color(0xFFF2C300)
private val GtGreen = Color(0xFF58E13E)
private val GtRed = Color(0xFFFF3B30)
private val GtWhite = Color(0xFFF2F2F2)
private val GtMuted = Color(0xFF989DA2)

@Composable
fun GT3DashboardScreen(
    state: AppState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showTracks by remember { mutableStateOf(false) }
    var battery by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        while (true) {
            battery = DiagnosticsProvider.collect(context).batteryPct
            delay(5_000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(GtBg)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 7.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GT3StatusBar(state, battery)
            GT3LapStrip(state)

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val sideWidth = if (maxWidth < 390.dp) 68.dp else 78.dp
                val gaugeHeight = if (maxWidth < 390.dp) 270.dp else 300.dp
                Row(
                    Modifier.fillMaxWidth().height(gaugeHeight),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.width(sideWidth).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GT3SideMetric(
                            title = "ТЕМП.\nМАСЛА",
                            value = state.obd.oilTempC,
                            unit = "°C",
                            modifier = Modifier.weight(1f),
                            accent = GtYellow,
                            rangeMax = 150.0
                        )
                        GT3SideMetric(
                            title = "ТЕМП.\nОЖ",
                            value = state.obd.coolantC,
                            unit = "°C",
                            modifier = Modifier.weight(1f),
                            accent = GtYellow,
                            rangeMax = 130.0
                        )
                    }

                    GT3Tachometer(
                        rpm = state.obd.rpm ?: 0.0,
                        gear = state.vehicleCan.gear,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )

                    Column(
                        Modifier.width(sideWidth).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GT3SideMetric(
                            title = "ГАЗ",
                            value = state.obd.throttlePct,
                            unit = "%",
                            modifier = Modifier.weight(1f),
                            accent = GtGreen,
                            rangeMax = 100.0
                        )
                        GT3SideMetric(
                            title = "ТОРМОЗ",
                            value = state.vehicleCan.brakePressureBar,
                            unit = "bar",
                            modifier = Modifier.weight(1f),
                            accent = GtRed,
                            rangeMax = 120.0
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(92.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GT3LargeMetric(
                    title = "СКОРОСТЬ",
                    value = state.speedKmh.toInt().toString(),
                    unit = "км/ч",
                    modifier = Modifier.weight(1.35f),
                    accent = GtYellow,
                    valueSize = 45
                )
                GT3LargeMetric(
                    title = "ПЕРЕДАЧА",
                    value = state.vehicleCan.gear?.let {
                        if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it)
                    } ?: "—",
                    unit = "",
                    modifier = Modifier.weight(.65f),
                    accent = GtWhite,
                    valueSize = 45
                )
            }

            Row(
                Modifier.fillMaxWidth().height(150.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GT3GMeter(state, Modifier.weight(1f).fillMaxHeight())
                GT3LapCounter(state, Modifier.weight(.76f).fillMaxHeight())
                GT3TrackMap(state.trackPreview, Modifier.weight(1.25f).fillMaxHeight())
            }

            Row(
                Modifier.fillMaxWidth().height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GT3RoundAction(
                    label = "ТРЕК",
                    onClick = { showTracks = true },
                    modifier = Modifier.width(66.dp).fillMaxHeight()
                )

                Button(
                    onClick = if (state.sessionActive) onStop else onStart,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF101214),
                        contentColor = if (state.sessionActive) GtRed else GtYellow
                    ),
                    border = BorderStroke(1.dp, if (state.sessionActive) GtRed.copy(alpha = .65f) else GtYellow.copy(alpha = .65f))
                ) {
                    Text(
                        when {
                            state.sessionActive -> "■  СТОП ЗАПИСИ"
                            state.startConfigured -> "●  ARM / СТАРТ"
                            else -> "●  НАЧАТЬ СЕССИЮ"
                        },
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }

                GT3RoundAction(
                    label = "START",
                    onClick = { RaceRuntime.setStartLineHere() },
                    modifier = Modifier.width(66.dp).fillMaxHeight()
                )
            }

            if (!state.startConfigured) {
                Text(
                    "Для тайминга проедь несколько метров и нажми START справа.",
                    color = GtMuted,
                    fontSize = 9.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }

        if (showTracks) {
            TrackLibraryDialog(state = state, onDismiss = { showTracks = false })
        }
    }
}

@Composable
private fun GT3StatusBar(state: AppState, battery: Int) {
    GT3Panel(Modifier.fillMaxWidth().height(64.dp), corner = 13) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GPS", color = if (state.latestPoint != null) GtGreen else GtMuted, fontWeight = FontWeight.Black, fontSize = 14.sp)
                    Spacer(Modifier.width(5.dp))
                    Text("▮▮▮", color = if (state.latestPoint != null) GtGreen else GtMuted, fontSize = 11.sp)
                }
                Text(
                    "±" + (state.accuracyM?.let { "%.1f м".format(it) } ?: "—") +
                        " • " + "%.1f Hz".format(state.gpsHz) +
                        " • SAT " + state.satellites,
                    color = GtMuted,
                    fontSize = 8.sp
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = if (state.sessionActive || state.videoRecording) GtRed else GtMuted, fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.sessionActive) "ЗАПИСЬ" else if (state.armed) "ARM" else "ГОТОВ",
                        color = GtWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    when {
                        state.videoRecording -> "VIDEO REC"
                        state.sessionActive -> state.sessionId ?: "SESSION"
                        else -> state.currentTrackName ?: "RaceLab"
                    },
                    color = GtMuted,
                    fontSize = 8.sp,
                    maxLines = 1
                )
            }

            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    if (battery >= 0) "$battery%" else "—%",
                    color = if (battery in 0..15) GtRed else GtWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    state.gpsSource.uppercase(),
                    color = GtMuted,
                    fontSize = 8.sp
                )
            }
        }
    }
}

@Composable
private fun GT3LapStrip(state: AppState) {
    Row(
        Modifier.fillMaxWidth().height(84.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        GT3LapCard("ТЕКУЩИЙ КРУГ", gtFmt(state.lapElapsedMs), Modifier.weight(1f), GtWhite, underline = GtYellow)
        GT3LapCard("ЛУЧШИЙ", gtFmt(state.bestLapMs), Modifier.weight(1f), GtWhite, underline = Color.Transparent)
        GT3LapCard("ДЕЛЬТА", gtDelta(state.deltaMs), Modifier.weight(1f), gtDeltaColor(state.deltaMs), underline = Color.Transparent)
    }
}

@Composable
private fun GT3LapCard(
    title: String,
    value: String,
    modifier: Modifier,
    color: Color,
    underline: Color
) {
    GT3Panel(modifier, corner = 11) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = GtMuted, fontSize = 8.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(value, color = color, fontSize = 21.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (underline != Color.Transparent) {
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth(.72f).height(2.dp).background(underline))
            }
        }
    }
}

@Composable
private fun GT3Tachometer(
    rpm: Double,
    gear: Double?,
    modifier: Modifier
) {
    val ratio = (rpm / 10_000.0).coerceIn(0.0, 1.0)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = minOf(size.width, size.height) * .45f
            val start = 135f
            val sweep = 270f

            drawCircle(Color(0xFF060708), r * 1.04f, c)
            drawCircle(GtBorder, r * 1.02f, c, style = Stroke(width = 2.5f))
            drawCircle(Color(0xFF15181A), r * .82f, c, style = Stroke(width = 2f))
            drawCircle(Color.Black, r * .68f, c)

            drawArc(
                color = GtYellow,
                startAngle = start + sweep * .74f,
                sweepAngle = sweep * .16f,
                useCenter = false,
                topLeft = Offset(c.x - r * .93f, c.y - r * .93f),
                size = androidx.compose.ui.geometry.Size(r * 1.86f, r * 1.86f),
                style = Stroke(width = 7f)
            )
            drawArc(
                color = GtRed,
                startAngle = start + sweep * .90f,
                sweepAngle = sweep * .10f,
                useCenter = false,
                topLeft = Offset(c.x - r * .93f, c.y - r * .93f),
                size = androidx.compose.ui.geometry.Size(r * 1.86f, r * 1.86f),
                style = Stroke(width = 7f)
            )

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(235, 235, 235)
                textSize = (r * .12f).coerceAtLeast(20f)
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            }
            for (i in 0..10) {
                val a = (start + sweep * (i / 10f)) * PI / 180.0
                val outer = Offset(c.x + cos(a).toFloat() * r * .96f, c.y + sin(a).toFloat() * r * .96f)
                val inner = Offset(c.x + cos(a).toFloat() * r * .84f, c.y + sin(a).toFloat() * r * .84f)
                drawLine(
                    if (i >= 8) GtYellow else GtWhite,
                    inner,
                    outer,
                    strokeWidth = if (i % 1 == 0) 3.2f else 2f
                )

                val tx = c.x + cos(a).toFloat() * r * .70f
                val ty = c.y + sin(a).toFloat() * r * .70f - (textPaint.ascent() + textPaint.descent()) / 2f
                if (i >= 8) textPaint.color = android.graphics.Color.rgb(242, 195, 0)
                else textPaint.color = android.graphics.Color.rgb(235, 235, 235)
                drawContext.canvas.nativeCanvas.drawText(i.toString(), tx, ty, textPaint)
            }

            for (i in 0..50) {
                if (i % 5 == 0) continue
                val a = (start + sweep * (i / 50f)) * PI / 180.0
                val outer = Offset(c.x + cos(a).toFloat() * r * .96f, c.y + sin(a).toFloat() * r * .96f)
                val inner = Offset(c.x + cos(a).toFloat() * r * .90f, c.y + sin(a).toFloat() * r * .90f)
                drawLine(Color(0xFF8B8E90), inner, outer, strokeWidth = 1.2f)
            }

            val needleAngle = (start + sweep * ratio.toFloat()) * PI / 180.0
            val tip = Offset(
                c.x + cos(needleAngle).toFloat() * r * .76f,
                c.y + sin(needleAngle).toFloat() * r * .76f
            )
            val tail = Offset(
                c.x - cos(needleAngle).toFloat() * r * .10f,
                c.y - sin(needleAngle).toFloat() * r * .10f
            )
            drawLine(Color(0xFF7C5F00), tail, tip, strokeWidth = 8f)
            drawLine(GtYellow, tail, tip, strokeWidth = 4f)
            drawCircle(Color(0xFF26292C), r * .10f, c)
            drawCircle(GtBorder, r * .10f, c, style = Stroke(width = 2f))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(22.dp))
            Text("ОБОРОТЫ", color = GtMuted, fontSize = 9.sp)
            Text("x1000 rpm", color = GtMuted, fontSize = 8.sp)
            Spacer(Modifier.height(26.dp))
            Text(
                gear?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: "—",
                color = GtYellow,
                fontSize = 38.sp,
                fontWeight = FontWeight.Black
            )
            Text("ПЕРЕДАЧА", color = GtMuted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun GT3SideMetric(
    title: String,
    value: Double?,
    unit: String,
    modifier: Modifier,
    accent: Color,
    rangeMax: Double
) {
    GT3Panel(modifier, corner = 10) {
        Column(
            Modifier.fillMaxSize().padding(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = GtMuted, fontSize = 8.sp, textAlign = TextAlign.Center, lineHeight = 9.sp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    value?.let { if (abs(it) >= 100) "%.0f".format(it) else "%.1f".format(it) } ?: "—",
                    color = GtWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(unit, color = GtMuted, fontSize = 8.sp)
            }
            LinearProgressIndicator(
                progress = { ((value ?: 0.0) / rangeMax).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = accent,
                trackColor = Color(0xFF232629)
            )
        }
    }
}

@Composable
private fun GT3LargeMetric(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier,
    accent: Color,
    valueSize: Int
) {
    GT3Panel(modifier, corner = 11) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = GtMuted, fontSize = 8.sp)
            Text(value, color = accent, fontSize = valueSize.sp, fontWeight = FontWeight.Medium, lineHeight = valueSize.sp)
            if (unit.isNotBlank()) {
                Text(unit, color = GtMuted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun GT3GMeter(state: AppState, modifier: Modifier) {
    GT3Panel(modifier, corner = 11) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Text("G-МЕТР", color = GtMuted, fontSize = 8.sp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.weight(1f).aspectRatio(1f)) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = minOf(size.width, size.height) * .42f
                    drawCircle(GtBorder, r, c, style = Stroke(1.5f))
                    drawCircle(GtBorder.copy(alpha = .55f), r * .5f, c, style = Stroke(1f))
                    drawLine(GtBorder, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
                    drawLine(GtBorder, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
                    val maxG = 1.5
                    val x = (state.lateralG / maxG).coerceIn(-1.0, 1.0).toFloat() * r
                    val y = -(state.longitudinalG / maxG).coerceIn(-1.0, 1.0).toFloat() * r
                    drawCircle(GtYellow, 6f, Offset(c.x + x, c.y + y))
                }
                Column(Modifier.width(64.dp), verticalArrangement = Arrangement.Center) {
                    Text("%+.2f".format(state.lateralG), color = GtWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("LAT G", color = GtMuted, fontSize = 7.sp)
                    Spacer(Modifier.height(7.dp))
                    Text("%+.2f".format(state.longitudinalG), color = GtWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("LONG G", color = GtMuted, fontSize = 7.sp)
                }
            }
        }
    }
}

@Composable
private fun GT3LapCounter(state: AppState, modifier: Modifier) {
    val current = state.laps.size + if (state.lapElapsedMs > 0L) 1 else 0
    GT3Panel(modifier, corner = 11) {
        Column(
            Modifier.fillMaxSize().padding(9.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("КРУГ", color = GtMuted, fontSize = 8.sp)
            Text(current.toString(), color = GtWhite, fontSize = 37.sp, fontWeight = FontWeight.Medium)
            Text(
                if (state.armed) "ARM" else if (state.sessionActive) "ACTIVE" else "READY",
                color = if (state.sessionActive || state.armed) GtYellow else GtMuted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun GT3TrackMap(points: List<GeoPoint>, modifier: Modifier) {
    GT3Panel(modifier, corner = 11) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Text("КАРТА ТРАССЫ", color = GtMuted, fontSize = 8.sp)
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                if (points.size < 2) return@Canvas
                val minLat = points.minOf { it.lat }
                val maxLat = points.maxOf { it.lat }
                val minLon = points.minOf { it.lon }
                val maxLon = points.maxOf { it.lon }
                val dx = (maxLon - minLon).takeIf { abs(it) > 1e-9 } ?: 1e-9
                val dy = (maxLat - minLat).takeIf { abs(it) > 1e-9 } ?: 1e-9
                val pad = 8f
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = pad + ((p.lon - minLon) / dx).toFloat() * (size.width - 2 * pad)
                    val y = size.height - pad - ((p.lat - minLat) / dy).toFloat() * (size.height - 2 * pad)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color(0xFFC5C7C9), style = Stroke(width = 3f, cap = StrokeCap.Round))
                val last = points.last()
                val x = pad + ((last.lon - minLon) / dx).toFloat() * (size.width - 2 * pad)
                val y = size.height - pad - ((last.lat - minLat) / dy).toFloat() * (size.height - 2 * pad)
                drawCircle(GtYellow, 6f, Offset(x, y))
            }
        }
    }
}

@Composable
private fun GT3RoundAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, GtBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = GtWhite),
        contentPadding = PaddingValues(2.dp)
    ) {
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun GT3Panel(
    modifier: Modifier,
    corner: Int,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier
            .background(
                brush = Brush.verticalGradient(listOf(GtPanelTop, GtPanelBottom)),
                shape = RoundedCornerShape(corner.dp)
            )
            .then(
                Modifier.background(Color.Transparent, RoundedCornerShape(corner.dp))
            ),
        content = {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .background(Color.Transparent, RoundedCornerShape(corner.dp)),
                content = content
            )
        }
    )
}

private fun gtFmt(ms: Long?): String {
    if (ms == null) return "--:--.---"
    val m = ms / 60000
    val s = (ms % 60000) / 1000
    val x = ms % 1000
    return "%d:%02d.%03d".format(m, s, x)
}

private fun gtDelta(ms: Long?): String = when {
    ms == null -> "—"
    ms < 0 -> "−%.3f".format(abs(ms) / 1000.0)
    else -> "+%.3f".format(ms / 1000.0)
}

private fun gtDeltaColor(ms: Long?): Color = when {
    ms == null -> GtWhite
    ms <= 0 -> GtGreen
    else -> GtRed
}
