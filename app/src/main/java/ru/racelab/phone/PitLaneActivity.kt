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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import ru.racelab.phone.pitlane.PitTeamSnapshot
import ru.racelab.phone.pitlane.TeamCarEntry
import ru.racelab.phone.pitlane.TeamGarageRepository
import ru.racelab.phone.pitlane.TeamDevicePresence
import ru.racelab.phone.pitlane.TeamPresenceClient
import ru.racelab.phone.pitlane.defaultRole
import kotlin.math.abs
import kotlin.math.roundToInt

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
                var garage by remember {
                    mutableStateOf(
                        if (initial.valid) TeamGarageRepository.ensurePrimary(this, initial)
                        else TeamGarageRepository.load(this)
                    )
                }
                var selectedCar by remember { mutableStateOf<TeamCarEntry?>(null) }
                var editing by remember { mutableStateOf(!initial.valid) }

                if (editing) {
                    PitTeamSetup(
                        initial = config,
                        onConnect = {
                            PitTeamConfigRepository.save(this, it)
                            config = it
                            garage = TeamGarageRepository.ensurePrimary(this, it)
                            selectedCar = null
                            editing = false
                        }
                    )
                } else if (selectedCar != null) {
                    val car = selectedCar!!
                    PitTeamDashboard(
                        config = car.toPitConfig(config.deviceSlot, config.deviceRole),
                        onSettings = { editing = true },
                        onGarage = { selectedCar = null }
                    )
                } else {
                    TeamGarageScreen(
                        cars = garage,
                        deviceSlot = config.deviceSlot,
                        deviceRole = config.deviceRole,
                        defaultRelay = config.relayUrl,
                        onOpenCar = { selectedCar = it },
                        onAddCar = { entry ->
                            val next = (garage + entry)
                                .distinctBy { it.relayUrl.trimEnd('/') + "|" + it.room }
                                .take(TeamGarageRepository.MAX_CARS)
                            TeamGarageRepository.save(this, next)
                            garage = next
                        },
                        onRemoveCar = { entry ->
                            val next = garage.filterNot { it.id == entry.id }
                            TeamGarageRepository.save(this, next)
                            garage = next
                        },
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
    private const val SLOT = "device_slot"
    private const val ROLE = "device_role"

    fun load(context: Context, uri: Uri?): PitTeamConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val slot = prefs.getInt(SLOT, 1).coerceIn(1, 5)
        val role = prefs.getString(ROLE, defaultRole(slot))?.takeIf { it.isNotBlank() } ?: defaultRole(slot)

        val stored = PitTeamConfig(
            relayUrl = prefs.getString(RELAY, InternetPitRelaySettingsRepository.DEFAULT_BASE_URL)
                ?: InternetPitRelaySettingsRepository.DEFAULT_BASE_URL,
            room = prefs.getString(ROOM, "") ?: "",
            key = prefs.getString(KEY, "") ?: "",
            deviceSlot = slot,
            deviceRole = role
        )

        val fromUri = uri?.takeIf { it.scheme == "racelab" && it.host == "pit" }?.let {
            PitTeamConfig(
                relayUrl = it.getQueryParameter("relay") ?: InternetPitRelaySettingsRepository.DEFAULT_BASE_URL,
                room = it.getQueryParameter("room") ?: "",
                key = it.getQueryParameter("key") ?: "",
                deviceSlot = slot,
                deviceRole = role
            )
        }

        val result = fromUri?.takeIf { it.valid } ?: stored
        if (fromUri?.valid == true) save(context, result)
        return result
    }

    fun save(context: Context, config: PitTeamConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(RELAY, config.relayUrl.trim().trimEnd('/'))
            .putString(ROOM, config.room.trim())
            .putString(KEY, config.key.trim())
            .putInt(SLOT, config.deviceSlot.coerceIn(1, 5))
            .putString(ROLE, config.deviceRole.trim().take(24))
            .apply()
    }
}

private object PitTargetRepository {
    private const val PREFS = "racelab_pit_team"
    private const val TARGET_SECONDS = "target_seconds"
    private const val DEFAULT_TARGET_SECONDS = 60

    fun loadSeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(TARGET_SECONDS, DEFAULT_TARGET_SECONDS)
            .coerceIn(5, 600)

    fun saveSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(TARGET_SECONDS, seconds.coerceIn(5, 600))
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
    var deviceSlot by remember(initial) { mutableIntStateOf(initial.deviceSlot.coerceIn(1, 5)) }
    var deviceRole by remember(initial) { mutableStateOf(initial.deviceRole.ifBlank { defaultRole(initial.deviceSlot) }) }

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

                Text("Устройство команды", color = TeamMuted, fontSize = 10.sp)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    (1..5).forEach { slot ->
                        FilterChip(
                            selected = deviceSlot == slot,
                            onClick = {
                                deviceSlot = slot
                                deviceRole = defaultRole(slot)
                            },
                            label = { Text(slot.toString(), fontSize = 9.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                OutlinedTextField(
                    value = deviceRole,
                    onValueChange = { deviceRole = it.take(24) },
                    label = { Text("Роль устройства") },
                    supportingText = { Text("Например: Тимлид, Инженер, Механик") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val candidate = PitTeamConfig(
                    relayUrl = relay.trim(),
                    room = room.trim(),
                    key = key.trim(),
                    deviceSlot = deviceSlot,
                    deviceRole = deviceRole.trim().ifBlank { defaultRole(deviceSlot) }
                )
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
private fun TeamGarageScreen(
    cars: List<TeamCarEntry>,
    deviceSlot: Int,
    deviceRole: String,
    defaultRelay: String,
    onOpenCar: (TeamCarEntry) -> Unit,
    onAddCar: (TeamCarEntry) -> Unit,
    onRemoveCar: (TeamCarEntry) -> Unit,
    onSettings: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    val snapshots = remember { mutableStateMapOf<String, PitTeamSnapshot>() }

    val rankedIds = cars
        .mapNotNull { car ->
            snapshots[car.id]?.lapBestMs?.let { best -> car.id to best }
        }
        .sortedBy { it.second }
        .map { it.first }
    val positionById = rankedIds.mapIndexed { index, id -> id to (index + 1) }.toMap()
    val fastestTeamBestMs = snapshots.values.mapNotNull { it.lapBestMs }.minOrNull()
    val onlineCount = cars.count { car ->
        val s = snapshots[car.id]
        s != null && s.lastReceiveElapsedMs > 0L &&
            SystemClock.elapsedRealtime() - s.lastReceiveElapsedMs < 3_500L
    }

    if (showAddDialog) {
        AddTeamCarDialog(
            defaultRelay = defaultRelay,
            canAdd = cars.size < TeamGarageRepository.MAX_CARS,
            onDismiss = { showAddDialog = false },
            onAdd = {
                onAddCar(it)
                showAddDialog = false
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TeamBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(9.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = TeamPanel,
            border = BorderStroke(1.dp, TeamBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "RACELAB · TEAM GARAGE",
                        color = TeamWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "${cars.size} машин · ${onlineCount} онлайн · устройство ${deviceSlot}/5 · $deviceRole",
                        color = TeamMuted,
                        fontSize = 9.sp
                    )
                }
                Button(
                    onClick = { showAddDialog = true },
                    enabled = cars.size < TeamGarageRepository.MAX_CARS,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TeamGreen,
                        contentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("+ МАШИНА", fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onSettings) {
                    Text("⚙", color = TeamMuted, fontSize = 17.sp)
                }
            }
        }

        Spacer(Modifier.height(7.dp))

        if (cars.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Добавь первую машину команды", color = TeamMuted)
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                cars.forEachIndexed { index, car ->
                    TeamGarageCarRow(
                        car = car,
                        deviceSlot = deviceSlot,
                        deviceRole = deviceRole,
                        teamPosition = positionById[car.id],
                        fastestTeamBestMs = fastestTeamBestMs,
                        canDelete = index > 0,
                        onSnapshot = { snapshots[car.id] = it },
                        onOpen = { onOpenCar(car) },
                        onDelete = { onRemoveCar(car) }
                    )
                }
                Text(
                    "P TEAM — позиция только среди машин вашей команды по лучшему кругу. Для общей позиции гонки нужен внешний тайминг трассы.",
                    color = TeamMuted,
                    fontSize = 8.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun TeamGarageCarRow(
    car: TeamCarEntry,
    deviceSlot: Int,
    deviceRole: String,
    teamPosition: Int?,
    fastestTeamBestMs: Long?,
    canDelete: Boolean,
    onSnapshot: (PitTeamSnapshot) -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val config = remember(car, deviceSlot, deviceRole) {
        car.toPitConfig(deviceSlot, deviceRole)
    }
    val client = remember(config) { PitTeamClient(config) }
    val snapshot by client.state.collectAsState()

    DisposableEffect(client) {
        val job = client.start(scope)
        onDispose { job.cancel() }
    }
    LaunchedEffect(snapshot) { onSnapshot(snapshot) }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(snapshot.lastReceiveElapsedMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500L)
        }
    }

    val live = snapshot.lastReceiveElapsedMs > 0L &&
        now - snapshot.lastReceiveElapsedMs < 3_500L
    val pitDistance = snapshot.pitDistanceM
    val pitNow = snapshot.pitLaneActive || snapshot.pitActive
    val pitSoon = !pitNow && pitDistance != null && pitDistance <= 350.0
    val pitAlert = when {
        pitNow -> "PIT NOW"
        pitSoon -> "PIT SOON ${pitDistance!!.roundToInt()}m"
        else -> null
    }
    val teamGapMs = if (snapshot.lapBestMs != null && fastestTeamBestMs != null) {
        (snapshot.lapBestMs!! - fastestTeamBestMs).coerceAtLeast(0L)
    } else null
    val identity = listOfNotNull(
        snapshot.carNumber.takeIf { it.isNotBlank() }?.let { "#$it" },
        snapshot.driverName.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { car.label.ifBlank { car.room } }
    val carInfo = listOf(
        snapshot.teamName.takeIf { it.isNotBlank() } ?: car.label.ifBlank { "Команда" },
        snapshot.carName.ifBlank { "Машина —" },
        snapshot.raceClass.ifBlank { "Класс —" }
    ).joinToString(" · ")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        color = TeamPanel,
        border = BorderStroke(
            if (pitNow) 2.dp else 1.dp,
            when {
                pitNow -> TeamRed
                pitSoon -> TeamYellow
                live -> TeamGreen
                else -> TeamBorder
            }
        ),
        shape = RoundedCornerShape(13.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    teamPosition?.let { "P$it" } ?: "P—",
                    color = if (teamPosition != null) TeamYellow else TeamMuted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.width(40.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        identity,
                        color = TeamWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        carInfo,
                        color = TeamMuted,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (pitAlert != null) {
                    Text(
                        pitAlert,
                        color = if (pitNow) TeamRed else TeamYellow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (live) "● LIVE" else "● OFFLINE",
                    color = if (live) TeamGreen else TeamRed,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
                if (canDelete) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = onDelete,
                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                    ) {
                        Text("×", color = TeamRed, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                GarageMetric("LAPS", snapshot.lapsCompleted.toString(), Modifier.weight(.58f))
                GarageMetric(
                    "STINT",
                    "S${snapshot.stintNo} · L${snapshot.stintLapNo}",
                    Modifier.weight(.78f),
                    TeamYellow
                )
                GarageMetric("CURRENT", format100(snapshot.lapCurrentMs.takeIf { it > 0L }), Modifier.weight(1f))
                GarageMetric("BEST", format100(snapshot.lapBestMs), Modifier.weight(1f), TeamGreen)
            }

            Spacer(Modifier.height(5.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                GarageMetric(
                    "TEAM GAP",
                    teamGapMs?.let { if (it == 0L) "FASTEST" else "+%.2f".format(it / 1000.0) } ?: "—",
                    Modifier.weight(.85f),
                    if (teamGapMs == 0L) TeamGreen else TeamWhite
                )
                GarageMetric(
                    "DELTA",
                    formatDelta100(snapshot.deltaMs),
                    Modifier.weight(.72f),
                    when {
                        snapshot.deltaMs == null -> TeamWhite
                        snapshot.deltaMs!! <= 0 -> TeamGreen
                        else -> TeamRed
                    }
                )
                GarageMetric("SPEED", "${snapshot.speedKmh.toInt()} km/h", Modifier.weight(.8f))
                GarageMetric(
                    "PIT",
                    when {
                        pitNow -> "NOW"
                        pitSoon -> "${pitDistance!!.roundToInt()}m"
                        (snapshot.pitLastMs ?: 0L) > 0L -> format100(snapshot.pitLastMs)
                        else -> "—"
                    },
                    Modifier.weight(.8f),
                    when {
                        pitNow -> TeamRed
                        pitSoon -> TeamYellow
                        else -> TeamWhite
                    }
                )
            }
        }
    }
}

@Composable
private fun GarageMetric(
    title: String,
    value: String,
    modifier: Modifier,
    color: Color = TeamWhite
) {
    Surface(
        modifier = modifier.height(43.dp),
        color = Color(0xFF0B0D0F),
        border = BorderStroke(1.dp, TeamBorder),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = TeamMuted, fontSize = 6.sp, maxLines = 1)
            Text(
                value,
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AddTeamCarDialog(
    defaultRelay: String,
    canAdd: Boolean,
    onDismiss: () -> Unit,
    onAdd: (TeamCarEntry) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var relay by remember(defaultRelay) { mutableStateOf(defaultRelay) }
    var room by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    val candidate = TeamCarEntry(
        label = label.trim(),
        relayUrl = relay.trim().trimEnd('/'),
        room = room.trim(),
        key = key.trim()
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("ДОБАВИТЬ МАШИНУ", color = TeamYellow, fontWeight = FontWeight.Black)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Для машины сокомандника укажи её ROOM/KEY. После подключения имя пилота, номер, модель и класс подтянутся автоматически.",
                    color = TeamMuted,
                    fontSize = 10.sp
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(32) },
                    label = { Text("Подпись (необязательно)") },
                    placeholder = { Text("Машина 2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = relay,
                    onValueChange = { relay = it },
                    label = { Text("Relay URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = room,
                        onValueChange = {
                            room = it.filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }
                        },
                        label = { Text("ROOM") },
                        singleLine = true,
                        modifier = Modifier.weight(.8f)
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.filterNot(Char::isWhitespace) },
                        label = { Text("KEY") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f)
                    )
                }
                Text(
                    "Гараж поддерживает до ${TeamGarageRepository.MAX_CARS} машин одновременно.",
                    color = TeamMuted,
                    fontSize = 8.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(candidate) },
                enabled = canAdd && candidate.valid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TeamGreen,
                    contentColor = Color.Black
                )
            ) {
                Text("ДОБАВИТЬ", fontWeight = FontWeight.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ОТМЕНА") }
        },
        containerColor = TeamPanel
    )
}

@Composable
private fun PitTeamDashboard(
    config: PitTeamConfig,
    onSettings: () -> Unit,
    onGarage: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(config) { PitTeamClient(config) }
    val presenceClient = remember(config) { TeamPresenceClient(config) }
    val snapshot by client.state.collectAsState()
    val teamDevices by presenceClient.devices.collectAsState()
    var targetSeconds by remember { mutableIntStateOf(PitTargetRepository.loadSeconds(context)) }
    var showTargetDialog by remember { mutableStateOf(false) }

    DisposableEffect(client, presenceClient) {
        val telemetryJob = client.start(scope)
        val presenceJob = presenceClient.start(scope)
        onDispose {
            telemetryJob.cancel()
            presenceJob.cancel()
        }
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

    val targetMs = targetSeconds * 1_000L
    val remainingMs = (targetMs - pitMs).coerceAtLeast(0L)
    val warningActive = snapshot.pitActive && pitMs < targetMs && remainingMs <= 15_000L
    val targetReached = snapshot.pitActive && pitMs >= targetMs
    val flashOn = warningActive && ((frameNow / 350L) % 2L == 0L)

    if (showTargetDialog) {
        PitTargetDialog(
            currentSeconds = targetSeconds,
            onDismiss = { showTargetDialog = false },
            onSave = { seconds ->
                targetSeconds = seconds
                PitTargetRepository.saveSeconds(context, seconds)
                showTargetDialog = false
            }
        )
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(TeamBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val portrait = maxHeight > maxWidth

        if (portrait) {
            Column(
                Modifier.fillMaxSize().padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TeamHeader(
                    live = live,
                    receiveAgeMs = receiveAge,
                    transport = snapshot.transport,
                    rttMs = snapshot.rttMs,
                    track = snapshot.track,
                    teamName = snapshot.teamName,
                    driverName = snapshot.driverName,
                    carNumber = snapshot.carNumber,
                    carName = snapshot.carName,
                    raceClass = snapshot.raceClass,
                    targetSeconds = targetSeconds,
                    onTarget = { showTargetDialog = true },
                    onSettings = onSettings,
                    onGarage = onGarage
                )
                TeamDevicesBar(teamDevices)

                TeamPitCard(
                    active = snapshot.pitActive,
                    pitMs = pitMs,
                    remainingMs = remainingMs,
                    targetSeconds = targetSeconds,
                    warningActive = warningActive,
                    flashOn = flashOn,
                    targetReached = targetReached,
                    trigger = snapshot.pitTrigger,
                    modifier = Modifier.fillMaxWidth().weight(1.55f)
                )

                Row(
                    Modifier.fillMaxWidth().height(92.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TeamMetric("LAST", format100(snapshot.pitLastMs), TeamWhite, Modifier.weight(1f))
                    TeamMetric("BEST", format100(snapshot.pitBestMs), TeamGreen, Modifier.weight(1f))
                }

                Row(
                    Modifier.fillMaxWidth().height(84.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TeamMetric("PIT #", snapshot.pitCount.toString(), TeamYellow, Modifier.weight(1f))
                    TeamMetric("SPEED", "${snapshot.speedKmh.toInt()} км/ч", TeamWhite, Modifier.weight(1f))
                }

                Row(
                    Modifier.fillMaxWidth().height(66.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TeamBottomMetric("CURRENT LAP", format100(snapshot.lapCurrentMs), Modifier.weight(1f))
                    TeamBottomMetric("BEST LAP", format100(snapshot.lapBestMs), Modifier.weight(1f))
                    TeamBottomMetric(
                        "DELTA",
                        formatDelta100(snapshot.deltaMs),
                        Modifier.weight(.82f),
                        valueColor = when {
                            snapshot.deltaMs == null -> TeamWhite
                            snapshot.deltaMs!! <= 0 -> TeamGreen
                            else -> TeamRed
                        }
                    )
                }

                Row(
                    Modifier.fillMaxWidth().height(62.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TeamBottomMetric(
                        "GPS",
                        "%.1f Hz · S%d".format(snapshot.gpsHz, snapshot.satellites),
                        Modifier.weight(1f)
                    )
                    TeamBottomMetric(
                        "SIGNAL RTT",
                        snapshot.rttMs?.let { "${it} ms" } ?: if (live) snapshot.transport else "—",
                        Modifier.weight(1f),
                        valueColor = if (snapshot.transport == "WEBSOCKET" && snapshot.rttMs != null) TeamGreen
                            else if (live) TeamYellow else TeamRed
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                TeamHeader(
                    live = live,
                    receiveAgeMs = receiveAge,
                    transport = snapshot.transport,
                    rttMs = snapshot.rttMs,
                    track = snapshot.track,
                    teamName = snapshot.teamName,
                    driverName = snapshot.driverName,
                    carNumber = snapshot.carNumber,
                    carName = snapshot.carName,
                    raceClass = snapshot.raceClass,
                    targetSeconds = targetSeconds,
                    onTarget = { showTargetDialog = true },
                    onSettings = onSettings,
                    onGarage = onGarage
                )
                TeamDevicesBar(teamDevices)

                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    TeamPitCard(
                        active = snapshot.pitActive,
                        pitMs = pitMs,
                        remainingMs = remainingMs,
                        targetSeconds = targetSeconds,
                        warningActive = warningActive,
                        flashOn = flashOn,
                        targetReached = targetReached,
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
    }
}

@Composable
private fun TeamHeader(
    live: Boolean,
    receiveAgeMs: Long,
    transport: String,
    rttMs: Long?,
    track: String,
    teamName: String,
    driverName: String,
    carNumber: String,
    carName: String,
    raceClass: String,
    targetSeconds: Int,
    onTarget: () -> Unit,
    onSettings: () -> Unit,
    onGarage: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        val driverLabel = listOfNotNull(
            carNumber.takeIf { it.isNotBlank() }?.let { "#$it" },
            driverName.takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { "ПИЛОТ —" }
        val raceIdentity = listOf(
            teamName.ifBlank { "Команда —" },
            carName.ifBlank { "Машина —" },
            raceClass.ifBlank { "Класс —" },
            track
        ).joinToString("  ·  ")

        Surface(
            modifier = Modifier.fillMaxWidth().height(if (compact) 90.dp else 62.dp),
            color = TeamPanel,
            border = BorderStroke(1.dp, TeamBorder),
            shape = RoundedCornerShape(13.dp)
        ) {
            if (compact) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "RACELAB · PIT TEAM",
                            color = TeamWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            driverLabel,
                            color = TeamYellow,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(
                            onClick = onGarage,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("ГАРАЖ", color = TeamGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = onSettings,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("⚙", color = TeamMuted, fontSize = 15.sp)
                        }
                    }

                    Text(
                        raceIdentity,
                        color = TeamMuted,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                !live -> "● НЕТ СВЯЗИ"
                                transport == "WEBSOCKET" -> "● WS LIVE"
                                else -> "● HTTP"
                            },
                            color = when {
                                !live -> TeamRed
                                transport == "WEBSOCKET" -> TeamGreen
                                else -> TeamYellow
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            rttMs?.let { "RTT ${it}ms" }
                                ?: if (receiveAgeMs != Long.MAX_VALUE) "AGE ${receiveAgeMs}ms" else "—",
                            color = TeamMuted,
                            fontSize = 8.sp,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = onTarget,
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("PIT ${targetSeconds}s", color = TeamYellow, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("RACELAB · PIT TEAM", color = TeamWhite, fontSize = 16.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                driverLabel,
                                color = TeamYellow,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            raceIdentity,
                            color = TeamMuted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onTarget,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("PIT ${targetSeconds}s", color = TeamYellow, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onGarage,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("ГАРАЖ", color = TeamGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onSettings) {
                        Text("⚙", color = TeamMuted, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamDevicesBar(devices: List<TeamDevicePresence>) {
    val onlineCount = devices.count { it.online }
    Surface(
        modifier = Modifier.fillMaxWidth().height(34.dp),
        color = Color(0xFF090B0D),
        border = BorderStroke(1.dp, TeamBorder),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$onlineCount/5",
                color = if (onlineCount > 0) TeamGreen else TeamRed,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.width(28.dp)
            )
            devices.forEach { device ->
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    color = if (device.online) Color(0xFF102012) else Color(0xFF151719),
                    border = BorderStroke(1.dp, if (device.online) TeamGreen else TeamBorder),
                    shape = RoundedCornerShape(7.dp)
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "●",
                            color = if (device.online) TeamGreen else TeamRed,
                            fontSize = 7.sp
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "${device.slot} ${device.role}",
                            color = if (device.online) TeamWhite else TeamMuted,
                            fontSize = 7.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamPitCard(
    active: Boolean,
    pitMs: Long,
    remainingMs: Long,
    targetSeconds: Int,
    warningActive: Boolean,
    flashOn: Boolean,
    targetReached: Boolean,
    trigger: String,
    modifier: Modifier
) {
    val alertVisible = targetReached || flashOn
    val cardColor = if (alertVisible) Color(0xFF4A0606) else Color(0xFF0D0F11)
    val borderColor = when {
        targetReached -> TeamRed
        warningActive && flashOn -> TeamRed
        active -> TeamYellow
        else -> TeamBorder
    }

    Surface(
        modifier = modifier,
        color = cardColor,
        border = BorderStroke(if (alertVisible) 3.dp else 1.dp, borderColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                when {
                    targetReached -> "PIT TIME"
                    warningActive -> "PIT • ОСТАЛОСЬ"
                    active -> "PIT ACTIVE"
                    else -> "PIT READY"
                },
                color = when {
                    targetReached || (warningActive && flashOn) -> TeamWhite
                    active -> TeamYellow
                    else -> TeamGreen
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )

            val mainTimeMs = if (active) remainingMs else targetSeconds * 1_000L
            val parts = timeParts100(mainTimeMs)
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
                    color = if (alertVisible) TeamWhite else TeamYellow,
                    fontSize = 72.sp,
                    lineHeight = 72.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Text(
                if (active) "ПРОШЛО ${format100(pitMs)} · TARGET ${targetSeconds}s · ${trigger}"
                else "TARGET ${targetSeconds}s · ${trigger}",
                color = if (alertVisible) TeamWhite else TeamMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PitTargetDialog(
    currentSeconds: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var value by remember(currentSeconds) { mutableStateOf(currentSeconds.toString()) }
    val seconds = value.toIntOrNull()?.coerceIn(5, 600)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("ТАЙМЕР ПИТ-СТОПА", color = TeamYellow, fontWeight = FontWeight.Black)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Установи целевое время. За 15 секунд до окончания табло начнёт мигать красным.",
                    color = TeamMuted,
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        value = input.filter(Char::isDigit).take(3)
                    },
                    label = { Text("Секунды (5–600)") },
                    suffix = { Text("с") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 45, 60, 90, 120).forEach { preset ->
                        AssistChip(
                            onClick = { value = preset.toString() },
                            label = { Text("${preset}s", fontSize = 9.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { seconds?.let(onSave) },
                enabled = seconds != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TeamYellow,
                    contentColor = Color.Black
                )
            ) {
                Text("УСТАНОВИТЬ", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ОТМЕНА") }
        },
        containerColor = TeamPanel
    )
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
