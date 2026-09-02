package com.tsss.gt6lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class OverlayView(context: Context) : View(context) {
    data class UiTrack(
        val state: Int = 0,
        val cx: Float = 0f,
        val cy: Float = 0f,
        val bw: Float = 0f,
        val bh: Float = 0f,
        val conf: Float = 0f,
        val jitter: Float = 0f,
        val latency: Float = 0f,
        val misses: Int = 0,
        val fps: Float = 0f
    )

    @Volatile var track = UiTrack()

    var imageW = 320
    var imageH = 180
    var rotationDegrees = 0

    // Kept for compatibility; camera preview is never IMU-rotated in v0.8.
    var levelCorrectionDegrees = 0f
    var levelScale = 1f

    var sensorRollDegrees = 0f
    var sensorPitchDegrees = 0f
    var sensorHeadingDegrees = 0f
    var gyroPDeg = 0f
    var gyroQDeg = 0f
    var gyroRDeg = 0f
    var gLoad = 1f
    var avionicsHudEnabled = true

    var cameraFps = 0f
    var cameraLabel = "CAMERA"
    var cameraHzLabel = "AUTO"
    var fpsModeLabel = "60"

    var onTapImage: ((Float, Float) -> Unit)? = null
    var onCameraCycle: (() -> Unit)? = null
    var onFpsToggle: (() -> Unit)? = null
    var onAutoLevelToggle: (() -> Unit)? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x72000000
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xA5192328.toInt()
    }
    private val activeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xB52A4D3B.toInt()
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xAAffffff.toInt()
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xCCd8e1e5.toInt()
    }

    private fun cameraButton(): RectF = RectF(width - 440f, 16f, width - 300f, 72f)
    private fun hudButton(): RectF = RectF(width - 290f, 16f, width - 150f, 72f)
    private fun fpsButton(): RectF = RectF(width - 140f, 16f, width - 16f, 72f)

    private fun rotatedSize(): Pair<Float, Float> =
        if (rotationDegrees == 90 || rotationDegrees == 270) imageH.toFloat() to imageW.toFloat()
        else imageW.toFloat() to imageH.toFloat()

    private fun baseRotateImagePoint(x: Float, y: Float): Pair<Float, Float> = when (rotationDegrees) {
        90 -> y to (imageW - x)
        180 -> (imageW - x) to (imageH - y)
        270 -> (imageH - y) to x
        else -> x to y
    }

    private fun inverseBaseRotatePoint(x: Float, y: Float): Pair<Float, Float> = when (rotationDegrees) {
        90 -> (imageW - y) to x
        180 -> (imageW - x) to (imageH - y)
        270 -> y to (imageH - x)
        else -> x to y
    }

    private fun imageToView(x: Float, y: Float): Pair<Float, Float> {
        val p = baseRotateImagePoint(x, y)
        val rs = rotatedSize()
        val scale = max(width.toFloat() / rs.first, height.toFloat() / rs.second)
        val ox = (width - rs.first * scale) / 2f
        val oy = (height - rs.second * scale) / 2f
        return (ox + p.first * scale) to (oy + p.second * scale)
    }

    private fun viewToImage(x: Float, y: Float): Pair<Float, Float> {
        val rs = rotatedSize()
        val scale = max(width.toFloat() / rs.first, height.toFloat() / rs.second)
        val ox = (width - rs.first * scale) / 2f
        val oy = (height - rs.second * scale) / 2f
        val rx = ((x - ox) / scale).coerceIn(0f, rs.first)
        val ry = ((y - oy) / scale).coerceIn(0f, rs.second)
        val raw = inverseBaseRotatePoint(rx, ry)
        return raw.first.coerceIn(0f, imageW.toFloat() - 1f) to
            raw.second.coerceIn(0f, imageH.toFloat() - 1f)
    }

    private fun drawTrackedBox(c: Canvas, tr: UiTrack, col: Int) {
        if (tr.state == 0 || tr.bw <= 0f || tr.bh <= 0f) return

        boxPaint.color = col
        val x0 = tr.cx - tr.bw / 2f
        val y0 = tr.cy - tr.bh / 2f
        val x1 = tr.cx + tr.bw / 2f
        val y1 = tr.cy + tr.bh / 2f

        val p0 = imageToView(x0, y0)
        val p1 = imageToView(x1, y0)
        val p2 = imageToView(x1, y1)
        val p3 = imageToView(x0, y1)

        val path = Path()
        path.moveTo(p0.first, p0.second)
        path.lineTo(p1.first, p1.second)
        path.lineTo(p2.first, p2.second)
        path.lineTo(p3.first, p3.second)
        path.close()
        c.drawPath(path, boxPaint)

        val pc = imageToView(tr.cx, tr.cy)
        c.drawLine(pc.first - 13f, pc.second, pc.first + 13f, pc.second, boxPaint)
        c.drawLine(pc.first, pc.second - 13f, pc.first, pc.second + 13f, boxPaint)
    }

    private fun drawAvionics(c: Canvas) {
        if (!avionicsHudEnabled) return

        val cx = width / 2f
        val cy = height / 2f
        val pitchPx = sensorPitchDegrees.coerceIn(-25f, 25f) * 5f

        c.save()
        c.rotate(-sensorRollDegrees, cx, cy)
        horizonPaint.color = 0xCCd8e1e5.toInt()
        c.drawLine(cx - 210f, cy + pitchPx, cx - 38f, cy + pitchPx, horizonPaint)
        c.drawLine(cx + 38f, cy + pitchPx, cx + 210f, cy + pitchPx, horizonPaint)

        for (p in -20..20 step 5) {
            if (p == 0) continue
            val y = cy + pitchPx - p * 5f
            val half = if (p % 10 == 0) 52f else 30f
            c.drawLine(cx - half, y, cx + half, y, whitePaint)
        }
        c.restore()

        // Fixed aircraft reference
        c.drawLine(cx - 72f, cy, cx - 18f, cy, whitePaint)
        c.drawLine(cx + 18f, cy, cx + 72f, cy, whitePaint)
        c.drawLine(cx - 18f, cy, cx, cy + 12f, whitePaint)
        c.drawLine(cx, cy + 12f, cx + 18f, cy, whitePaint)

        // Roll scale
        val radius = 118f
        for (a in -45..45 step 15) {
            val rad = Math.toRadians((a - 90).toDouble())
            val r0 = radius - if (a % 30 == 0) 12f else 7f
            val x0 = cx + cos(rad).toFloat() * r0
            val y0 = cy + sin(rad).toFloat() * r0
            val x1 = cx + cos(rad).toFloat() * radius
            val y1 = cy + sin(rad).toFloat() * radius
            c.drawLine(x0, y0, x1, y1, whitePaint)
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)

        val tr = track
        val stateText = when (tr.state) {
            1 -> "TRACK"
            2 -> "LOST"
            else -> "SEARCH"
        }
        val col = when (tr.state) {
            1 -> 0xff71df8a.toInt()
            2 -> 0xffff9a3d.toInt()
            else -> 0xffcbd5da.toInt()
        }

        drawAvionics(c)
        drawTrackedBox(c, tr, col)

        c.drawRoundRect(RectF(16f, 14f, min(width - 460f, 940f), 128f), 12f, 12f, shadePaint)

        textPaint.color = col
        textPaint.textSize = 28f
        c.drawText(stateText + "  " + (tr.conf * 100).toInt() + "%", 30f, 43f, textPaint)

        textPaint.color = 0xffe7eef1.toInt()
        textPaint.textSize = 18f
        c.drawText(
            "CAM " + "%.1f".format(cameraFps) + " FPS   TRACK " + "%.1f".format(tr.fps) +
                " FPS   LAT " + "%.1f".format(tr.latency) + "ms   JIT " + "%.1f".format(tr.jitter) + "px",
            30f, 70f, textPaint
        )
        c.drawText(
            "ROLL " + "%+.1f".format(sensorRollDegrees) + "°   PITCH " + "%+.1f".format(sensorPitchDegrees) +
                "°   HDG " + "%03d".format(sensorHeadingDegrees.toInt()) + "°   G " + "%.2f".format(gLoad),
            30f, 96f, textPaint
        )
        c.drawText(
            "P " + "%+.1f".format(gyroPDeg) + "°/s   Q " + "%+.1f".format(gyroQDeg) +
                "°/s   R " + "%+.1f".format(gyroRDeg) + "°/s   CAMERA HAL " + cameraHzLabel,
            30f, 119f, textPaint
        )

        c.drawRoundRect(cameraButton(), 12f, 12f, buttonPaint)
        c.drawRoundRect(hudButton(), 12f, 12f, if (avionicsHudEnabled) activeButtonPaint else buttonPaint)
        c.drawRoundRect(fpsButton(), 12f, 12f, buttonPaint)

        textPaint.color = 0xffe7eef1.toInt()
        textPaint.textSize = 18f
        c.drawText("CAMERA", width - 420f, 51f, textPaint)
        c.drawText(if (avionicsHudEnabled) "HUD✓" else "HUD", width - 255f, 51f, textPaint)
        c.drawText(fpsModeLabel + " FPS", width - 126f, 51f, textPaint)

        val labelW = min(width - 32f, 1220f)
        c.drawRoundRect(RectF(16f, height - 67f, 16f + labelW, height - 14f), 12f, 12f, shadePaint)
        textPaint.color = 0xffd8e1e5.toInt()
        textPaint.textSize = 15f
        c.drawText(cameraLabel, 30f, height - 42f, textPaint)
        c.drawText("ТАП = LOCK   •   ДВОЙНОЙ ТАП = RESET", 30f, height - 20f, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            if (cameraButton().contains(e.x, e.y)) {
                onCameraCycle?.invoke()
                return true
            }
            if (hudButton().contains(e.x, e.y)) {
                onAutoLevelToggle?.invoke()
                return true
            }
            if (fpsButton().contains(e.x, e.y)) {
                onFpsToggle?.invoke()
                return true
            }

            val p = viewToImage(e.x, e.y)
            onTapImage?.invoke(p.first, p.second)
        }
        return true
    }
}
