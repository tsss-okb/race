package ru.racelab.phone.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.racelab.phone.session.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ArchivePanel = Color(0xFF131A15)
private val ArchiveGreen = Color(0xFF69FF72)
private val ArchiveAmber = Color(0xFFFFCA58)
private val ArchiveRed = Color(0xFFFF6B6B)
private val ArchiveMuted = Color(0xFF93A096)

@Composable
fun SessionsScreen() {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf(SessionRepository.list(context)) }
    var selected by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }

    fun refresh() {
        sessions = SessionRepository.list(context)
    }

    Column(Modifier.fillMaxSize().padding(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("АРХИВ СЕССИЙ", color = ArchiveGreen, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(
                    sessions.size.toString() + " сессий • " + formatBytes(sessions.sumOf { it.sizeBytes }),
                    color = ArchiveMuted,
                    fontSize = 10.sp
                )
            }
            OutlinedButton(onClick = { refresh() }) { Text("ОБНОВИТЬ", fontSize = 9.sp) }
        }
        Spacer(Modifier.height(7.dp))

        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Завершённые заезды появятся здесь.", color = ArchiveMuted)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onOpen = { selected = session },
                        onDelete = { deleteTarget = session }
                    )
                }
            }
        }
    }

    selected?.let { session ->
        SessionDetailsDialog(
            session = session,
            onDismiss = { selected = null },
            onDeleted = {
                selected = null
                refresh()
            }
        )
    }

    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить сессию?") },
            text = { Text(session.id + "\nТелеметрия этой сессии будет удалена.") },
            confirmButton = {
                Button(
                    onClick = {
                        SessionRepository.delete(session)
                        deleteTarget = null
                        refresh()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ArchiveRed, contentColor = Color.Black)
                ) { Text("УДАЛИТЬ") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("ОТМЕНА") } }
        )
    }
}

@Composable
private fun SessionCard(
    session: SessionSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ArchivePanel
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.trackName ?: "Сессия",
                        fontWeight = FontWeight.Black,
                        color = if (session.bestLapMs != null) ArchiveGreen else Color.White
                    )
                    Text(formatDate(session.startedAtMs) + " • " + session.id, color = ArchiveMuted, fontSize = 9.sp)
                }
                TextButton(onClick = onDelete) { Text("×", color = ArchiveRed, fontSize = 18.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LAPS " + session.laps.size, fontSize = 10.sp)
                Text("BEST " + (session.bestLapMs?.let(::formatLap) ?: "—"), color = ArchiveGreen, fontSize = 10.sp)
                Text("VMAX " + "%.0f".format(session.maxSpeedKmh), color = ArchiveAmber, fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DataBadge("GPS", session.hasGps)
                DataBadge("IMU", session.hasSensors)
                DataBadge("OBD", session.hasObdCustom)
                DataBadge("CAN", session.hasCan)
                DataBadge("VIDEO " + session.videoRefs.size, session.videoRefs.isNotEmpty())
            }
        }
    }
}

@Composable
private fun DataBadge(text: String, active: Boolean) {
    Text(
        text,
        modifier = Modifier
            .background(
                if (active) Color(0xFF17361B) else Color(0xFF202320),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        color = if (active) ArchiveGreen else ArchiveMuted,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SessionDetailsDialog(
    session: SessionSummary,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var lastExport by remember { mutableStateOf<SessionExportResult?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(session.trackName ?: "СЕССИЯ")
                Text(formatDate(session.startedAtMs), color = ArchiveMuted, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ГОТОВО") } },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ArchiveMetric("BEST", session.bestLapMs?.let(::formatLap) ?: "—", Modifier.weight(1f), ArchiveGreen)
                    ArchiveMetric("VMAX", "%.0f".format(session.maxSpeedKmh), Modifier.weight(1f), ArchiveAmber)
                    ArchiveMetric("LAPS", session.laps.size.toString(), Modifier.weight(1f), Color.White)
                }
                Text(
                    "GPS " + (session.gpsSource ?: "—") + " • " + formatBytes(session.sizeBytes),
                    color = ArchiveMuted,
                    fontSize = 10.sp
                )

                if (session.laps.isNotEmpty()) {
                    Text("КРУГИ", color = ArchiveMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    session.laps.take(12).forEach { lap ->
                        Row(
                            Modifier.fillMaxWidth().background(ArchivePanel, RoundedCornerShape(8.dp)).padding(7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("#" + lap.no, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            Text(formatLap(lap.timeMs), color = if (lap.timeMs == session.bestLapMs) ArchiveGreen else Color.White, fontSize = 10.sp)
                            Text("%.0f km/h".format(lap.maxSpeedKmh), color = ArchiveMuted, fontSize = 9.sp)
                        }
                    }
                }

                if (session.videoRefs.isNotEmpty()) {
                    Text("ВИДЕО", color = ArchiveMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    session.videoRefs.take(8).forEach { ref ->
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(Uri.parse(ref.uri), "video/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    )
                                }.onFailure { status = "Не удалось открыть видео" }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(ref.name, fontSize = 9.sp, maxLines = 1) }
                    }
                }

                Text("ЭКСПОРТ → Downloads/RaceLab", color = ArchiveMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                SessionExportFormat.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { format ->
                            OutlinedButton(
                                onClick = {
                                    status = "Экспорт " + format.name + "…"
                                    scope.launch {
                                        val result = runCatching {
                                            withContext(Dispatchers.IO) {
                                                SessionExporter.export(context, session, format)
                                            }
                                        }
                                        result.onSuccess {
                                            lastExport = it
                                            status = "Готово: " + it.displayName
                                        }.onFailure {
                                            status = "Ошибка: " + (it.message ?: "export")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp)
                            ) { Text(format.name, fontSize = 8.sp) }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                lastExport?.let { exp ->
                    if (exp.shareable) {
                        Button(
                            onClick = { SessionExporter.share(context, exp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ArchiveGreen, contentColor = Color.Black)
                        ) { Text("ПОДЕЛИТЬСЯ " + exp.displayName, fontSize = 9.sp) }
                    }
                }
                if (status.isNotBlank()) Text(status, color = ArchiveAmber, fontSize = 9.sp)

                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("УДАЛИТЬ СЕССИЮ", color = ArchiveRed)
                }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить?") },
            text = { Text("Файлы телеметрии " + session.id + " будут удалены.") },
            confirmButton = {
                Button(
                    onClick = {
                        SessionRepository.delete(session)
                        confirmDelete = false
                        onDeleted()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ArchiveRed, contentColor = Color.Black)
                ) { Text("УДАЛИТЬ") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ОТМЕНА") } }
        )
    }
}

@Composable
private fun ArchiveMetric(title: String, value: String, modifier: Modifier, color: Color) {
    Column(modifier.background(ArchivePanel, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Text(title, color = ArchiveMuted, fontSize = 8.sp)
        Text(value, color = color, fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatLap(ms: Long): String {
    val m = ms / 60000
    val s = (ms % 60000) / 1000
    val x = ms % 1000
    return "%02d:%02d.%03d".format(m, s, x)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}
