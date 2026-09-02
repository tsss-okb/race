package com.tsss.targetlock;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class HudView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final CameraPreview camera;
    private final TargetTracker t;
    private final YoloDetector yolo;

    private final RectF[] buttons=new RectF[5];
    private final String[] labels={"DETECT","HOLD","REACQ","CLEAR","DEBUG"};
    private boolean debug=false;

    public HudView(Context c,CameraPreview cp,TargetTracker tr,ImuCompensator im){
        super(c);
        camera=cp;t=tr;yolo=cp.getDetector();

        p.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.BOLD));
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
                camera.setAutoReacqEnabled(!camera.autoReacqEnabled);
                break;
            case 3:
                camera.clearTarget();
                break;
            case 4:
                debug=!debug;
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

        final int w=getWidth();
        final int h=getHeight();

        final int green=Color.rgb(110,255,126);
        final int cyan=Color.rgb(96,225,255);
        final int amber=Color.rgb(255,205,86);
        final int red=Color.rgb(255,92,92);
        final int white=Color.rgb(224,240,226);
        final int dim=Color.rgb(115,145,120);
        final int panel=Color.argb(170,0,10,4);
        final int panelSoft=Color.argb(125,0,10,4);

        boolean active=t.locked||t.acquiring||t.coasting||t.reacquiring;
        boolean search=!active;

        String state;
        int stateColor;

        if(t.locked){
            state="LOCK";
            stateColor=green;
        }else if(t.reacquiring){
            state="REACQUIRE";
            stateColor=amber;
        }else if(t.coasting){
            state="COAST";
            stateColor=amber;
        }else if(t.acquiring){
            state="ACQUIRE";
            stateColor=amber;
        }else{
            state="SEARCH";
            stateColor=cyan;
        }

        // ===== Main top status: readable in one glance =====
        p.setStyle(Paint.Style.FILL);
        p.setColor(panel);
        c.drawRoundRect(14,12,430,92,14,14,p);

        p.setTextSize(28);
        p.setColor(stateColor);
        c.drawText(state,28,46,p);

        p.setTextSize(17);
        p.setColor(white);
        if(active){
            String cls=t.targetName==null?"target":t.targetName;
            c.drawText(cls.toUpperCase()+"  "+Math.round(t.confidence*100)+"%",28,74,p);
        }else{
            c.drawText("ТАП ПО РАМКЕ ЦЕЛИ",28,74,p);
        }

        // ===== Compact performance card =====
        float statsW=330f;
        float statsL=w-statsW-14f;
        p.setColor(panelSoft);
        c.drawRoundRect(statsL,12,w-14,92,14,14,p);

        p.setTextSize(16);
        p.setColor(white);
        c.drawText(String.format("CAM  %.0f",camera.cameraFps),statsL+18,37,p);
        c.drawText(String.format("YOLO %.1f",yolo.detectorFps),statsL+125,37,p);
        c.drawText(String.format("KCF  %.0f",t.trackerFps),statsL+230,37,p);

        p.setColor(yolo.ready?green:amber);
        c.drawText("YOLO "+(yolo.ready?"OK":"..."),statsL+18,68,p);

        p.setColor(t.kcfReady?green:red);
        c.drawText("KCF "+(t.kcfReady?"OK":"ERR"),statsL+125,68,p);

        p.setColor(active?stateColor:dim);
        c.drawText("Q "+Math.round(t.confidence*100)+"%",statsL+230,68,p);

        // ===== YOLO candidates ONLY while searching =====
        if(search&&camera.detectEnabled){
            List<YoloDetector.Detection> ds=new ArrayList<>(yolo.getDetections());
            ds.sort(Comparator.comparingDouble((YoloDetector.Detection d)->d.confidence).reversed());

            int max=Math.min(8,ds.size());
            p.setTextSize(15);

            for(int i=0;i<max;i++){
                YoloDetector.Detection d=ds.get(i);

                float l=d.left*w;
                float top=d.top*h;
                float r=d.right*w;
                float b=d.bottom*h;

                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f);
                p.setColor(cyan);
                drawCorners(c,l,top,r,b,13,p);

                p.setStyle(Paint.Style.FILL);
                p.setColor(panelSoft);
                float textW=p.measureText(d.className+" "+Math.round(d.confidence*100)+"%");
                c.drawRoundRect(l,Math.max(4,top-24),l+textW+10,Math.max(23,top-3),5,5,p);

                p.setColor(white);
                c.drawText(d.className+" "+Math.round(d.confidence*100)+"%",
                        l+5,Math.max(20,top-7),p);
            }
        }

        // ===== ONE selected target box =====
        if(active){
            float cx=t.renderX*w;
            float cy=t.renderY*h;

            float halfW=Math.max(15f,t.boxW*w*.5f);
            float halfH=Math.max(15f,t.boxH*h*.5f);

            float l=cx-halfW;
            float top=cy-halfH;
            float r=cx+halfW;
            float b=cy+halfH;

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(t.locked?3.8f:3.2f);
            p.setColor(stateColor);
            drawCorners(c,l,top,r,b,18,p);

            c.drawCircle(cx,cy,5,p);

            p.setStrokeWidth(1.5f);
            p.setColor(Color.argb(120,
                    Color.red(stateColor),
                    Color.green(stateColor),
                    Color.blue(stateColor)));
            c.drawLine(w/2f,h/2f,cx,cy,p);

            // Selected target label only.
            String tag=state+" • "+t.targetName+" • "+Math.round(t.confidence*100)+"%";
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(16);
            float tw=p.measureText(tag);
            float tagTop=Math.max(102,top-30);
            p.setColor(panel);
            c.drawRoundRect(l,tagTop,l+tw+16,tagTop+26,6,6,p);
            p.setColor(stateColor);
            c.drawText(tag,l+8,tagTop+19,p);
        }

        // ===== Minimal center reticle =====
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(Color.argb(145,110,255,126));
        c.drawLine(w/2f-15,h/2f,w/2f-5,h/2f,p);
        c.drawLine(w/2f+5,h/2f,w/2f+15,h/2f,p);
        c.drawLine(w/2f,h/2f-15,w/2f,h/2f-5,p);
        c.drawLine(w/2f,h/2f+5,w/2f,h/2f+15,p);

        // ===== Debug is opt-in only =====
        if(debug){
            drawDebug(c,w,h,panel,white,green,red);
        }

        drawDashboard(c,w,h,green,dim,panel);

        postInvalidateOnAnimation();
    }

    private void drawDebug(Canvas c,int w,int h,int panel,int white,int green,int red){
        float top=108f;
        float right=Math.min(w-14f,770f);

        p.setStyle(Paint.Style.FILL);
        p.setColor(panel);
        c.drawRoundRect(14,top,right,top+128,10,10,p);

        p.setTextSize(14);
        p.setColor(white);

        c.drawText(String.format("YOLO %s %s  %.1f FPS / %.0f ms  det %d",
                yolo.modelMode,yolo.tensorTypes,yolo.detectorFps,yolo.latencyMs,yolo.detectionCount),
                26,top+24,p);

        p.setColor(t.kcfReady?green:red);
        c.drawText(String.format("KCF upd %d  drift %d  miss %d  prior %.1fpx",
                t.kcfUpdates,t.driftRejects,t.verifyMisses,t.filterInnovation),
                26,top+48,p);

        p.setColor(white);
        c.drawText(String.format("STATE %s  lost %d  reacq %d  Ycorr %d",
                t.acquireStatus,t.lostFrames,t.reacquireCount,t.yoloCorrections),
                26,top+72,p);

        c.drawText(String.format("ANALYSIS %dx%d  grab %.0f FPS  track %.1f ms",
                camera.getAnalysisW(),camera.getAnalysisH(),
                camera.analysisGrabFps,t.latencyMs),
                26,top+96,p);

        if(!t.kcfReady&&t.kcfError.length()>0){
            p.setColor(red);
            c.drawText("KCF ERR "+shortText(t.kcfError,70),26,top+120,p);
        }else if(yolo.error.length()>0){
            p.setColor(red);
            c.drawText("YOLO ERR "+shortText(yolo.error,70),26,top+120,p);
        }
    }

    private void drawDashboard(Canvas c,int w,int h,int onColor,int offColor,int panel){
        float bw=132f;
        float bh=46f;
        float gap=8f;
        float total=bw*5+gap*4;
        float left=Math.max(14f,w-total-14f);
        float top=h-bh-14f;

        p.setStyle(Paint.Style.FILL);
        p.setColor(panel);
        c.drawRoundRect(left-8,top-7,w-6,h-7,12,12,p);

        for(int i=0;i<5;i++){
            float l=left+i*(bw+gap);
            buttons[i].set(l,top,l+bw,top+bh);

            boolean on;
            switch(i){
                case 0:on=camera.detectEnabled;break;
                case 1:on=camera.trackEnabled;break;
                case 2:on=camera.autoReacqEnabled;break;
                case 3:on=true;break;
                case 4:on=debug;break;
                default:on=false;
            }

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(i==3?Color.rgb(255,150,90):(on?onColor:offColor));
            c.drawRoundRect(buttons[i],8,8,p);

            p.setStyle(Paint.Style.FILL);
            p.setTextSize(15);
            float tw=p.measureText(labels[i]);
            c.drawText(labels[i],l+(bw-tw)/2f,top+29,p);
        }
    }

    private void drawCorners(Canvas c,float l,float top,float r,float b,float k,Paint p){
        c.drawLine(l,top,l+k,top,p);
        c.drawLine(l,top,l,top+k,p);

        c.drawLine(r,top,r-k,top,p);
        c.drawLine(r,top,r,top+k,p);

        c.drawLine(l,b,l+k,b,p);
        c.drawLine(l,b,l,b-k,p);

        c.drawLine(r,b,r-k,b,p);
        c.drawLine(r,b,r,b-k,p);
    }

    private String shortText(String s,int n){
        if(s==null)return "";
        return s.length()<=n?s:s.substring(0,n);
    }
}
