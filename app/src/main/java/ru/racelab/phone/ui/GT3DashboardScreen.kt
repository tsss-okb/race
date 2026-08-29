package ru.racelab.phone.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.racelab.phone.core.GeoPoint
import ru.racelab.phone.data.AppState
import ru.racelab.phone.data.RaceRuntime
import ru.racelab.phone.diag.DiagnosticsProvider
import kotlin.math.abs

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
    var battery by remember { mutableIntStateOf(-1) }
    var pitTick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(Unit) {
        while (true) {
            battery = DiagnosticsProvider.collect(context).batteryPct
            delay(5_000)
        }
    }

    LaunchedEffect(state.pitTimerActive) {
        if (state.pitTimerActive) {
            while (true) {
                pitTick = SystemClock.elapsedRealtime()
                delay(50)
            }
        } else {
            pitTick = SystemClock.elapsedRealtime()
        }
    }

    GT3LandscapeDashboard(
        state = state,
        battery = battery,
        pitElapsedMs = if (state.pitTimerActive) RaceRuntime.pitElapsedMs(pitTick) else (state.pitLastMs ?: 0L),
        onStart = onStart,
        onStop = onStop
    )
}

@Composable
private fun GT3LandscapeDashboard(
    state: AppState,
    battery: Int,
    pitElapsedMs: Long,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(GtBg)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            GT3LandscapeStatus(state, battery, Modifier.weight(1.25f))
            GT3LandscapeLapCard(
                "ТЕКУЩИЙ",
                gtFmt(state.lapElapsedMs),
                Modifier.weight(1f),
                GtWhite,
                GtYellow
            )
            GT3LandscapeLapCard(
                "ЛУЧШИЙ",
                gtFmt(state.bestLapMs),
                Modifier.weight(1f),
                GtWhite,
                Color.Transparent
            )
            GT3LandscapeLapCard(
                "ДЕЛЬТА",
                gtDelta(state.deltaMs),
                Modifier.weight(.82f),
                gtDeltaColor(state.deltaMs),
                Color.Transparent
            )
            GT3LandscapeLapCard(
                "КРУГ",
                (state.laps.size + if (state.lapElapsedMs > 0L) 1 else 0).toString(),
                Modifier.weight(.52f),
                if (state.sessionActive || state.armed) GtYellow else GtWhite,
                Color.Transparent
            )
        }

        GT3LandscapeRpmStrip(
            rpm = state.obd.rpm,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        )

        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            GT3LandscapeSpeed(
                speedKmh = state.speedKmh,
                modifier = Modifier.weight(.82f).fillMaxHeight()
            )

            Column(
                Modifier.weight(1.02f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    GT3CompactMetric(
                        title = "ГАЗ",
                        value = state.obd.throttlePct,
                        unit = "%",
                        accent = GtGreen,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    GT3CompactMetric(
                        title = "ТОРМОЗ",
                        value = state.vehicleCan.brakePressureBar,
                        unit = "bar",
                        accent = GtRed,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    GT3CompactMetric(
                        title = "МАСЛО",
                        value = state.obd.oilTempC,
                        unit = "°C",
                        accent = GtYellow,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    GT3CompactMetric(
                        title = "ОЖ",
                        value = state.obd.coolantC,
                        unit = "°C",
                        accent = GtYellow,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }

            GT3LandscapePit(
                state = state,
                elapsedMs = pitElapsedMs,
                modifier = Modifier.weight(1.10f).fillMaxHeight()
            )

            GT3GMeter(
                state = state,
                modifier = Modifier.weight(.95f).fillMaxHeight()
            )

            GT3TrackMap(
                points = state.trackPreview,
                modifier = Modifier.weight(1.18f).fillMaxHeight()
            )
        }

        Row(
            Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GT3RoundAction(
                label = if (state.pitTimerActive) "PIT ■" else "PIT ▶",
                onClick = { RaceRuntime.togglePitTimer("SCREEN") },
                modifier = Modifier.width(76.dp).fillMaxHeight(),
                active = state.pitTimerActive
            )

            Button(
                onClick = if (state.sessionActive) onStop else onStart,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF101214),
                    contentColor = if (state.sessionActive) GtRed else GtYellow
                ),
                border = BorderStroke(
                    1.dp,
                    if (state.sessionActive) GtRed.copy(alpha = .65f) else GtYellow.copy(alpha = .65f)
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(
                    when {
                        state.sessionActive -> "■  СТОП ЗАПИСИ"
                        state.startConfigured -> "●  ARM / СТАРТ"
                        else -> "●  НАЧАТЬ СЕССИЮ"
                    },
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            GT3RoundAction(
                label = "SET START",
                onClick = { RaceRuntime.setStartLineHere() },
                modifier = Modifier.width(90.dp).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun GT3LandscapeStatus(state: AppState, battery: Int, modifier: Modifier) {
    GT3Panel(modifier, corner = 10) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "GPS",
                        color = if (state.latestPoint != null) GtGreen else GtMuted,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "▮▮▮",
                        color = if (state.latestPoint != null) GtGreen else GtMuted,
                        fontSize = 8.sp
                    )
                }
                Text(
                    "±" + (state.accuracyM?.let { "%.1fм".format(it) } ?: "—") +
                        " • %.1fHz".format(state.gpsHz) +
                        " • S" + state.satellites,
                    color = GtMuted,
                    fontSize = 7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (state.sessionActive || state.videoRecording) "● " else "") +
                        when {
                            state.sessionActive -> "REC"
                            state.armed -> "ARM"
                            else -> "READY"
                        },
                    color = if (state.sessionActive || state.videoRecording) GtRed else GtWhite,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    if (battery >= 0) "$battery%" else "—%",
                    color = if (battery in 0..15) GtRed else GtMuted,
                    fontSize = 8.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun GT3LandscapeLapCard(
    title: String,
    value: String,
    modifier: Modifier,
    valueColor: Color,
    underline: Color
) {
    GT3Panel(modifier, corner = 10) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                title,
                color = GtMuted,
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                color = valueColor,
                fontSize = 15.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            if (underline != Color.Transparent) {
                Box(Modifier.fillMaxWidth(.66f).height(2.dp).background(underline))
            }
        }
    }
}

@Composable
private fun GT3LandscapeRpmStrip(rpm: Double?, modifier: Modifier) {
    val value = (rpm ?: 0.0).coerceAtLeast(0.0)
    val ratio = (value / 10_000.0).coerceIn(0.0, 1.0)

    GT3Panel(modifier, corner = 10) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("RPM", color = GtMuted, fontSize = 8.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.weight(1f).height(17.dp)) {
                val gap = 3f
                val segments = 32
                val segmentWidth = (size.width - gap * (segments - 1)) / segments
                val active = (ratio * segments).toInt()
                for (i in 0 until segments) {
                    val x = i * (segmentWidth + gap)
                    val fraction = i.toFloat() / (segments - 1).coerceAtLeast(1)
                    val baseColor = when {
                        fraction >= .90f -> GtRed
                        fraction >= .75f -> GtYellow
                        else -> Color(0xFFD8DBDD)
                    }
                    drawRoundRect(
                        color = if (i < active) baseColor else Color(0xFF2A2D30),
                        topLeft = Offset(x, 0f),
                        size = androidx.compose.ui.geometry.Size(segmentWidth, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.3f, 2.3f)
                    )
                }
            }
            Spacer(Modifier.width(9.dp))
            Text(
                rpm?.let { "%,.0f".format(it).replace(",", " ") } ?: "—",
                color = when {
                    rpm == null -> GtMuted
                    ratio >= .90 -> GtRed
                    ratio >= .75 -> GtYellow
                    else -> GtWhite
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun GT3LandscapeSpeed(speedKmh: Double, modifier: Modifier) {
    GT3Panel(modifier, corner = 10) {
        Column(
            Modifier.fillMaxSize().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("СКОРОСТЬ", color = GtMuted, fontSize = 8.sp, maxLines = 1)
            Text(
                speedKmh.toInt().toString(),
                color = GtYellow,
                fontSize = 48.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Text("км/ч", color = GtMuted, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
private fun GT3LandscapePit(state: AppState, elapsedMs: Long, modifier: Modifier) {
    GT3Panel(modifier, corner = 10) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                if (state.pitTimerActive) "PIT • ACTIVE" else "PIT STOP",
                color = if (state.pitTimerActive) GtYellow else GtMuted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Text(
                pitFmt(elapsedMs),
                color = if (state.pitTimerActive) GtYellow else GtWhite,
                fontSize = 24.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "LAST " + (state.pitLastMs?.let(::pitFmtShort) ?: "—"),
                color = GtWhite,
                fontSize = 7.sp,
                maxLines = 1
            )
            Text(
                "BEST " + (state.pitBestMs?.let(::pitFmtShort) ?: "—"),
                color = GtGreen,
                fontSize = 7.sp,
                maxLines = 1
            )
            Text(
                state.pitLastTrigger.take(20),
                color = GtMuted,
                fontSize = 6.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
private fun GT3PitStrip(
    state: AppState,
    elapsedMs: Long,
    modifier: Modifier
) {
    GT3Panel(modifier, corner = 11) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.width(72.dp)) {
                Text(
                    "PIT STOP",
                    color = if (state.pitTimerActive) GtYellow else GtMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    if (state.pitTimerActive) "ACTIVE" else "READY",
                    color = if (state.pitTimerActive) GtYellow else GtGreen,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    state.pitLastTrigger.take(18),
                    color = GtMuted,
                    fontSize = 6.sp,
                    maxLines = 1
                )
            }

            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    pitFmt(elapsedMs),
                    color = if (state.pitTimerActive) GtYellow else GtWhite,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
                Text(
                    if (state.pitTimerActive) "НАЖМИ КНОПКУ РУЛЯ ДЛЯ STOP" else "КНОПКА РУЛЯ → START",
                    color = GtMuted,
                    fontSize = 7.sp,
                    maxLines = 1
                )
            }

            Column(
                Modifier.width(84.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text("LAST " + (state.pitLastMs?.let(::pitFmtShort) ?: "—"), color = GtWhite, fontSize = 8.sp)
                Text("BEST " + (state.pitBestMs?.let(::pitFmtShort) ?: "—"), color = GtGreen, fontSize = 8.sp)
                Text("PIT #" + state.pitStopCount, color = GtMuted, fontSize = 7.sp)
                if (!state.pitTimerActive && state.pitStopCount > 0) {
                    TextButton(
                        onClick = { RaceRuntime.resetPitTimer() },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(20.dp)
                    ) {
                        Text("СБРОС", color = GtMuted, fontSize = 7.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GT3RpmStrip(
    rpm: Double?,
    modifier: Modifier
) {
    val value = (rpm ?: 0.0).coerceAtLeast(0.0)
    val ratio = (value / 10_000.0).coerceIn(0.0, 1.0)

    GT3Panel(modifier, corner = 11) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("ОБОРОТЫ", color = GtMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text("RPM", color = GtMuted, fontSize = 7.sp)
                }
                Text(
                    rpm?.let { "%,.0f".format(it).replace(",", " ") } ?: "—",
                    color = when {
                        rpm == null -> GtMuted
                        ratio >= .90 -> GtRed
                        ratio >= .75 -> GtYellow
                        else -> GtWhite
                    },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(Modifier.height(7.dp))

            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val gap = 3f
                val segments = 28
                val segmentWidth = (size.width - gap * (segments - 1)) / segments
                val active = (ratio * segments).toInt()

                for (i in 0 until segments) {
                    val x = i * (segmentWidth + gap)
                    val fraction = i.toFloat() / (segments - 1).coerceAtLeast(1)
                    val baseColor = when {
                        fraction >= .90f -> GtRed
                        fraction >= .75f -> GtYellow
                        else -> Color(0xFFD8DBDD)
                    }
                    val color = if (i < active) baseColor else Color(0xFF2A2D30)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, 0f),
                        size = androidx.compose.ui.geometry.Size(segmentWidth, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f)
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("0", "2", "4", "6", "8", "10").forEach { label ->
                    Text(label, color = GtMuted, fontSize = 7.sp)
                }
            }
        }
    }
}

@Composable
private fun GT3CompactMetric(
    title: String,
    value: Double?,
    unit: String,
    accent: Color,
    modifier: Modifier
) {
    GT3Panel(modifier, corner = 10) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = GtMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(unit, color = GtMuted, fontSize = 6.sp, maxLines = 1)
            }
            Text(
                value?.let { if (abs(it) >= 100) "%.0f".format(it) else "%.1f".format(it) } ?: "—",
                color = if (value == null) GtMuted else accent,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
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
            Text("G-МЕТР", color = GtMuted, fontSize = 7.sp, maxLines = 1)
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
                Column(Modifier.width(54.dp), verticalArrangement = Arrangement.Center) {
                    Text("%+.2f".format(state.lateralG), color = GtWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("LAT G", color = GtMuted, fontSize = 7.sp)
                    Spacer(Modifier.height(7.dp))
                    Text("%+.2f".format(state.longitudinalG), color = GtWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
            Text("КАРТА ТРАССЫ", color = GtMuted, fontSize = 7.sp, maxLines = 1)
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
    modifier: Modifier,
    active: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, if (active) GtYellow else GtBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (active) GtYellow else GtWhite),
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

private fun pitFmt(ms: Long): String {
    val m = ms / 60000
    val s = (ms % 60000) / 1000
    val x = ms % 1000
    return "%02d:%02d.%03d".format(m, s, x)
}

private fun pitFmtShort(ms: Long): String {
    val s = ms / 1000
    val x = ms % 1000
    return "%d.%03d".format(s, x)
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
