package com.tsss.gt6lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
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
    var rotationDegrees = 0 // diagnostic only; mapping is matrix-driven in v1.0

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
    var bodyCalibrated = false

    var cameraFps = 0f
    var cameraLabel = "CAMERA"
    var cameraHzLabel = "AUTO"
    var fpsModeLabel = "60"
    var mappingLabel = "MAP WAIT"
    // Realme GT6 CAM0 analysis stream reports X/Y transposed relative to displayed preview.
    // 0=NORMAL, 1=90CW, 3=90CCW. Default to 90CW from the user's observed axis swap.
    var axisMode = 1
    var onAxisModeChanged: ((Int) -> Unit)? = null

    var onTapImage: ((Float, Float) -> Unit)? = null
    var onCameraCycle: (() -> Unit)? = null
    var onFpsToggle: (() -> Unit)? = null
    var onAutoLevelToggle: (() -> Unit)? = null
    var onCalibrate: (() -> Unit)? = null

    private val bufferToView = Matrix()
    private val viewToBuffer = Matrix()
    @Volatile private var mappingReady = false

    fun setCameraMapping(bufferW: Int, bufferH: Int, displayedCorners: FloatArray) {
        if (displayedCorners.size < 8 || bufferW <= 0 || bufferH <= 0) return

        imageW = bufferW
        imageH = bufferH

        val src = floatArrayOf(
            0f, 0f,
            bufferW.toFloat(), 0f,
            bufferW.toFloat(), bufferH.toFloat(),
            0f, bufferH.toFloat()
        )

        val m = Matrix()
        val ok = m.setPolyToPoly(src, 0, displayedCorners, 0, 4)
        if (!ok) {
            mappingReady = false
            mappingLabel = "MAP ERROR"
            invalidate()
            return
        }

        val inv = Matrix()
        if (!m.invert(inv)) {
            mappingReady = false
            mappingLabel = "MAP SINGULAR"
            invalidate()
            return
        }

        bufferToView.set(m)
        viewToBuffer.set(inv)
        mappingReady = true

        val values = FloatArray(9)
        m.getValues(values)
        val det = values[Matrix.MSCALE_X] * values[Matrix.MSCALE_Y] -
            values[Matrix.MSKEW_X] * values[Matrix.MSKEW_Y]
        val base = if (det < 0f) "MAP MIRROR" else "MAP NORMAL"
        val axis = when (axisMode) { 1 -> "XY↻"; 3 -> "XY↺"; 2 -> "XY180"; else -> "XY0" }
        mappingLabel = base + " " + axis
        invalidate()
    }

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

    private fun cameraButton(): RectF = RectF(width - 690f, 16f, width - 560f, 72f)
    private fun axisButton(): RectF = RectF(width - 550f, 16f, width - 430f, 72f)
    private fun calButton(): RectF = RectF(width - 420f, 16f, width - 310f, 72f)
    private fun hudButton(): RectF = RectF(width - 300f, 16f, width - 160f, 72f)
    private fun fpsButton(): RectF = RectF(width - 150f, 16f, width - 16f, 72f)

    private fun analysisToPreviewPoint(x: Float, y: Float): Pair<Float, Float> {
        val u = (x / imageW.toFloat()).coerceIn(0f, 1f)
        val v = (y / imageH.toFloat()).coerceIn(0f, 1f)
        val p = when (axisMode) {
            1 -> (1f - v) to u       // 90° clockwise
            2 -> (1f - u) to (1f - v)
            3 -> v to (1f - u)       // 90° counter-clockwise
            else -> u to v
        }
        return (p.first * imageW) to (p.second * imageH)
    }

    private fun previewToAnalysisPoint(x: Float, y: Float): Pair<Float, Float> {
        val u = (x / imageW.toFloat()).coerceIn(0f, 1f)
        val v = (y / imageH.toFloat()).coerceIn(0f, 1f)
        val p = when (axisMode) {
            1 -> v to (1f - u)       // inverse of 90° clockwise
            2 -> (1f - u) to (1f - v)
            3 -> (1f - v) to u       // inverse of 90° counter-clockwise
            else -> u to v
        }
        return (p.first * imageW) to (p.second * imageH)
    }

    private fun imageToView(x: Float, y: Float): Pair<Float, Float> {
        if (!mappingReady) return x to y
        val corrected = analysisToPreviewPoint(x, y)
        val pts = floatArrayOf(corrected.first, corrected.second)
        bufferToView.mapPoints(pts)
        return pts[0] to pts[1]
    }

    private fun viewToImage(x: Float, y: Float): Pair<Float, Float> {
        if (!mappingReady) {
            return x.coerceIn(0f, imageW.toFloat() - 1f) to
                y.coerceIn(0f, imageH.toFloat() - 1f)
        }

        val pts = floatArrayOf(x, y)
        viewToBuffer.mapPoints(pts)
        val raw = previewToAnalysisPoint(pts[0], pts[1])
        return raw.first.coerceIn(0f, imageW.toFloat() - 1f) to
            raw.second.coerceIn(0f, imageH.toFloat() - 1f)
    }

    private fun drawTrackedBox(c: Canvas, tr: UiTrack, col: Int) {
        if (tr.state == 0 || tr.bw <= 0f || tr.bh <= 0f || !mappingReady) return

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
        c.drawLine(cx - 210f, cy + pitchPx, cx - 38f, cy + pitchPx, horizonPaint)
        c.drawLine(cx + 38f, cy + pitchPx, cx + 210f, cy + pitchPx, horizonPaint)

        for (p in -20..20 step 5) {
            if (p == 0) continue
            val y = cy + pitchPx - p * 5f
            val half = if (p % 10 == 0) 52f else 30f
            c.drawLine(cx - half, y, cx + half, y, whitePaint)
        }
        c.restore()

        c.drawLine(cx - 72f, cy, cx - 18f, cy, whitePaint)
        c.drawLine(cx + 18f, cy, cx + 72f, cy, whitePaint)
        c.drawLine(cx - 18f, cy, cx, cy + 12f, whitePaint)
        c.drawLine(cx, cy + 12f, cx + 18f, cy, whitePaint)

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

        c.drawRoundRect(RectF(16f, 14f, min(width - 580f, 1040f), 128f), 12f, 12f, shadePaint)

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
                "°/s   R " + "%+.1f".format(gyroRDeg) + "°/s   HAL " + cameraHzLabel + "   " + mappingLabel,
            30f, 119f, textPaint
        )

        c.drawRoundRect(cameraButton(), 12f, 12f, buttonPaint)
        c.drawRoundRect(axisButton(), 12f, 12f, activeButtonPaint)
        c.drawRoundRect(calButton(), 12f, 12f, if (bodyCalibrated) activeButtonPaint else buttonPaint)
        c.drawRoundRect(hudButton(), 12f, 12f, if (avionicsHudEnabled) activeButtonPaint else buttonPaint)
        c.drawRoundRect(fpsButton(), 12f, 12f, buttonPaint)

        textPaint.color = 0xffe7eef1.toInt()
        textPaint.textSize = 18f
        c.drawText("CAMERA", width - 675f, 51f, textPaint)
        val axisText = when (axisMode) { 1 -> "XY↻"; 3 -> "XY↺"; 2 -> "180"; else -> "XY0" }
        c.drawText(axisText, width - 530f, 51f, textPaint)
        c.drawText(if (bodyCalibrated) "CAL✓" else "CAL", width - 396f, 51f, textPaint)
        c.drawText(if (avionicsHudEnabled) "HUD✓" else "HUD", width - 268f, 51f, textPaint)
        c.drawText(fpsModeLabel + " FPS", width - 136f, 51f, textPaint)

        val labelW = min(width - 32f, 1320f)
        c.drawRoundRect(RectF(16f, height - 67f, 16f + labelW, height - 14f), 12f, 12f, shadePaint)
        textPaint.color = 0xffd8e1e5.toInt()
        textPaint.textSize = 15f
        c.drawText(cameraLabel, 30f, height - 42f, textPaint)
        c.drawText("ТАП = LOCK   •   ДВОЙНОЙ ТАП = RESET   •   ONE MATRIX", 30f, height - 20f, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            if (cameraButton().contains(e.x, e.y)) {
                onCameraCycle?.invoke()
                return true
            }
            if (axisButton().contains(e.x, e.y)) {
                axisMode = when (axisMode) { 1 -> 3; 3 -> 0; else -> 1 }
                onAxisModeChanged?.invoke(axisMode)
                mappingLabel = when (axisMode) { 1 -> "AXIS XY↻"; 3 -> "AXIS XY↺"; else -> "AXIS XY0" }
                invalidate()
                return true
            }
            if (calButton().contains(e.x, e.y)) {
                onCalibrate?.invoke()
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
