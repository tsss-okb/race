package ru.racelab.phone.core

import kotlin.math.floor

enum class RaceResourceKind {
    NONE,
    FUEL,
    ENERGY
}

data class RaceResourceStrategyMetrics(
    val kind: RaceResourceKind = RaceResourceKind.NONE,
    val resourcePct: Double? = null,
    val usagePerLapPct: Double? = null,
    val estimatedLapsRemaining: Double? = null,
    val raceLapsRemaining: Int? = null,
    val finishMarginLaps: Double? = null,
    val mandatoryPit: Boolean = false,
    val resourcePitWindow: String = "DATA",
    val combinedStrategy: String = "DATA",
    val sampleCount: Int = 0
)

class RaceResourceStrategyTracker {
    private var kind: RaceResourceKind = RaceResourceKind.NONE
    private var lastLapResourcePct: Double? = null
    private val usageSamples = ArrayDeque<Double>()

    fun reset() {
        kind = RaceResourceKind.NONE
        lastLapResourcePct = null
        usageSamples.clear()
    }

    fun resetStint(resourceKind: RaceResourceKind, resourcePct: Double?) {
        kind = resourceKind
        lastLapResourcePct = resourcePct?.coerceIn(0.0, 100.0)
        usageSamples.clear()
    }

    fun onLapCompleted(resourceKind: RaceResourceKind, resourcePct: Double?) {
        val pct = resourcePct?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: return
        if (resourceKind == RaceResourceKind.NONE) return
        if (kind != resourceKind) {
            resetStint(resourceKind, pct)
            return
        }

        val previous = lastLapResourcePct
        if (previous != null) {
            val used = previous - pct
            when {
                used in 0.05..25.0 -> {
                    usageSamples.addLast(used)
                    while (usageSamples.size > 5) usageSamples.removeFirst()
                }
                used < -0.5 -> {
                    // Refuel / recharge detected. Start a fresh consumption baseline.
                    usageSamples.clear()
                }
            }
        }
        lastLapResourcePct = pct
    }

    fun metrics(
        resourceKind: RaceResourceKind,
        resourcePct: Double?,
        raceTargetLaps: Int,
        lapsCompleted: Int,
        reservePct: Double,
        pacePitWindow: String
    ): RaceResourceStrategyMetrics {
        val pct = resourcePct?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
        if (resourceKind == RaceResourceKind.NONE || pct == null) {
            return RaceResourceStrategyMetrics(
                kind = resourceKind,
                resourcePct = pct,
                combinedStrategy = normalizePaceOnly(pacePitWindow)
            )
        }

        if (kind != resourceKind) {
            kind = resourceKind
            lastLapResourcePct = null
            usageSamples.clear()
        }

        val recent = usageSamples.takeLast(4)
        val usage = recent.takeIf { it.isNotEmpty() }?.average()
        val safeReserve = reservePct.coerceIn(0.0, 25.0)
        val estimatedLaps = usage
            ?.takeIf { it > 0.01 }
            ?.let { ((pct - safeReserve).coerceAtLeast(0.0) / it).coerceAtLeast(0.0) }

        val remaining = raceTargetLaps
            .takeIf { it > 0 }
            ?.let { (it - lapsCompleted).coerceAtLeast(0) }

        val margin = if (estimatedLaps != null && remaining != null) estimatedLaps - remaining else null
        val mandatory = remaining != null && estimatedLaps != null && estimatedLaps + 0.05 < remaining
        val resourceWindow = when {
            usage == null -> "DATA"
            estimatedLaps == null -> "DATA"
            estimatedLaps <= 1.0 -> "NOW"
            mandatory -> {
                val pitBy = floor(estimatedLaps).toInt().coerceAtLeast(1)
                when {
                    pitBy <= 1 -> "NOW"
                    pitBy == 2 -> "1 LAP"
                    pitBy <= 4 -> "2-3 LAPS"
                    pitBy <= 6 -> "3-5 LAPS"
                    else -> "REQ ≤${pitBy}L"
                }
            }
            estimatedLaps <= 2.0 -> "1 LAP"
            else -> "OK"
        }

        return RaceResourceStrategyMetrics(
            kind = resourceKind,
            resourcePct = pct,
            usagePerLapPct = usage,
            estimatedLapsRemaining = estimatedLaps,
            raceLapsRemaining = remaining,
            finishMarginLaps = margin,
            mandatoryPit = mandatory,
            resourcePitWindow = resourceWindow,
            combinedStrategy = combine(pacePitWindow, resourceWindow, mandatory, estimatedLaps),
            sampleCount = recent.size
        )
    }

    private fun combine(
        pace: String,
        resource: String,
        mandatory: Boolean,
        estimatedLaps: Double?
    ): String {
        val paceUrgency = urgency(pace)
        val resourceUrgency = urgency(resource)
        return when {
            resource == "NOW" || pace == "NOW" -> "PIT NOW"
            resourceUrgency < paceUrgency && resourceUrgency < 90 -> "FUEL " + resource
            paceUrgency < resourceUrgency && paceUrgency < 90 -> "PACE " + pace
            resourceUrgency < 90 -> "PIT " + resource
            mandatory && estimatedLaps != null -> "PIT ≤${floor(estimatedLaps).toInt().coerceAtLeast(1)}L"
            pace == "HOLD" && resource == "OK" -> "HOLD"
            pace == "DATA" && resource == "DATA" -> "DATA"
            pace == "DATA" && resource == "OK" -> "FUEL OK"
            pace == "HOLD" && resource == "DATA" -> "PACE HOLD"
            pace != "DATA" -> "PACE " + pace
            resource != "DATA" -> "FUEL " + resource
            else -> "DATA"
        }
    }

    private fun urgency(window: String): Int = when {
        window == "NOW" -> 0
        window == "1 LAP" -> 1
        window == "2-3 LAPS" -> 2
        window == "3-5 LAPS" -> 3
        window.startsWith("REQ") -> 4
        window == "HOLD" || window == "OK" -> 90
        else -> 99
    }

    private fun normalizePaceOnly(pace: String): String = when (pace) {
        "NOW" -> "PIT NOW"
        "1 LAP", "2-3 LAPS", "3-5 LAPS" -> "PACE " + pace
        "HOLD" -> "PACE HOLD"
        else -> "DATA"
    }
}
