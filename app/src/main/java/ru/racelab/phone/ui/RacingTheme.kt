package ru.racelab.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.racelab.phone.R
import kotlin.math.cos
import kotlin.math.sin

object RacingColors {
    val Background = Color(0xFF090B10)
    val Surface = Color(0xFF141923)
    val Raised = Color(0xFF1C2330)
    val Border = Color(0xFF333D4E)
    val Accent = Color(0xFFFF5E52)
    val AccentSurface = Color(0xFF332026)
    val White = Color(0xFFF3F5F8)
    val Muted = Color(0xFFA7B0C1)
    val Success = Color(0xFF68DFA6)
    val Warning = Color(0xFFFFCD72)
    val Error = Color(0xFFFF7776)
}

val RacingDisplay = FontFamily(
    Font(R.font.racing_display_medium, FontWeight.Medium),
    Font(R.font.racing_display_bold, FontWeight.Bold)
)

private val RacingTypography = Typography(
    displayLarge = TextStyle(fontFamily = RacingDisplay, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 68.sp),
    displayMedium = TextStyle(fontFamily = RacingDisplay, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = RacingDisplay, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = RacingDisplay, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = RacingDisplay, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = RacingDisplay, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = .3.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

@Composable
fun RacingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RacingColors.Accent,
            onPrimary = RacingColors.Background,
            primaryContainer = RacingColors.AccentSurface,
            onPrimaryContainer = RacingColors.White,
            secondary = RacingColors.Success,
            onSecondary = RacingColors.Background,
            secondaryContainer = RacingColors.AccentSurface,
            onSecondaryContainer = RacingColors.White,
            tertiary = RacingColors.Warning,
            onTertiary = RacingColors.Background,
            tertiaryContainer = RacingColors.Surface,
            onTertiaryContainer = RacingColors.Warning,
            background = RacingColors.Background,
            onBackground = RacingColors.White,
            surface = RacingColors.Surface,
            onSurface = RacingColors.White,
            surfaceDim = RacingColors.Background,
            surfaceBright = RacingColors.Raised,
            surfaceContainerLowest = RacingColors.Background,
            surfaceContainerLow = RacingColors.Surface,
            surfaceContainer = RacingColors.Surface,
            surfaceContainerHigh = RacingColors.Raised,
            surfaceContainerHighest = RacingColors.Raised,
            surfaceVariant = RacingColors.Raised,
            onSurfaceVariant = RacingColors.Muted,
            outline = RacingColors.Border,
            error = RacingColors.Error,
            onError = RacingColors.Background
        ),
        typography = RacingTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = CutCornerShape(6.dp),
            medium = CutCornerShape(10.dp),
            large = CutCornerShape(14.dp),
            extraLarge = CutCornerShape(18.dp)
        ),
        content = content
    )
}

@Composable
fun RacingWordmark(modifier: Modifier = Modifier, size: Int = 36) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "RACE",
            color = RacingColors.White,
            fontFamily = RacingDisplay,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold,
            fontSize = size.sp,
            lineHeight = (size + 4).sp,
            letterSpacing = .6.sp
        )
        Text(
            "LAB",
            color = RacingColors.Accent,
            fontFamily = RacingDisplay,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold,
            fontSize = size.sp,
            lineHeight = (size + 4).sp,
            letterSpacing = .6.sp
        )
    }
}

@Composable
fun RacingSectionHeader(title: String, kicker: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(kicker, color = RacingColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Text(title, color = RacingColors.White, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
fun RacingStatusChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CutCornerShape(5.dp),
        color = color.copy(alpha = .10f),
        border = BorderStroke(1.dp, color.copy(alpha = .45f))
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(5.dp).background(color, CircleShape))
            Text(label, color = color, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
fun RacingEmptyState(title: String, subtitle: String, tag: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = RacingColors.Surface,
        shape = CutCornerShape(12.dp),
        border = BorderStroke(1.dp, RacingColors.Border)
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(tag, color = RacingColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(title, color = RacingColors.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(subtitle, color = RacingColors.Muted, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun RacingNavIcon(index: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round)
            when (index) {
                0 -> {
                    for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) {
                        drawRoundRect(color, Offset(x, y), Size(7f, 7f), style = stroke)
                    }
                }
                1 -> {
                    drawLine(color, Offset(5f, 3f), Offset(5f, 22f), 1.8f, StrokeCap.Round)
                    val flag = Path().apply {
                        moveTo(5f, 4f); lineTo(20f, 4f); lineTo(17f, 9f)
                        lineTo(20f, 14f); lineTo(5f, 14f); close()
                    }
                    drawPath(flag, color, style = stroke)
                }
                2 -> {
                    drawLine(color, Offset(3f, 21f), Offset(21f, 21f), 1.8f)
                    val chart = Path().apply {
                        moveTo(3f, 16f); lineTo(8f, 10f); lineTo(13f, 13f); lineTo(21f, 4f)
                    }
                    drawPath(chart, color, style = stroke)
                }
                3 -> {
                    val nodes = listOf(Offset(4f, 14f), Offset(12f, 5f), Offset(20f, 17f))
                    drawLine(color, nodes[0], nodes[1], 1.8f)
                    drawLine(color, nodes[1], nodes[2], 1.8f)
                    nodes.forEach { drawCircle(RacingColors.Background, 3f, it); drawCircle(color, 3f, it, style = stroke) }
                }
                else -> {
                    drawCircle(color, 6.5f, Offset(12f, 12f), style = stroke)
                    drawCircle(color, 2.5f, Offset(12f, 12f), style = stroke)
                    for (i in 0 until 8) {
                        val angle = i * Math.PI / 4
                        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                        drawLine(color, Offset(12f, 12f) + direction * 7.5f, Offset(12f, 12f) + direction * 10f, 1.8f)
                    }
                }
            }
        }
    }
}
