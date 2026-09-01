package com.tsss.targetlock;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * v1.4 REF Hold
 *
 * Passive visual tracking reference port:
 * YOLO selection -> same-class nearest association -> 3-bbox smoothing
 * -> 8-state Kalman [cx,cy,w,h,vx,vy,vw,vh]
 * -> prediction during short detector loss.
 *
 * Deliberately simple for A/B comparison against v1.3 Precision Hold.
 */
public class TargetTracker {
    public volatile boolean locked=false;
    public volatile boolean acquiring=false;
    public volatile boolean coasting=false;
    public volatile boolean reacquiring=false;

    public volatile float x=.5f,y=.5f,predX=.5f,predY=.5f;
    public volatile float renderX=.5f,renderY=.5f;
    public volatile float vx=0,vy=0,box=.075f,confidence=0;

    public volatile float trackerFps=0,latencyMs=0,yoloConfidence=0;
    public volatile float flowDx=0,flowDy=0,flowQuality=0,flowFps=0;
    public volatile float cameraDx=0,cameraDy=0,imuWeight=0f,flowWeight=0f;
    public volatile int flowPoints=0;

    public volatile int lostFrames=0,targetClass=-1,yoloCorrections=0,reacquireCount=0;
    public volatile int imuCorrections=0,flowCorrections=0;
    public volatile String targetName="manual";
    public volatile int tapCount=0;
    public volatile String acquireStatus="IDLE";
    public volatile boolean useImu=false;
    public volatile boolean useFlow=false;
    public volatile boolean autoReacq=true;

    public volatile float filterSigmaX=.02f,filterSigmaY=.02f;
    public volatile float filterInnovation=0f;
    public volatile float errorX=0f,errorY=0f;
    public volatile float errorEmaX=0f,errorEmaY=0f;

    private ImuCompensator imu;
    private final ReferenceHoldFilter ref=new ReferenceHoldFilter();
    private final Deque<float[]> bboxHistory=new ArrayDeque<>(3);

    private long lastFrameNs=0;
    private long lastDetectionNs=0;
    private long lastCorrectionNs=0;

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
        bboxHistory.clear();

        ref.reset(nx,ny,.15f,.15f);
        long now=System.nanoTime();
        lastDetectionNs=now;
        lastCorrectionNs=now;

        locked=true;
        acquiring=false;
        coasting=false;
        reacquiring=false;
        lostFrames=0;
        confidence=.50f;
        acquireStatus="REF LOCK";
        syncFromFilter();
    }

    public synchronized void requestDetectedLock(YoloDetector.Detection d){
        if(d==null)return;

        targetClass=d.classId;
        targetName=d.className;
        yoloConfidence=d.confidence;
        tapCount++;

        float w=Math.max(.01f,d.right-d.left);
        float h=Math.max(.01f,d.bottom-d.top);

        bboxHistory.clear();
        bboxHistory.addLast(new float[]{d.cx(),d.cy(),w,h});

        ref.reset(d.cx(),d.cy(),w,h);

        long now=System.nanoTime();
        lastDetectionNs=now;
        lastCorrectionNs=now;

        locked=true;
        acquiring=false;
        coasting=false;
        reacquiring=false;
        lostFrames=0;
        confidence=Math.max(.55f,d.confidence);
        acquireStatus="REF LOCK";
        syncFromFilter();
    }

    public synchronized boolean seedFromArgb(int[] pixels,int w,int h,float nx,float ny){
        requestLock(nx,ny);
        return true;
    }

    public synchronized void clear(){
        ref.clear();
        bboxHistory.clear();

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
        filterSigmaX=filterSigmaY=.02f;
        filterInnovation=0f;
        errorX=errorY=errorEmaX=errorEmaY=0f;
        acquireStatus="IDLE";

        lastDetectionNs=0;
        lastCorrectionNs=0;
    }

    public void processArgb(int[] pixels,int w,int h){
        long started=System.nanoTime();
        long now=started;

        float dt=.016f;
        if(lastFrameNs!=0){
            dt=Math.max(.004f,Math.min(.10f,(now-lastFrameNs)/1e9f));
            float f=1e9f/Math.max(1,now-lastFrameNs);
            trackerFps=trackerFps==0?f:.88f*trackerFps+.12f*f;
        }
        lastFrameNs=now;

        synchronized(this){
            if(ref.isInitialized()){
                boolean coast=coasting||reacquiring;
                ref.predict(dt,coast);
                syncFromFilter();

                long since=lastDetectionNs==0?Long.MAX_VALUE:now-lastDetectionNs;
                float sinceSec=since/1e9f;

                if(sinceSec>2.0f){
                    clear();
                }else if(sinceSec>.35f&&autoReacq){
                    locked=false;
                    coasting=true;
                    reacquiring=true;
                    acquireStatus="REF REACQ";
                    confidence*=.985f;
                }else if(sinceSec>.15f){
                    locked=false;
                    coasting=true;
                    reacquiring=false;
                    acquireStatus="REF PREDICT";
                    confidence*=.992f;
                }else{
                    locked=true;
                    coasting=false;
                    reacquiring=false;
                    acquireStatus="REF HOLD";
                }

                if(trackerFps>1&&lastDetectionNs>0){
                    lostFrames=Math.max(0,Math.round(sinceSec*trackerFps));
                }
            }
        }

        latencyMs=(System.nanoTime()-started)/1_000_000f;
    }

    public synchronized float associationScore(YoloDetector.Detection d){
        if(d==null)return Float.MAX_VALUE;
        if(!ref.isInitialized())return 0f;
        if(targetClass>=0&&d.classId!=targetClass)return Float.MAX_VALUE;

        float dw=Math.max(.01f,d.right-d.left);
        float dh=Math.max(.01f,d.bottom-d.top);

        float predictedW=Math.max(.01f,box*2f);
        float predictedH=predictedW;

        float dist=(float)Math.hypot(d.cx()-predX,d.cy()-predY);
        float gate=reacquiring?.34f:(coasting?.24f:Math.max(.10f,box*2.6f));
        if(dist>gate)return Float.MAX_VALUE;

        float sizeRatio=Math.max(
                dw/Math.max(.01f,predictedW),
                predictedW/Math.max(.01f,dw));
        float sizeRatioY=Math.max(
                dh/Math.max(.01f,predictedH),
                predictedH/Math.max(.01f,dh));
        if(sizeRatio>3.0f||sizeRatioY>3.0f)return Float.MAX_VALUE;

        float innovation=ref.associationInnovation(d.cx(),d.cy(),d.confidence);
        float confPenalty=(1f-d.confidence)*.20f;

        // Old working behavior: nearest same-class box dominates association.
        return dist*4.0f + innovation*.08f + confPenalty;
    }

    public synchronized void onYoloDetection(YoloDetector.Detection d){
        if(d==null)return;
        if(targetClass>=0&&d.classId!=targetClass)return;
        if(associationScore(d)==Float.MAX_VALUE)return;

        boolean wasLost=coasting||reacquiring||!locked;

        if(targetClass<0){
            targetClass=d.classId;
            targetName=d.className;
        }

        float w=Math.max(.01f,d.right-d.left);
        float h=Math.max(.01f,d.bottom-d.top);

        bboxHistory.addLast(new float[]{d.cx(),d.cy(),w,h});
        while(bboxHistory.size()>3)bboxHistory.removeFirst();

        float acx=0,acy=0,aw=0,ah=0;
        int n=0;
        for(float[] b:bboxHistory){
            acx+=b[0];acy+=b[1];aw+=b[2];ah+=b[3];n++;
        }
        acx/=n;acy/=n;aw/=n;ah/=n;

        ref.correct(acx,acy,aw,ah,d.confidence);

        long now=System.nanoTime();
        lastDetectionNs=now;
        lastCorrectionNs=now;

        yoloConfidence=d.confidence;
        yoloCorrections++;
        if(wasLost)reacquireCount++;

        locked=true;
        coasting=false;
        reacquiring=false;
        acquiring=false;
        lostFrames=0;
        acquireStatus=wasLost?"REF REACQUIRED":"REF HOLD";
        confidence=.72f*confidence+.28f*d.confidence;

        syncFromFilter();
    }

    public synchronized void predictOnly(){
        if(!ref.isInitialized())return;
        syncFromFilter();
    }

    private void syncFromFilter(){
        x=predX=renderX=ref.cx();
        y=predY=renderY=ref.cy();
        vx=ref.vx();
        vy=ref.vy();

        float w=ref.w();
        float h=ref.h();
        box=clamp(Math.max(w,h)*.5f,.015f,.40f);

        filterSigmaX=ref.sigmaX();
        filterSigmaY=ref.sigmaY();
        filterInnovation=ref.innovation();

        errorX=(renderX-.5f)*2f;
        errorY=(renderY-.5f)*2f;
        errorEmaX=.92f*errorEmaX+.08f*errorX;
        errorEmaY=.92f*errorEmaY+.08f*errorY;

        // Reference mode intentionally does not fuse IMU/flow.
        imuWeight=0f;
        flowWeight=0f;
        flowQuality=0f;
        flowPoints=0;
    }

    private static float clamp(float v,float lo,float hi){
        return Math.max(lo,Math.min(hi,v));
    }
}
