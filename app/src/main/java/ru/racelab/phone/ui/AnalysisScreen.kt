package ru.racelab.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.racelab.phone.analysis.LapAnalysis
import ru.racelab.phone.core.LapResult
import ru.racelab.phone.data.AppState
import kotlin.math.abs

private val AnalysisPanel = Color(0xFF131A15)
private val AnalysisGreen = Color(0xFF69FF72)
private val AnalysisAmber = Color(0xFFFFCA58)
private val AnalysisRed = Color(0xFFFF6B6B)
private val AnalysisMuted = Color(0xFF93A096)

@Composable
fun AnalysisScreen(state: AppState) {
    if (state.laps.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text("После первого завершённого круга здесь появится анализ.", color = AnalysisMuted)
        }
        return
    }

    val best = state.laps.minByOrNull { it.timeMs } ?: state.laps.first()
    val last = state.laps.last()
    var referenceNo by remember(state.laps.size) { mutableIntStateOf(best.no) }
    var compareNo by remember(state.laps.size) { mutableIntStateOf(last.no) }
    val reference = state.laps.firstOrNull { it.no == referenceNo } ?: best
    val compare = state.laps.firstOrNull { it.no == compareNo } ?: last
    val delta = LapAnalysis.delta(reference, compare)
    val zones = LapAnalysis.zones(compare)
    val theoretical = LapAnalysis.theoreticalBest(state.laps)

    LazyColumn(
        Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LapSelector("REF", reference, state.laps, Modifier.weight(1f)) { referenceNo = it.no }
                LapSelector("CMP", compare, state.laps, Modifier.weight(1f)) { compareNo = it.no }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AnalysisMetric("REF", formatMs(reference.timeMs), Modifier.weight(1f), AnalysisGreen)
                AnalysisMetric("CMP", formatMs(compare.timeMs), Modifier.weight(1f), Color.White)
                AnalysisMetric("THEORY", theoretical?.let(::formatMs) ?: "—", Modifier.weight(1f), AnalysisAmber)
            }
        }
        item {
            ChartCard("СКОРОСТЬ ПО ДИСТАНЦИИ") {
                SpeedChart(reference, compare, Modifier.fillMaxWidth().height(180.dp))
            }
        }
        item {
            ChartCard("DELTA: CMP − REF") {
                DeltaChart(delta.map { it.progress to it.deltaMs }, Modifier.fillMaxWidth().height(150.dp))
            }
        }
        item {
            ChartCard("КАРТА КРУГА / СКОРОСТЬ") {
                SpeedTraceMap(compare, Modifier.fillMaxWidth().height(230.dp))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AnalysisMetric("VMAX REF", "%.0f".format(reference.maxSpeedKmh), Modifier.weight(1f), Color.White)
                AnalysisMetric("VMAX CMP", "%.0f".format(compare.maxSpeedKmh), Modifier.weight(1f), Color.White)
                AnalysisMetric(
                    "GPS ±",
                    LapAnalysis.averageAccuracy(compare)?.let { "%.1f m".format(it) } ?: "—",
                    Modifier.weight(1f),
                    AnalysisAmber
                )
            }
        }
        item { Text("СИЛЬНЫЕ ЗОНЫ ТОРМОЖЕНИЯ / РАЗГОНА", color = AnalysisMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        items(zones) { zone ->
            Row(
                Modifier.fillMaxWidth().background(AnalysisPanel, RoundedCornerShape(10.dp)).padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(if (zone.type == "BRAKE") "BRAKE" else "ACCEL", color = if (zone.type == "BRAKE") AnalysisRed else AnalysisGreen, fontWeight = FontWeight.Black)
                Text("%.0f м".format(zone.distanceM))
                Text("%+.1f km/h/s".format(zone.strengthKmhPerS), color = AnalysisMuted)
            }
        }
    }
}

@Composable
private fun LapSelector(title: String, selected: LapResult, laps: List<LapResult>, modifier: Modifier, onSelect: (LapResult) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(title + " #" + selected.no + "  " + formatMs(selected.timeMs), maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            laps.forEach { lap ->
                DropdownMenuItem(
                    text = { Text("#" + lap.no + "  " + formatMs(lap.timeMs)) },
                    onClick = { onSelect(lap); open = false }
                )
            }
        }
    }
}

@Composable
private fun AnalysisMetric(title: String, value: String, modifier: Modifier, color: Color) {
    Column(modifier.background(AnalysisPanel, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(title, color = AnalysisMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(AnalysisPanel, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(title, color = AnalysisMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun SpeedChart(reference: LapResult, compare: LapResult, modifier: Modifier) {
    val ref = LapAnalysis.series(reference)
    val cmp = LapAnalysis.series(compare)
    val maxSpeed = (ref + cmp).maxOfOrNull { it.speedKmh }?.coerceAtLeast(30.0) ?: 100.0
    Canvas(modifier) {
        fun path(points: List<ru.racelab.phone.analysis.LapSeriesPoint>): Path {
            val p = Path()
            points.forEachIndexed { i, s ->
                val x = (s.progress * size.width).toFloat()
                val y = (size.height - s.speedKmh / maxSpeed * size.height).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        drawLine(AnalysisMuted.copy(alpha = .35f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
        if (ref.size > 1) drawPath(path(ref), AnalysisGreen, style = Stroke(3f))
        if (cmp.size > 1) drawPath(path(cmp), AnalysisAmber, style = Stroke(3f))
    }
}

@Composable
private fun DeltaChart(points: List<Pair<Double, Long>>, modifier: Modifier) {
    val maxAbs = points.maxOfOrNull { abs(it.second) }?.coerceAtLeast(500L) ?: 1000L
    Canvas(modifier) {
        val mid = size.height / 2f
        drawLine(AnalysisMuted.copy(alpha = .5f), Offset(0f, mid), Offset(size.width, mid), 1f)
        if (points.size < 2) return@Canvas
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = (p.first * size.width).toFloat()
            val y = mid + (p.second.toDouble() / maxAbs * mid).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (points.last().second <= 0) AnalysisGreen else AnalysisRed, style = Stroke(3f))
    }
}

@Composable
private fun SpeedTraceMap(lap: LapResult, modifier: Modifier) {
    val pts = lap.trace
    Canvas(modifier.background(Color(0xFF090D0A), RoundedCornerShape(8.dp))) {
        if (pts.size < 2) return@Canvas
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val dx = (maxLon - minLon).coerceAtLeast(1e-9)
        val dy = (maxLat - minLat).coerceAtLeast(1e-9)
        val maxSpeed = pts.maxOfOrNull { (it.speedMps ?: 0.0) * 3.6 }?.coerceAtLeast(1.0) ?: 1.0
        fun xy(i: Int): Offset {
            val p = pts[i]
            val x = ((p.lon - minLon) / dx * size.width).toFloat()
            val y = (size.height - (p.lat - minLat) / dy * size.height).toFloat()
            return Offset(x, y)
        }
        for (i in 1 until pts.size) {
            val ratio = (((pts[i].speedMps ?: 0.0) * 3.6) / maxSpeed).coerceIn(0.0, 1.0)
            val color = when {
                ratio > .72 -> AnalysisGreen
                ratio > .42 -> AnalysisAmber
                else -> AnalysisRed
            }
            drawLine(color, xy(i - 1), xy(i), strokeWidth = 4f)
        }
    }
}

private fun formatMs(ms: Long): String {
    val m = ms / 60000
    val s = (ms % 60000) / 1000
    val x = ms % 1000
    return "%02d:%02d.%03d".format(m, s, x)
}
