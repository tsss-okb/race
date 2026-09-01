package com.tsss.targetlock;

import java.util.Arrays;

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
    public volatile float cameraDx=0,cameraDy=0,imuWeight=1f,flowWeight=0f;
    public volatile int flowPoints=0;

    public volatile int lostFrames=0,targetClass=-1,yoloCorrections=0,reacquireCount=0;
    public volatile int imuCorrections=0,flowCorrections=0;
    public volatile String targetName="manual";
    public volatile int tapCount=0;
    public volatile String acquireStatus="IDLE";
    public volatile boolean useImu=true;
    public volatile boolean useFlow=true;
    public volatile boolean autoReacq=true;

    private ImuCompensator imu;
    private final PrecisionFilter precision=new PrecisionFilter();

    public volatile float filterSigmaX=.02f,filterSigmaY=.02f;
    public volatile float filterInnovation=0f;
    public volatile float errorX=0f,errorY=0f;
    public volatile float errorEmaX=0f,errorEmaY=0f;

    private static final int GRID=15;
    private static final int FLOW_PATCH=2;
    private static final int FLOW_SEARCH=4;

    private final float[] template=new float[GRID*GRID];
    private float templateMean=0f;
    private float templateStd=1f;
    private int templateSpan=18;

    private boolean hasTemplate=false;
    private float pendingX=-1,pendingY=-1;
    private float pendingBox=-1f;
    private boolean pendingReseed=false;

    private byte[] gray;
    private byte[] prevGray;
    private boolean havePrev=false;

    private long lastNs=0,lastFrameNs=0,lastFlowNs=0;
    private long coastStartNs=0;

    public synchronized void setImu(ImuCompensator compensator){imu=compensator;}
    public void setUseImu(boolean v){useImu=v;}
    public void setUseFlow(boolean v){useFlow=v;}
    public void setAutoReacq(boolean v){autoReacq=v;}

    public synchronized void requestLock(float nx,float ny){
        pendingX=clamp(nx,.06f,.94f);pendingY=clamp(ny,.10f,.90f);
        pendingBox=-1f;pendingReseed=false;
        precision.clear();
        acquiring=true;locked=false;coasting=false;reacquiring=false;
        confidence=.15f;lostFrames=0;vx=vy=0;hasTemplate=false;
        renderX=predX=pendingX;renderY=predY=pendingY;
        targetClass=-1;targetName="manual";tapCount++;acquireStatus="TAP";
        if(imu!=null)imu.resetMotion();
    }

    public synchronized void requestDetectedLock(YoloDetector.Detection d){
        if(d==null)return;
        float nx=clamp(d.cx(),.06f,.94f);
        float ny=clamp(d.cy(),.10f,.90f);
        pendingX=nx;pendingY=ny;
        precision.clear();
        pendingBox=clamp(d.halfBox(),.025f,.28f);
        pendingReseed=false;
        acquiring=true;locked=false;coasting=false;reacquiring=false;
        confidence=Math.max(.30f,d.confidence);
        lostFrames=0;vx=vy=0;hasTemplate=false;
        renderX=predX=nx;renderY=predY=ny;
        box=pendingBox;
        targetName=d.className;
        targetClass=d.classId;
        yoloConfidence=d.confidence;
        tapCount++;
        acquireStatus="YOLO SELECT";
        if(imu!=null)imu.resetMotion();
    }

    public synchronized boolean seedFromArgb(int[] pixels,int w,int h,float nx,float ny){
        if(pixels==null||pixels.length<w*h||w<80||h<60)return false;
        ensureBuffers(w*h);
        toGray(pixels,gray,w*h);

        templateSpan=spanFromBox(box,w,h);
        int cx=safeX(Math.round(clamp(nx,.06f,.94f)*(w-1)),w);
        int cy=safeY(Math.round(clamp(ny,.10f,.90f)*(h-1)),h);
        if(!extractTemplate(gray,w,h,cx,cy,template,templateSpan)){
            acquireStatus="SEED FAIL";
            return false;
        }
        recomputeTemplateStats();

        x=predX=renderX=cx/(float)(w-1);
        y=predY=renderY=cy/(float)(h-1);
        precision.reset(x,y);
        syncFromPrecision(.055f);
        pendingX=pendingY=-1;pendingBox=-1;
        vx=vy=0;confidence=.90f;lostFrames=0;
        locked=true;acquiring=false;coasting=false;reacquiring=false;hasTemplate=true;
        lastNs=System.nanoTime();coastStartNs=0;
        acquireStatus="LOCKED";
        copyCurrentToPrev(w*h);
        if(imu!=null)imu.resetMotion();
        return true;
    }

    public synchronized void clear(){
        locked=false;acquiring=false;coasting=false;reacquiring=false;hasTemplate=false;
        confidence=0;lostFrames=0;vx=vy=0;acquireStatus="IDLE";
        pendingX=pendingY=-1;pendingBox=-1;pendingReseed=false;
        targetClass=-1;targetName="manual";
        precision.clear();
        filterSigmaX=filterSigmaY=.02f;filterInnovation=0f;
        errorX=errorY=errorEmaX=errorEmaY=0f;
        renderX=predX=.5f;renderY=predY=.5f;
        if(imu!=null)imu.resetMotion();
    }

    public void processArgb(int[] pixels,int w,int h){
        long started=System.nanoTime();
        try{
            if(pixels==null||w<80||h<60||pixels.length<w*h)return;
            ensureBuffers(w*h);
            toGray(pixels,gray,w*h);

            long now=System.nanoTime();
            float frameDt=lastFrameNs==0?.016f:
                    Math.max(.004f,Math.min(.10f,(now-lastFrameNs)/1e9f));
            if(lastFrameNs!=0){
                float f=1e9f/Math.max(1,now-lastFrameNs);
                trackerFps=trackerFps==0?f:.86f*trackerFps+.14f*f;
            }
            lastFrameNs=now;

            Motion vm=estimateGlobalMotion(gray,prevGray,w,h);
            flowDx=vm.dx;flowDy=vm.dy;flowQuality=vm.quality;flowPoints=vm.points;
            if(lastFlowNs!=0){
                float f=1e9f/Math.max(1,now-lastFlowNs);
                flowFps=flowFps==0?f:.88f*flowFps+.12f*f;
            }
            lastFlowNs=now;

            float imuDx=0,imuDy=0;
            if(imu!=null&&useImu){
                float[] raw=imu.consumeAnalysisRawShift();
                imu.updateCalibration(raw[0],raw[1],vm.dx,vm.dy,vm.quality);
                float[] scaled=imu.scaleRaw(raw[0],raw[1]);
                imuDx=scaled[0];imuDy=scaled[1];
            }

            float fw=useFlow?computeFlowWeight(vm.quality,imu==null?0:imu.angularSpeedDeg):0f;
            float iw=1f-fw;
            float fusedDx=imuDx*iw+vm.dx*fw;
            float fusedDy=imuDy*iw+vm.dy*fw;
            imuWeight=iw;flowWeight=fw;cameraDx=fusedDx;cameraDy=fusedDy;

            if(locked||coasting||reacquiring){
                if(precision.isInitialized()){
                    precision.predict(frameDt,fusedDx,fusedDy,coasting||reacquiring);
                    syncFromPrecision(.055f);
                }else{
                    applyCameraShift(fusedDx,fusedDy);
                }
                if(Math.abs(imuDx)+Math.abs(imuDy)>.00002f)imuCorrections++;
                if(fw>.08f&&vm.quality>.25f)flowCorrections++;
            }

            float px,py,pb;
            boolean reseed;
            synchronized(this){
                px=pendingX;py=pendingY;pb=pendingBox;reseed=pendingReseed;
            }

            if(px>=0&&py>=0){
                if(pb>0)box=pb;
                templateSpan=spanFromBox(box,w,h);
                int cx=safeX(Math.round(px*(w-1)),w);
                int cy=safeY(Math.round(py*(h-1)),h);

                if(extractTemplate(gray,w,h,cx,cy,template,templateSpan)){
                    recomputeTemplateStats();
                    synchronized(this){
                        x=predX=renderX=cx/(float)(w-1);
                        y=predY=renderY=cy/(float)(h-1);
                        precision.reset(x,y);
                        if(!reseed){vx=vy=0;}
                        syncFromPrecision(.055f);
                        confidence=Math.max(.82f,confidence);
                        locked=true;acquiring=false;coasting=false;reacquiring=false;hasTemplate=true;
                        pendingX=pendingY=-1;pendingBox=-1;pendingReseed=false;
                        lostFrames=0;coastStartNs=0;lastNs=System.nanoTime();
                        acquireStatus=reseed?"REACQUIRED":"LOCKED";
                        if(reseed)reacquireCount++;
                        if(imu!=null)imu.resetMotion();
                    }
                }else{
                    acquireStatus="SEED FAIL";
                }
                copyCurrentToPrev(w*h);
                return;
            }

            if(hasTemplate&&(locked||coasting||reacquiring)){
                int cx=safeX(Math.round(predX*(w-1)),w);
                int cy=safeY(Math.round(predY*(h-1)),h);

                int base=Math.max(12,Math.round(Math.max(templateSpan*1.5f,box*Math.min(w,h)*1.2f)));
                int radius=Math.min(92,base+lostFrames*5+(imu!=null&&imu.angularSpeedDeg>55f?10:0));

                Match best=searchMultiScale(gray,w,h,cx,cy,radius);

                if(best!=null&&best.quality>=.52f){
                    updateMeasurement(best.x/(float)(w-1),best.y/(float)(h-1),best.quality);
                    lostFrames=0;
                    coasting=false;reacquiring=false;locked=true;
                    coastStartNs=0;
                    acquireStatus="LOCKED";

                    if(best.quality>.72f){
                        adaptTemplate(gray,w,h,best.x,best.y,.025f,templateSpan);
                    }
                }else{
                    lostFrames++;
                    coast();

                    if(lostFrames>=3){
                        coasting=true;locked=false;
                        if(coastStartNs==0)coastStartNs=now;
                        acquireStatus="COAST";
                    }
                    if(lostFrames>=7&&autoReacq){
                        reacquiring=true;
                        acquireStatus="REACQUIRE";
                    }

                    // Keep target identity alive longer when auto reacquisition is enabled.
                    int maxLost=autoReacq?70:24;
                    long maxCoast=autoReacq?1_600_000_000L:650_000_000L;
                    if(lostFrames>maxLost || (coastStartNs!=0&&now-coastStartNs>maxCoast)){
                        clear();
                    }
                }
            }

            copyCurrentToPrev(w*h);
        }finally{
            latencyMs=(System.nanoTime()-started)/1_000_000f;
        }
    }

    public synchronized float associationScore(YoloDetector.Detection d){
        if(d==null)return Float.MAX_VALUE;
        if((coasting||reacquiring)&&!autoReacq)return Float.MAX_VALUE;
        if(targetClass>=0&&d.classId!=targetClass)return Float.MAX_VALUE;

        float currentSize=Math.max(.025f,box);
        float newSize=Math.max(.025f,d.halfBox());
        float scaleErr=Math.abs((float)Math.log(newSize/currentSize));
        if(scaleErr>1.0f)return Float.MAX_VALUE;

        float sigma=.040f-(clamp(d.confidence,.05f,1f)*.025f);
        float innovation=precision.isInitialized()
                ?precision.innovationScore(d.cx(),d.cy(),sigma)
                :(float)Math.sqrt((d.cx()-predX)*(d.cx()-predX)+(d.cy()-predY)*(d.cy()-predY))/.02f;

        float gate=reacquiring?11f:(coasting?8f:5.5f);
        if(innovation>gate)return Float.MAX_VALUE;

        float expectedHalf=Math.max(.025f,box);
        float iou=boxIou(
                predX-expectedHalf,predY-expectedHalf,predX+expectedHalf,predY+expectedHalf,
                d.left,d.top,d.right,d.bottom);

        if(locked&&iou<.015f&&innovation>3.2f)return Float.MAX_VALUE;

        float confPenalty=(1f-d.confidence)*.60f;
        float iouPenalty=(1f-iou)*.85f;
        return innovation + scaleErr*.75f + confPenalty + iouPenalty;
    }

    public synchronized void onYoloDetection(YoloDetector.Detection d){
        if(d==null)return;
        if(targetClass>=0&&d.classId!=targetClass)return;

        float score=associationScore(d);
        if(score==Float.MAX_VALUE)return;

        yoloConfidence=d.confidence;

        if((reacquiring||coasting||!locked)&&autoReacq){
            pendingX=clamp(d.cx(),.06f,.94f);
            pendingY=clamp(d.cy(),.10f,.90f);
            pendingBox=clamp(d.halfBox(),.025f,.28f);
            pendingReseed=true;
            acquiring=true;
            reacquiring=true;
            acquireStatus="YOLO REACQ";
            confidence=Math.max(confidence,d.confidence*.80f);
            return;
        }

        if(precision.isInitialized()){
            precision.updateYolo(d.cx(),d.cy(),d.confidence);
            filterInnovation=precision.lastInnovation();
            syncFromPrecision(.055f);
        }else{
            precision.reset(d.cx(),d.cy());
            syncFromPrecision(.055f);
        }
        float a=d.confidence>.65f?.20f:.12f;
        box=(1f-a)*box+a*clamp(d.halfBox(),.025f,.28f);
        confidence=Math.max(confidence,d.confidence*.88f);
        yoloCorrections++;

        // If scale changed substantially, reseed the visual template on next frame.
        int desiredSpan=Math.max(10,Math.min(34,Math.round(box*180f)));
        if(Math.abs(desiredSpan-templateSpan)>=5){
            pendingX=d.cx();pendingY=d.cy();pendingBox=box;pendingReseed=true;
        }
    }

    private void ensureBuffers(int n){
        if(gray==null||gray.length!=n)gray=new byte[n];
        if(prevGray==null||prevGray.length!=n){
            prevGray=new byte[n];
            havePrev=false;
        }
    }

    private void toGray(int[] src,byte[] dst,int n){
        for(int i=0;i<n;i++){
            int c=src[i];
            int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            dst[i]=(byte)((r*77+g*150+b*29)>>8);
        }
    }

    private void copyCurrentToPrev(int n){
        System.arraycopy(gray,0,prevGray,0,n);
        havePrev=true;
    }

    private Motion estimateGlobalMotion(byte[] cur,byte[] prev,int w,int h){
        if(!havePrev)return Motion.NONE;

        int[] xs={w/6,w/3,w/2,2*w/3,5*w/6};
        int[] ys={h/5,2*h/5,3*h/5,4*h/5};
        float[] dxs=new float[20],dys=new float[20];
        int count=0;
        float scoreSum=0;

        float tx=predX,ty=predY;
        float ex=Math.max(.14f,box*2.4f),ey=Math.max(.18f,box*3.0f);

        for(int yy:ys){
            for(int xx:xs){
                float nx=xx/(float)Math.max(1,w-1);
                float ny=yy/(float)Math.max(1,h-1);
                if((locked||coasting||reacquiring)&&Math.abs(nx-tx)<ex&&Math.abs(ny-ty)<ey)continue;

                FlowMatch m=matchPatch(prev,cur,w,h,xx,yy);
                if(m!=null&&m.score<22f){
                    dxs[count]=m.dx;dys[count]=m.dy;
                    scoreSum+=m.score;count++;
                }
            }
        }

        if(count<5)return Motion.NONE;

        float mdx=median(dxs,count),mdy=median(dys,count);
        int inliers=0;float spread=0;
        for(int i=0;i<count;i++){
            float ddx=dxs[i]-mdx,ddy=dys[i]-mdy;
            float d=(float)Math.sqrt(ddx*ddx+ddy*ddy);
            if(d<=2.2f){inliers++;spread+=d;}
        }

        float avgScore=scoreSum/count;
        float inlierRatio=inliers/(float)count;
        float avgSpread=inliers>0?spread/inliers:8f;
        float q=clamp(inlierRatio*(1f-avgScore/40f)*(1f-avgSpread/7f),0f,1f);

        return new Motion(mdx/Math.max(1f,w-1f),mdy/Math.max(1f,h-1f),q,count);
    }

    private FlowMatch matchPatch(byte[] prev,byte[] cur,int w,int h,int x,int y){
        if(x<FLOW_PATCH+FLOW_SEARCH||y<FLOW_PATCH+FLOW_SEARCH||
                x>=w-FLOW_PATCH-FLOW_SEARCH||y>=h-FLOW_PATCH-FLOW_SEARCH)return null;

        float best=Float.MAX_VALUE,second=Float.MAX_VALUE;
        int bdx=0,bdy=0;
        for(int dy=-FLOW_SEARCH;dy<=FLOW_SEARCH;dy++){
            for(int dx=-FLOW_SEARCH;dx<=FLOW_SEARCH;dx++){
                float s=patchSad(prev,cur,w,x,y,x+dx,y+dy);
                if(s<best){second=best;best=s;bdx=dx;bdy=dy;}
                else if(s<second)second=s;
            }
        }
        if(best>=second*.97f)return null;
        return new FlowMatch(bdx,bdy,best);
    }

    private float patchSad(byte[] a,byte[] b,int w,int ax,int ay,int bx,int by){
        int sum=0,n=0;
        for(int yy=-FLOW_PATCH;yy<=FLOW_PATCH;yy++){
            int ia=(ay+yy)*w+(ax-FLOW_PATCH);
            int ib=(by+yy)*w+(bx-FLOW_PATCH);
            for(int xx=0;xx<FLOW_PATCH*2+1;xx++){
                sum+=Math.abs((a[ia+xx]&255)-(b[ib+xx]&255));
                n++;
            }
        }
        return sum/(float)n;
    }

    private Match searchMultiScale(byte[] g,int w,int h,int cx,int cy,int radius){
        int[] spans={
                Math.max(8,Math.round(templateSpan*.82f)),
                Math.max(8,Math.round(templateSpan*.92f)),
                templateSpan,
                Math.min(38,Math.round(templateSpan*1.10f)),
                Math.min(38,Math.round(templateSpan*1.22f))
        };
        Match best=null;
        for(int span:spans){
            Match coarse=search(g,w,h,cx,cy,radius,4,span);
            if(coarse==null)continue;
            Match fine=search(g,w,h,coarse.x,coarse.y,7,1,span);
            Match candidate=fine!=null?fine:coarse;
            if(best==null||candidate.quality>best.quality)best=candidate;
        }
        if(best!=null&&best.quality>.74f&&Math.abs(best.span-templateSpan)>=3){
            templateSpan=best.span;
            box=clamp(templateSpan/(float)Math.max(1,Math.min(w,h)),.025f,.28f);
        }
        return best;
    }

    private Match search(byte[] g,int w,int h,int cx,int cy,int radius,int step,int span){
        Match best=null;
        int minX=Math.max(span+2,cx-radius),maxX=Math.min(w-span-3,cx+radius);
        int minY=Math.max(span+2,cy-radius),maxY=Math.min(h-span-3,cy+radius);

        for(int yy=minY;yy<=maxY;yy+=step){
            for(int xx=minX;xx<=maxX;xx+=step){
                float q=znccQuality(g,w,h,xx,yy,span);
                if(best==null||q>best.quality)best=new Match(xx,yy,q,span);
            }
        }
        return best;
    }

    private float znccQuality(byte[] g,int w,int h,int cx,int cy,int span){
        float mean=0f;
        int n=GRID*GRID;

        for(int gy=0;gy<GRID;gy++){
            int yy=cy+((gy-GRID/2)*span*2)/(GRID-1);
            for(int gx=0;gx<GRID;gx++){
                int xx=cx+((gx-GRID/2)*span*2)/(GRID-1);
                mean+=(g[yy*w+xx]&255);
            }
        }
        mean/=n;

        float num=0f,den=0f;
        int k=0;
        for(int gy=0;gy<GRID;gy++){
            int yy=cy+((gy-GRID/2)*span*2)/(GRID-1);
            for(int gx=0;gx<GRID;gx++,k++){
                int xx=cx+((gx-GRID/2)*span*2)/(GRID-1);
                float a=(g[yy*w+xx]&255)-mean;
                float b=template[k]-templateMean;
                num+=a*b;
                den+=a*a;
            }
        }

        if(den<1f||templateStd<1f)return 0f;
        float corr=(float)(num/(Math.sqrt(den)*templateStd));
        return clamp((corr+1f)*.5f,0f,1f);
    }

    private boolean extractTemplate(byte[] g,int w,int h,int cx,int cy,float[] out,int span){
        if(cx<span||cy<span||cx>=w-span||cy>=h-span)return false;
        int k=0;
        for(int gy=0;gy<GRID;gy++){
            int yy=cy+((gy-GRID/2)*span*2)/(GRID-1);
            for(int gx=0;gx<GRID;gx++,k++){
                int xx=cx+((gx-GRID/2)*span*2)/(GRID-1);
                out[k]=g[yy*w+xx]&255;
            }
        }
        return true;
    }

    private void adaptTemplate(byte[] g,int w,int h,int cx,int cy,float alpha,int span){
        if(cx<span||cy<span||cx>=w-span||cy>=h-span)return;
        int k=0;
        for(int gy=0;gy<GRID;gy++){
            int yy=cy+((gy-GRID/2)*span*2)/(GRID-1);
            for(int gx=0;gx<GRID;gx++,k++){
                int xx=cx+((gx-GRID/2)*span*2)/(GRID-1);
                float v=g[yy*w+xx]&255;
                template[k]=(1f-alpha)*template[k]+alpha*v;
            }
        }
        recomputeTemplateStats();
    }

    private void recomputeTemplateStats(){
        float m=0f;
        for(float v:template)m+=v;
        m/=template.length;
        float ss=0f;
        for(float v:template){
            float d=v-m;ss+=d*d;
        }
        templateMean=m;
        templateStd=(float)Math.sqrt(Math.max(1f,ss));
    }

    private int spanFromBox(float halfBox,int w,int h){
        int span=Math.round(clamp(halfBox,.035f,.22f)*Math.min(w,h));
        return Math.max(10,Math.min(34,span));
    }

    private int safeX(int x,int w){
        return Math.max(templateSpan+2,Math.min(w-templateSpan-3,x));
    }

    private int safeY(int y,int h){
        return Math.max(templateSpan+2,Math.min(h-templateSpan-3,y));
    }

    private float computeFlowWeight(float q,float angularSpeedDeg){
        float w=clamp((q-.20f)/.60f,0f,.68f);
        if(angularSpeedDeg>100f)w*=.45f;
        else if(angularSpeedDeg>60f)w*=.68f;
        return clamp(w,0f,.68f);
    }

    private synchronized void applyCameraShift(float dx,float dy){
        if(Math.abs(dx)+Math.abs(dy)<.000001f)return;
        x=clamp(x+dx);y=clamp(y+dy);
        predX=clamp(predX+dx);predY=clamp(predY+dy);
        renderX=predX;renderY=predY;
    }

    private synchronized void updateMeasurement(float nx,float ny,float q){
        lastNs=System.nanoTime();

        if(!precision.isInitialized())precision.reset(nx,ny);
        else precision.updateTracker(nx,ny,q);

        filterInnovation=precision.lastInnovation();
        syncFromPrecision(.055f);

        // ArduPilot-style normalized image error with adaptive EMA.
        float alpha=q>.78f?.32f:(q>.60f?.22f:.14f);
        errorX=(predX-.5f)*2f;
        errorY=(predY-.5f)*2f;
        errorEmaX=(1f-alpha)*errorEmaX+alpha*errorX;
        errorEmaY=(1f-alpha)*errorEmaY+alpha*errorY;

        confidence=.78f*confidence+.22f*q;
    }

    private synchronized void coast(){
        if(precision.isInitialized())syncFromPrecision(.075f);
        else{
            long now=System.nanoTime();
            float dt=lastNs==0?.016f:Math.min(.08f,(now-lastNs)/1e9f);
            predX=clamp(x+vx*(dt+.055f));
            predY=clamp(y+vy*(dt+.055f));
            renderX=predX;renderY=predY;
        }
        errorX=(predX-.5f)*2f;
        errorY=(predY-.5f)*2f;
        errorEmaX=.90f*errorEmaX+.10f*errorX;
        errorEmaY=.90f*errorEmaY+.10f*errorY;
        confidence*=.965f;
    }

    public synchronized void predictOnly(){
        if(!(locked||coasting||reacquiring))return;

        if(precision.isInitialized()){
            predX=precision.predictedX(coasting||reacquiring?.075f:.055f);
            predY=precision.predictedY(coasting||reacquiring?.075f:.055f);
        }

        float dx=0,dy=0;
        if(imu!=null&&useImu){
            float[] d=imu.peekDisplayAppliedShift();
            dx=d[0];dy=d[1];
        }
        renderX=clamp(predX+dx);
        renderY=clamp(predY+dy);

        errorX=(renderX-.5f)*2f;
        errorY=(renderY-.5f)*2f;
        errorEmaX=.94f*errorEmaX+.06f*errorX;
        errorEmaY=.94f*errorEmaY+.06f*errorY;
    }

    private synchronized void syncFromPrecision(float lead){
        if(!precision.isInitialized())return;
        x=precision.x();y=precision.y();
        vx=precision.vx();vy=precision.vy();
        predX=precision.predictedX(lead);
        predY=precision.predictedY(lead);
        renderX=predX;renderY=predY;
        filterSigmaX=precision.sigmaX();
        filterSigmaY=precision.sigmaY();
        filterInnovation=precision.lastInnovation();
    }

    private static float boxIou(float l1,float t1,float r1,float b1,
                                float l2,float t2,float r2,float b2){
        float l=Math.max(l1,l2),t=Math.max(t1,t2);
        float r=Math.min(r1,r2),b=Math.min(b1,b2);
        float iw=Math.max(0f,r-l),ih=Math.max(0f,b-t);
        float inter=iw*ih;
        float a1=Math.max(0f,r1-l1)*Math.max(0f,b1-t1);
        float a2=Math.max(0f,r2-l2)*Math.max(0f,b2-t2);
        return inter/Math.max(1e-6f,a1+a2-inter);
    }

    private static float median(float[] a,int n){
        float[] c=Arrays.copyOf(a,n);
        Arrays.sort(c);
        return (n&1)==1?c[n/2]:(c[n/2-1]+c[n/2])*.5f;
    }

    private static float clamp(float v){return Math.max(0,Math.min(1,v));}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    private static final class Match{
        final int x,y,span;final float quality;
        Match(int x,int y,float q,int span){this.x=x;this.y=y;this.quality=q;this.span=span;}
    }

    private static final class FlowMatch{
        final int dx,dy;final float score;
        FlowMatch(int dx,int dy,float s){this.dx=dx;this.dy=dy;this.score=s;}
    }

    private static final class Motion{
        static final Motion NONE=new Motion(0,0,0,0);
        final float dx,dy,quality;final int points;
        Motion(float dx,float dy,float q,int p){this.dx=dx;this.dy=dy;this.quality=q;this.points=p;}
    }
}
