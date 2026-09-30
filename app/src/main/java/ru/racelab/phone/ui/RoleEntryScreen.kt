package ru.racelab.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val EntryBg = Color(0xFF050607)
private val EntryPanel = Color(0xFF111315)
private val EntryBorder = Color(0xFF2B2F33)
private val EntryYellow = Color(0xFFF2C300)
private val EntryGreen = Color(0xFF58E13E)
private val EntryMuted = Color(0xFF989DA2)

@Composable
fun RoleEntryScreen(
    onPilot: () -> Unit,
    onTeam: () -> Unit
) {
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
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = EntryPanel),
                border = BorderStroke(1.dp, EntryBorder),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "RACELAB",
                        color = EntryYellow,
                        fontSize = 31.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Text(
                        "Выберите режим работы",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Пилот записывает сессию и передаёт телеметрию. Команда подключается через GSM/интернет в режиме чтения — до 5 устройств одновременно.",
                        color = EntryMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    RoleButton(
                        title = "ПИЛОТ",
                        subtitle = "GPS · камера · OBD/CAN · PIT · отправка телеметрии",
                        accent = EntryYellow,
                        onClick = onPilot
                    )
                    RoleButton(
                        title = "КОМАНДА",
                        subtitle = "PIT экран · круги · скорость · дельта · GSM relay",
                        accent = EntryGreen,
                        onClick = onTeam
                    )

                    Text(
                        "Командный режим не запускает GPS, камеру и OBD на этом устройстве.",
                        color = EntryMuted,
                        fontSize = 10.sp,
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
        modifier = Modifier.fillMaxWidth().height(72.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF171A1D),
            contentColor = Color.White
        ),
        border = BorderStroke(1.dp, accent),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = accent, fontSize = 19.sp, fontWeight = FontWeight.Black)
                Text(subtitle, color = EntryMuted, fontSize = 10.sp)
            }
            Text("›", color = accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}
