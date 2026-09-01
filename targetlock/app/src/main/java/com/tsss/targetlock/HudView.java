package com.tsss.targetlock;

import android.content.Context;
import android.graphics.*;
import android.view.View;

public class HudView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final CameraPreview camera;
    private final TargetTracker t;
    private final YoloDetector yolo;
    private final ImuCompensator imu;

    public HudView(Context c,CameraPreview cameraPreview,TargetTracker tr,YoloDetector detector,ImuCompensator imuCompensator){
        super(c);camera=cameraPreview;t=tr;yolo=detector;imu=imuCompensator;
        p.setTypeface(Typeface.MONOSPACE);setWillNotDraw(false);
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        int w=getWidth(),h=getHeight();
        int green=Color.rgb(112,255,125),amber=Color.rgb(255,210,92),red=Color.rgb(255,100,100);
        int dim=Color.argb(175,112,255,125),panel=Color.argb(155,0,12,4);

        p.setColor(panel);p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(12,10,900,222,12,12,p);
        p.setTextSize(18);p.setStyle(Paint.Style.FILL);

        String state=t.acquiring?"ACQUIRE":(t.locked?"LOCK":"SEARCH");
        p.setColor(t.locked?green:amber);
        c.drawText(String.format("%s   CAM %.0f/%d FPS   TRK %.0f FPS / %.1f ms",
                state,camera.cameraFps,camera.requestedFps,t.trackerFps,t.latencyMs),24,34,p);

        p.setColor("ERROR".equals(camera.status)?red:green);
        c.drawText("CAM "+camera.status+"  "+camera.analysisSize.getWidth()+"x"+camera.analysisSize.getHeight(),24,62,p);

        p.setColor(green);
        String ys=!yolo.isStarted()?"YOLO STANDBY":
                (yolo.ready?String.format("YOLO26n %.1f FPS / %.0f ms",yolo.detectorFps,yolo.latencyMs)
                :(yolo.error.length()>0?"YOLO ERROR: "+shortText(yolo.error,55):"YOLO LOADING"));
        c.drawText(ys,24,90,p);

        String cal=imu.calibrating?String.format("CAL %3.0f%%",imu.calibrationProgress*100f):"CAL OK";
        c.drawText(String.format("IMU %.0f Hz  %.1f deg/s  %s  Kx %.2f Ky %.2f",
                imu.gyroHz,imu.angularSpeedDeg,cal,imu.strengthX,imu.strengthY),24,118,p);

        c.drawText(String.format("FLOW %.0f FPS  Q %.0f%%  pts %d   FUSION IMU %.0f%% / FLOW %.0f%%",
                t.flowFps,t.flowQuality*100f,t.flowPoints,t.imuWeight*100f,t.flowWeight*100f),24,146,p);

        c.drawText(String.format("TARGET %s  Q %.0f%%  lost %d  Ycorr %d  Fcorr %d",
                t.targetName,t.confidence*100,t.lostFrames,t.yoloCorrections,t.flowCorrections),24,174,p);

        if(camera.lastError.length()>0){
            p.setColor(red);
            c.drawText("ERR "+shortText(camera.lastError,82),24,202,p);
        }

        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);p.setColor(dim);
        c.drawLine(w/2-18,h/2,w/2-5,h/2,p);c.drawLine(w/2+5,h/2,w/2+18,h/2,p);
        c.drawLine(w/2,h/2-18,w/2,h/2-5,p);c.drawLine(w/2,h/2+5,w/2,h/2+18,p);

        if(t.locked||t.acquiring){
            float x=t.renderX*w,y=t.renderY*h,s=Math.min(w,h)*t.box;
            p.setStrokeWidth(3.2f);p.setColor(green);p.setStyle(Paint.Style.STROKE);
            drawCorners(c,x-s,y-s,x+s,y+s,14,p);c.drawCircle(x,y,6,p);
            c.drawLine(w/2,h/2,x,y,p);
        }else{
            p.setStyle(Paint.Style.FILL);p.setTextSize(19);p.setColor(green);
            c.drawText("120Hz DISPLAY + IMU  •  camera auto 120/60/30  •  tap = manual lock",24,h-28,p);
        }
        postInvalidateOnAnimation();
    }

    private String shortText(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n));}

    private void drawCorners(Canvas c,float l,float t,float r,float b,float k,Paint p){
        c.drawLine(l,t,l+k,t,p);c.drawLine(l,t,l,t+k,p);c.drawLine(r,t,r-k,t,p);c.drawLine(r,t,r,t+k,p);
        c.drawLine(l,b,l+k,b,p);c.drawLine(l,b,l,b-k,p);c.drawLine(r,b,r-k,b,p);c.drawLine(r,b,r,b-k,p);
    }
}
