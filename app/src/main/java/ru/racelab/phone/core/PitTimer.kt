package ru.racelab.phone.core

data class PitTimerSnapshot(
    val active: Boolean = false,
    val startedElapsedMs: Long? = null,
    val lastMs: Long? = null,
    val bestMs: Long? = null,
    val count: Int = 0,
    val lastTrigger: String = "—"
)

data class PitTimerEvent(
    val type: String,
    val elapsedMs: Long?,
    val trigger: String,
    val snapshot: PitTimerSnapshot
)

class PitTimerEngine(
    private val debounceMs: Long = 250L
) {
    var snapshot = PitTimerSnapshot()
        private set

    private var lastToggleElapsedMs: Long? = null

    fun toggle(nowElapsedMs: Long, trigger: String): PitTimerEvent? {
        val last = lastToggleElapsedMs
        if (last != null && nowElapsedMs - last in 0 until debounceMs) return null
        lastToggleElapsedMs = nowElapsedMs

        return if (!snapshot.active) {
            snapshot = snapshot.copy(
                active = true,
                startedElapsedMs = nowElapsedMs,
                lastTrigger = trigger
            )
            PitTimerEvent("START", null, trigger, snapshot)
        } else {
            val start = snapshot.startedElapsedMs ?: nowElapsedMs
            val elapsed = (nowElapsedMs - start).coerceAtLeast(0L)
            val best = snapshot.bestMs?.let { minOf(it, elapsed) } ?: elapsed
            snapshot = snapshot.copy(
                active = false,
                startedElapsedMs = null,
                lastMs = elapsed,
                bestMs = best,
                count = snapshot.count + 1,
                lastTrigger = trigger
            )
            PitTimerEvent("STOP", elapsed, trigger, snapshot)
        }
    }

    fun elapsed(nowElapsedMs: Long): Long {
        val start = snapshot.startedElapsedMs ?: return snapshot.lastMs ?: 0L
        return (nowElapsedMs - start).coerceAtLeast(0L)
    }

    fun reset() {
        snapshot = PitTimerSnapshot()
        lastToggleElapsedMs = null
    }
}
