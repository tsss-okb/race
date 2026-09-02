package com.tsss.targetlock;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_tracking.TrackerKCF;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;

/**
 * TargetLock v1.5 KCF Hybrid
 *
 * Passive visual tracker:
 * YOLO26n INT8 acquisition/verification
 * -> native OpenCV KCF every analysis frame
 * -> detector-confirmed motion prior
 * -> drift reject
 * -> COAST
 * -> expanding YOLO reacquisition
 * -> KCF re-init.
 *
 * No flight-control or actuation output is produced here.
 */
public class TargetTracker {
    public volatile boolean locked=false;
    public volatile boolean acquiring=false;
    public volatile boolean coasting=false;
    public volatile boolean reacquiring=false;

    public volatile float x=.5f,y=.5f,predX=.5f,predY=.5f;
    public volatile float renderX=.5f,renderY=.5f;
    public volatile float vx=0f,vy=0f,box=.075f,confidence=0f;

    public volatile float trackerFps=0f,latencyMs=0f,yoloConfidence=0f;
    public volatile float flowDx=0f,flowDy=0f,flowQuality=0f,flowFps=0f;
    public volatile float cameraDx=0f,cameraDy=0f,imuWeight=0f,flowWeight=0f;
    public volatile int flowPoints=0;

    public volatile int lostFrames=0,targetClass=-1,yoloCorrections=0,reacquireCount=0;
    public volatile int imuCorrections=0,flowCorrections=0;
    public volatile String targetName="manual";
    public volatile int tapCount=0;
    public volatile String acquireStatus="IDLE";
    public volatile boolean useImu=false;
    public volatile boolean useFlow=false;
    public volatile boolean autoReacq=true;

    // HUD-compatible diagnostics.
    public volatile float filterSigmaX=.02f,filterSigmaY=.02f;
    public volatile float filterInnovation=0f;
    public volatile float errorX=0f,errorY=0f;
    public volatile float errorEmaX=0f,errorEmaY=0f;

    // KCF-specific diagnostics.
    public volatile boolean kcfReady=false;
    public volatile String kcfError="";
    public volatile int kcfUpdates=0;
    public volatile int driftRejects=0;
    public volatile int verifyMisses=0;

    private ImuCompensator imu;

    private TrackerKCF kcf;
    private Mat frameMat;
    private byte[] bgr;
    private int frameW=0,frameH=0;
    private long lastFrameNs=0;
    private long frameIndex=0;

    private YoloDetector.Detection pendingInit;
    private YoloDetector.Detection pendingCorrection;

    // Detector-confirmed motion prior, normalized image coordinates.
    private boolean motionValid=false;
    private float motionX=.5f,motionY=.5f,motionW=.12f,motionH=.12f;
    private float motionVx=0f,motionVy=0f;
    private long motionFrame=0;

    private int consecutiveKcfFails=0;

    public TargetTracker(){
        try{
            Loader.load(TrackerKCF.class);
            kcfReady=true;
            kcfError="";
        }catch(Throwable t){
            kcfReady=false;
            kcfError=t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
        }
    }

    public synchronized void setImu(ImuCompensator compensator){imu=compensator;}
    public void setUseImu(boolean v){useImu=v;}
    public void setUseFlow(boolean v){useFlow=v;}
    public void setAutoReacq(boolean v){autoReacq=v;}

    public synchronized void requestLock(float nx,float ny){
        nx=clamp(nx,.02f,.98f);
        ny=clamp(ny,.02f,.98f);

        targetClass=-1;
        targetName="manual";
        tapCount++;

        // Manual point lock becomes a modest synthetic ROI that KCF seeds
        // on the next available analysis frame.
        pendingInit=new YoloDetector.Detection(
                clamp(nx-.06f,0f,1f),clamp(ny-.09f,0f,1f),
                clamp(nx+.06f,0f,1f),clamp(ny+.09f,0f,1f),
                .50f,-1,"manual");

        acquiring=true;
        locked=false;
        coasting=false;
        reacquiring=false;
        lostFrames=0;
        acquireStatus="KCF SEED";
    }

    public synchronized void requestDetectedLock(YoloDetector.Detection d){
        if(d==null)return;

        targetClass=d.classId;
        targetName=d.className;
        yoloConfidence=d.confidence;
        tapCount++;

        pendingInit=d;
        pendingCorrection=null;

        acquiring=true;
        locked=false;
        coasting=false;
        reacquiring=false;
        lostFrames=0;
        verifyMisses=0;
        confidence=Math.max(.55f,d.confidence);
        acquireStatus="YOLO → KCF";
    }

    public synchronized boolean seedFromArgb(int[] pixels,int w,int h,float nx,float ny){
        requestLock(nx,ny);
        processArgb(pixels,w,h);
        return locked;
    }

    public synchronized void clear(){
        closeKcf();
        if(frameMat!=null){
            try{frameMat.close();}catch(Throwable ignored){}
            frameMat=null;
        }
        bgr=null;
        frameW=frameH=0;

        pendingInit=null;
        pendingCorrection=null;
        motionValid=false;
        motionX=motionY=.5f;
        motionW=motionH=.12f;
        motionVx=motionVy=0f;
        motionFrame=0;

        locked=false;
        acquiring=false;
        coasting=false;
        reacquiring=false;

        x=y=predX=predY=renderX=renderY=.5f;
        vx=vy=0f;
        box=.075f;
        confidence=0f;

        targetClass=-1;
        targetName="manual";
        lostFrames=0;
        verifyMisses=0;
        consecutiveKcfFails=0;
        filterInnovation=0f;
        filterSigmaX=filterSigmaY=.02f;
        errorX=errorY=errorEmaX=errorEmaY=0f;
        acquireStatus="IDLE";
    }

    public void processArgb(int[] pixels,int w,int h){
        final long started=System.nanoTime();
        final long now=started;

        synchronized(this){
            frameIndex++;

            if(lastFrameNs!=0){
                float fps=1e9f/Math.max(1,now-lastFrameNs);
                trackerFps=trackerFps==0?fps:.88f*trackerFps+.12f*fps;
            }
            lastFrameNs=now;

            if(!kcfReady){
                latencyMs=(System.nanoTime()-started)/1_000_000f;
                return;
            }

            try{
                ensureFrame(pixels,w,h);

                if(pendingInit!=null){
                    YoloDetector.Detection d=pendingInit;
                    pendingInit=null;
                    if(initKcfFromDetection(d)){
                        setMotionFromDetection(d,true);
                        locked=true;
                        acquiring=false;
                        coasting=false;
                        reacquiring=false;
                        lostFrames=0;
                        consecutiveKcfFails=0;
                        verifyMisses=0;
                        acquireStatus="KCF LOCK";
                    }else{
                        acquiring=false;
                        locked=false;
                        coasting=true;
                        reacquiring=true;
                        acquireStatus="KCF INIT ERR";
                    }
                }

                // Apply detector correction/reacquisition before normal KCF update.
                if(pendingCorrection!=null){
                    YoloDetector.Detection d=pendingCorrection;
                    pendingCorrection=null;
                    boolean wasLost=coasting||reacquiring||!locked||kcf==null;

                    if(initKcfFromDetection(d)){
                        setMotionFromDetection(d,false);
                        yoloCorrections++;
                        if(wasLost)reacquireCount++;

                        locked=true;
                        acquiring=false;
                        coasting=false;
                        reacquiring=false;
                        lostFrames=0;
                        consecutiveKcfFails=0;
                        verifyMisses=0;
                        acquireStatus=wasLost?"REACQUIRED":"KCF VERIFIED";
                    }
                }

                if(kcf!=null&&locked){
                    Rect r=new Rect(
                            clampInt(Math.round((x-box)*w),0,Math.max(0,w-2)),
                            clampInt(Math.round((y-box)*h),0,Math.max(0,h-2)),
                            Math.max(2,Math.round(box*2f*w)),
                            Math.max(2,Math.round(box*2f*h))
                    );
                    clampRect(r,w,h);

                    boolean ok=false;
                    try{ok=kcf.update(frameMat,r);}catch(Throwable t){
                        kcfError="update: "+t.getClass().getSimpleName();
                    }

                    if(ok){
                        float kx=(r.x()+r.width()*.5f)/Math.max(1f,w);
                        float ky=(r.y()+r.height()*.5f)/Math.max(1f,h);
                        float kw=r.width()/Math.max(1f,w);
                        float kh=r.height()/Math.max(1f,h);

                        float[] mp=predictMotion(frameIndex);
                        float driftPx=Float.MAX_VALUE;
                        if(mp!=null){
                            float dx=(kx-mp[0])*w;
                            float dy=(ky-mp[1])*h;
                            driftPx=(float)Math.hypot(dx,dy);
                        }

                        float driftGate=Math.max(22f,Math.min(w,h)*.14f);
                        if(motionValid&&driftPx>driftGate){
                            driftRejects++;
                            closeKcf();
                            enterCoast("DRIFT REJECT");
                        }else{
                            consecutiveKcfFails=0;
                            kcfUpdates++;
                            x=predX=renderX=clamp(kx);
                            y=predY=renderY=clamp(ky);
                            box=clamp(Math.max(kw,kh)*.5f,.012f,.42f);
                            confidence=Math.max(.30f,confidence*.997f);
                            acquireStatus="KCF HOLD";
                            updateErrors();
                        }
                    }else{
                        consecutiveKcfFails++;
                        if(consecutiveKcfFails>=1){
                            closeKcf();
                            enterCoast("KCF LOST");
                        }
                    }

                    try{r.close();}catch(Throwable ignored){}
                }

                if(coasting||reacquiring){
                    lostFrames++;
                    float[] mp=predictMotion(frameIndex);
                    if(mp!=null){
                        predX=renderX=clamp(mp[0]);
                        predY=renderY=clamp(mp[1]);
                        x=predX;y=predY;
                        box=clamp(Math.max(motionW,motionH)*.5f,.012f,.42f);
                    }

                    confidence*=.985f;
                    reacquiring=autoReacq&&lostFrames>=3;
                    acquireStatus=reacquiring?"YOLO REACQ":"COAST";
                    updateErrors();

                    // Keep identity alive long enough for full-frame YOLO recovery.
                    if(lostFrames>120){
                        closeKcf();
                        locked=false;
                        coasting=false;
                        reacquiring=false;
                        motionValid=false;
                        acquireStatus="SEARCH";
                    }
                }

            }catch(Throwable t){
                kcfError=t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
                closeKcf();
                enterCoast("KCF ERROR");
            }

            latencyMs=(System.nanoTime()-started)/1_000_000f;
        }
    }

    public synchronized float associationScore(YoloDetector.Detection d){
        if(d==null)return Float.MAX_VALUE;
        if(targetClass>=0&&d.classId!=targetClass)return Float.MAX_VALUE;

        float[] mp=predictMotion(frameIndex);
        float px=mp==null?predX:mp[0];
        float py=mp==null?predY:mp[1];

        float dxPx=(d.cx()-px)*Math.max(1,frameW);
        float dyPx=(d.cy()-py)*Math.max(1,frameH);
        float distPx=(float)Math.hypot(dxPx,dyPx);

        float gatePx;
        if(reacquiring||coasting){
            gatePx=Math.min(
                    Math.max(frameW,frameH)*.45f,
                    Math.max(18f,Math.min(frameW,frameH)*.10f)+1.5f*lostFrames
            );
        }else{
            gatePx=Math.max(20f,Math.min(frameW,frameH)*.12f);
        }

        if(distPx>gatePx)return Float.MAX_VALUE;

        float dw=Math.max(.008f,d.right-d.left);
        float dh=Math.max(.008f,d.bottom-d.top);
        float rw=Math.max(.008f,motionValid?motionW:box*2f);
        float rh=Math.max(.008f,motionValid?motionH:box*2f);

        float ratio=Math.max(
                Math.max(dw/rw,rw/dw),
                Math.max(dh/rh,rh/dh));
        if(ratio>3.0f)return Float.MAX_VALUE;

        float scalePenalty=Math.abs((float)Math.log(Math.max(1e-4f,ratio)))*5f;
        float confBonus=d.confidence*4f;
        return distPx+scalePenalty-confBonus;
    }

    public synchronized void onYoloDetection(YoloDetector.Detection d){
        if(d==null)return;
        if(targetClass>=0&&d.classId!=targetClass)return;
        if(associationScore(d)==Float.MAX_VALUE)return;

        if(targetClass<0){
            targetClass=d.classId;
            targetName=d.className;
        }

        yoloConfidence=d.confidence;
        confidence=Math.max(confidence,d.confidence);
        pendingCorrection=d;
    }

    public synchronized void onYoloMiss(){
        verifyMisses++;
    }

    public synchronized void predictOnly(){
        if(!(locked||coasting||reacquiring))return;

        if(coasting||reacquiring){
            float[] mp=predictMotion(frameIndex);
            if(mp!=null){
                renderX=clamp(mp[0]);
                renderY=clamp(mp[1]);
            }
        }else{
            renderX=predX;
            renderY=predY;
        }
        updateErrors();
    }

    private void ensureFrame(int[] pixels,int w,int h){
        int need=w*h*3;
        if(frameMat==null||frameW!=w||frameH!=h||bgr==null||bgr.length!=need){
            if(frameMat!=null){
                try{frameMat.close();}catch(Throwable ignored){}
            }
            frameW=w;frameH=h;
            bgr=new byte[need];
            frameMat=new Mat(h,w,CV_8UC3);
        }

        int j=0;
        for(int c:pixels){
            bgr[j++]=(byte)(c&255);          // B
            bgr[j++]=(byte)((c>>8)&255);     // G
            bgr[j++]=(byte)((c>>16)&255);    // R
        }
        frameMat.data().position(0).put(bgr);
    }

    private boolean initKcfFromDetection(YoloDetector.Detection d){
        if(frameMat==null||frameW<2||frameH<2)return false;

        int l=clampInt(Math.round(d.left*frameW),0,frameW-2);
        int t=clampInt(Math.round(d.top*frameH),0,frameH-2);
        int r=clampInt(Math.round(d.right*frameW),l+2,frameW);
        int b=clampInt(Math.round(d.bottom*frameH),t+2,frameH);

        Rect rect=new Rect(l,t,Math.max(2,r-l),Math.max(2,b-t));
        clampRect(rect,frameW,frameH);

        try{
            closeKcf();
            kcf=TrackerKCF.create();
            kcf.init(frameMat,rect);

            x=predX=renderX=(rect.x()+rect.width()*.5f)/Math.max(1f,frameW);
            y=predY=renderY=(rect.y()+rect.height()*.5f)/Math.max(1f,frameH);
            float nw=rect.width()/Math.max(1f,frameW);
            float nh=rect.height()/Math.max(1f,frameH);
            box=clamp(Math.max(nw,nh)*.5f,.012f,.42f);
            updateErrors();
            return true;
        }catch(Throwable err){
            kcfError="init: "+err.getClass().getSimpleName()+": "+String.valueOf(err.getMessage());
            closeKcf();
            return false;
        }finally{
            try{rect.close();}catch(Throwable ignored){}
        }
    }

    private void setMotionFromDetection(YoloDetector.Detection d,boolean resetVelocity){
        float nx=d.cx(),ny=d.cy();
        float nw=Math.max(.008f,d.right-d.left);
        float nh=Math.max(.008f,d.bottom-d.top);

        if(!motionValid||resetVelocity){
            motionVx=motionVy=0f;
        }else{
            long dt=Math.max(1,frameIndex-motionFrame);
            float mvx=(nx-motionX)/dt;
            float mvy=(ny-motionY)/dt;
            final float a=.35f;
            motionVx=(1f-a)*motionVx+a*mvx;
            motionVy=(1f-a)*motionVy+a*mvy;
        }

        motionX=nx;motionY=ny;
        motionW=nw;motionH=nh;
        motionFrame=frameIndex;
        motionValid=true;

        vx=motionVx*Math.max(1f,trackerFps);
        vy=motionVy*Math.max(1f,trackerFps);
    }

    private float[] predictMotion(long atFrame){
        if(!motionValid)return null;
        long dt=Math.max(0,atFrame-motionFrame);
        float decay=(float)Math.pow(.985,Math.max(0,dt-1));
        return new float[]{
                clamp(motionX+motionVx*dt*decay),
                clamp(motionY+motionVy*dt*decay)
        };
    }

    private void enterCoast(String reason){
        locked=false;
        acquiring=false;
        coasting=true;
        reacquiring=autoReacq;
        lostFrames=Math.max(1,lostFrames);
        acquireStatus=reason;
    }

    private void updateErrors(){
        errorX=(renderX-.5f)*2f;
        errorY=(renderY-.5f)*2f;
        errorEmaX=.92f*errorEmaX+.08f*errorX;
        errorEmaY=.92f*errorEmaY+.08f*errorY;

        float conf=Math.max(.05f,confidence);
        filterSigmaX=filterSigmaY=.004f+(1f-conf)*.025f;
        filterInnovation=motionValid
                ?(float)Math.hypot((renderX-motionX)*frameW,(renderY-motionY)*frameH)
                :0f;

        imuWeight=0f;
        flowWeight=0f;
        flowQuality=0f;
        flowPoints=0;
    }

    private void closeKcf(){
        if(kcf!=null){
            try{kcf.close();}catch(Throwable ignored){}
            kcf=null;
        }
    }

    private static void clampRect(Rect r,int w,int h){
        int x=clampInt(r.x(),0,Math.max(0,w-2));
        int y=clampInt(r.y(),0,Math.max(0,h-2));
        int rw=Math.max(2,Math.min(r.width(),w-x));
        int rh=Math.max(2,Math.min(r.height(),h-y));
        r.x(x);r.y(y);r.width(rw);r.height(rh);
    }

    private static int clampInt(int v,int lo,int hi){
        return Math.max(lo,Math.min(hi,v));
    }
    private static float clamp(float v){return Math.max(0f,Math.min(1f,v));}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
