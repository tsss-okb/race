package com.tsss.gt6lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class OverlayView(context: Context) : View(context) {
    @Volatile var detections: List<Detection> = emptyList()
    @Volatile var locked: Detection? = null
    @Volatile var stateLabel: String = "SEARCH"
    @Volatile var sourceAspectRatio: Float = 16f / 9f

    @Volatile var cameraFps: Float = 0f
    @Volatile var trackFps: Float = 0f
    @Volatile var outputFps: Float = 0f
    @Volatile var displayFps: Float = 0f
    @Volatile var maxDisplayFps: Float = 0f
    @Volatile var trackConf: Float = 0f
    @Volatile var jitterPx: Float = 0f
    @Volatile var yoloMode: String = "IDLE"
    @Volatile var yoloMs: Double = 0.0
    @Volatile var yoloBackend: String = "OFF"

    @Volatile var rollDeg: Float = 0f
    @Volatile var pitchDeg: Float = 0f
    @Volatile var headingDeg: Float = 0f
    @Volatile var pDeg: Float = 0f
    @Volatile var qDeg: Float = 0f
    @Volatile var rDeg: Float = 0f
    @Volatile var gLoad: Float = 1f
    @Volatile var bodyCalibrated: Boolean = false
    @Volatile var showAvionics: Boolean = true
    @Volatile var softRescue: Boolean = false

    @Volatile var mavConnected: Boolean = false
    @Volatile var mavRollDeg: Float = 0f
    @Volatile var mavPitchDeg: Float = 0f
    @Volatile var mavHeadingDeg: Float = 0f
    @Volatile var mavLine1: String = "MAV --"
    @Volatile var mavLine2: String = ""
    @Volatile var mavLine3: String = ""

    var onTapNormalized: ((Float, Float) -> Unit)? = null
    var onReset: (() -> Unit)? = null
    var onSearch: (() -> Unit)? = null
    var onCalibrate: (() -> Unit)? = null
    var onHudToggle: (() -> Unit)? = null

    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        color = Color.WHITE
    }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x75000000
    }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x99ffffff.toInt()
    }
    private val button = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xA5142118.toInt()
    }
    private val buttonActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xB52A5034.toInt()
    }
    private val horizon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x8897E7A5.toInt()
    }
    private val horizonStrong = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xCCB7F8C5.toInt()
    }

    private fun videoRect(): RectF {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val a = sourceAspectRatio.coerceAtLeast(0.2f)
        return if (w / h > a) {
            val cw = h * a
            val left = (w - cw) * 0.5f
            RectF(left, 0f, left + cw, h)
        } else {
            val ch = w / a
            val top = (h - ch) * 0.5f
            RectF(0f, top, w, top + ch)
        }
    }

    private fun searchButton() = RectF(width - 410f, 16f, width - 310f, 68f)
    private fun resetButton() = RectF(width - 300f, 16f, width - 200f, 68f)
    private fun calButton() = RectF(width - 190f, 16f, width - 110f, 68f)
    private fun hudButton() = RectF(width - 100f, 16f, width - 16f, 68f)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val vr = videoRect()

        // Reticle is fixed to video center.
        val cx = vr.centerX()
        val cy = vr.centerY()
        c.drawCircle(cx, cy, 18f, center)
        c.drawLine(cx - 38f, cy, cx - 10f, cy, center)
        c.drawLine(cx + 10f, cy, cx + 38f, cy, center)
        c.drawLine(cx, cy - 38f, cx, cy - 10f, center)
        c.drawLine(cx, cy + 10f, cx, cy + 38f, center)

        if (showAvionics && mavConnected) {
            // Very cheap Canvas-only artificial horizon. No bitmap/shader allocation.
            val hcX = vr.centerX()
            val hcY = vr.centerY()
            val pitchPx = mavPitchDeg.coerceIn(-30f, 30f) * 5.2f
            c.save()
            c.clipRect(vr)
            c.rotate(-mavRollDeg.coerceIn(-85f, 85f), hcX, hcY)

            val hy = hcY + pitchPx
            c.drawLine(hcX - 190f, hy, hcX - 30f, hy, horizonStrong)
            c.drawLine(hcX + 30f, hy, hcX + 190f, hy, horizonStrong)

            var mark = -20
            while (mark <= 20) {
                if (mark != 0) {
                    val y = hy - mark * 5.2f
                    val half = if (mark % 10 == 0) 48f else 28f
                    c.drawLine(hcX - half, y, hcX + half, y, horizon)
                }
                mark += 5
            }
            c.restore()

            // Fixed aircraft symbol stays screen-aligned.
            c.drawLine(hcX - 72f, hcY, hcX - 18f, hcY, horizonStrong)
            c.drawLine(hcX + 18f, hcY, hcX + 72f, hcY, horizonStrong)
            c.drawLine(hcX - 18f, hcY, hcX, hcY + 10f, horizonStrong)
            c.drawLine(hcX + 18f, hcY, hcX, hcY + 10f, horizonStrong)
        }

        box.color = 0x8877d98a.toInt()
        box.strokeWidth = 2f
        for (d in detections.take(20)) {
            val r = RectF(
                vr.left + d.x1 * vr.width(),
                vr.top + d.y1 * vr.height(),
                vr.left + d.x2 * vr.width(),
                vr.top + d.y2 * vr.height()
            )
            c.drawRect(r, box)
        }

        locked?.let { d ->
            box.color = when (stateLabel) {
                "LOCK" -> 0xff58e47a.toInt()
                "PREDICT" -> 0xffffcf55.toInt()
                "REACQUIRE" -> 0xffff9138.toInt()
                else -> 0xffd8e1e5.toInt()
            }
            box.strokeWidth = 5f
            val r = RectF(
                vr.left + d.x1 * vr.width(),
                vr.top + d.y1 * vr.height(),
                vr.left + d.x2 * vr.width(),
                vr.top + d.y2 * vr.height()
            )
            c.drawRect(r, box)
            val tx = vr.left + d.cx * vr.width()
            val ty = vr.top + d.cy * vr.height()
            c.drawCircle(tx, ty, 10f, box)
        }

        // Minimal top-left telemetry.
        val panelBottom = if (showAvionics && mavConnected) 174f else 126f
        c.drawRoundRect(RectF(14f, 14f, min(width - 430f, 1040f), panelBottom), 12f, 12f, shade)
        text.textSize = 28f
        text.color = when (stateLabel) {
            "LOCK" -> 0xff71ef8d.toInt()
            "PREDICT" -> 0xffffd35c.toInt()
            "REACQUIRE" -> 0xffff9b49.toInt()
            else -> 0xffd8e1e5.toInt()
        }
        c.drawText(
            stateLabel + (if (softRescue) " • SR" else "") +
                "  " + (trackConf * 100).toInt() + "%",
            28f, 44f, text
        )

        text.color = Color.WHITE
        text.textSize = 18f
        c.drawText(
            "CAM " + "%.1f".format(cameraFps) + "  MEAS " + "%.1f".format(trackFps) +
                "  OUT " + "%.1f".format(outputFps) + "  JIT " + "%.1f".format(jitterPx) + "px  YOLO " + yoloMode,
            28f, 72f, text
        )
        c.drawText(
            "DISP " + "%.1f".format(displayFps) + "/MAX " + "%.0f".format(maxDisplayFps) +
                "  •  REQ120  •  " + yoloBackend + "  " + "%.1f".format(yoloMs) + "ms",
            28f, 97f, text
        )
        if (showAvionics) {
            if (mavConnected) {
                text.color = 0xff8ff0a4.toInt()
                c.drawText(mavLine1, 28f, 121f, text)
                text.color = Color.WHITE
                c.drawText(mavLine2, 28f, 145f, text)
                c.drawText(mavLine3, 28f, 169f, text)
            } else {
                c.drawText(
                    "PHONE IMU  R " + "%+.1f".format(rollDeg) + "  P " + "%+.1f".format(pitchDeg) +
                        "  HDG " + "%03d".format(headingDeg.toInt()) + "  G " + "%.2f".format(gLoad) +
                        "  rates " + "%+.0f/%+.0f/%+.0f".format(pDeg, qDeg, rDeg),
                    28f, 121f, text
                )
            }
        }

        c.drawRoundRect(searchButton(), 10f, 10f, button)
        c.drawRoundRect(resetButton(), 10f, 10f, button)
        c.drawRoundRect(calButton(), 10f, 10f, if (bodyCalibrated) buttonActive else button)
        c.drawRoundRect(hudButton(), 10f, 10f, if (showAvionics) buttonActive else button)
        text.textSize = 16f
        text.color = Color.WHITE
        c.drawText("ПОИСК", width - 394f, 49f, text)
        c.drawText("СБРОС", width - 286f, 49f, text)
        c.drawText(if (bodyCalibrated) "CAL✓" else "CAL", width - 176f, 49f, text)
        c.drawText(if (showAvionics) "HUD✓" else "HUD", width - 88f, 49f, text)

        c.drawRoundRect(RectF(14f, height - 42f, min(width - 14f, 760f), height - 10f), 10f, 10f, shade)
        text.textSize = 15f
        c.drawText("ТАП = LOCK  •  ДВОЙНОЙ ТАП = RESET  •  STRONG HOLD • VIDEO/TRACK AXES = CAMERAX", 26f, height - 20f, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        when {
            searchButton().contains(e.x, e.y) -> onSearch?.invoke()
            resetButton().contains(e.x, e.y) -> onReset?.invoke()
            calButton().contains(e.x, e.y) -> onCalibrate?.invoke()
            hudButton().contains(e.x, e.y) -> onHudToggle?.invoke()
            else -> {
                val vr = videoRect()
                if (vr.contains(e.x, e.y)) {
                    val nx = ((e.x - vr.left) / vr.width()).coerceIn(0f, 1f)
                    val ny = ((e.y - vr.top) / vr.height()).coerceIn(0f, 1f)
                    onTapNormalized?.invoke(nx, ny)
                }
            }
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
