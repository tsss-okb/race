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

    private final RectF[] buttons=new RectF[6];
    private final String[] labels={"DETECT","TRACK","IMU","FLOW","REACQ","CLEAR"};

    public HudView(Context c,CameraPreview cp,TargetTracker tr,ImuCompensator im){
        super(c);
        camera=cp;t=tr;imu=im;yolo=cp.getDetector();
        p.setTypeface(Typeface.MONOSPACE);
        setWillNotDraw(false);
        setClickable(true);
        for(int i=0;i<buttons.length;i++)buttons[i]=new RectF();

        setOnTouchListener((v,e)->{
            if(e.getAction()!=MotionEvent.ACTION_UP)return true;

            float x=e.getX(),y=e.getY();
            for(int i=0;i<buttons.length;i++){
                if(buttons[i].contains(x,y)){
                    handleButton(i);
                    performClick();
                    return true;
                }
            }

            if(getWidth()>0&&getHeight()>0){
                camera.selectTargetAt(x/getWidth(),y/getHeight());
            }
            performClick();
            return true;
        });
    }

    private void handleButton(int i){
        switch(i){
            case 0:
                camera.setDetectEnabled(!camera.detectEnabled);
                break;
            case 1:
                camera.setTrackEnabled(!camera.trackEnabled);
                break;
            case 2:
                camera.setImuEnabled(!camera.imuEnabled);
                break;
            case 3:
                camera.setFlowEnabled(!camera.flowEnabled);
                break;
            case 4:
                camera.setAutoReacqEnabled(!camera.autoReacqEnabled);
                break;
            case 5:
                camera.clearTarget();
                break;
        }
        invalidate();
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
        int off=Color.rgb(95,105,95);

        // Compact telemetry panel.
        p.setColor(panel);p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(12,10,1040,210,12,12,p);
        p.setTextSize(17);p.setStyle(Paint.Style.FILL);

        p.setColor("ERROR".equals(camera.status)?red:green);
        c.drawText(String.format("CAM %.0f/%d FPS  %s  ANALYSIS %dx%d",
                camera.cameraFps,camera.requestedFps,camera.status,
                camera.getAnalysisW(),camera.getAnalysisH()),24,34,p);

        p.setColor(yolo.ready?green:(yolo.error.length()>0?red:amber));
        c.drawText(String.format("YOLO %s %s  %.1f FPS / %.0f ms  det %d  %s",
                yolo.ready?"READY":(yolo.started?"LOADING":"STANDBY"),
                yolo.modelMode,yolo.detectorFps,yolo.latencyMs,yolo.detectionCount,
                yolo.tensorTypes),24,60,p);

        if(yolo.error.length()>0){
            p.setColor(red);
            c.drawText("YOLO ERR "+shortText(yolo.error,86),24,86,p);
        }else{
            p.setColor(green);
            c.drawText(String.format("IMU %.0f Hz  %.1f deg/s  Kx %.2f Ky %.2f",
                    imu.gyroHz,imu.angularSpeedDeg,imu.strengthX,imu.strengthY),24,86,p);
        }

        p.setColor(green);
        c.drawText(String.format("TRK %.0f FPS / %.1f ms  FLOW %.0f%% pts %d  GRAB %.0f",
                t.trackerFps,t.latencyMs,t.flowQuality*100f,t.flowPoints,camera.analysisGrabFps),24,112,p);

        c.drawText(String.format("STATE %s  CLASS %s  Q %.0f%%  lost %d  reacq %d",
                t.acquireStatus,t.targetName,t.confidence*100,t.lostFrames,t.reacquireCount),24,138,p);

        c.drawText(String.format("FUSION IMU/FLOW %.0f/%.0f%%  Ycorr %d  tap %d",
                t.imuWeight*100,t.flowWeight*100,t.yoloCorrections,t.tapCount),24,164,p);

        if(camera.lastError.length()>0){
            p.setColor(red);
            c.drawText("ERR "+shortText(camera.lastError,92),24,192,p);
        }

        // YOLO candidate boxes are ALWAYS visible when DETECT is enabled.
        if(camera.detectEnabled){
            List<YoloDetector.Detection> ds=yolo.getDetections();
            p.setTextSize(15);
            for(YoloDetector.Detection d:ds){
                float l=d.left*w,tb=d.top*h,r=d.right*w,b=d.bottom*h;

                boolean selectedClass=t.targetClass>=0&&d.classId==t.targetClass;
                p.setColor(selectedClass?green:cyan);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(selectedClass?3.0f:2.0f);
                c.drawRect(l,tb,r,b,p);

                p.setStyle(Paint.Style.FILL);
                c.drawText(d.className+" "+Math.round(d.confidence*100)+"%",
                        l+4,Math.max(18,tb-5),p);
            }
        }

        // Tracked target.
        if(t.locked||t.acquiring||t.coasting||t.reacquiring){
            float x=t.renderX*w,y=t.renderY*h,s=Math.max(18f,Math.min(w,h)*t.box);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.4f);
            p.setColor(t.locked?green:amber);
            drawCorners(c,x-s,y-s,x+s,y+s,16,p);
            c.drawCircle(x,y,6,p);
        }

        // Screen center reticle.
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.2f);p.setColor(dim);
        c.drawLine(w/2-18,h/2,w/2-5,h/2,p);c.drawLine(w/2+5,h/2,w/2+18,h/2,p);
        c.drawLine(w/2,h/2-18,w/2,h/2-5,p);c.drawLine(w/2,h/2+5,w/2,h/2+18,p);

        drawDashboard(c,w,h,green,off,panel);

        postInvalidateOnAnimation();
    }

    private void drawDashboard(Canvas c,int w,int h,int onColor,int offColor,int panel){
        float bw=142f,bh=50f,gap=8f;
        float total=bw*6+gap*5;
        float left=Math.max(16,w-total-18);
        float top=h-bh-16;

        p.setColor(panel);p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(left-10,top-8,w-8,h-8,12,12,p);

        for(int i=0;i<6;i++){
            float l=left+i*(bw+gap);
            buttons[i].set(l,top,l+bw,top+bh);

            boolean on;
            switch(i){
                case 0:on=camera.detectEnabled;break;
                case 1:on=camera.trackEnabled;break;
                case 2:on=camera.imuEnabled;break;
                case 3:on=camera.flowEnabled;break;
                case 4:on=camera.autoReacqEnabled;break;
                default:on=true;break;
            }

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(i==5?Color.rgb(255,150,90):(on?onColor:offColor));
            c.drawRoundRect(buttons[i],8,8,p);

            p.setStyle(Paint.Style.FILL);
            p.setTextSize(16);
            float tw=p.measureText(labels[i]);
            c.drawText(labels[i],l+(bw-tw)/2,top+31,p);
        }
    }

    private void drawCorners(Canvas c,float l,float t,float r,float b,float k,Paint p){
        c.drawLine(l,t,l+k,t,p);c.drawLine(l,t,l,t+k,p);
        c.drawLine(r,t,r-k,t,p);c.drawLine(r,t,r,t+k,p);
        c.drawLine(l,b,l+k,b,p);c.drawLine(l,b,l,b-k,p);
        c.drawLine(r,b,r-k,b,p);c.drawLine(r,b,r,b-k,p);
    }

    private String shortText(String s,int n){
        return s==null?"":(s.length()<=n?s:s.substring(0,n));
    }
}
