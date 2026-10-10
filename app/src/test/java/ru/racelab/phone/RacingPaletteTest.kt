package ru.racelab.phone

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.racelab.phone.ui.RacingColors
import kotlin.math.pow

class RacingPaletteTest {
    @Test
    fun bodyAndSecondaryTextMeetNormalTextContrast() {
        listOf(RacingColors.Background, RacingColors.Surface, RacingColors.Raised).forEach { surface ->
            assertReadable("body", RacingColors.White, surface)
            assertReadable("secondary", RacingColors.Muted, surface)
        }
    }

    @Test
    fun actionsAndSemanticLabelsMeetNormalTextContrast() {
        assertReadable("primary action", RacingColors.Background, RacingColors.Accent)
        assertReadable("selected navigation", RacingColors.Accent, RacingColors.AccentSurface)
        listOf(RacingColors.Success, RacingColors.Warning, RacingColors.Error).forEach { color ->
            assertReadable("semantic label", color, RacingColors.Surface)
        }
    }

    private fun assertReadable(role: String, foreground: Color, background: Color) {
        val fg = luminance(foreground)
        val bg = luminance(background)
        val ratio = (maxOf(fg, bg) + .05) / (minOf(fg, bg) + .05)
        assertTrue("$role contrast $ratio must be at least 4.5:1", ratio >= 4.5)
    }

    private fun luminance(color: Color): Double {
        fun linear(value: Float): Double {
            val c = value.toDouble()
            return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
    }
}
