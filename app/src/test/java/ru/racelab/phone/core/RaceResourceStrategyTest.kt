package ru.racelab.phone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceResourceStrategyTest {
    @Test
    fun predictsFuelLapsFromLapConsumption() {
        val tracker = RaceResourceStrategyTracker()
        tracker.onLapCompleted(RaceResourceKind.FUEL, 80.0)
        tracker.onLapCompleted(RaceResourceKind.FUEL, 76.0)
        tracker.onLapCompleted(RaceResourceKind.FUEL, 72.0)

        val m = tracker.metrics(
            resourceKind = RaceResourceKind.FUEL,
            resourcePct = 72.0,
            raceTargetLaps = 20,
            lapsCompleted = 5,
            reservePct = 5.0,
            pacePitWindow = "HOLD"
        )

        assertEquals(RaceResourceKind.FUEL, m.kind)
        assertEquals(4.0, m.usagePerLapPct ?: 0.0, 0.01)
        assertNotNull(m.estimatedLapsRemaining)
        assertFalse(m.mandatoryPit)
    }

    @Test
    fun marksPitMandatoryWhenResourceCannotFinishRace() {
        val tracker = RaceResourceStrategyTracker()
        tracker.onLapCompleted(RaceResourceKind.FUEL, 30.0)
        tracker.onLapCompleted(RaceResourceKind.FUEL, 26.0)
        tracker.onLapCompleted(RaceResourceKind.FUEL, 22.0)

        val m = tracker.metrics(
            resourceKind = RaceResourceKind.FUEL,
            resourcePct = 22.0,
            raceTargetLaps = 20,
            lapsCompleted = 8,
            reservePct = 5.0,
            pacePitWindow = "HOLD"
        )

        assertTrue(m.mandatoryPit)
        assertTrue(m.combinedStrategy.contains("PIT") || m.combinedStrategy.contains("FUEL"))
        assertTrue((m.finishMarginLaps ?: 0.0) < 0.0)
    }

    @Test
    fun refuelResetsConsumptionHistory() {
        val tracker = RaceResourceStrategyTracker()
        tracker.onLapCompleted(RaceResourceKind.FUEL, 50.0)
        tracker.onLapCompleted(RaceResourceKind.FUEL, 46.0)
        tracker.onLapCompleted(RaceResourceKind.FUEL, 80.0)

        val m = tracker.metrics(
            resourceKind = RaceResourceKind.FUEL,
            resourcePct = 80.0,
            raceTargetLaps = 20,
            lapsCompleted = 6,
            reservePct = 5.0,
            pacePitWindow = "HOLD"
        )

        assertEquals(0, m.sampleCount)
        assertEquals("DATA", m.resourcePitWindow)
    }

    @Test
    fun oneConsumptionIntervalIsStillDataCollection() {
        val tracker = RaceResourceStrategyTracker()
        tracker.onLapCompleted(RaceResourceKind.FUEL, 80.0)
        tracker.onLapCompleted(RaceResourceKind.FUEL, 76.0)

        val m = tracker.metrics(
            resourceKind = RaceResourceKind.FUEL,
            resourcePct = 76.0,
            raceTargetLaps = 20,
            lapsCompleted = 2,
            reservePct = 5.0,
            pacePitWindow = "HOLD"
        )

        assertEquals("DATA", m.resourcePitWindow)
        assertEquals(1, m.sampleCount)
    }

    @Test
    fun energySocUsesSameLapRangeModel() {
        val tracker = RaceResourceStrategyTracker()
        tracker.onLapCompleted(RaceResourceKind.ENERGY, 64.0)
        tracker.onLapCompleted(RaceResourceKind.ENERGY, 60.0)
        tracker.onLapCompleted(RaceResourceKind.ENERGY, 56.0)

        val m = tracker.metrics(
            resourceKind = RaceResourceKind.ENERGY,
            resourcePct = 56.0,
            raceTargetLaps = 12,
            lapsCompleted = 4,
            reservePct = 5.0,
            pacePitWindow = "HOLD"
        )

        assertEquals(RaceResourceKind.ENERGY, m.kind)
        assertTrue((m.estimatedLapsRemaining ?: 0.0) > 10.0)
    }
}
