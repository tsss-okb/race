package com.tsss.gt6lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
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

    var levelCorrectionDegrees = 0f
    var levelScale = 1f
    var sensorRollDegrees = 0f
    var autoLevelEnabled = true

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
        textSize = 32f
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
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x88ffffff.toInt()
    }

    private fun cameraButton(): RectF = RectF(width - 440f, 16f, width - 300f, 72f)
    private fun autoButton(): RectF = RectF(width - 290f, 16f, width - 150f, 72f)
    private fun fpsButton(): RectF = RectF(width - 140f, 16f, width - 16f, 72f)

    private fun rotatedSize(): Pair<Float, Float> {
        return if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageH.toFloat() to imageW.toFloat()
        } else {
            imageW.toFloat() to imageH.toFloat()
        }
    }

    private fun baseRotateImagePoint(x: Float, y: Float): Pair<Float, Float> {
        return when (rotationDegrees) {
            90 -> y to (imageW - x)
            180 -> (imageW - x) to (imageH - y)
            270 -> (imageH - y) to x
            else -> x to y
        }
    }

    private fun inverseBaseRotatePoint(x: Float, y: Float): Pair<Float, Float> {
        return when (rotationDegrees) {
            90 -> (imageW - y) to x
            180 -> (imageW - x) to (imageH - y)
            270 -> y to (imageH - x)
            else -> x to y
        }
    }

    private fun rotateAround(x: Float, y: Float, degrees: Float): Pair<Float, Float> {
        if (abs(degrees) < 0.001f) return x to y
        val cx = width / 2f
        val cy = height / 2f
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        return (cx + dx * c - dy * s) to (cy + dx * s + dy * c)
    }

    private fun imageToView(x: Float, y: Float): Pair<Float, Float> {
        val p = baseRotateImagePoint(x, y)
        val rs = rotatedSize()
        val scale = max(width.toFloat() / rs.first, height.toFloat() / rs.second)
        val ox = (width - rs.first * scale) / 2f
        val oy = (height - rs.second * scale) / 2f

        var vx = ox + p.first * scale
        var vy = oy + p.second * scale

        val cx = width / 2f
        val cy = height / 2f
        vx = cx + (vx - cx) * levelScale
        vy = cy + (vy - cy) * levelScale

        return rotateAround(vx, vy, levelCorrectionDegrees)
    }

    private fun viewToImage(x: Float, y: Float): Pair<Float, Float> {
        var p = rotateAround(x, y, -levelCorrectionDegrees)

        val cx = width / 2f
        val cy = height / 2f
        p = (cx + (p.first - cx) / levelScale) to
            (cy + (p.second - cy) / levelScale)

        val rs = rotatedSize()
        val scale = max(width.toFloat() / rs.first, height.toFloat() / rs.second)
        val ox = (width - rs.first * scale) / 2f
        val oy = (height - rs.second * scale) / 2f

        val rx = ((p.first - ox) / scale).coerceIn(0f, rs.first)
        val ry = ((p.second - oy) / scale).coerceIn(0f, rs.second)
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

        val vcx = width / 2f
        val vcy = height / 2f
        c.drawCircle(vcx, vcy, 18f, centerPaint)
        c.drawLine(vcx - 34f, vcy, vcx - 10f, vcy, centerPaint)
        c.drawLine(vcx + 10f, vcy, vcx + 34f, vcy, centerPaint)
        c.drawLine(vcx, vcy - 34f, vcx, vcy - 10f, centerPaint)
        c.drawLine(vcx, vcy + 10f, vcx, vcy + 34f, centerPaint)

        drawTrackedBox(c, tr, col)

        c.drawRoundRect(RectF(16f, 14f, min(width - 460f, 820f), 105f), 12f, 12f, shadePaint)
        textPaint.color = col
        textPaint.textSize = 30f
        c.drawText(stateText + "  " + (tr.conf * 100).toInt() + "%", 30f, 48f, textPaint)

        textPaint.textSize = 20f
        val perf = "TRACK " + "%.1f".format(tr.fps) + " FPS" +
            "   LAT " + "%.1f".format(tr.latency) + "ms" +
            "   JITTER " + "%.1f".format(tr.jitter) + "px"
        c.drawText(perf, 30f, 78f, textPaint)

        textPaint.textSize = 17f
        val imu = "IMU ROLL " + "%.1f".format(sensorRollDegrees) + "°" +
            "   LEVEL " + if (autoLevelEnabled) "AUTO" else "OFF" +
            "   CAMERA " + cameraHzLabel
        c.drawText(imu, 30f, 99f, textPaint)

        c.drawRoundRect(cameraButton(), 12f, 12f, buttonPaint)
        c.drawRoundRect(autoButton(), 12f, 12f, if (autoLevelEnabled) activeButtonPaint else buttonPaint)
        c.drawRoundRect(fpsButton(), 12f, 12f, buttonPaint)

        textPaint.color = 0xffe7eef1.toInt()
        textPaint.textSize = 18f
        c.drawText("CAMERA", width - 420f, 51f, textPaint)
        c.drawText(if (autoLevelEnabled) "AUTO✓" else "AUTO", width - 265f, 51f, textPaint)
        c.drawText(fpsModeLabel + " FPS", width - 126f, 51f, textPaint)

        val labelW = min(width - 32f, 1160f)
        c.drawRoundRect(RectF(16f, height - 67f, 16f + labelW, height - 14f), 12f, 12f, shadePaint)
        textPaint.color = 0xffd8e1e5.toInt()
        textPaint.textSize = 16f
        c.drawText(cameraLabel, 30f, height - 42f, textPaint)
        c.drawText("ТАП = LOCK   •   ДВОЙНОЙ ТАП = RESET", 30f, height - 20f, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            if (cameraButton().contains(e.x, e.y)) {
                onCameraCycle?.invoke()
                return true
            }
            if (autoButton().contains(e.x, e.y)) {
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
