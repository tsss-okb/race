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
import ru.racelab.phone.ui.RacingColors
import ru.racelab.phone.ui.RacingDisplay
import ru.racelab.phone.ui.RacingTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val TeamBg = RacingColors.Background
private val TeamPanel = RacingColors.Surface
private val TeamBorder = RacingColors.Border
private val TeamYellow = RacingColors.Accent
private val TeamGreen = RacingColors.Success
private val TeamRed = RacingColors.Error
private val TeamWhite = RacingColors.White
private val TeamMuted = RacingColors.Muted

private enum class TeamMenuSection(val title: String, val shortTitle: String) {
    OVERVIEW("ОБЗОР", "ОБЗОР"),
    CARS("МАШИНЫ", "МАШИНЫ"),
    STRATEGY("СТРАТЕГИЯ", "СТРАТ."),
    PIT("PIT", "PIT"),
    CONNECTION("СВЯЗЬ", "СВЯЗЬ")
}

class PitLaneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val initial = PitTeamConfigRepository.load(this, intent?.data)

        setContent {
            RacingTheme {
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
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight(.96f),
            colors = CardDefaults.cardColors(containerColor = TeamPanel),
            border = BorderStroke(1.dp, TeamBorder),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                Modifier.padding(18.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("PIT LANE TEAM", color = TeamWhite, fontFamily = RacingDisplay, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                Text(
                    "Второй экран команды. Только чтение — управление PIT остаётся у гонщика.",
                    color = TeamMuted,
                    fontSize = 12.sp
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

                Text("Устройство команды", color = TeamMuted, fontSize = 12.sp)
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
                            label = { Text(slot.toString(), fontSize = 12.sp) },
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
    var section by remember { mutableStateOf(TeamMenuSection.OVERVIEW) }
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
    val engineerAlerts = cars.count { car ->
        val s = snapshots[car.id]
        s != null && (
            s.pitActive || s.pitLaneActive || s.strategyMandatoryPit ||
                s.strategyCombined.contains("NOW") || s.strategyCombined.contains("1 LAP")
            )
    }
    val mandatoryPitCount = cars.count { snapshots[it.id]?.strategyMandatoryPit == true }
    val pitActiveCount = cars.count { car ->
        val s = snapshots[car.id]
        s?.pitActive == true || s?.pitLaneActive == true
    }
    val fastestCarId = rankedIds.firstOrNull()
    val fastestSnapshot = fastestCarId?.let { id -> snapshots[id] }
    val fastestLabel = fastestSnapshot?.let { s ->
        listOfNotNull(
            s.carNumber.takeIf { it.isNotBlank() }?.let { "#$it" },
            s.driverName.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }.orEmpty().ifBlank { "—" }

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
                        "RACELAB · TEAM RACE ENGINEER",
                        color = TeamWhite,
                        fontFamily = RacingDisplay,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${cars.size} машин · ${onlineCount} онлайн · PIT alerts ${engineerAlerts} · устройство ${deviceSlot}/5 · $deviceRole",
                        color = TeamMuted,
                        fontSize = 12.sp
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
                    Text("+ МАШИНА", fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onSettings) {
                    Text("⚙", color = TeamMuted, fontSize = 17.sp)
                }
            }
        }

        Spacer(Modifier.height(7.dp))

        TeamCommandSummary(
            cars = cars.size,
            online = onlineCount,
            alerts = engineerAlerts,
            mandatoryPit = mandatoryPitCount,
            pitActive = pitActiveCount,
            fastest = fastestLabel,
            fastestLapMs = fastestTeamBestMs,
            onOverview = { section = TeamMenuSection.OVERVIEW },
            onStrategy = { section = TeamMenuSection.STRATEGY },
            onPit = { section = TeamMenuSection.PIT },
            onConnection = { section = TeamMenuSection.CONNECTION }
        )

        Spacer(Modifier.height(6.dp))

        TeamMenuTabs(
            selected = section,
            onSelected = { section = it }
        )

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
                        section = section,
                        canDelete = index > 0,
                        onSnapshot = { snapshots[car.id] = it },
                        onOpen = { onOpenCar(car) },
                        onDelete = { onRemoveCar(car) }
                    )
                }
                Text(
                    when (section) {
                        TeamMenuSection.OVERVIEW -> "ОБЗОР: статус всей команды, лучшие времена и срочные действия."
                        TeamMenuSection.CARS -> "МАШИНЫ: круги, текущий темп, лучший круг, дельта и скорость."
                        TeamMenuSection.STRATEGY -> "СТРАТЕГИЯ: прогноз круга, деградация, ресурс, запас до финиша и итоговая рекомендация."
                        TeamMenuSection.PIT -> "PIT: приближение, активный pit-lane, PIT-таймер, количество остановок и стратегия заезда."
                        TeamMenuSection.CONNECTION -> "СВЯЗЬ: LIVE/OFFLINE, транспорт, RTT, GPS и качество телеметрии."
                    },
                    color = TeamMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun TeamCommandSummary(
    cars: Int,
    online: Int,
    alerts: Int,
    mandatoryPit: Int,
    pitActive: Int,
    fastest: String,
    fastestLapMs: Long?,
    onOverview: () -> Unit,
    onStrategy: () -> Unit,
    onPit: () -> Unit,
    onConnection: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        TeamSummaryCard(
            title = "КОМАНДА",
            value = "$online/$cars LIVE",
            note = if (online == cars && cars > 0) "все на связи" else "проверь связь",
            color = if (online == cars && cars > 0) TeamGreen else TeamYellow,
            modifier = Modifier.weight(.8f),
            onClick = onConnection
        )
        TeamSummaryCard(
            title = "АЛЕРТЫ",
            value = alerts.toString(),
            note = if (alerts == 0) "нет срочных" else "нужно внимание",
            color = if (alerts == 0) TeamGreen else TeamRed,
            modifier = Modifier.weight(.65f),
            onClick = onOverview
        )
        TeamSummaryCard(
            title = "PIT REQUIRED",
            value = mandatoryPit.toString(),
            note = if (mandatoryPit == 0) "ресурс OK" else "по ресурсу",
            color = if (mandatoryPit == 0) TeamGreen else TeamYellow,
            modifier = Modifier.weight(.8f),
            onClick = onStrategy
        )
        TeamSummaryCard(
            title = "PIT ACTIVE",
            value = pitActive.toString(),
            note = if (pitActive == 0) "pit lane свободен" else "машина в PIT",
            color = if (pitActive == 0) TeamWhite else TeamRed,
            modifier = Modifier.weight(.75f),
            onClick = onPit
        )
        TeamSummaryCard(
            title = "FASTEST TEAM",
            value = fastest,
            note = format100(fastestLapMs),
            color = TeamGreen,
            modifier = Modifier.weight(1.15f),
            onClick = onOverview
        )
    }
}

@Composable
private fun TeamSummaryCard(
    title: String,
    value: String,
    note: String,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(58.dp)
            .clickable(onClick = onClick),
        color = Color(0xFF0B0D0F),
        border = BorderStroke(1.dp, TeamBorder),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = TeamMuted, fontSize = 10.sp, maxLines = 1)
            Text(
                value,
                color = color,
                fontFamily = RacingDisplay,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(note, color = TeamMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TeamMenuTabs(
    selected: TeamMenuSection,
    onSelected: (TeamMenuSection) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        TeamMenuSection.entries.forEach { item ->
            val active = item == selected
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clickable { onSelected(item) },
                color = if (active) TeamYellow else Color(0xFF0B0D0F),
                border = BorderStroke(1.dp, if (active) TeamYellow else TeamBorder),
                shape = RoundedCornerShape(9.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        item.shortTitle,
                        color = if (active) Color.Black else TeamWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                }
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
    section: TeamMenuSection,
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

    val receiveAgeMs = if (snapshot.lastReceiveElapsedMs > 0L) {
        (now - snapshot.lastReceiveElapsedMs).coerceAtLeast(0L)
    } else {
        Long.MAX_VALUE
    }
    val live = receiveAgeMs < 3_500L
    val currentPitMs = if (snapshot.pitActive && snapshot.pitBaseReceivedElapsedMs > 0L) {
        snapshot.pitBaseMs + (now - snapshot.pitBaseReceivedElapsedMs).coerceAtLeast(0L)
    } else {
        snapshot.pitLastMs ?: 0L
    }
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
    val strategyWindow = if (pitNow) "NOW" else snapshot.pacePitWindow
    val strategyWindowText = if (strategyWindow == "DATA") {
        "DATA ${snapshot.stintAnalysisLapCount}/3"
    } else {
        strategyWindow
    }
    val strategyWindowColor = when (strategyWindow) {
        "NOW" -> TeamRed
        "1 LAP", "2-3 LAPS", "3-5 LAPS" -> TeamYellow
        "HOLD" -> TeamGreen
        else -> TeamMuted
    }
    val degradationColor = when {
        snapshot.stintDegradationMsPerLap == null -> TeamMuted
        snapshot.stintDegradationMsPerLap!! >= 500.0 -> TeamRed
        snapshot.stintDegradationMsPerLap!! >= 200.0 -> TeamYellow
        snapshot.stintDegradationMsPerLap!! <= 0.0 -> TeamGreen
        else -> TeamWhite
    }
    val identity = listOfNotNull(
        snapshot.carNumber.takeIf { it.isNotBlank() }?.let { "#$it" },
        snapshot.driverName.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { car.label.ifBlank { car.room } }
    val carInfo = listOf(
        snapshot.teamName.takeIf { it.isNotBlank() } ?: car.label.ifBlank { "Команда" },
        snapshot.carName.ifBlank { "Машина —" },
        snapshot.raceClass.ifBlank { "Класс —" },
        snapshot.raceTargetLaps.takeIf { it > 0 }?.let { "RACE ${it}L" } ?: "RACE —"
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
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (pitAlert != null) {
                    Text(
                        pitAlert,
                        color = if (pitNow) TeamRed else TeamYellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (live) "● LIVE" else "● OFFLINE",
                    color = if (live) TeamGreen else TeamRed,
                    fontSize = 12.sp,
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

            when (section) {
                TeamMenuSection.OVERVIEW -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GarageMetric("LAPS", snapshot.lapsCompleted.toString(), Modifier.weight(.6f))
                        GarageMetric("BEST", format100(snapshot.lapBestMs), Modifier.weight(1f), TeamGreen)
                        GarageMetric(
                            "STRATEGY",
                            snapshot.strategyCombined,
                            Modifier.weight(1.05f),
                            strategyCombinedColor(snapshot)
                        )
                        GarageMetric(
                            "RESOURCE",
                            formatResource(snapshot),
                            Modifier.weight(.95f),
                            resourceLevelColor(snapshot.strategyResourcePct)
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GarageMetric(
                            "TEAM GAP",
                            teamGapMs?.let { if (it == 0L) "FASTEST" else "+%.2f".format(it / 1000.0) } ?: "—",
                            Modifier.weight(.9f),
                            if (teamGapMs == 0L) TeamGreen else TeamWhite
                        )
                        GarageMetric("PRED LAP", format100(snapshot.stintNextLapForecastMs), Modifier.weight(1f))
                        GarageMetric(
                            "RANGE",
                            snapshot.strategyEstimatedLapsRemaining?.let { "%.1f L".format(it) } ?: "—",
                            Modifier.weight(.8f),
                            resourceRangeColor(snapshot)
                        )
                        GarageMetric(
                            "PIT",
                            when {
                                pitNow -> "NOW"
                                pitSoon -> "${pitDistance!!.roundToInt()}m"
                                snapshot.strategyMandatoryPit -> "REQUIRED"
                                else -> "READY"
                            },
                            Modifier.weight(.85f),
                            when {
                                pitNow -> TeamRed
                                pitSoon || snapshot.strategyMandatoryPit -> TeamYellow
                                else -> TeamGreen
                            }
                        )
                    }
                }

                TeamMenuSection.CARS -> {
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
                            "RACE LEFT",
                            snapshot.strategyRaceLapsRemaining?.let { "$it L" } ?: "—",
                            Modifier.weight(.8f)
                        )
                    }
                }

                TeamMenuSection.STRATEGY -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GarageMetric("PRED LAP", format100(snapshot.stintNextLapForecastMs), Modifier.weight(1f))
                        GarageMetric("STINT AVG", format100(snapshot.stintAverageLapMs), Modifier.weight(1f))
                        GarageMetric(
                            "DEG",
                            formatDegradation(snapshot.stintDegradationMsPerLap),
                            Modifier.weight(.82f),
                            degradationColor
                        )
                        GarageMetric(
                            "PACE PIT",
                            strategyWindowText,
                            Modifier.weight(.95f),
                            strategyWindowColor
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GarageMetric(
                            "RESOURCE",
                            formatResource(snapshot),
                            Modifier.weight(.95f),
                            resourceLevelColor(snapshot.strategyResourcePct)
                        )
                        GarageMetric(
                            "USE/LAP",
                            snapshot.strategyUsagePerLapPct?.let { "%.2f%%".format(it) }
                                ?: if (snapshot.strategyResourceKind != "NONE") "DATA ${snapshot.strategySampleCount}" else "—",
                            Modifier.weight(.8f)
                        )
                        GarageMetric(
                            "RANGE",
                            snapshot.strategyEstimatedLapsRemaining?.let { "%.1f L".format(it) } ?: "—",
                            Modifier.weight(.75f),
                            resourceRangeColor(snapshot)
                        )
                        GarageMetric(
                            "FINISH Δ",
                            formatFinishMargin(snapshot.strategyFinishMarginLaps),
                            Modifier.weight(.78f),
                            finishMarginColor(snapshot.strategyFinishMarginLaps)
                        )
                        GarageMetric(
                            "STRATEGY",
                            snapshot.strategyCombined,
                            Modifier.weight(1.05f),
                            strategyCombinedColor(snapshot)
                        )
                    }
                }

                TeamMenuSection.PIT -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GarageMetric(
                            "PIT STATUS",
                            when {
                                pitNow -> "ACTIVE"
                                pitSoon -> "SOON"
                                snapshot.strategyMandatoryPit -> "REQUIRED"
                                else -> "READY"
                            },
                            Modifier.weight(.9f),
                            when {
                                pitNow -> TeamRed
                                pitSoon || snapshot.strategyMandatoryPit -> TeamYellow
                                else -> TeamGreen
                            }
                        )
                        GarageMetric(
                            "DISTANCE",
                            when {
                                pitNow -> "IN PIT"
                                pitDistance != null -> "${pitDistance.roundToInt()} m"
                                else -> "—"
                            },
                            Modifier.weight(.9f)
                        )
                        GarageMetric(
                            "PIT TIMER",
                            if (snapshot.pitActive) format100(currentPitMs) else "—",
                            Modifier.weight(1f),
                            if (snapshot.pitActive) TeamYellow else TeamWhite
                        )
                        GarageMetric("STOPS", snapshot.pitCount.toString(), Modifier.weight(.6f))
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GarageMetric("LAST PIT", format100(snapshot.pitLastMs), Modifier.weight(1f))
                        GarageMetric("BEST PIT", format100(snapshot.pitBestMs), Modifier.weight(1f), TeamGreen)
                        GarageMetric(
                            "TRIGGER",
                            snapshot.pitTrigger.ifBlank { "—" },
                            Modifier.weight(.95f)
                        )
                        GarageMetric(
                            "PIT PLAN",
                            snapshot.strategyCombined,
                            Modifier.weight(1f),
                            strategyCombinedColor(snapshot)
                        )
                    }
                }

                TeamMenuSection.CONNECTION -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GarageMetric(
                            "STATUS",
                            if (live) "LIVE" else "OFFLINE",
                            Modifier.weight(.75f),
                            if (live) TeamGreen else TeamRed
                        )
                        GarageMetric(
                            "TRANSPORT",
                            snapshot.transport,
                            Modifier.weight(.9f),
                            if (snapshot.transport == "WEBSOCKET") TeamGreen else if (live) TeamYellow else TeamRed
                        )
                        GarageMetric(
                            "RTT",
                            snapshot.rttMs?.let { "$it ms" } ?: "—",
                            Modifier.weight(.75f),
                            when {
                                snapshot.rttMs == null -> TeamMuted
                                snapshot.rttMs!! <= 200L -> TeamGreen
                                snapshot.rttMs!! <= 600L -> TeamYellow
                                else -> TeamRed
                            }
                        )
                        GarageMetric(
                            "AGE",
                            if (receiveAgeMs == Long.MAX_VALUE) "—" else "${receiveAgeMs} ms",
                            Modifier.weight(.8f),
                            if (live) TeamWhite else TeamRed
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GarageMetric("GPS", "%.1f Hz".format(snapshot.gpsHz), Modifier.weight(.8f))
                        GarageMetric("SAT", snapshot.satellites.toString(), Modifier.weight(.6f))
                        GarageMetric(
                            "ROOM",
                            car.room,
                            Modifier.weight(.9f)
                        )
                        GarageMetric(
                            "LAST ERROR",
                            snapshot.lastError?.take(18) ?: "—",
                            Modifier.weight(1.25f),
                            if (snapshot.lastError == null) TeamGreen else TeamRed
                        )
                    }
                }
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
            Text(title, color = TeamMuted, fontSize = 12.sp, maxLines = 1)
            Text(
                value,
                color = color,
                fontSize = 12.sp,
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
                    fontSize = 12.sp
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
                    fontSize = 12.sp
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
                TeamEngineerBar(snapshot)

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
                TeamEngineerBar(snapshot)

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
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(
                            onClick = onGarage,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("ГАРАЖ", color = TeamGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        fontSize = 12.sp,
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
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            rttMs?.let { "RTT ${it}ms" }
                                ?: if (receiveAgeMs != Long.MAX_VALUE) "AGE ${receiveAgeMs}ms" else "—",
                            color = TeamMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = onTarget,
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("PIT ${targetSeconds}s", color = TeamYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            raceIdentity,
                            color = TeamMuted,
                            fontSize = 12.sp,
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        rttMs?.let { "RTT ${it}ms" }
                            ?: if (receiveAgeMs != Long.MAX_VALUE) "AGE ${receiveAgeMs}ms" else "—",
                        color = TeamMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onTarget,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("PIT ${targetSeconds}s", color = TeamYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onGarage,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("ГАРАЖ", color = TeamGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
private fun TeamEngineerBar(snapshot: PitTeamSnapshot) {
    val window = if (snapshot.pitActive || snapshot.pitLaneActive) "NOW" else snapshot.pacePitWindow
    val windowText = if (window == "DATA") {
        "DATA ${snapshot.stintAnalysisLapCount}/3"
    } else {
        window
    }
    val windowColor = when (window) {
        "NOW" -> TeamRed
        "1 LAP", "2-3 LAPS", "3-5 LAPS" -> TeamYellow
        "HOLD" -> TeamGreen
        else -> TeamMuted
    }
    val degradationColor = when {
        snapshot.stintDegradationMsPerLap == null -> TeamMuted
        snapshot.stintDegradationMsPerLap!! >= 500.0 -> TeamRed
        snapshot.stintDegradationMsPerLap!! >= 200.0 -> TeamYellow
        snapshot.stintDegradationMsPerLap!! <= 0.0 -> TeamGreen
        else -> TeamWhite
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(50.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            GarageMetric("PRED LAP", format100(snapshot.stintNextLapForecastMs), Modifier.weight(1f))
            GarageMetric("STINT AVG", format100(snapshot.stintAverageLapMs), Modifier.weight(1f))
            GarageMetric(
                "DEG",
                formatDegradation(snapshot.stintDegradationMsPerLap),
                Modifier.weight(.82f),
                degradationColor
            )
            GarageMetric("PACE PIT", windowText, Modifier.weight(.95f), windowColor)
        }
        Row(
            Modifier.fillMaxWidth().height(50.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            GarageMetric(
                "RESOURCE",
                formatResource(snapshot),
                Modifier.weight(.95f),
                resourceLevelColor(snapshot.strategyResourcePct)
            )
            GarageMetric(
                "USE/LAP",
                snapshot.strategyUsagePerLapPct?.let { "%.2f%%".format(it) }
                    ?: if (snapshot.strategyResourceKind != "NONE") "DATA ${snapshot.strategySampleCount}" else "—",
                Modifier.weight(.8f)
            )
            GarageMetric(
                "RANGE",
                snapshot.strategyEstimatedLapsRemaining?.let { "%.1f L".format(it) } ?: "—",
                Modifier.weight(.75f),
                resourceRangeColor(snapshot)
            )
            GarageMetric(
                "FINISH Δ",
                formatFinishMargin(snapshot.strategyFinishMarginLaps),
                Modifier.weight(.78f),
                finishMarginColor(snapshot.strategyFinishMarginLaps)
            )
            GarageMetric(
                "STRATEGY",
                snapshot.strategyCombined,
                Modifier.weight(1.05f),
                strategyCombinedColor(snapshot)
            )
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
                fontSize = 12.sp,
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
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "${device.slot} ${device.role}",
                            color = if (device.online) TeamWhite else TeamMuted,
                            fontSize = 12.sp,
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
                    fontFamily = RacingDisplay,
                    fontSize = 72.sp,
                    lineHeight = 72.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    parts.second,
                    color = if (alertVisible) TeamWhite else TeamYellow,
                    fontFamily = RacingDisplay,
                    fontSize = 72.sp,
                    lineHeight = 72.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Text(
                if (active) "ПРОШЛО ${format100(pitMs)} · TARGET ${targetSeconds}s · ${trigger}"
                else "TARGET ${targetSeconds}s · ${trigger}",
                color = if (alertVisible) TeamWhite else TeamMuted,
                fontSize = 12.sp,
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
                    fontSize = 12.sp
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
                            label = { Text("${preset}s", fontSize = 12.sp) }
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
            Text(title, color = TeamMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                value,
                color = color,
                fontFamily = RacingDisplay,
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
            Text(title, color = TeamMuted, fontSize = 12.sp, maxLines = 1)
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

private fun formatResource(snapshot: PitTeamSnapshot): String {
    val pct = snapshot.strategyResourcePct ?: return "—"
    val label = when (snapshot.strategyResourceKind) {
        "FUEL" -> "FUEL"
        "ENERGY" -> "SOC"
        else -> return "—"
    }
    return "%s %.1f%%".format(label, pct)
}

private fun formatFinishMargin(value: Double?): String {
    if (value == null) return "—"
    val sign = if (value < 0.0) "−" else "+"
    return sign + "%.1f L".format(abs(value))
}

private fun resourceLevelColor(value: Double?): Color = when {
    value == null -> TeamMuted
    value <= 10.0 -> TeamRed
    value <= 20.0 -> TeamYellow
    else -> TeamWhite
}

private fun resourceRangeColor(snapshot: PitTeamSnapshot): Color = when {
    snapshot.strategyEstimatedLapsRemaining == null -> TeamMuted
    snapshot.strategyMandatoryPit -> TeamYellow
    else -> TeamWhite
}

private fun finishMarginColor(value: Double?): Color = when {
    value == null -> TeamMuted
    value < 0.0 -> TeamRed
    value < 1.0 -> TeamYellow
    else -> TeamGreen
}

private fun strategyCombinedColor(snapshot: PitTeamSnapshot): Color = when {
    snapshot.strategyCombined.contains("NOW") -> TeamRed
    snapshot.strategyMandatoryPit -> TeamYellow
    snapshot.strategyCombined.contains("1 LAP") ||
        snapshot.strategyCombined.contains("2-3") ||
        snapshot.strategyCombined.contains("3-5") -> TeamYellow
    snapshot.strategyCombined.contains("HOLD") ||
        snapshot.strategyCombined.contains("OK") -> TeamGreen
    else -> TeamMuted
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

private fun formatDegradation(msPerLap: Double?): String {
    if (msPerLap == null) return "—"
    val sign = if (msPerLap < 0.0) "−" else "+"
    return sign + "%.2f s/L".format(abs(msPerLap) / 1000.0)
}
