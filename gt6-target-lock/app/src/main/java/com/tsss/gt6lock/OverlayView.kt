package com.tsss.gt6lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class OverlayView(context: Context) : View(context) {
    data class UiTrack(val state:Int=0,val cx:Float=0f,val cy:Float=0f,val bw:Float=0f,val bh:Float=0f,val conf:Float=0f,val jitter:Float=0f,val latency:Float=0f,val misses:Int=0,val fps:Float=0f)
    @Volatile var track=UiTrack()
    var imageW=1280; var imageH=720
    var onTapImage: ((Float,Float)->Unit)? = null

    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{ style=Paint.Style.STROKE; strokeWidth=4f }
    private val t=Paint(Paint.ANTI_ALIAS_FLAG).apply{ textSize=32f; typeface=android.graphics.Typeface.MONOSPACE }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s=min(width.toFloat()/imageW, height.toFloat()/imageH)
        val ox=(width-imageW*s)/2f; val oy=(height-imageH*s)/2f
        val tr=track
        val stateText=when(tr.state){1->"TRACK";2->"LOST";else->"SEARCH"}
        val col=when(tr.state){1->0xff71df8a.toInt();2->0xffff9a3d.toInt();else->0xffcbd5da.toInt()}
        p.color=col; t.color=col
        if(tr.state!=0 && tr.bw>0){
            val l=ox+(tr.cx-tr.bw/2)*s; val top=oy+(tr.cy-tr.bh/2)*s
            val r=ox+(tr.cx+tr.bw/2)*s; val b=oy+(tr.cy+tr.bh/2)*s
            c.drawRect(RectF(l,top,r,b),p)
            c.drawLine((l+r)/2-18*s,(top+b)/2,(l+r)/2+18*s,(top+b)/2,p)
            c.drawLine((l+r)/2,(top+b)/2-18*s,(l+r)/2,(top+b)/2+18*s,p)
        }
        c.drawText("$stateText  ${(tr.conf*100).toInt()}%",28f,44f,t)
        t.textSize=24f
        c.drawText("FPS ${"%.1f".format(tr.fps)}   TRACK ${"%.1f".format(tr.latency)} ms   JITTER ${"%.1f".format(tr.jitter)} px",28f,78f,t)
        c.drawText("Tap target to LOCK   •   double tap to RESET",28f,height-28f,t)
        t.textSize=32f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(e.action==MotionEvent.ACTION_UP){
            val s=min(width.toFloat()/imageW,height.toFloat()/imageH)
            val ox=(width-imageW*s)/2f; val oy=(height-imageH*s)/2f
            val x=((e.x-ox)/s).coerceIn(0f,imageW.toFloat()-1)
            val y=((e.y-oy)/s).coerceIn(0f,imageH.toFloat()-1)
            onTapImage?.invoke(x,y)
        }
        return true
    }
}
