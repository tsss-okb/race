package com.tsss.targetlock;

import android.content.Context;

import org.tensorflow.lite.DataType;
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
    private static final float IOU=.45f;

    public volatile boolean ready=false;
    public volatile boolean started=false;
    public volatile String error="";
    public volatile float detectorFps=0f,latencyMs=0f;
    public volatile String backend="XNNPACK";
    public volatile int detectionCount=0;
    public volatile long resultSerial=0;
    public volatile String outputShapeText="?";
    public volatile String decoderMode="?";
    public volatile String modelMode="?";
    public volatile String tensorTypes="?";
    public volatile float confidenceThreshold=.28f;

    private final Context context;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean(false);

    private Interpreter interpreter;
    private ByteBuffer input;
    private ByteBuffer output;

    private int inputW=640,inputH=640;
    private boolean inputNchw=false;

    private DataType inputType=DataType.FLOAT32;
    private DataType outputType=DataType.FLOAT32;
    private float inputScale=1f,inputZero=0f;
    private float outputScale=1f,outputZero=0f;

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
            Throwable int8Failure=null;
            try{
                loadInterpreter("yolo26n_int8.tflite","INT8");
                ready=true;
                error="";
                return;
            }catch(Throwable t){
                int8Failure=t;
                closeInterpreter();
            }

            try{
                loadInterpreter("yolo26n_w8a32.tflite","W8A32");
                ready=true;
                error="INT8 fallback: "+int8Failure.getClass().getSimpleName();
            }catch(Throwable t){
                closeInterpreter();
                error="INT8 "+int8Failure.getClass().getSimpleName()+"; W8A32 "+
                        t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
                ready=false;
            }
        });
    }

    private void loadInterpreter(String assetName,String mode)throws Exception{
        MappedByteBuffer model=loadModel(assetName);

        Interpreter.Options o=new Interpreter.Options();
        o.setNumThreads(Math.max(2,Math.min(4,Runtime.getRuntime().availableProcessors()-1)));
        try{o.setUseXNNPACK(true);}catch(Throwable ignored){}

        interpreter=new Interpreter(model,o);

        int[] inShape=interpreter.getInputTensor(0).shape();
        if(inShape.length!=4||inShape[0]!=1)
            throw new IllegalStateException("Unexpected input "+shapeToString(inShape));

        if(inShape[1]==3){
            inputNchw=true;
            inputH=inShape[2];
            inputW=inShape[3];
        }else{
            inputNchw=false;
            inputH=inShape[1];
            inputW=inShape[2];
        }

        int[] outShape=interpreter.getOutputTensor(0).shape();
        outputShapeText=shapeToString(outShape);
        if(outShape.length!=3||outShape[0]!=1)
            throw new IllegalStateException("Unexpected output "+outputShapeText);

        if(outShape[1]<=256&&outShape[2]>outShape[1]){
            channelsFirst=true;
            channels=outShape[1];
            boxes=outShape[2];
        }else{
            channelsFirst=false;
            boxes=outShape[1];
            channels=outShape[2];
        }

        classes=Math.min(NAMES.length,Math.max(1,channels-4));

        inputType=interpreter.getInputTensor(0).dataType();
        outputType=interpreter.getOutputTensor(0).dataType();

        try{
            inputScale=interpreter.getInputTensor(0).quantizationParams().getScale();
            inputZero=interpreter.getInputTensor(0).quantizationParams().getZeroPoint();
        }catch(Throwable ignored){
            inputScale=1f;inputZero=0f;
        }
        try{
            outputScale=interpreter.getOutputTensor(0).quantizationParams().getScale();
            outputZero=interpreter.getOutputTensor(0).quantizationParams().getZeroPoint();
        }catch(Throwable ignored){
            outputScale=1f;outputZero=0f;
        }

        if(inputScale==0f)inputScale=1f;
        if(outputScale==0f)outputScale=1f;

        int inputElements=inputW*inputH*3;
        int outputElements=channels*boxes;

        input=ByteBuffer.allocateDirect(inputElements*bytesPer(inputType)).order(ByteOrder.nativeOrder());
        output=ByteBuffer.allocateDirect(outputElements*bytesPer(outputType)).order(ByteOrder.nativeOrder());

        modelMode=mode;
        tensorTypes=(inputNchw?"NCHW ":"NHWC ")+inputType.name()+"→"+outputType.name();
        backend="XNNPACK";
    }

    private void closeInterpreter(){
        try{if(interpreter!=null)interpreter.close();}catch(Throwable ignored){}
        interpreter=null;input=null;output=null;
    }

    public boolean isBusy(){return busy.get();}
    public void setConfidenceThreshold(float v){
        confidenceThreshold=Math.max(.10f,Math.min(.60f,v));
    }
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
                output.rewind();
                interpreter.run(input,output);
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

        float scale=Math.min(inputW/(float)w,inputH/(float)h);
        int nw=Math.max(1,Math.round(w*scale));
        int nh=Math.max(1,Math.round(h*scale));
        int padX=(inputW-nw)/2;
        int padY=(inputH-nh)/2;
        final float pad=114f/255f;

        if(!inputNchw){
            for(int y=0;y<inputH;y++){
                int sy=y-padY;
                int oy=(sy>=0&&sy<nh)?Math.min(h-1,Math.max(0,(int)(sy/scale))):-1;

                for(int x=0;x<inputW;x++){
                    int sx=x-padX;
                    if(oy>=0&&sx>=0&&sx<nw){
                        int ox=Math.min(w-1,Math.max(0,(int)(sx/scale)));
                        int c=px[oy*w+ox];
                        putInput(((c>>16)&255)/255f);
                        putInput(((c>>8)&255)/255f);
                        putInput((c&255)/255f);
                    }else{
                        putInput(pad);putInput(pad);putInput(pad);
                    }
                }
            }
        }else{
            for(int channel=0;channel<3;channel++){
                for(int y=0;y<inputH;y++){
                    int sy=y-padY;
                    int oy=(sy>=0&&sy<nh)?Math.min(h-1,Math.max(0,(int)(sy/scale))):-1;

                    for(int x=0;x<inputW;x++){
                        int sx=x-padX;
                        if(oy>=0&&sx>=0&&sx<nw){
                            int ox=Math.min(w-1,Math.max(0,(int)(sx/scale)));
                            int c=px[oy*w+ox];
                            float v=channel==0?((c>>16)&255)/255f:
                                    (channel==1?((c>>8)&255)/255f:(c&255)/255f);
                            putInput(v);
                        }else{
                            putInput(pad);
                        }
                    }
                }
            }
        }

        input.rewind();
        return new Letterbox(w,h,nw,nh,padX,padY,scale,inputW,inputH);
    }

    private void putInput(float v){
        v=Math.max(0f,Math.min(1f,v));
        switch(inputType){
            case FLOAT32:
                input.putFloat(v);
                break;
            case INT8:{
                int q=Math.round(v/inputScale+inputZero);
                q=Math.max(-128,Math.min(127,q));
                input.put((byte)q);
                break;
            }
            case UINT8:{
                int q=Math.round(v/inputScale+inputZero);
                q=Math.max(0,Math.min(255,q));
                input.put((byte)(q&0xFF));
                break;
            }
            default:
                throw new IllegalStateException("Unsupported input "+inputType);
        }
    }

    private List<Detection> postprocess(Letterbox lb){
        ArrayList<Detection> raw=new ArrayList<>();

        float geometryMax=0f;
        int probe=Math.min(boxes,128);
        for(int i=0;i<probe;i++){
            geometryMax=Math.max(geometryMax,Math.abs(value(0,i)));
            geometryMax=Math.max(geometryMax,Math.abs(value(1,i)));
            geometryMax=Math.max(geometryMax,Math.abs(value(2,i)));
            geometryMax=Math.max(geometryMax,Math.abs(value(3,i)));
        }

        boolean normalized=geometryMax<=2.5f;
        decoderMode=(normalized?"NORM":"PIXEL")+" "+modelMode;

        for(int i=0;i<boxes;i++){
            int bestClass=-1;
            float best=0f;

            for(int c=0;c<classes;c++){
                float s=value(4+c,i);
                if(s>best){best=s;bestClass=c;}
            }
            if(best<confidenceThreshold||bestClass<0)continue;

            float cx=value(0,i),cy=value(1,i);
            float bw=value(2,i),bh=value(3,i);

            if(normalized){
                cx*=lb.modelW;cy*=lb.modelH;
                bw*=lb.modelW;bh*=lb.modelH;
            }

            float leftPx=cx-bw*.5f;
            float topPx=cy-bh*.5f;
            float rightPx=cx+bw*.5f;
            float bottomPx=cy+bh*.5f;

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

        int linear=channelsFirst
                ? channel*boxes+box
                : box*channels+channel;

        switch(outputType){
            case FLOAT32:
                return output.getFloat(linear*4);
            case INT8:{
                int q=output.get(linear);
                return (q-outputZero)*outputScale;
            }
            case UINT8:{
                int q=output.get(linear)&0xFF;
                return (q-outputZero)*outputScale;
            }
            default:
                throw new IllegalStateException("Unsupported output "+outputType);
        }
    }

    public Detection pick(float nx,float ny){
        Detection best=null;
        float bestScore=Float.MAX_VALUE;

        for(Detection d:detections){
            float margin=.025f;
            boolean inside=nx>=d.left-margin&&nx<=d.right+margin&&
                    ny>=d.top-margin&&ny<=d.bottom+margin;
            float dx=nx-d.cx(),dy=ny-d.cy();
            float dist=dx*dx+dy*dy;
            if(inside)dist*=.05f;
            if(dist<bestScore){bestScore=dist;best=d;}
        }
        return bestScore<.10f?best:null;
    }

    private MappedByteBuffer loadModel(String assetName)throws Exception{
        android.content.res.AssetFileDescriptor afd=context.getAssets().openFd(assetName);
        try(FileInputStream fis=new FileInputStream(afd.getFileDescriptor());
            FileChannel ch=fis.getChannel()){
            return ch.map(FileChannel.MapMode.READ_ONLY,afd.getStartOffset(),afd.getDeclaredLength());
        }
    }

    private static int bytesPer(DataType t){
        switch(t){
            case FLOAT32:return 4;
            case INT8:
            case UINT8:return 1;
            default:throw new IllegalStateException("Unsupported tensor type "+t);
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
        final int w,h,nw,nh,padX,padY,modelW,modelH;
        final float scale;

        Letterbox(int w,int h,int nw,int nh,int padX,int padY,float scale,int modelW,int modelH){
            this.w=w;this.h=h;this.nw=nw;this.nh=nh;
            this.padX=padX;this.padY=padY;this.scale=scale;
            this.modelW=modelW;this.modelH=modelH;
        }
    }

    public static final class Detection{
        public final float left,top,right,bottom,confidence;
        public final int classId;
        public final String className;

        public Detection(float l,float t,float r,float b,float conf,int id,String name){
            left=l;top=t;right=r;bottom=b;
            confidence=conf;classId=id;className=name;
        }

        public float cx(){return (left+right)*.5f;}
        public float cy(){return (top+bottom)*.5f;}
        public float halfBox(){return Math.max(right-left,bottom-top)*.5f;}
    }
}
