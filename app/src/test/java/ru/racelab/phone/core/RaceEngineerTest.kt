package ru.racelab.phone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceEngineerTest {
    @Test
    fun stablePaceHolds() {
        val m = RaceEngineer.analyze(
            listOf(90_000L, 90_080L, 90_120L, 90_100L),
            bestLapMs = 90_000L,
            pitActive = false
        )
        assertEquals("HOLD", m.pitWindow)
        assertNotNull(m.averageLapMs)
        assertTrue((m.degradationMsPerLap ?: 9999.0) < 120.0)
    }

    @Test
    fun degradingPaceCreatesPitWindow() {
        val m = RaceEngineer.analyze(
            listOf(90_000L, 90_350L, 90_800L, 91_300L),
            bestLapMs = 90_000L,
            pitActive = false
        )
        assertTrue(m.pitWindow == "2-3 LAPS" || m.pitWindow == "1 LAP" || m.pitWindow == "NOW")
        assertTrue((m.degradationMsPerLap ?: 0.0) > 400.0)
    }

    @Test
    fun activePitIsAlwaysNow() {
        val m = RaceEngineer.analyze(
            listOf(90_000L, 90_100L, 90_200L),
            bestLapMs = 90_000L,
            pitActive = true
        )
        assertEquals("NOW", m.pitWindow)
    }

    @Test
    fun lessThanThreeLapsNeedsData() {
        val m = RaceEngineer.analyze(
            listOf(90_000L, 90_300L),
            bestLapMs = 90_000L,
            pitActive = false
        )
        assertEquals("DATA", m.pitWindow)
        assertNotNull(m.nextLapForecastMs)
    }
}
