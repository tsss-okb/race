package com.tsss.targetlock;

import java.util.Arrays;

public class TargetTracker {
    public volatile boolean locked=false;
    public volatile boolean acquiring=false;

    public volatile float x=.5f,y=.5f,predX=.5f,predY=.5f;
    public volatile float renderX=.5f,renderY=.5f;
    public volatile float vx=0,vy=0,box=.075f,confidence=0;

    public volatile float trackerFps=0,latencyMs=0,yoloConfidence=0;
    public volatile float flowDx=0,flowDy=0,flowQuality=0,flowFps=0;
    public volatile float cameraDx=0,cameraDy=0,imuWeight=1f,flowWeight=0f;
    public volatile int flowPoints=0;

    public volatile int lostFrames=0,targetClass=-1,yoloCorrections=0;
    public volatile int imuCorrections=0,flowCorrections=0;
    public volatile String targetName="manual";

    private ImuCompensator imu;

    private static final int GRID=15;
    private static final int HALF_SPAN=18;
    private static final int FLOW_PATCH=2;
    private static final int FLOW_SEARCH=4;

    private final float[] template=new float[GRID*GRID];
    private boolean hasTemplate=false;
    private float pendingX=-1,pendingY=-1;

    private byte[] gray;
    private byte[] prevGray;
    private boolean havePrev=false;

    private long lastNs=0,lastFrameNs=0,lastFlowNs=0;

    public synchronized void setImu(ImuCompensator compensator){imu=compensator;}

    public synchronized void requestLock(float nx,float ny){
        pendingX=clamp(nx);pendingY=clamp(ny);
        acquiring=true;locked=false;confidence=0;lostFrames=0;
        vx=vy=0;hasTemplate=false;
        renderX=predX=pendingX;renderY=predY=pendingY;
        targetName="manual";
        if(imu!=null)imu.resetMotion();
    }

    public synchronized void clear(){
        locked=false;acquiring=false;hasTemplate=false;
        confidence=0;lostFrames=0;vx=vy=0;
        pendingX=pendingY=-1;
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
            if(imu!=null){
                float[] raw=imu.consumeAnalysisRawShift();
                imu.updateCalibration(raw[0],raw[1],vm.dx,vm.dy,vm.quality);
                float[] scaled=imu.scaleRaw(raw[0],raw[1]);
                imuDx=scaled[0];imuDy=scaled[1];
            }

            float fw=computeFlowWeight(vm.quality,imu==null?0:imu.angularSpeedDeg);
            float iw=1f-fw;
            float fusedDx=imuDx*iw+vm.dx*fw;
            float fusedDy=imuDy*iw+vm.dy*fw;
            imuWeight=iw;flowWeight=fw;cameraDx=fusedDx;cameraDy=fusedDy;

            if(locked){
                applyCameraShift(fusedDx,fusedDy);
                if(Math.abs(imuDx)+Math.abs(imuDy)>.00002f)imuCorrections++;
                if(fw>.08f&&vm.quality>.25f)flowCorrections++;
            }

            float px,py;
            synchronized(this){px=pendingX;py=pendingY;}
            if(px>=0&&py>=0){
                int cx=Math.round(px*(w-1)),cy=Math.round(py*(h-1));
                if(extractTemplate(gray,w,h,cx,cy,template)){
                    synchronized(this){
                        x=predX=renderX=px;
                        y=predY=renderY=py;
                        vx=vy=0;
                        confidence=.82f;
                        locked=true;acquiring=false;hasTemplate=true;
                        pendingX=pendingY=-1;lostFrames=0;
                        lastNs=System.nanoTime();
                        if(imu!=null)imu.resetMotion();
                    }
                }
                copyCurrentToPrev(w*h);
                return;
            }

            if(hasTemplate&&locked){
                int cx=Math.round(predX*(w-1)),cy=Math.round(predY*(h-1));
                int radius=Math.min(70,22+lostFrames*8+(imu!=null&&imu.angularSpeedDeg>55f?10:0));
                Match coarse=search(gray,w,h,cx,cy,radius,3);
                Match best=coarse==null?null:search(gray,w,h,coarse.x,coarse.y,6,1);

                if(best!=null){
                    float q=scoreToConfidence(best.score);
                    if(q>=.22f){
                        updateMeasurement(best.x/(float)(w-1),best.y/(float)(h-1),q);
                        lostFrames=0;
                        if(q>.62f)adaptTemplate(gray,w,h,best.x,best.y,.035f);
                    }else{
                        lostFrames++;
                        coast();
                    }
                }else{
                    lostFrames++;
                    coast();
                }
                if(lostFrames>18||confidence<.08f)clear();
            }

            copyCurrentToPrev(w*h);
        }finally{
            latencyMs=(System.nanoTime()-started)/1_000_000f;
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
                if(locked&&Math.abs(nx-tx)<ex&&Math.abs(ny-ty)<ey)continue;
                FlowMatch m=matchPatch(prev,cur,w,h,xx,yy);
                if(m!=null&&m.score<22f){
                    dxs[count]=m.dx;
                    dys[count]=m.dy;
                    scoreSum+=m.score;
                    count++;
                }
            }
        }

        if(count<5)return Motion.NONE;
        float mdx=median(dxs,count),mdy=median(dys,count);
        int inliers=0;
        float spread=0;
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

    private Match search(byte[] g,int w,int h,int cx,int cy,int radius,int step){
        Match best=null;
        int minX=Math.max(HALF_SPAN+2,cx-radius),maxX=Math.min(w-HALF_SPAN-3,cx+radius);
        int minY=Math.max(HALF_SPAN+2,cy-radius),maxY=Math.min(h-HALF_SPAN-3,cy+radius);
        for(int yy=minY;yy<=maxY;yy+=step){
            for(int xx=minX;xx<=maxX;xx+=step){
                float s=sadScore(g,w,h,xx,yy);
                if(best==null||s<best.score)best=new Match(xx,yy,s);
            }
        }
        return best;
    }

    private float sadScore(byte[] g,int w,int h,int cx,int cy){
        float sum=0;
        int k=0;
        for(int gy=0;gy<GRID;gy++){
            int yy=cy+((gy-GRID/2)*HALF_SPAN*2)/(GRID-1);
            for(int gx=0;gx<GRID;gx++,k++){
                int xx=cx+((gx-GRID/2)*HALF_SPAN*2)/(GRID-1);
                sum+=Math.abs((g[yy*w+xx]&255)-template[k]);
            }
        }
        return sum/(GRID*GRID);
    }

    private boolean extractTemplate(byte[] g,int w,int h,int cx,int cy,float[] out){
        if(cx<HALF_SPAN||cy<HALF_SPAN||cx>=w-HALF_SPAN||cy>=h-HALF_SPAN)return false;
        int k=0;
        for(int gy=0;gy<GRID;gy++){
            int yy=cy+((gy-GRID/2)*HALF_SPAN*2)/(GRID-1);
            for(int gx=0;gx<GRID;gx++,k++){
                int xx=cx+((gx-GRID/2)*HALF_SPAN*2)/(GRID-1);
                out[k]=g[yy*w+xx]&255;
            }
        }
        return true;
    }

    private void adaptTemplate(byte[] g,int w,int h,int cx,int cy,float alpha){
        if(cx<HALF_SPAN||cy<HALF_SPAN||cx>=w-HALF_SPAN||cy>=h-HALF_SPAN)return;
        int k=0;
        for(int gy=0;gy<GRID;gy++){
            int yy=cy+((gy-GRID/2)*HALF_SPAN*2)/(GRID-1);
            for(int gx=0;gx<GRID;gx++,k++){
                int xx=cx+((gx-GRID/2)*HALF_SPAN*2)/(GRID-1);
                float v=g[yy*w+xx]&255;
                template[k]=(1f-alpha)*template[k]+alpha*v;
            }
        }
    }

    private float scoreToConfidence(float sad){return clamp(1f-sad/48f);}

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
        long now=System.nanoTime();
        float dt=lastNs==0?.016f:Math.max(.004f,Math.min(.12f,(now-lastNs)/1e9f));
        lastNs=now;

        float mx=(nx-x)/dt,my=(ny-y)/dt;
        float va=q>.65f?.32f:.20f;
        vx=(1f-va)*vx+va*mx;
        vy=(1f-va)*vy+va*my;

        float pa=q>.65f?.50f:.34f;
        x=(1f-pa)*x+pa*nx;
        y=(1f-pa)*y+pa*ny;

        float lead=.050f;
        predX=clamp(x+vx*lead);predY=clamp(y+vy*lead);
        renderX=predX;renderY=predY;
        confidence=.70f*confidence+.30f*q;
        locked=true;
    }

    private synchronized void coast(){
        if(!locked)return;
        long now=System.nanoTime();
        float dt=lastNs==0?.016f:Math.min(.07f,(now-lastNs)/1e9f);
        predX=clamp(x+vx*(dt+.050f));
        predY=clamp(y+vy*(dt+.050f));
        renderX=predX;renderY=predY;
        confidence*=.92f;
    }

    public synchronized void predictOnly(){
        if(!locked)return;
        float dx=0,dy=0;
        if(imu!=null){
            float[] d=imu.peekDisplayAppliedShift();
            dx=d[0];dy=d[1];
        }
        renderX=clamp(predX+dx);
        renderY=clamp(predY+dy);
    }

    public void onYoloDetection(YoloDetector.Detection d){}

    private static float median(float[] a,int n){
        float[] c=Arrays.copyOf(a,n);
        Arrays.sort(c);
        return (n&1)==1?c[n/2]:(c[n/2-1]+c[n/2])*.5f;
    }

    private static float clamp(float v){return Math.max(0,Math.min(1,v));}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    private static final class Match{
        final int x,y;final float score;
        Match(int x,int y,float s){this.x=x;this.y=y;this.score=s;}
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
