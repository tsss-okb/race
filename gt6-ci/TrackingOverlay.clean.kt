package com.tsss.gt6v6.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.tsss.gt6v6.V6Benchmark
import com.tsss.gt6v6.V6AdaptiveStatus
import com.tsss.gt6v6.V6DetectorUrgency
import com.tsss.gt6v6.V6Snapshot
import com.tsss.gt6v6.V6MotionStatus
import com.tsss.gt6v6.V6MotionCalibrationStatus
import com.tsss.gt6v6.V6TrackState
import java.util.Locale

class TrackingOverlay(context: Context) : View(context) {
    @Volatile var snapshot: V6Snapshot? = null
    @Volatile var benchmark: V6Benchmark? = null
    @Volatile var adaptive: V6AdaptiveStatus? = null
    @Volatile var motionStatus: V6MotionStatus? = null
    @Volatile var calibration: V6MotionCalibrationStatus? = null
    @Volatile var mapper: FrameMapper? = null
    @Volatile var fps: Float = 0f
    @Volatile var detailed: Boolean = true

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 26f }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 18f; color = Color.WHITE }
    private val panelPaint = Paint().apply { color = 0xB0000000.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f; color = Color.CYAN }

    private fun stateColor(s: V6TrackState) = when (s) {
        V6TrackState.LOCK -> Color.rgb(80, 255, 140)
        V6TrackState.PREDICT -> Color.rgb(255, 205, 70)
        V6TrackState.LOST -> Color.rgb(255, 80, 80)
        V6TrackState.SEARCH -> Color.WHITE
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = snapshot
        val m = mapper
        if (s != null && m != null && s.w > 1f && s.h > 1f) {
            val rect: RectF = m.imageRectToView(s.x, s.y, s.w, s.h)
            val col = stateColor(s.state)
            boxPaint.color = col
            c.drawRect(rect, boxPaint)
            c.drawCircle(rect.centerX(), rect.centerY(), 7f, boxPaint)
            c.drawLine(width * .5f, height * .5f, rect.centerX(), rect.centerY(), linePaint)
        }

        val b = benchmark
        val panelW = if (detailed) minOf(width * .58f, 900f) else minOf(width * .35f, 560f)
        val panelH = if (detailed) 360f else 116f
        c.drawRoundRect(8f, 8f, panelW, panelH, 10f, 10f, panelPaint)

        if (s == null) {
            textPaint.color = Color.WHITE
            c.drawText("GT6 v6  ·  TAP TO LOCK", 20f, 42f, textPaint)
            return
        }

        textPaint.color = stateColor(s.state)
        val primary = buildString {
            append(s.state.name)
            append("  OBS "); append((s.observationScore * 100).toInt()); append('%')
            if (s.state == V6TrackState.PREDICT) { append("  PRED "); append((s.predictionScore * 100).toInt()); append('%') }
            append("  "); append(s.motion.name)
        }
        c.drawText(primary, 20f, 38f, textPaint)

        smallPaint.color = Color.WHITE
        c.drawText(String.format(Locale.US, "FPS %.1f  JIT %.1fpx  MEM %.0f%%  KF %d", fps, s.jitterPx, s.memoryScore * 100f, s.keyframeIndex), 20f, 68f, smallPaint)
        c.drawText("YOLO ${s.detectorUrgency.name} · ${s.detectorReason.name} · ${s.detectorIntervalMs}ms", 20f, 92f, smallPaint)
        val a = adaptive
        if (a != null) {
            val warm = if (a.warmedUp) "" else " WARMUP ${a.framesSeen}/${a.warmupFrames}"
            c.drawText(String.format(Locale.US, "ADAPT %s%s  RISK %.0f%%", a.profile.name, warm, a.risk * 100f), 20f, 116f, smallPaint)
        }

        if (detailed && b != null) {
            c.drawText(String.format(Locale.US, "LOCK %.1f%%  PRED %.1f%%  LOST %.1f%%  frames %d", b.lockPct, b.predictPct, b.lostPct, b.frames), 20f, 146f, smallPaint)
            c.drawText(String.format(Locale.US, "JIT avg/P95 %.2f / %.2f px   RAQ avg/P95 %.1f / %.1f ms", b.jitterAvgPx, b.jitterP95Px, b.reacquireAvgMs, b.reacquireP95Ms), 20f, 172f, smallPaint)
            c.drawText(String.format(Locale.US, "FLOW avg/P95 %.0f / %.0f us   ENG %.0f / %.0f us", b.flowAvgUs, b.flowP95Us, b.engineAvgUs, b.engineP95Us), 20f, 198f, smallPaint)
            c.drawText(String.format(Locale.US, "DET avg/P95 %.0f / %.0f us   req %d (I%d/W%d)", b.detectorAvgUs, b.detectorP95Us, b.detectorRequests, b.detectorImmediate, b.detectorWarm), 20f, 224f, smallPaint)
            c.drawText("MEM rescue ${b.memoryRescues}   SHOCK ${b.shockFrames}   AGE ${s.predictionAge}", 20f, 250f, smallPaint)
            if (a != null) {
                c.drawText(String.format(Locale.US, "THR Q %.2f/%.2f  RAQ %.2f  SH %.3f  JIT %.1f", a.strongQuality, a.weakQuality, a.reacquireScore, a.shockSpeedNorm, a.detectorHighJitterPx), 20f, 276f, smallPaint)
                c.drawText("DET lock/weak ${a.detectorLockIntervalMs}/${a.detectorWeakIntervalMs}ms  PRED ${a.predictMaxFrames}/${a.predictMaxFramesShock}", 20f, 300f, smallPaint)
            }
            motionStatus?.let { ms ->
                val neon = if (ms.neonActive) "NEON" else "SCALAR"
                c.drawText(String.format(Locale.US, "LK %d/%d  GMC %d/%d %.0f%%  IMU %.0f%%  REL %.1f/%.1f  P%d %s",
                    ms.targetInliers, ms.targetPoints, ms.backgroundInliers, ms.backgroundPoints, ms.gmcQuality*100f, ms.imuQuality*100f, ms.relativeDxPx, ms.relativeDyPx, ms.runtimeProfile, neon), 20f, 326f, smallPaint)
            }
            calibration?.let { cal ->
                val text = when {
                    cal.complete -> "CAL DONE → P${cal.chosenProfile}  score ${String.format(Locale.US, "%.2f", cal.bestScore)}"
                    cal.enabled -> "CAL P${cal.activeProfile} ${cal.framesInProfile}/${cal.framesPerProfile}"
                    else -> "CAL READY"
                }
                c.drawText(text, 20f, 350f, smallPaint)
            }
        }
    }
}
