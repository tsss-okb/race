package ru.racelab.phone

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.racelab.phone.pitlane.InternetPitRelaySettingsRepository
import ru.racelab.phone.pitlane.PitTeamClient
import ru.racelab.phone.pitlane.PitTeamConfig
import kotlin.math.abs

private val TeamBg = Color(0xFF050607)
private val TeamPanel = Color(0xFF111315)
private val TeamBorder = Color(0xFF2B2F33)
private val TeamYellow = Color(0xFFF2C300)
private val TeamGreen = Color(0xFF58E13E)
private val TeamRed = Color(0xFFFF3B30)
private val TeamWhite = Color(0xFFF2F2F2)
private val TeamMuted = Color(0xFF989DA2)

class PitLaneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val initial = PitTeamConfigRepository.load(this, intent?.data)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = TeamYellow,
                    background = TeamBg,
                    surface = TeamPanel,
                    onBackground = TeamWhite,
                    onSurface = TeamWhite
                )
            ) {
                var config by remember { mutableStateOf(initial) }
                var editing by remember { mutableStateOf(!initial.valid) }

                if (editing) {
                    PitTeamSetup(
                        initial = config,
                        onConnect = {
                            PitTeamConfigRepository.save(this, it)
                            config = it
                            editing = false
                        }
                    )
                } else {
                    PitTeamDashboard(
                        config = config,
                        onSettings = { editing = true }
                    )
                }
            }
        }
    }
}

private object PitTeamConfigRepository {
    private const val PREFS = "racelab_pit_team"
    private const val RELAY = "relay"
    private const val ROOM = "room"
    private const val KEY = "key"

    fun load(context: Context, uri: Uri?): PitTeamConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fromUri = uri?.takeIf { it.scheme == "racelab" && it.host == "pit" }?.let {
            PitTeamConfig(
                relayUrl = it.getQueryParameter("relay") ?: InternetPitRelaySettingsRepository.DEFAULT_BASE_URL,
                room = it.getQueryParameter("room") ?: "",
                key = it.getQueryParameter("key") ?: ""
            )
        }

        val stored = PitTeamConfig(
            relayUrl = prefs.getString(RELAY, InternetPitRelaySettingsRepository.DEFAULT_BASE_URL)
                ?: InternetPitRelaySettingsRepository.DEFAULT_BASE_URL,
            room = prefs.getString(ROOM, "") ?: "",
            key = prefs.getString(KEY, "") ?: ""
        )

        val result = fromUri?.takeIf { it.valid } ?: stored
        if (fromUri?.valid == true) save(context, fromUri)
        return result
    }

    fun save(context: Context, config: PitTeamConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(RELAY, config.relayUrl.trim().trimEnd('/'))
            .putString(ROOM, config.room.trim())
            .putString(KEY, config.key.trim())
            .apply()
    }
}

@Composable
private fun PitTeamSetup(
    initial: PitTeamConfig,
    onConnect: (PitTeamConfig) -> Unit
) {
    var relay by remember(initial) { mutableStateOf(initial.relayUrl.ifBlank { InternetPitRelaySettingsRepository.DEFAULT_BASE_URL }) }
    var room by remember(initial) { mutableStateOf(initial.room) }
    var key by remember(initial) { mutableStateOf(initial.key) }

    Box(
        Modifier
            .fillMaxSize()
            .background(TeamBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TeamPanel),
            border = BorderStroke(1.dp, TeamBorder),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("PIT LANE TEAM", color = TeamYellow, fontWeight = FontWeight.Black, fontSize = 24.sp)
                Text(
                    "Второй экран команды. Только чтение — управление PIT остаётся у гонщика.",
                    color = TeamMuted,
                    fontSize = 11.sp
                )

                OutlinedTextField(
                    value = relay,
                    onValueChange = { relay = it },
                    label = { Text("Relay URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = room,
                        onValueChange = { room = it.filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } },
                        label = { Text("ROOM") },
                        singleLine = true,
                        modifier = Modifier.weight(.7f)
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.filterNot(Char::isWhitespace) },
                        label = { Text("KEY") },
                        singleLine = true,
                        modifier = Modifier.weight(1.1f)
                    )
                }

                val candidate = PitTeamConfig(relay.trim(), room.trim(), key.trim())
                Button(
                    onClick = { onConnect(candidate) },
                    enabled = candidate.valid,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TeamYellow,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("ПОДКЛЮЧИТЬСЯ К PIT", fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun PitTeamDashboard(
    config: PitTeamConfig,
    onSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val client = remember(config) { PitTeamClient(config) }
    val snapshot by client.state.collectAsState()

    DisposableEffect(client) {
        val job = client.start(scope)
        onDispose { job.cancel() }
    }

    var frameNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(snapshot.pitActive, snapshot.lastReceiveElapsedMs) {
        while (true) {
            if (snapshot.pitActive) {
                withFrameNanos {
                    frameNow = SystemClock.elapsedRealtime()
                }
            } else {
                frameNow = SystemClock.elapsedRealtime()
                delay(80)
            }
        }
    }

    val receiveAge = if (snapshot.lastReceiveElapsedMs > 0L) {
        (frameNow - snapshot.lastReceiveElapsedMs).coerceAtLeast(0L)
    } else Long.MAX_VALUE
    val live = receiveAge < 2_000L

    val pitMs = if (snapshot.pitActive && snapshot.pitBaseReceivedElapsedMs > 0L) {
        snapshot.pitBaseMs + (frameNow - snapshot.pitBaseReceivedElapsedMs).coerceAtLeast(0L)
    } else snapshot.pitBaseMs

    Column(
        Modifier
            .fillMaxSize()
            .background(TeamBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        TeamHeader(
            live = live,
            receiveAgeMs = receiveAge,
            transport = snapshot.transport,
            rttMs = snapshot.rttMs,
            track = snapshot.track,
            onSettings = onSettings
        )

        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            TeamPitCard(
                active = snapshot.pitActive,
                pitMs = pitMs,
                trigger = snapshot.pitTrigger,
                modifier = Modifier.weight(1.55f).fillMaxHeight()
            )

            Column(
                Modifier.weight(.8f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                TeamMetric("LAST", format100(snapshot.pitLastMs), TeamWhite, Modifier.weight(1f))
                TeamMetric("BEST", format100(snapshot.pitBestMs), TeamGreen, Modifier.weight(1f))
            }

            Column(
                Modifier.weight(.72f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                TeamMetric("PIT #", snapshot.pitCount.toString(), TeamYellow, Modifier.weight(1f))
                TeamMetric("SPEED", "${snapshot.speedKmh.toInt()} км/ч", TeamWhite, Modifier.weight(1f))
            }
        }

        Row(
            Modifier.fillMaxWidth().height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            TeamBottomMetric("CURRENT LAP", format100(snapshot.lapCurrentMs), Modifier.weight(1f))
            TeamBottomMetric("BEST LAP", format100(snapshot.lapBestMs), Modifier.weight(1f))
            TeamBottomMetric(
                "DELTA",
                formatDelta100(snapshot.deltaMs),
                Modifier.weight(.8f),
                valueColor = when {
                    snapshot.deltaMs == null -> TeamWhite
                    snapshot.deltaMs!! <= 0 -> TeamGreen
                    else -> TeamRed
                }
            )
            TeamBottomMetric("GPS", "%.1f Hz · S%d".format(snapshot.gpsHz, snapshot.satellites), Modifier.weight(.95f))
            TeamBottomMetric(
                "SIGNAL RTT",
                snapshot.rttMs?.let { "${it} ms" } ?: if (live) snapshot.transport else "—",
                Modifier.weight(.9f),
                valueColor = if (snapshot.transport == "WEBSOCKET" && snapshot.rttMs != null) TeamGreen
                    else if (live) TeamYellow else TeamRed
            )
        }
    }
}

@Composable
private fun TeamHeader(
    live: Boolean,
    receiveAgeMs: Long,
    transport: String,
    rttMs: Long?,
    track: String,
    onSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        color = TeamPanel,
        border = BorderStroke(1.dp, TeamBorder),
        shape = RoundedCornerShape(13.dp)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("RACELAB · PIT LANE TEAM", color = TeamWhite, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(12.dp))
            Text(
                track,
                color = TeamMuted,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    !live -> "● НЕТ СВЯЗИ"
                    transport == "WEBSOCKET" -> "● WS LIVE"
                    else -> "● HTTP FALLBACK"
                },
                color = when {
                    !live -> TeamRed
                    transport == "WEBSOCKET" -> TeamGreen
                    else -> TeamYellow
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.width(8.dp))
            Text(
                rttMs?.let { "RTT ${it}ms" }
                    ?: if (receiveAgeMs != Long.MAX_VALUE) "AGE ${receiveAgeMs}ms" else "—",
                color = TeamMuted,
                fontSize = 8.sp
            )
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = onSettings) {
                Text("⚙", color = TeamMuted, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun TeamPitCard(
    active: Boolean,
    pitMs: Long,
    trigger: String,
    modifier: Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF0D0F11),
        border = BorderStroke(1.dp, if (active) TeamYellow else TeamBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                if (active) "PIT ACTIVE" else "PIT READY",
                color = if (active) TeamYellow else TeamGreen,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )

            val parts = timeParts100(pitMs)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    parts.first,
                    color = TeamWhite,
                    fontSize = 72.sp,
                    lineHeight = 72.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    parts.second,
                    color = TeamYellow,
                    fontSize = 72.sp,
                    lineHeight = 72.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Text(
                trigger,
                color = TeamMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TeamMetric(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier
) {
    Surface(
        modifier = modifier,
        color = TeamPanel,
        border = BorderStroke(1.dp, TeamBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = TeamMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(
                value,
                color = color,
                fontSize = 25.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TeamBottomMetric(
    title: String,
    value: String,
    modifier: Modifier,
    valueColor: Color = TeamWhite
) {
    Surface(
        modifier = modifier,
        color = TeamPanel,
        border = BorderStroke(1.dp, TeamBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = TeamMuted, fontSize = 7.sp, maxLines = 1)
            Text(
                value,
                color = valueColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun timeParts100(ms: Long): Pair<String, String> {
    val safe = ms.coerceAtLeast(0L)
    val min = safe / 60_000
    val sec = (safe % 60_000) / 1_000
    val cs = (safe % 1_000) / 10
    return "%02d:%02d.".format(min, sec) to "%02d".format(cs)
}

private fun format100(ms: Long?): String {
    if (ms == null) return "—"
    val (base, cs) = timeParts100(ms)
    return base + cs
}

private fun formatDelta100(ms: Long?): String {
    if (ms == null) return "—"
    val sign = if (ms < 0) "−" else "+"
    return sign + "%.2f".format(abs(ms) / 1000.0)
}
