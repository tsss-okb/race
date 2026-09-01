package com.tsss.targetlock;

import android.content.Context;
import android.graphics.*;
import android.view.View;

public class HudView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TargetTracker t;
    private long last=System.nanoTime(); private float uiFps=0;

    public HudView(Context c, TargetTracker tr) {
        super(c); t=tr; p.setTypeface(Typeface.MONOSPACE); setWillNotDraw(false);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        long n=System.nanoTime(); float inst=1e9f/Math.max(1,n-last); last=n; uiFps=uiFps==0?inst:.90f*uiFps+.10f*inst;
        int w=getWidth(), h=getHeight();
        int green=Color.rgb(112,255,125), dim=Color.argb(175,112,255,125), panel=Color.argb(115,0,12,4);

        p.setColor(panel); p.setStyle(Paint.Style.FILL); c.drawRoundRect(12,10,520,92,12,12,p);
        p.setColor(green); p.setTextSize(22); p.setStyle(Paint.Style.FILL);
        String state = t.acquiring ? "ЗАХВАТ" : (t.locked ? "LOCK" : "ПОИСК");
        c.drawText(String.format("%s   TRK %.0f FPS   %.1f ms",state,t.trackerFps,t.latencyMs),24,39,p);
        c.drawText(String.format("Q %3.0f%%   lead 50 ms   lost %d",t.confidence*100,t.lostFrames),24,70,p);

        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f); p.setColor(dim);
        c.drawLine(w/2-18,h/2,w/2-5,h/2,p); c.drawLine(w/2+5,h/2,w/2+18,h/2,p);
        c.drawLine(w/2,h/2-18,w/2,h/2-5,p); c.drawLine(w/2,h/2+5,w/2,h/2+18,p);

        if (t.locked || t.acquiring) {
            float x=t.predX*w,y=t.predY*h,s=Math.min(w,h)*t.box;
            p.setStrokeWidth(3.2f); p.setColor(green); p.setStyle(Paint.Style.STROKE);
            drawCorners(c,x-s,y-s,x+s,y+s,14,p); c.drawCircle(x,y,6,p);
            c.drawLine(w/2,h/2,x,y,p);
        } else {
            p.setStyle(Paint.Style.FILL); p.setTextSize(22); p.setColor(green);
            c.drawText("КОСНИСЬ ЦЕЛИ ДЛЯ ЗАХВАТА",24,h-28,p);
        }
        postInvalidateOnAnimation();
    }

    private void drawCorners(Canvas c,float l,float t,float r,float b,float k,Paint p){
        c.drawLine(l,t,l+k,t,p);c.drawLine(l,t,l,t+k,p);c.drawLine(r,t,r-k,t,p);c.drawLine(r,t,r,t+k,p);
        c.drawLine(l,b,l+k,b,p);c.drawLine(l,b,l,b-k,p);c.drawLine(r,b,r-k,b,p);c.drawLine(r,b,r,b-k,p);
    }
}
