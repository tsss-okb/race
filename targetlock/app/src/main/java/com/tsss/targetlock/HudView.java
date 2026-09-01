package com.tsss.targetlock;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

public class HudView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final CameraPreview camera;
    private final TargetTracker t;
    private final ImuCompensator imu;
    private final YoloDetector yolo;

    public HudView(Context c,CameraPreview cp,TargetTracker tr,ImuCompensator im){
        super(c);
        camera=cp;t=tr;imu=im;yolo=cp.getDetector();
        p.setTypeface(Typeface.MONOSPACE);
        setWillNotDraw(false);
        setClickable(true);

        setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_UP&&getWidth()>0&&getHeight()>0){
                float nx=e.getX()/getWidth();
                float ny=e.getY()/getHeight();
                camera.selectTargetAt(nx,ny);
                performClick();
                return true;
            }
            return true;
        });
    }

    @Override public boolean performClick(){
        super.performClick();
        return true;
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        int w=getWidth(),h=getHeight();
        int green=Color.rgb(112,255,125);
        int amber=Color.rgb(255,210,92);
        int red=Color.rgb(255,100,100);
        int cyan=Color.rgb(96,230,255);
        int panel=Color.argb(165,0,12,4);
        int dim=Color.argb(170,112,255,125);

        p.setColor(panel);p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(12,10,980,200,12,12,p);
        p.setTextSize(18);p.setStyle(Paint.Style.FILL);

        p.setColor("ERROR".equals(camera.status)?red:green);
        c.drawText(String.format("CAM %.0f/%d FPS   %s",camera.cameraFps,camera.requestedFps,camera.status),24,34,p);

        p.setColor(green);
        c.drawText(String.format("YOLO %s  %.1f FPS / %.0f ms  det %d",
                yolo.ready?"READY":(yolo.started?"LOADING":"STANDBY"),
                yolo.detectorFps,yolo.latencyMs,yolo.detectionCount),24,62,p);

        if(yolo.error.length()>0){
            p.setColor(red);
            c.drawText("YOLO ERR "+shortText(yolo.error,72),24,90,p);
        }else{
            p.setColor(green);
            c.drawText(String.format("IMU %.0f Hz  %.1f deg/s  Kx %.2f Ky %.2f",
                    imu.gyroHz,imu.angularSpeedDeg,imu.strengthX,imu.strengthY),24,90,p);
        }

        p.setColor(green);
        c.drawText(String.format("TRK %.0f FPS / %.1f ms  FLOW Q %.0f%% pts %d  GRAB %.0f",
                t.trackerFps,t.latencyMs,t.flowQuality*100f,t.flowPoints,camera.analysisGrabFps),24,118,p);

        c.drawText(String.format("STATE %s  TARGET %s  Q %.0f%%  lost %d  Ycorr %d",
                t.acquireStatus,t.targetName,t.confidence*100,t.lostFrames,t.yoloCorrections),24,146,p);

        c.drawText(String.format("FUSION IMU/FLOW %.0f/%.0f%%   tap %d",
                t.imuWeight*100,t.flowWeight*100,t.tapCount),24,174,p);

        if(camera.lastError.length()>0){
            p.setColor(red);
            c.drawText("ERR "+shortText(camera.lastError,88),24,198,p);
        }

        // YOLO candidate boxes while searching/selecting.
        if(!t.locked){
            List<YoloDetector.Detection> ds=yolo.getDetections();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.2f);
            p.setTextSize(16);

            for(YoloDetector.Detection d:ds){
                float l=d.left*w,tb=d.top*h,r=d.right*w,b=d.bottom*h;
                p.setColor(cyan);
                c.drawRect(l,tb,r,b,p);

                p.setStyle(Paint.Style.FILL);
                c.drawText(d.className+" "+Math.round(d.confidence*100)+"%",l+4,Math.max(18,tb-4),p);
                p.setStyle(Paint.Style.STROKE);
            }
        }

        // Center reticle.
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.4f);p.setColor(dim);
        c.drawLine(w/2-18,h/2,w/2-5,h/2,p);c.drawLine(w/2+5,h/2,w/2+18,h/2,p);
        c.drawLine(w/2,h/2-18,w/2,h/2-5,p);c.drawLine(w/2,h/2+5,w/2,h/2+18,p);

        if(t.locked||t.acquiring){
            float x=t.renderX*w,y=t.renderY*h,s=Math.min(w,h)*t.box;
            p.setStrokeWidth(3f);
            p.setColor(t.locked?green:amber);
            c.drawRect(x-s,y-s,x+s,y+s,p);
            c.drawCircle(x,y,5,p);
        }else{
            p.setStyle(Paint.Style.FILL);p.setColor(amber);p.setTextSize(19);
            c.drawText("YOLO DETECT → TAP BOX → TRACK + IMU + FLOW",24,h-26,p);
        }

        postInvalidateOnAnimation();
    }

    private String shortText(String s,int n){
        return s==null?"":(s.length()<=n?s:s.substring(0,n));
    }
}
