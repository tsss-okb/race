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
        c.drawRoundRect(RectF(14f, 14f, min(width - 430f, 990f), 126f), 12f, 12f, shade)
        text.textSize = 28f
        text.color = when (stateLabel) {
            "LOCK" -> 0xff71ef8d.toInt()
            "PREDICT" -> 0xffffd35c.toInt()
            "REACQUIRE" -> 0xffff9b49.toInt()
            else -> 0xffd8e1e5.toInt()
        }
        c.drawText(stateLabel + "  " + (trackConf * 100).toInt() + "%", 28f, 44f, text)

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
            c.drawText(
                "R " + "%+.1f".format(rollDeg) + "  P " + "%+.1f".format(pitchDeg) +
                    "  HDG " + "%03d".format(headingDeg.toInt()) + "  G " + "%.2f".format(gLoad) +
                    "  rates " + "%+.0f/%+.0f/%+.0f".format(pDeg, qDeg, rDeg),
                28f, 121f, text
            )
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
