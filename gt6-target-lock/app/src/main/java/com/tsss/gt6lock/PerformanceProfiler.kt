package com.tsss.gt6lock

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Lightweight in-process profiler.
 * Hot-path timings are just nanoTime + EMA; CPU/RAM sampling runs at 1 Hz
 * on a background-priority thread.
 */
class PerformanceProfiler {
    @Volatile var flowMs: Float = 0f
        private set
    @Volatile var nccMs: Float = 0f
        private set
    @Volatile var trackMs: Float = 0f
        private set
    @Volatile var cpuPct: Float = 0f
        private set
    @Volatile var ramMb: Float = 0f
        private set

    @Volatile private var running = false
    private var worker: Thread? = null

    fun recordFlow(elapsedNs: Long) {
        flowMs = ema(flowMs, elapsedNs * 1e-6f, 0.16f)
    }

    fun recordNcc(elapsedNs: Long) {
        nccMs = ema(nccMs, elapsedNs * 1e-6f, 0.18f)
    }

    fun recordTrack(elapsedNs: Long) {
        trackMs = ema(trackMs, elapsedNs * 1e-6f, 0.12f)
    }

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "GT6-Profiler", isDaemon = true) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            var lastWall = SystemClock.elapsedRealtime()
            var lastCpu = Process.getElapsedCpuTime()

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

                // Process CPU usage. Can exceed 100% when multiple cores are busy.
                cpuPct = (cpu * 100f / wall).coerceIn(0f, 800f)
                ramMb = runCatching { Debug.getPss() / 1024f }.getOrDefault(ramMb)

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

    private fun ema(prev: Float, value: Float, alpha: Float): Float =
        if (prev <= 0f) value else prev + alpha * (value - prev)
}
