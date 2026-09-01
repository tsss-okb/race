package com.tsss.targetlock;

import android.content.Context;
import android.graphics.*;
import android.view.View;

public class HudView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final CameraPreview camera;
    private final TargetTracker t;
    private final ImuCompensator imu;

    public HudView(Context c,CameraPreview cp,TargetTracker tr,ImuCompensator im){
        super(c);camera=cp;t=tr;imu=im;
        p.setTypeface(Typeface.MONOSPACE);setWillNotDraw(false);
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        int w=getWidth(),h=getHeight();
        int green=Color.rgb(112,255,125),amber=Color.rgb(255,210,92),red=Color.rgb(255,100,100);
        int panel=Color.argb(165,0,12,4),dim=Color.argb(170,112,255,125);

        p.setColor(panel);p.setStyle(Paint.Style.FILL);c.drawRoundRect(12,10,900,174,12,12,p);
        p.setTextSize(18);p.setStyle(Paint.Style.FILL);

        p.setColor("ERROR".equals(camera.status)?red:green);
        c.drawText(String.format("CORE 120Hz UI   CAM %.0f/%d FPS   %s",camera.cameraFps,camera.requestedFps,camera.status),24,34,p);

        p.setColor(green);
        c.drawText("CAM 120 CAPABILITY: "+(camera.highSpeed120Supported?"YES":"NO / HIDDEN BY API"),24,62,p);
        c.drawText(String.format("IMU %.0f Hz  %.1f deg/s  Kx %.2f Ky %.2f",imu.gyroHz,imu.angularSpeedDeg,imu.strengthX,imu.strengthY),24,90,p);
        c.drawText(String.format("FLOW %.0f FPS  Q %.0f%%  TRK %.0f FPS / %.1f ms",t.flowFps,t.flowQuality*100f,t.trackerFps,t.latencyMs),24,118,p);
        c.drawText(String.format("TARGET Q %.0f%%  lost %d  IMU/FLOW %.0f/%.0f%%",t.confidence*100,t.lostFrames,t.imuWeight*100,t.flowWeight*100),24,146,p);

        if(camera.lastError.length()>0){
            p.setColor(red);c.drawText("ERR "+shortText(camera.lastError,80),24,170,p);
        }

        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.4f);p.setColor(dim);
        c.drawLine(w/2-18,h/2,w/2-5,h/2,p);c.drawLine(w/2+5,h/2,w/2+18,h/2,p);
        c.drawLine(w/2,h/2-18,w/2,h/2-5,p);c.drawLine(w/2,h/2+5,w/2,h/2+18,p);

        if(t.locked||t.acquiring){
            float x=t.renderX*w,y=t.renderY*h,s=Math.min(w,h)*t.box;
            p.setStrokeWidth(3f);p.setColor(green);
            c.drawRect(x-s,y-s,x+s,y+s,p);c.drawCircle(x,y,5,p);
        }else{
            p.setStyle(Paint.Style.FILL);p.setColor(amber);
            c.drawText("SAFE CORE: YOLO OFF  •  TAP TARGET",24,h-26,p);
        }
        postInvalidateOnAnimation();
    }

    private String shortText(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n));}
}
