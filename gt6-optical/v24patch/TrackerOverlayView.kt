package com.tsss.gt6optical

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class TrackerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Snapshot(
        val state: String = "IDLE",
        val box: RectF? = null,
        val confidence: Float = 0f,
        val nccScore: Float = 0f,
        val uniqueness: Float = 0f,
        val frameW: Int = 16,
        val frameH: Int = 9,
        val ageMs: Long = 0L,
        val lostFrames: Int = 0,
        val processMs: Float = 0f
    )

    var onTapNormalized: ((Float, Float) -> Unit)? = null
    @Volatile private var snapshot = Snapshot()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 27f
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }

    fun setSnapshot(s: Snapshot) {
        snapshot = s
        postInvalidateOnAnimation()
    }

    fun clear() {
        snapshot = Snapshot()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = snapshot
        val b = s.box ?: return
        val content = contentRect(s.frameW, s.frameH)
        val left = content.left + b.left * content.width()
        val top = content.top + b.top * content.height()
        val right = content.left + b.right * content.width()
        val bottom = content.top + b.bottom * content.height()

        val color = when (s.state) {
            "LOCK", "REACQUIRED" -> 0xff42ff67.toInt()
            "ACQUIRE" -> 0xff54ddff.toInt()
            "PRED" -> 0xffffd34f.toInt()
            "REACQUIRE" -> 0xffff923d.toInt()
            else -> 0xffff5252.toInt()
        }
        boxPaint.color = color
        textPaint.color = color
        crossPaint.color = color

        canvas.drawRect(left, top, right, bottom, boxPaint)
        val cx = (left + right) * 0.5f
        val cy = (top + bottom) * 0.5f
        val arm = min(right - left, bottom - top) * 0.18f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)

        val label = "%s %.0f%% NCC%.0f U%.1f L%d %.1fms %dms".format(
            s.state,
            s.confidence * 100f,
            s.nccScore * 100f,
            s.uniqueness * 100f,
            s.lostFrames,
            s.processMs,
            s.ageMs
        )
        canvas.drawText(label, left, (top - 10f).coerceAtLeast(32f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val s = snapshot
        val content = contentRect(s.frameW, s.frameH)
        if (!content.contains(event.x, event.y)) return true
        val nx = ((event.x - content.left) / content.width()).coerceIn(0f, 1f)
        val ny = ((event.y - content.top) / content.height()).coerceIn(0f, 1f)
        onTapNormalized?.invoke(nx, ny)
        return true
    }

    private fun contentRect(frameW: Int, frameH: Int): RectF {
        val fw = frameW.coerceAtLeast(1).toFloat()
        val fh = frameH.coerceAtLeast(1).toFloat()
        val vw = width.coerceAtLeast(1).toFloat()
        val vh = height.coerceAtLeast(1).toFloat()
        val scale = min(vw / fw, vh / fh)
        val cw = fw * scale
        val ch = fh * scale
        val l = (vw - cw) * 0.5f
        val t = (vh - ch) * 0.5f
        return RectF(l, t, l + cw, t + ch)
    }
}
