package com.tsss.gt6lock

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * v4.4 CPU Breakdown profiler.
 *
 * Hot path:
 * - nanoTime is measured by caller
 * - one FloatArray write + counters
 * - no sorting, file IO or allocations
 *
 * Background (1 Hz):
 * - CPU/RAM sampling
 * - rolling LOOP avg/P95/max
 * - FLOW/NCC invocation rates
 */
class PerformanceProfiler {
    @Volatile var lumaMs: Float = 0f
        private set
    @Volatile var flowMs: Float = 0f
        private set
    @Volatile var nccMs: Float = 0f
        private set
    @Volatile var fusionMs: Float = 0f
        private set
    @Volatile var trackMs: Float = 0f
        private set

    @Volatile var flowHz: Float = 0f
        private set
    @Volatile var nccHz: Float = 0f
        private set

    @Volatile var loopAvgMs: Float = 0f
        private set
    @Volatile var loopP95Ms: Float = 0f
        private set
    @Volatile var loopMaxMs: Float = 0f
        private set
    @Volatile var frameHeadroomPct: Float = 100f
        private set

    @Volatile var cpuPct: Float = 0f
        private set
    @Volatile var ramMb: Float = 0f
        private set

    @Volatile private var running = false
    private var worker: Thread? = null

    // ~3 seconds at 60 FPS. Written only by camera analyzer thread.
    private val loopSamples = FloatArray(180)
    @Volatile private var loopWrite = 0
    @Volatile private var loopCount = 0

    @Volatile private var flowEvents = 0
    @Volatile private var nccEvents = 0

    fun recordLuma(elapsedNs: Long) {
        val ms = elapsedNs * 1e-6f
        lumaMs = ema(lumaMs, ms, 0.16f)
    }

    fun recordFlow(elapsedNs: Long) {
        val ms = elapsedNs * 1e-6f
        flowMs = ema(flowMs, ms, 0.16f)
        flowEvents++
    }

    fun recordNcc(elapsedNs: Long) {
        val ms = elapsedNs * 1e-6f
        nccMs = ema(nccMs, ms, 0.18f)
        nccEvents++
    }

    fun recordFusion(elapsedNs: Long) {
        val ms = elapsedNs * 1e-6f
        fusionMs = ema(fusionMs, ms, 0.16f)
    }

    fun recordTrack(elapsedNs: Long) {
        val ms = elapsedNs * 1e-6f
        trackMs = ema(trackMs, ms, 0.12f)

        val i = loopWrite
        loopSamples[i] = ms
        loopWrite = (i + 1) % loopSamples.size
        if (loopCount < loopSamples.size) loopCount++
    }

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "GT6-Benchmark", isDaemon = true) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }

            var lastWall = SystemClock.elapsedRealtime()
            var lastCpu = Process.getElapsedCpuTime()
            var lastFlowEvents = flowEvents
            var lastNccEvents = nccEvents

            while (running) {
                try {
                    Thread.sleep(1000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break

                val nowWall = SystemClock.elapsedRealtime()
                val nowCpu = Process.getElapsedCpuTime()
                val wall = (nowWall - lastWall).coerceAtLeast(1L)
                val cpu = (nowCpu - lastCpu).coerceAtLeast(0L)

                cpuPct = (cpu * 100f / wall).coerceIn(0f, 800f)
                ramMb = runCatching { Debug.getPss() / 1024f }.getOrDefault(ramMb)

                val fNow = flowEvents
                val nNow = nccEvents
                flowHz = ((fNow - lastFlowEvents).coerceAtLeast(0) * 1000f / wall)
                nccHz = ((nNow - lastNccEvents).coerceAtLeast(0) * 1000f / wall)
                lastFlowEvents = fNow
                lastNccEvents = nNow

                updateLoopStats()

                lastWall = nowWall
                lastCpu = nowCpu
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun updateLoopStats() {
        val count = loopCount.coerceIn(0, loopSamples.size)
        if (count <= 0) return

        // Copying/sorting happens only on background thread once per second.
        val snapshot = FloatArray(count)
        val write = loopWrite
        val start = if (count == loopSamples.size) write else 0
        var sum = 0f
        var max = 0f
        for (k in 0 until count) {
            val idx = (start + k) % loopSamples.size
            val v = loopSamples[idx]
            snapshot[k] = v
            sum += v
            if (v > max) max = v
        }
        snapshot.sort()

        loopAvgMs = sum / count
        val p95Index = ((count - 1) * 0.95f).toInt().coerceIn(0, count - 1)
        loopP95Ms = snapshot[p95Index]
        loopMaxMs = max

        val budgetMs = 1000f / 60f
        frameHeadroomPct =
            ((budgetMs - loopP95Ms) / budgetMs * 100f).coerceIn(-999f, 100f)
    }

    private fun ema(prev: Float, value: Float, alpha: Float): Float =
        if (prev <= 0f) value else prev + alpha * (value - prev)
}
