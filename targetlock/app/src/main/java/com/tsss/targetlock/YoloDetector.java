package com.tsss.targetlock;

import android.content.Context;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class YoloDetector {
    private static final int IN=640;
    private static final float CONF=.28f;
    private static final float IOU=.45f;

    public volatile boolean ready=false;
    public volatile boolean started=false;
    public volatile String error="";
    public volatile float detectorFps=0f,latencyMs=0f;
    public volatile String backend="CPU";
    public volatile int detectionCount=0;
    public volatile long resultSerial=0;
    public volatile String outputShapeText="?";
    public volatile String decoderMode="?";

    private final Context context;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean(false);

    private Interpreter interpreter;
    private ByteBuffer input;
    private Object outputTensor;
    private float[][][] outChannelsFirst;
    private float[][][] outBoxesFirst;
    private boolean channelsFirst=true;
    private int channels=84;
    private int boxes=8400;
    private int classes=80;
    private long lastDoneNs=0;

    private volatile List<Detection> detections=Collections.emptyList();

    private static final String[] NAMES={
        "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat","traffic light",
        "fire hydrant","stop sign","parking meter","bench","bird","cat","dog","horse","sheep","cow",
        "elephant","bear","zebra","giraffe","backpack","umbrella","handbag","tie","suitcase","frisbee",
        "skis","snowboard","sports ball","kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket","bottle",
        "wine glass","cup","fork","knife","spoon","bowl","banana","apple","sandwich","orange",
        "broccoli","carrot","hot dog","pizza","donut","cake","chair","couch","potted plant","bed",
        "dining table","toilet","tv","laptop","mouse","remote","keyboard","cell phone","microwave","oven",
        "toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear","hair drier","toothbrush"
    };

    public YoloDetector(Context c){
        context=c.getApplicationContext();
    }

    public void start(){
        if(started)return;
        started=true;
        executor.execute(()->{
            try{
                MappedByteBuffer model=loadModel();
                Interpreter.Options o=new Interpreter.Options();
                o.setNumThreads(Math.max(2,Math.min(4,Runtime.getRuntime().availableProcessors()-1)));
                interpreter=new Interpreter(model,o);

                int[] shape=interpreter.getOutputTensor(0).shape();
                outputShapeText=shapeToString(shape);
                if(shape.length!=3||shape[0]!=1)throw new IllegalStateException("Unexpected output "+outputShapeText);

                // Supports both [1,84,8400] and [1,8400,84].
                if(shape[1]<=256&&shape[2]>shape[1]){
                    channelsFirst=true;
                    channels=shape[1];
                    boxes=shape[2];
                    outChannelsFirst=new float[1][channels][boxes];
                    outputTensor=outChannelsFirst;
                }else{
                    channelsFirst=false;
                    boxes=shape[1];
                    channels=shape[2];
                    outBoxesFirst=new float[1][boxes][channels];
                    outputTensor=outBoxesFirst;
                }

                classes=Math.min(NAMES.length,Math.max(1,channels-4));
                input=ByteBuffer.allocateDirect(IN*IN*3*4).order(ByteOrder.nativeOrder());
                ready=true;
                error="";
            }catch(Throwable t){
                error=t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
                ready=false;
            }
        });
    }

    public boolean isBusy(){return busy.get();}
    public List<Detection> getDetections(){return detections;}

    public void clearDetections(){
        detections=Collections.emptyList();
        detectionCount=0;
    }

    public void maybeSubmit(int[] pixels,int w,int h,long minIntervalMs){
        if(!started)start();
        if(!ready||interpreter==null||pixels==null||busy.get()||w<2||h<2)return;

        long now=System.nanoTime();
        if(lastDoneNs!=0&&(now-lastDoneNs)<minIntervalMs*1_000_000L)return;

        final int[] copy=pixels.clone();
        if(!busy.compareAndSet(false,true))return;

        executor.execute(()->{
            long t0=System.nanoTime();
            try{
                Letterbox lb=preprocess(copy,w,h);
                interpreter.run(input,outputTensor);
                detections=postprocess(lb);
                detectionCount=detections.size();
                resultSerial++;

                long done=System.nanoTime();
                latencyMs=(done-t0)/1_000_000f;
                if(lastDoneNs!=0){
                    float f=1e9f/Math.max(1,done-lastDoneNs);
                    detectorFps=detectorFps==0?f:.8f*detectorFps+.2f*f;
                }
                lastDoneNs=done;
            }catch(Throwable t){
                error=t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
            }finally{
                busy.set(false);
            }
        });
    }

    private Letterbox preprocess(int[] px,int w,int h){
        input.rewind();

        float scale=Math.min(IN/(float)w,IN/(float)h);
        int nw=Math.max(1,Math.round(w*scale));
        int nh=Math.max(1,Math.round(h*scale));
        int padX=(IN-nw)/2;
        int padY=(IN-nh)/2;
        final float pad=114f/255f;

        for(int y=0;y<IN;y++){
            for(int x=0;x<IN;x++){
                int sx=x-padX,sy=y-padY;
                if(sx>=0&&sy>=0&&sx<nw&&sy<nh){
                    int ox=Math.min(w-1,Math.max(0,(int)(sx/scale)));
                    int oy=Math.min(h-1,Math.max(0,(int)(sy/scale)));
                    int c=px[oy*w+ox];
                    input.putFloat(((c>>16)&255)/255f);
                    input.putFloat(((c>>8)&255)/255f);
                    input.putFloat((c&255)/255f);
                }else{
                    input.putFloat(pad);input.putFloat(pad);input.putFloat(pad);
                }
            }
        }
        input.rewind();
        return new Letterbox(w,h,nw,nh,padX,padY,scale);
    }

    private List<Detection> postprocess(Letterbox lb){
        ArrayList<Detection> raw=new ArrayList<>();

        // Some LiteRT exports return xywh in pixels (0..640), others normalized (0..1).
        // Detect the convention from candidate geometry rather than assuming one.
        float geometryMax=0f;
        int probe=Math.min(boxes,128);
        for(int i=0;i<probe;i++){
            geometryMax=Math.max(geometryMax,Math.abs(value(0,i)));
            geometryMax=Math.max(geometryMax,Math.abs(value(1,i)));
            geometryMax=Math.max(geometryMax,Math.abs(value(2,i)));
            geometryMax=Math.max(geometryMax,Math.abs(value(3,i)));
        }
        boolean normalized=geometryMax<=2.5f;
        decoderMode=normalized?"NORM":"PIXEL";

        for(int i=0;i<boxes;i++){
            int bestClass=-1;
            float best=0f;
            for(int c=0;c<classes;c++){
                float s=value(4+c,i);
                if(s>best){best=s;bestClass=c;}
            }
            if(best<CONF||bestClass<0)continue;

            float cx=value(0,i),cy=value(1,i);
            float bw=value(2,i),bh=value(3,i);

            if(normalized){
                cx*=IN;cy*=IN;bw*=IN;bh*=IN;
            }

            float leftPx=cx-bw*.5f;
            float topPx=cy-bh*.5f;
            float rightPx=cx+bw*.5f;
            float bottomPx=cy+bh*.5f;

            // Undo letterbox back to the exact bitmap coordinate system.
            float l=(leftPx-lb.padX)/Math.max(1f,lb.nw);
            float r=(rightPx-lb.padX)/Math.max(1f,lb.nw);
            float t=(topPx-lb.padY)/Math.max(1f,lb.nh);
            float b=(bottomPx-lb.padY)/Math.max(1f,lb.nh);

            if(r<=0||l>=1||b<=0||t>=1)continue;
            l=clamp(l);r=clamp(r);t=clamp(t);b=clamp(b);
            if(r-l<.008f||b-t<.008f)continue;

            String name=bestClass<NAMES.length?NAMES[bestClass]:"class"+bestClass;
            raw.add(new Detection(l,t,r,b,best,bestClass,name));
        }

        raw.sort((a,b)->Float.compare(b.confidence,a.confidence));
        ArrayList<Detection> keep=new ArrayList<>();
        for(Detection d:raw){
            boolean suppressed=false;
            for(Detection k:keep){
                if(d.classId==k.classId&&iou(d,k)>IOU){suppressed=true;break;}
            }
            if(!suppressed){
                keep.add(d);
                if(keep.size()>=20)break;
            }
        }
        return keep;
    }

    private float value(int channel,int box){
        if(channel<0||channel>=channels||box<0||box>=boxes)return 0f;
        return channelsFirst?outChannelsFirst[0][channel][box]:outBoxesFirst[0][box][channel];
    }

    public Detection pick(float nx,float ny){
        Detection best=null;
        float bestScore=Float.MAX_VALUE;

        for(Detection d:detections){
            float margin=.025f;
            boolean inside=nx>=d.left-margin&&nx<=d.right+margin&&ny>=d.top-margin&&ny<=d.bottom+margin;
            float dx=nx-d.cx(),dy=ny-d.cy();
            float dist=dx*dx+dy*dy;
            if(inside)dist*=.05f;
            if(dist<bestScore){bestScore=dist;best=d;}
        }
        return bestScore<.10f?best:null;
    }

    private MappedByteBuffer loadModel()throws Exception{
        android.content.res.AssetFileDescriptor afd=context.getAssets().openFd("yolo26n_w8a32.tflite");
        try(FileInputStream fis=new FileInputStream(afd.getFileDescriptor());
            FileChannel ch=fis.getChannel()){
            return ch.map(FileChannel.MapMode.READ_ONLY,afd.getStartOffset(),afd.getDeclaredLength());
        }
    }

    private static float iou(Detection a,Detection b){
        float l=Math.max(a.left,b.left),t=Math.max(a.top,b.top);
        float r=Math.min(a.right,b.right),bt=Math.min(a.bottom,b.bottom);
        float iw=Math.max(0,r-l),ih=Math.max(0,bt-t);
        float inter=iw*ih;
        float ua=(a.right-a.left)*(a.bottom-a.top);
        float ub=(b.right-b.left)*(b.bottom-b.top);
        return inter/Math.max(.00001f,ua+ub-inter);
    }

    private static String shapeToString(int[] s){
        StringBuilder b=new StringBuilder("[");
        for(int i=0;i<s.length;i++){
            if(i>0)b.append(',');
            b.append(s[i]);
        }
        return b.append(']').toString();
    }

    private static float clamp(float v){return Math.max(0f,Math.min(1f,v));}

    private static final class Letterbox{
        final int w,h,nw,nh,padX,padY;
        final float scale;
        Letterbox(int w,int h,int nw,int nh,int padX,int padY,float scale){
            this.w=w;this.h=h;this.nw=nw;this.nh=nh;this.padX=padX;this.padY=padY;this.scale=scale;
        }
    }

    public static final class Detection{
        public final float left,top,right,bottom,confidence;
        public final int classId;
        public final String className;

        public Detection(float l,float t,float r,float b,float conf,int id,String name){
            left=l;top=t;right=r;bottom=b;confidence=conf;classId=id;className=name;
        }

        public float cx(){return (left+right)*.5f;}
        public float cy(){return (top+bottom)*.5f;}
        public float halfBox(){return Math.max(right-left,bottom-top)*.5f;}
    }
}
