package com.tsss.targetlock;

import android.content.Context;
import android.graphics.*;
import android.view.View;

public class HudView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TargetTracker t;
    private final YoloDetector yolo;
    private final ImuCompensator imu;

    public HudView(Context c, TargetTracker tr, YoloDetector detector, ImuCompensator imuCompensator) {
        super(c); t=tr; yolo=detector; imu=imuCompensator;
        p.setTypeface(Typeface.MONOSPACE); setWillNotDraw(false);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w=getWidth(), h=getHeight();
        int green=Color.rgb(112,255,125), amber=Color.rgb(255,210,92);
        int dim=Color.argb(175,112,255,125), panel=Color.argb(145,0,12,4);

        p.setColor(panel); p.setStyle(Paint.Style.FILL); c.drawRoundRect(12,10,760,154,12,12,p);
        p.setTextSize(19); p.setStyle(Paint.Style.FILL);

        String state = t.acquiring ? "ACQUIRE" : (t.locked ? "LOCK" : "SEARCH");
        p.setColor(t.locked ? green : amber);
        c.drawText(String.format("%s   TRK %.0f FPS / %.1f ms",state,t.trackerFps,t.latencyMs),24,36,p);

        p.setColor(green);
        String ys = yolo.ready ? String.format("YOLO26n %.1f FPS / %.0f ms",yolo.detectorFps,yolo.latencyMs)
                               : (yolo.error.length()>0 ? "YOLO ERROR" : "YOLO LOADING");
        c.drawText(ys,24,66,p);

        String is = imu.active
            ? String.format("IMU %.0f Hz  %.1f deg/s  dX%+.3f dY%+.3f",imu.gyroHz,imu.angularSpeedDeg,imu.lastDx,imu.lastDy)
            : "IMU unavailable";
        c.drawText(is,24,96,p);

        c.drawText(String.format("TARGET %s   Q %.0f%%   lost %d   corr %d",
                t.targetName,t.confidence*100,t.lostFrames,t.yoloCorrections),24,126,p);
        c.drawText(String.format("PITCH %+.1f°  ROLL %+.1f°  IMU-COMP %d",imu.pitchDeg,imu.rollDeg,t.imuCorrections),24,150,p);

        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f); p.setColor(dim);
        c.drawLine(w/2-18,h/2,w/2-5,h/2,p); c.drawLine(w/2+5,h/2,w/2+18,h/2,p);
        c.drawLine(w/2,h/2-18,w/2,h/2-5,p); c.drawLine(w/2,h/2+5,w/2,h/2+18,p);

        if (t.locked || t.acquiring) {
            float x=t.predX*w,y=t.predY*h,s=Math.min(w,h)*t.box;
            p.setStrokeWidth(3.2f); p.setColor(green); p.setStyle(Paint.Style.STROKE);
            drawCorners(c,x-s,y-s,x+s,y+s,14,p); c.drawCircle(x,y,6,p);
            c.drawLine(w/2,h/2,x,y,p);
        } else {
            p.setStyle(Paint.Style.FILL); p.setTextSize(20); p.setColor(green);
            c.drawText("AUTO YOLO26n + IMU SEARCH  •  tap = manual lock",24,h-28,p);
        }
        postInvalidateOnAnimation();
    }

    private void drawCorners(Canvas c,float l,float t,float r,float b,float k,Paint p){
        c.drawLine(l,t,l+k,t,p);c.drawLine(l,t,l,t+k,p);c.drawLine(r,t,r-k,t,p);c.drawLine(r,t,r,t+k,p);
        c.drawLine(l,b,l+k,b,p);c.drawLine(l,b,l,b-k,p);c.drawLine(r,b,r-k,b,p);c.drawLine(r,b,r,b-k,p);
    }
}
