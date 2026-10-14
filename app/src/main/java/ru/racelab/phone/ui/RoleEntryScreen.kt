package ru.racelab.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.racelab.phone.pitlane.RaceProfile

private val EntryBg = RacingColors.Background
private val EntryPanel = RacingColors.Surface
private val EntryBorder = RacingColors.Border
private val EntryYellow = RacingColors.Accent
private val EntryMuted = RacingColors.Muted

@Composable
fun RoleEntryScreen(
    initialProfile: RaceProfile,
    onPilot: (RaceProfile) -> Unit,
    onTeam: () -> Unit
) {
    var teamName by remember(initialProfile) { mutableStateOf(initialProfile.teamName) }
    var driverName by remember(initialProfile) { mutableStateOf(initialProfile.driverName) }
    var carNumber by remember(initialProfile) { mutableStateOf(initialProfile.carNumber) }
    var carName by remember(initialProfile) { mutableStateOf(initialProfile.carName) }
    var raceClass by remember(initialProfile) { mutableStateOf(initialProfile.raceClass) }
    var raceTargetLaps by remember(initialProfile) {
        mutableStateOf(initialProfile.raceTargetLaps.takeIf { it > 0 }?.toString().orEmpty())
    }
    var resourceReservePct by remember(initialProfile) {
        mutableStateOf("%.0f".format(initialProfile.resourceReservePct))
    }

    RacingTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(EntryBg)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.widthIn(max = 780.dp).fillMaxWidth().fillMaxHeight(0.96f),
                colors = CardDefaults.cardColors(containerColor = EntryPanel),
                border = BorderStroke(1.dp, EntryBorder),
                shape = CutCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        RacingWordmark(size = 44)
                        Text(
                            "RACE\nCONTROL",
                            color = EntryMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.End
                        )
                    }
                    Text(
                        "Телеметрия. Тайминг. Контроль заезда.",
                        modifier = Modifier.fillMaxWidth(),
                        color = EntryMuted,
                        fontSize = 14.sp
                    )
                    HorizontalDivider(color = EntryBorder, modifier = Modifier.padding(vertical = 4.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = RacingColors.Background,
                        border = BorderStroke(1.dp, EntryBorder),
                        shape = CutCornerShape(10.dp)
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                "ПРОФИЛЬ ЗАЕЗДА",
                                color = RacingColors.White,
                                fontFamily = RacingDisplay,
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp
                            )
                            OutlinedTextField(
                                value = teamName,
                                onValueChange = { teamName = it.take(48) },
                                label = { Text("Название команды") },
                                placeholder = { Text("TSSS Racing") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = carNumber,
                                    onValueChange = { input ->
                                        carNumber = input.filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(8)
                                    },
                                    label = { Text("№ машины") },
                                    placeholder = { Text("27") },
                                    singleLine = true,
                                    modifier = Modifier.weight(.55f)
                                )
                                OutlinedTextField(
                                    value = driverName,
                                    onValueChange = { driverName = it.take(48) },
                                    label = { Text("Пилот") },
                                    placeholder = { Text("Иван") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.45f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = carName,
                                    onValueChange = { carName = it.take(64) },
                                    label = { Text("Машина") },
                                    placeholder = { Text("Porsche 911 GT3") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.25f)
                                )
                                OutlinedTextField(
                                    value = raceClass,
                                    onValueChange = { raceClass = it.take(32) },
                                    label = { Text("Класс") },
                                    placeholder = { Text("GT3") },
                                    singleLine = true,
                                    modifier = Modifier.weight(.75f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = raceTargetLaps,
                                    onValueChange = { input ->
                                        raceTargetLaps = input.filter(Char::isDigit).take(4)
                                    },
                                    label = { Text("Кругов в гонке") },
                                    placeholder = { Text("20") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = resourceReservePct,
                                    onValueChange = { input ->
                                        resourceReservePct = input
                                            .filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
                                            .replace(',', '.')
                                            .take(4)
                                    },
                                    label = { Text("Резерв, %") },
                                    placeholder = { Text("5") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                "Эти данные сохраняются и передаются на все командные экраны вместе с телеметрией.",
                                color = EntryMuted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }

                    RoleButton(
                        title = "ПИЛОТ",
                        subtitle = "GPS · камера · OBD/CAN · PIT · GSM телеметрия",
                        accent = EntryYellow,
                        primary = true,
                        onClick = {
                            onPilot(
                                RaceProfile(
                                    teamName = teamName.trim(),
                                    driverName = driverName.trim(),
                                    carNumber = carNumber.trim(),
                                    carName = carName.trim(),
                                    raceClass = raceClass.trim(),
                                    raceTargetLaps = raceTargetLaps.toIntOrNull()?.coerceIn(0, 9999) ?: 0,
                                    resourceReservePct = resourceReservePct.toDoubleOrNull()?.coerceIn(0.0, 25.0) ?: 5.0
                                )
                            )
                        }
                    )
                    RoleButton(
                        title = "КОМАНДА",
                        subtitle = "PIT · круги · скорость · дельта · до 5 устройств",
                        accent = EntryYellow,
                        onClick = onTeam
                    )

                    Text(
                        "Командный режим не запускает GPS, камеру и OBD на этом устройстве.",
                        color = EntryMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleButton(
    title: String,
    subtitle: String,
    accent: Color,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val textColor = if (primary) RacingColors.Background else RacingColors.White
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
        shape = CutCornerShape(9.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) accent else RacingColors.Raised,
            contentColor = textColor
        ),
        border = BorderStroke(1.dp, accent),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = textColor, fontFamily = RacingDisplay, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = if (primary) textColor.copy(alpha = .80f) else EntryMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Text("›", color = if (primary) textColor else accent, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
    }
}
