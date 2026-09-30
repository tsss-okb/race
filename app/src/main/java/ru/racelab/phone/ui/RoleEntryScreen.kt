package ru.racelab.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.racelab.phone.pitlane.RaceProfile

private val EntryBg = Color(0xFF050607)
private val EntryPanel = Color(0xFF111315)
private val EntryBorder = Color(0xFF2B2F33)
private val EntryYellow = Color(0xFFF2C300)
private val EntryGreen = Color(0xFF58E13E)
private val EntryMuted = Color(0xFF989DA2)

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

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = EntryYellow,
            secondary = EntryGreen,
            background = EntryBg,
            surface = EntryPanel,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
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
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "RACELAB",
                        color = EntryYellow,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Text(
                        "Выберите режим работы",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF0C0E10),
                        border = BorderStroke(1.dp, EntryBorder),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                "ПРОФИЛЬ ЗАЕЗДА",
                                color = EntryYellow,
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp
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
                            Text(
                                "Эти данные сохраняются и передаются на все командные экраны вместе с телеметрией.",
                                color = EntryMuted,
                                fontSize = 9.sp
                            )
                        }
                    }

                    RoleButton(
                        title = "ПИЛОТ",
                        subtitle = "GPS · камера · OBD/CAN · PIT · GSM телеметрия",
                        accent = EntryYellow,
                        onClick = {
                            onPilot(
                                RaceProfile(
                                    teamName = teamName.trim(),
                                    driverName = driverName.trim(),
                                    carNumber = carNumber.trim(),
                                    carName = carName.trim(),
                                    raceClass = raceClass.trim()
                                )
                            )
                        }
                    )
                    RoleButton(
                        title = "КОМАНДА",
                        subtitle = "PIT · круги · скорость · дельта · до 5 устройств",
                        accent = EntryGreen,
                        onClick = onTeam
                    )

                    Text(
                        "Командный режим не запускает GPS, камеру и OBD на этом устройстве.",
                        color = EntryMuted,
                        fontSize = 9.sp,
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
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF171A1D),
            contentColor = Color.White
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
                Text(title, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text(subtitle, color = EntryMuted, fontSize = 9.sp)
            }
            Text("›", color = accent, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        }
    }
}
