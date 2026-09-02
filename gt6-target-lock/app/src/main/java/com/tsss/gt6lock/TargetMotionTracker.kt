package com.tsss.gt6lock

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

    val locked: Detection? @Synchronized get() = target
    val misses: Int @Synchronized get() = lostFrames

    @Synchronized fun clear() {
        target = null; lostFrames = 0
        vx = 0f; vy = 0f; ax = 0f; ay = 0f; lastNs = 0L
    }

    @Synchronized
    fun forceLock(d: Detection, nowNs: Long): Detection {
        target = d; lostFrames = 0
        vx = 0f; vy = 0f; ax = 0f; ay = 0f; lastNs = nowNs
        return d
    }

    @Synchronized
    fun applyVisual(measured: Detection, nowNs: Long): Detection {
        val prev = target ?: return forceLock(measured, nowNs)
        val dt = if (lastNs == 0L) 1f/60f else ((nowNs-lastNs)/1e9).toFloat().coerceIn(1f/240f, 0.12f)
        val mvx = ((measured.cx - prev.cx) / dt).coerceIn(-3.2f, 3.2f)
        val mvy = ((measured.cy - prev.cy) / dt).coerceIn(-3.2f, 3.2f)
        val maxA = 14f
        val maxAx = ((mvx-vx)/dt).coerceIn(-maxA,maxA)
        val maxAy = ((mvy-vy)/dt).coerceIn(-maxA,maxA)
        val trust = measured.confidence.coerceIn(0.30f,0.95f)
        val va = (0.30f + 0.30f*trust).coerceIn(0.28f,0.62f)
        val aa = (0.10f + 0.18f*trust).coerceIn(0.10f,0.28f)
        vx = va*mvx + (1f-va)*vx
        vy = va*mvy + (1f-va)*vy
        ax = aa*maxAx + (1f-aa)*ax
        ay = aa*maxAy + (1f-aa)*ay
        val alpha = (0.62f + 0.22f*trust).coerceIn(0.62f,0.86f)
        fun mix(a:Float,b:Float)=a+alpha*(b-a)
        target = Detection(
            mix(prev.x1,measured.x1).coerceIn(0f,1f),
            mix(prev.y1,measured.y1).coerceIn(0f,1f),
            mix(prev.x2,measured.x2).coerceIn(0f,1f),
            mix(prev.y2,measured.y2).coerceIn(0f,1f),
            measured.confidence, measured.classId, measured.label, measured.predicted
        )
        lostFrames=0; lastNs=nowNs
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
    fun miss(nowNs: Long): Detection? {
        val prev=target ?: return null
        lostFrames++
        if (lostFrames>maxLostFrames) { clear(); return null }
        val dt=if(lastNs==0L)1f/60f else ((nowNs-lastNs)/1e9).toFloat().coerceIn(1f/240f,0.15f)
        val pred=shift(prev, vx*dt+0.5f*ax*dt*dt, vy*dt+0.5f*ay*dt*dt)
            .copy(confidence=(prev.confidence*(1f-lostFrames*0.06f)).coerceAtLeast(0.18f), predicted=true)
        target=pred
        vx*=0.94f; vy*=0.94f; ax*=0.82f; ay*=0.82f; lastNs=nowNs
        return pred
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
