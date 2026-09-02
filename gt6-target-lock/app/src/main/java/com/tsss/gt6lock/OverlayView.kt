package com.tsss.gt6lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

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
    var imageW = 640
    var imageH = 360
    var rotationDegrees = 0
    var cameraLabel = "CAMERA"
    var fpsModeLabel = "30 FPS"
    var orientationLabel = "MIRROR ON  ROT -45°"
    var manualRotationDegrees = -45f
    var mirrorX = true

    var onTapImage: ((Float, Float) -> Unit)? = null
    var onCameraCycle: (() -> Unit)? = null
    var onFpsToggle: (() -> Unit)? = null
    var onRotateLeft: (() -> Unit)? = null
    var onRotateRight: (() -> Unit)? = null
    var onMirrorToggle: (() -> Unit)? = null

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
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x88ffffff.toInt()
    }

    private fun toDisplayPoint(x: Float, y: Float): Pair<Float, Float> {
        return when (rotationDegrees) {
            180 -> (imageW - x) to (imageH - y)
            else -> x to y
        }
    }

    private fun fromDisplayPoint(x: Float, y: Float): Pair<Float, Float> {
        return when (rotationDegrees) {
            180 -> (imageW - x) to (imageH - y)
            else -> x to y
        }
    }

    private fun cameraButton(): RectF = RectF(width - 330f, 16f, width - 180f, 72f)
    private fun fpsButton(): RectF = RectF(width - 166f, 16f, width - 16f, 72f)
    private fun rotLeftButton(): RectF = RectF(width - 330f, 82f, width - 230f, 138f)
    private fun mirrorButton(): RectF = RectF(width - 220f, 82f, width - 110f, 138f)
    private fun rotRightButton(): RectF = RectF(width - 100f, 82f, width - 16f, 138f)

    private fun manualMatrix(): Matrix {
        val m = Matrix()
        val cx = width / 2f
        val cy = height / 2f
        val rad = Math.toRadians(manualRotationDegrees.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        val sx = if (mirrorX) -1f else 1f

        val a = sx * cos
        val b = -sin
        val d = sx * sin
        val e = cos
        val tx = cx - a * cx - b * cy
        val ty = cy - d * cx - e * cy
        m.setValues(floatArrayOf(a, b, tx, d, e, ty, 0f, 0f, 1f))
        return m
    }

    private fun inverseManualPoint(x: Float, y: Float): Pair<Float, Float> {
        val pts = floatArrayOf(x, y)
        val inv = Matrix()
        val m = manualMatrix()
        if (m.invert(inv)) inv.mapPoints(pts)
        return pts[0] to pts[1]
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)

        // Same center-crop as the TextureView: overlay and tap coordinates stay aligned.
        val s = max(width.toFloat() / imageW, height.toFloat() / imageH)
        val ox = (width - imageW * s) / 2f
        val oy = (height - imageH * s) / 2f
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

        boxPaint.color = col
        textPaint.color = col

        if (tr.state != 0 && tr.bw > 0) {
            c.save()
            c.concat(manualMatrix())
            val point = toDisplayPoint(tr.cx, tr.cy)
            val dcx = point.first
            val dcy = point.second
            val l = ox + (dcx - tr.bw / 2) * s
            val top = oy + (dcy - tr.bh / 2) * s
            val r = ox + (dcx + tr.bw / 2) * s
            val b = oy + (dcy + tr.bh / 2) * s

            val qx = (r - l) * 0.26f
            val qy = (b - top) * 0.26f
            c.drawLine(l, top, l + qx, top, boxPaint)
            c.drawLine(l, top, l, top + qy, boxPaint)
            c.drawLine(r, top, r - qx, top, boxPaint)
            c.drawLine(r, top, r, top + qy, boxPaint)
            c.drawLine(l, b, l + qx, b, boxPaint)
            c.drawLine(l, b, l, b - qy, boxPaint)
            c.drawLine(r, b, r - qx, b, boxPaint)
            c.drawLine(r, b, r, b - qy, boxPaint)

            val bx = (l + r) / 2f
            val by = (top + b) / 2f
            c.drawLine(bx - 14f, by, bx + 14f, by, boxPaint)
            c.drawLine(bx, by - 14f, bx, by + 14f, boxPaint)
            c.restore()
        }

        c.drawRoundRect(RectF(16f, 14f, min(width - 350f, 670f), 100f), 12f, 12f, shadePaint)
        textPaint.textSize = 30f
        c.drawText(stateText + "  " + (tr.conf * 100).toInt() + "%", 30f, 48f, textPaint)

        textPaint.textSize = 21f
        val perf = "FPS " + "%.1f".format(tr.fps) +
            "   LAT " + "%.1f".format(tr.latency) + " ms" +
            "   JITTER " + "%.1f".format(tr.jitter) + " px"
        c.drawText(perf, 30f, 79f, textPaint)

        c.drawRoundRect(cameraButton(), 12f, 12f, buttonPaint)
        c.drawRoundRect(fpsButton(), 12f, 12f, buttonPaint)
        c.drawRoundRect(rotLeftButton(), 12f, 12f, buttonPaint)
        c.drawRoundRect(mirrorButton(), 12f, 12f, buttonPaint)
        c.drawRoundRect(rotRightButton(), 12f, 12f, buttonPaint)

        textPaint.color = 0xffe7eef1.toInt()
        textPaint.textSize = 20f
        c.drawText("CAMERA", width - 310f, 51f, textPaint)
        c.drawText(fpsModeLabel, width - 150f, 51f, textPaint)
        c.drawText("-45°", width - 310f, 118f, textPaint)
        c.drawText(if (mirrorX) "MIRROR✓" else "MIRROR", width - 210f, 118f, textPaint)
        c.drawText("+45°", width - 90f, 118f, textPaint)

        textPaint.textSize = 16f
        c.drawText(orientationLabel, width - 330f, 158f, textPaint)

        val labelW = min(width - 32f, 820f)
        c.drawRoundRect(RectF(16f, height - 70f, 16f + labelW, height - 14f), 12f, 12f, shadePaint)
        textPaint.color = 0xffd8e1e5.toInt()
        textPaint.textSize = 18f
        c.drawText(cameraLabel, 30f, height - 42f, textPaint)
        c.drawText("ТАП ПО ЦЕЛИ = LOCK   •   ДВОЙНОЙ ТАП = RESET", 30f, height - 20f, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            if (cameraButton().contains(e.x, e.y)) {
                onCameraCycle?.invoke()
                return true
            }
            if (fpsButton().contains(e.x, e.y)) {
                onFpsToggle?.invoke()
                return true
            }
            if (rotLeftButton().contains(e.x, e.y)) {
                onRotateLeft?.invoke()
                return true
            }
            if (mirrorButton().contains(e.x, e.y)) {
                onMirrorToggle?.invoke()
                return true
            }
            if (rotRightButton().contains(e.x, e.y)) {
                onRotateRight?.invoke()
                return true
            }

            val pre = inverseManualPoint(e.x, e.y)
            val s = max(width.toFloat() / imageW, height.toFloat() / imageH)
            val ox = (width - imageW * s) / 2f
            val oy = (height - imageH * s) / 2f

            val dx = ((pre.first - ox) / s).coerceIn(0f, imageW.toFloat() - 1)
            val dy = ((pre.second - oy) / s).coerceIn(0f, imageH.toFloat() - 1)
            val point = fromDisplayPoint(dx, dy)
            onTapImage?.invoke(point.first, point.second)
        }
        return true
    }
}
