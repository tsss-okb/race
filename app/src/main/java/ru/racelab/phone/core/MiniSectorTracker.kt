package ru.racelab.phone.core

import kotlin.math.floor

data class MiniSectorSnapshot(
    val currentIndex: Int = 0,
    val currentDeltaMs: Long? = null,
    val deltasMs: List<Long?> = emptyList()
)

class MiniSectorTracker(private val count: Int = 10) {
    private var index = -1
    private var startDeltaMs: Long? = null
    private val deltas = MutableList<Long?>(count) { null }

    init {
        require(count in 2..50)
    }

    fun reset() {
        index = -1
        startDeltaMs = null
        for (i in deltas.indices) deltas[i] = null
    }

    fun update(progress: Double, cumulativeDeltaMs: Long, newLap: Boolean = false): MiniSectorSnapshot {
        if (newLap) reset()

        val p = progress.coerceIn(0.0, 1.0)
        val nextIndex = floor(p * count).toInt().coerceIn(0, count - 1)

        if (index < 0) {
            index = nextIndex
            startDeltaMs = cumulativeDeltaMs
        } else if (nextIndex > index) {
            val start = startDeltaMs ?: cumulativeDeltaMs
            deltas[index] = cumulativeDeltaMs - start
            index = nextIndex
            startDeltaMs = cumulativeDeltaMs
        }

        val current = if (index >= 0) cumulativeDeltaMs - (startDeltaMs ?: cumulativeDeltaMs) else null
        return MiniSectorSnapshot(
            currentIndex = index.coerceAtLeast(0) + 1,
            currentDeltaMs = current,
            deltasMs = deltas.toList()
        )
    }
}
