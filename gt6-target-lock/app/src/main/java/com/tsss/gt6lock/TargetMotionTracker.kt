package com.tsss.gt6lock

import kotlin.math.abs
import kotlin.math.hypot

class TargetMotionTracker(
    private val maxLostFrames: Int = 10
) {
    private var target: Detection? = null
    private var lostFrames = 0
    private var vx = 0f
    private var vy = 0f
    private var ax = 0f
    private var ay = 0f
    private var lastNs = 0L
    private var lastSystemNs = 0L
    private var lockW = 0f
    private var lockH = 0f

    val locked: Detection? @Synchronized get() = target
    val misses: Int @Synchronized get() = lostFrames

    @Synchronized fun clear() {
        target = null; lostFrames = 0
        vx = 0f; vy = 0f; ax = 0f; ay = 0f
        lastNs = 0L; lastSystemNs = 0L
        lockW = 0f; lockH = 0f
    }

    @Synchronized
    fun forceLock(d: Detection, nowNs: Long): Detection {
        target = d; lostFrames = 0
        lockW = d.width
        lockH = d.height
        vx = 0f; vy = 0f; ax = 0f; ay = 0f; lastNs = nowNs
        lastSystemNs = System.nanoTime()
        return d
    }

    @Synchronized
    fun applyVisual(measured: Detection, nowNs: Long): Detection {
        val prev = target ?: return forceLock(measured, nowNs)
        val dt = if (lastNs == 0L) 1f/60f
            else ((nowNs-lastNs)/1e9).toFloat().coerceIn(1f/240f, 0.12f)

        val trust = measured.confidence.coerceIn(0.30f, 0.95f)
        var rawDx = measured.cx - prev.cx
        var rawDy = measured.cy - prev.cy

        // Velocity innovation gate: compare the new measurement with the
        // displacement already predicted by velocity/acceleration. A single
        // wild flow/NCC point is clipped instead of throwing the box across
        // the screen; persistent real motion passes through on following frames.
        val expectedDx = vx * dt + 0.5f * ax * dt * dt
        val expectedDy = vy * dt + 0.5f * ay * dt * dt
        val innovXpx = (rawDx - expectedDx) * 640f
        val innovYpx = (rawDy - expectedDy) * 360f
        val innovationPx = hypot(innovXpx.toDouble(), innovYpx.toDouble()).toFloat()
        val expectedStepPx = hypot(
            (expectedDx * 640f).toDouble(),
            (expectedDy * 360f).toDouble()
        ).toFloat()
        val gatePx = (6.0f + 1.7f * expectedStepPx + 2.0f * trust)
            .coerceIn(7.0f, 22.0f)

        if (innovationPx > gatePx && innovationPx > 0.001f) {
            val k = gatePx / innovationPx
            rawDx = expectedDx + (rawDx - expectedDx) * k
            rawDy = expectedDy + (rawDy - expectedDy) * k
        }

        val stepPx = hypot(
            (rawDx * 640f).toDouble(),
            (rawDy * 360f).toDouble()
        ).toFloat()

        // Adaptive deadband: steadier at rest, almost disappears at speed.
        val motion01 = ((stepPx - 0.7f) / 8.0f).coerceIn(0f, 1f)
        val deadbandX = (0.00110f - 0.00055f * motion01).coerceAtLeast(0.00050f)
        val deadbandY = (0.00185f - 0.00090f * motion01).coerceAtLeast(0.00080f)
        val dx = if (abs(rawDx) < deadbandX) 0f else rawDx
        val dy = if (abs(rawDy) < deadbandY) 0f else rawDy

        val measuredCx = prev.cx + dx
        val measuredCy = prev.cy + dy
        val mvx = (dx / dt).coerceIn(-3.2f, 3.2f)
        val mvy = (dy / dt).coerceIn(-3.2f, 3.2f)

        val maxA = 14f
        val maxAx = ((mvx-vx)/dt).coerceIn(-maxA,maxA)
        val maxAy = ((mvy-vy)/dt).coerceIn(-maxA,maxA)

        // Calm target -> more smoothing. Fast target -> quicker response.
        val va = (0.18f + 0.42f * motion01 + 0.06f * trust)
            .coerceIn(0.18f, 0.64f)
        val aa = (0.06f + 0.16f * motion01 + 0.03f * trust)
            .coerceIn(0.06f, 0.25f)
        vx = va*mvx + (1f-va)*vx
        vy = va*mvy + (1f-va)*vy
        ax = aa*maxAx + (1f-aa)*ax
        ay = aa*maxAy + (1f-aa)*ay

        val alpha = (0.34f + 0.44f * motion01 + 0.05f * trust)
            .coerceIn(0.34f, 0.83f)
        val cx = prev.cx + alpha * (measuredCx - prev.cx)
        val cy = prev.cy + alpha * (measuredCy - prev.cy)

        val w = if (lockW > 0f) lockW else measured.width
        val h = if (lockH > 0f) lockH else measured.height
        target = centeredBox(
            cx, cy, w, h,
            measured.confidence, measured.classId, measured.label, measured.predicted
        )
        lostFrames=0; lastNs=nowNs; lastSystemNs=System.nanoTime()
        return target!!
    }

    @Synchronized
    fun predict(nowNs: Long): Detection? {
        val base=target ?: return null
        if (lastNs==0L) return base
        val dt=((nowNs-lastNs)/1e9).toFloat().coerceIn(0f,0.14f)
        val dx=vx*dt+0.5f*ax*dt*dt
        val dy=vy*dt+0.5f*ay*dt*dt
        return shift(base,dx,dy).copy(predicted = base.predicted || dt>0.040f)
    }

    @Synchronized
    fun predictRealtime(nowSystemNs: Long): Detection? {
        val base = target ?: return null
        if (lastSystemNs == 0L) return base

        // Inter-frame extrapolation only. Keep it short to reduce latency
        // without allowing the box to run away from the latest measurement.
        val dt = ((nowSystemNs - lastSystemNs) / 1e9)
            .toFloat()
            .coerceIn(0f, 0.028f)
        val speedPxSec = hypot(
            (vx * 640f).toDouble(),
            (vy * 360f).toDouble()
        ).toFloat()
        if (speedPxSec < 14f) return base

        val dx = vx * dt + 0.5f * ax * dt * dt
        val dy = vy * dt + 0.5f * ay * dt * dt
        return shift(base, dx, dy).copy(
            predicted = base.predicted || dt > 0.010f
        )
    }

    @Synchronized
    fun miss(nowNs: Long): Detection? {
        val prev=target ?: return null
        lostFrames++
        if (lostFrames>maxLostFrames) { clear(); return null }
        val dt=if(lastNs==0L)1f/60f else ((nowNs-lastNs)/1e9).toFloat().coerceIn(1f/240f,0.15f)
        val pred=shift(prev, vx*dt+0.5f*ax*dt*dt, vy*dt+0.5f*ay*dt*dt)
            .copy(confidence=(prev.confidence*(1f-lostFrames*0.06f)).coerceAtLeast(0.18f), predicted=true)
        target=pred
        vx*=0.94f; vy*=0.94f; ax*=0.82f; ay*=0.82f
        lastNs=nowNs; lastSystemNs=System.nanoTime()
        return pred
    }

    private fun centeredBox(
        cx0: Float, cy0: Float, w0: Float, h0: Float,
        confidence: Float, classId: Int, label: String, predicted: Boolean
    ): Detection {
        val w = w0.coerceIn(0.010f, 0.60f)
        val h = h0.coerceIn(0.010f, 0.60f)
        var cx = cx0.coerceIn(0f, 1f)
        var cy = cy0.coerceIn(0f, 1f)
        cx = cx.coerceIn(w * 0.5f, 1f - w * 0.5f)
        cy = cy.coerceIn(h * 0.5f, 1f - h * 0.5f)
        return Detection(
            cx - w * 0.5f, cy - h * 0.5f,
            cx + w * 0.5f, cy + h * 0.5f,
            confidence, classId, label, predicted
        )
    }

    private fun shift(d:Detection,dx0:Float,dy0:Float):Detection{
        val dx=dx0.coerceIn(-0.12f,0.12f)
        val dy=dy0.coerceIn(-0.12f,0.12f)
        var x1=d.x1+dx; var x2=d.x2+dx
        var y1=d.y1+dy; var y2=d.y2+dy
        if(x1<0f){x2-=x1;x1=0f}; if(x2>1f){x1-=x2-1f;x2=1f}
        if(y1<0f){y2-=y1;y1=0f}; if(y2>1f){y1-=y2-1f;y2=1f}
        return d.copy(x1=x1.coerceIn(0f,1f),y1=y1.coerceIn(0f,1f),x2=x2.coerceIn(0f,1f),y2=y2.coerceIn(0f,1f),predicted=true)
    }

    fun distance(a:Detection,b:Detection):Float =
        hypot((a.cx-b.cx).toDouble(),(a.cy-b.cy).toDouble()).toFloat()
}
