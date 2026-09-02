package com.tsss.targetlock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraPreview extends TextureView implements TextureView.SurfaceTextureListener {
    private CameraDevice camera;
    private CameraCaptureSession session;
    private HandlerThread thread;
    private Handler bg;
    private boolean opening=false;
    private long lastSensorTs=0;
    private int sensorOrientation=90;

    private static final int ANALYSIS_H=288;
    private final ExecutorService trackerExecutor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean trackerBusy=new AtomicBoolean(false);
    private Bitmap trackerBitmap;
    private int[] trackerPixels;
    private int analysisW=384;
    private int analysisH=ANALYSIS_H;
    private long lastTrackCaptureNs=0;
    private long lastTrackDoneNs=0;
    private long lastAppliedYoloSerial=0;
    private long analysisFrameCounter=0;

    public volatile String status="INIT";
    public volatile String lastError="";
    public volatile String cameraId="?";
    public volatile int rearCameraCount=0;
    public volatile float selectedHfovDeg=0f;
    public volatile int requestedFps=30;
    public volatile float cameraFps=0f;
    public volatile boolean highSpeed120Supported=false;
    public volatile Size previewSize=new Size(0,0);
    public volatile int displayRotation=0;
    public volatile float analysisGrabFps=0f;
    public volatile boolean detectEnabled=true;
    public volatile boolean trackEnabled=true;
    public volatile boolean imuEnabled=false;
    public volatile boolean flowEnabled=false;
    public volatile boolean autoReacqEnabled=true;

    private final TargetTracker tracker;
    private final ImuCompensator imu;
    private final YoloDetector detector;

    public TargetTracker getTracker(){return tracker;}
    public ImuCompensator getImu(){return imu;}
    public YoloDetector getDetector(){return detector;}
    public int getAnalysisW(){return analysisW;}
    public int getAnalysisH(){return analysisH;}

    public void setDetectEnabled(boolean v){
        detectEnabled=v;
        if(!v)detector.clearDetections();
    }
    public void setTrackEnabled(boolean v){
        trackEnabled=v;
        if(!v)tracker.clear();
    }
    public void setImuEnabled(boolean v){
        imuEnabled=v;
        tracker.setUseImu(v);
    }
    public void setFlowEnabled(boolean v){
        flowEnabled=v;
        tracker.setUseFlow(v);
    }
    public void setAutoReacqEnabled(boolean v){
        autoReacqEnabled=v;
        tracker.setAutoReacq(v);
    }
    public void clearTarget(){tracker.clear();}

    public CameraPreview(Context c){
        super(c);
        setOpaque(true);
        tracker=new TargetTracker();
        imu=new ImuCompensator(c);
        tracker.setImu(imu);
        detector=new YoloDetector(c);
        tracker.setUseImu(false);
        tracker.setUseFlow(false);
        tracker.setAutoReacq(true);
        setSurfaceTextureListener(this);
    }

    public void start(){
        try{
            if(imuEnabled&&!imu.active)imu.start();
            if(isAvailable())open();
            else status="WAIT SURFACE";
        }catch(Throwable t){fail("start",t);}
    }

    public void stop(){
        try{imu.stop();}catch(Throwable ignored){}
        try{if(session!=null)session.close();}catch(Throwable ignored){}
        try{if(camera!=null)camera.close();}catch(Throwable ignored){}
        session=null;camera=null;opening=false;
        lastSensorTs=0;cameraFps=0;
        trackerBusy.set(false);
        lastTrackCaptureNs=0;lastTrackDoneNs=0;
        analysisFrameCounter=0;
        lastAppliedYoloSerial=0;
        try{tracker.clear();}catch(Throwable ignored){}
        if(thread!=null){
            try{thread.quitSafely();}catch(Throwable ignored){}
            thread=null;bg=null;
        }
        status="STOPPED";
    }

    private void open(){
        if(opening||camera!=null)return;
        opening=true;
        try{
            if(thread==null){
                thread=new HandlerThread("target-lock-camera");
                thread.start();
                bg=new Handler(thread.getLooper());
            }

            CameraManager m=(CameraManager)getContext().getSystemService(Context.CAMERA_SERVICE);
            cameraId=chooseMainRearCamera(m);
            CameraCharacteristics ch=m.getCameraCharacteristics(cameraId);

            Integer so=ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation=so==null?90:so;

            configureFov(ch);
            inspectCapabilities(ch);
            previewSize=choosePreviewSize(ch);
            requestedFps=chooseRequestedFps(ch);

            post(this::applyPreviewTransform);

            if(getContext().checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                opening=false;status="NO CAMERA PERMISSION";return;
            }

            status="OPEN CAMERA "+cameraId;
            m.openCamera(cameraId,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice c){
                    camera=c;opening=false;
                    createPreviewOnlySession(ch);
                }
                @Override public void onDisconnected(CameraDevice c){
                    try{c.close();}catch(Throwable ignored){}
                    camera=null;opening=false;status="CAMERA DISCONNECTED";
                }
                @Override public void onError(CameraDevice c,int e){
                    try{c.close();}catch(Throwable ignored){}
                    camera=null;opening=false;
                    lastError="CameraDevice error "+e;
                    status="CAMERA ERROR";
                }
            },bg);
        }catch(Throwable t){
            opening=false;
            fail("open",t);
        }
    }

    private String chooseMainRearCamera(CameraManager m)throws CameraAccessException{
        String[] ids=m.getCameraIdList();
        if(ids.length==0)throw new IllegalStateException("No camera IDs");

        String bestId=null;
        double bestScore=Double.MAX_VALUE;
        int count=0;

        for(String id:ids){
            CameraCharacteristics ch=m.getCameraCharacteristics(id);
            Integer facing=ch.get(CameraCharacteristics.LENS_FACING);
            if(facing==null||facing!=CameraCharacteristics.LENS_FACING_BACK)continue;
            count++;

            float hfov=estimateHorizontalFov(ch);
            Integer level=ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);

            double score=Math.abs(hfov-72.0);
            if(hfov<=0)score+=50;
            if(hfov>95)score+=35;
            if(hfov<48)score+=35;
            if(level!=null&&level==CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY)score+=20;

            int[] caps=ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if(caps!=null){
                for(int cap:caps){
                    if(cap==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA){
                        score-=6;
                        break;
                    }
                }
            }

            if(score<bestScore){
                bestScore=score;
                bestId=id;
                selectedHfovDeg=hfov;
            }
        }

        rearCameraCount=count;
        if(bestId!=null)return bestId;
        return ids[0];
    }

    private float estimateHorizontalFov(CameraCharacteristics ch){
        try{
            SizeF physical=ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focal=ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if(physical==null||focal==null||focal.length==0||focal[0]<=0)return 0f;
            float minF=focal[0];
            for(float f:focal)if(f>0&&f<minF)minF=f;
            return (float)Math.toDegrees(2.0*Math.atan(physical.getWidth()/(2.0*minF)));
        }catch(Throwable t){return 0f;}
    }

    private Size choosePreviewSize(CameraCharacteristics ch){
        try{
            StreamConfigurationMap map=ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes=map==null?null:map.getOutputSizes(SurfaceTexture.class);
            if(sizes==null||sizes.length==0)return new Size(1280,720);

            // Prefer a standard 16:9 camera stream. Never stretch the stream to the phone screen ratio.
            for(Size s:sizes)if(s.getWidth()==1280&&s.getHeight()==720)return s;
            for(Size s:sizes)if(s.getWidth()==1920&&s.getHeight()==1080)return s;

            Size best=null;
            double bestScore=Double.MAX_VALUE;
            final float target=16f/9f;
            for(Size s:sizes){
                int sw=s.getWidth(),sh=s.getHeight();
                if(sw<640||sh<360)continue;
                if(sw>1920||sh>1080)continue;
                float ratio=sw/(float)sh;
                double score=Math.abs(ratio-target)*4000.0+
                        Math.abs(sw-1280)*0.12+Math.abs(sh-720)*0.12;
                if(score<bestScore){bestScore=score;best=s;}
            }
            if(best!=null)return best;

            best=sizes[0];
            for(Size s:sizes){
                if((long)s.getWidth()*s.getHeight()<(long)best.getWidth()*best.getHeight())best=s;
            }
            return best;
        }catch(Throwable t){return new Size(1280,720);}
    }

    private int chooseRequestedFps(CameraCharacteristics ch){
        try{
            Range<Integer>[] rs=ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if(rs==null||rs.length==0)return 30;
            int best=30;
            for(Range<Integer> r:rs){
                int hi=r.getUpper();
                if(hi<=60)best=Math.max(best,hi);
            }
            return best;
        }catch(Throwable t){return 30;}
    }

    private Range<Integer> chooseNormalRange(CameraCharacteristics ch,int target){
        try{
            Range<Integer>[] rs=ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if(rs==null||rs.length==0)return null;
            Range<Integer> best=null;
            for(Range<Integer> r:rs){
                if(r.getUpper()>60)continue;
                if(r.getUpper()>=target){
                    if(best==null||r.getLower()>best.getLower())best=r;
                }
            }
            if(best!=null)return best;
            for(Range<Integer> r:rs){
                if(r.getUpper()<=60&&(best==null||r.getUpper()>best.getUpper()))best=r;
            }
            return best;
        }catch(Throwable t){return null;}
    }

    private void configureFov(CameraCharacteristics c){
        try{
            SizeF physical=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focal=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if(physical!=null&&focal!=null&&focal.length>0&&focal[0]>0){
                float hf=(float)Math.toDegrees(2*Math.atan(physical.getWidth()/(2*focal[0])));
                float vf=(float)Math.toDegrees(2*Math.atan(physical.getHeight()/(2*focal[0])));
                imu.setFovDegrees(hf,vf);
            }
        }catch(Throwable ignored){}
    }

    private void inspectCapabilities(CameraCharacteristics c){
        highSpeed120Supported=false;
        try{
            int[] caps=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean hs=false;
            if(caps!=null){
                for(int v:caps){
                    if(v==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO){
                        hs=true;break;
                    }
                }
            }
            if(!hs)return;

            StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map==null)return;
            Size[] sizes=map.getHighSpeedVideoSizes();
            if(sizes==null)return;
            for(Size s:sizes){
                Range<Integer>[] rs=map.getHighSpeedVideoFpsRangesFor(s);
                if(rs!=null){
                    for(Range<Integer> r:rs){
                        if(r.getUpper()>=120){highSpeed120Supported=true;return;}
                    }
                }
            }
        }catch(Throwable ignored){}
    }

    private void createPreviewOnlySession(CameraCharacteristics ch){
        try{
            SurfaceTexture st=getSurfaceTexture();
            if(st==null){status="NO SURFACE";return;}

            st.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());
            post(this::applyPreviewTransform);

            Surface preview=new Surface(st);
            CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(preview);
            b.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
            try{b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);}catch(Throwable ignored){}
            try{b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);}catch(Throwable ignored){}

            Range<Integer> fps=chooseNormalRange(ch,requestedFps);
            if(fps!=null){
                requestedFps=fps.getUpper();
                try{b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps);}catch(Throwable ignored){}
            }

            status="CONFIG PREVIEW "+previewSize.getWidth()+"x"+previewSize.getHeight();
            camera.createCaptureSession(Collections.singletonList(preview),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession cs){
                    session=cs;
                    try{
                        session.setRepeatingRequest(b.build(),new CameraCaptureSession.CaptureCallback(){
                            @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest req,TotalCaptureResult result){
                                Long ts=result.get(CaptureResult.SENSOR_TIMESTAMP);
                                if(ts!=null&&ts>0){
                                    if(lastSensorTs!=0&&ts>lastSensorTs){
                                        float f=1e9f/(ts-lastSensorTs);
                                        if(f>0&&f<240)cameraFps=cameraFps==0?f:.92f*cameraFps+.08f*f;
                                    }
                                    lastSensorTs=ts;
                                }
                            }
                        },bg);
                        status="PREVIEW OK";
                        try{detector.start();}catch(Throwable ignored){}
                    }catch(Throwable t){fail("repeat",t);}
                }

                @Override public void onConfigureFailed(CameraCaptureSession cs){
                    lastError="Preview-only session failed";
                    status="SESSION ERROR";
                }
            },bg);

        }catch(Throwable t){fail("preview",t);}
    }

    private void applyPreviewTransform(){
        try{
            if(previewSize.getWidth()<=0||previewSize.getHeight()<=0||getWidth()<=0||getHeight()<=0)return;

            int viewW=getWidth();
            int viewH=getHeight();
            int rotation=getDisplay()==null?Surface.ROTATION_0:getDisplay().getRotation();
            displayRotation=rotation;

            Matrix matrix=new Matrix();
            RectF viewRect=new RectF(0,0,viewW,viewH);
            float centerX=viewRect.centerX();
            float centerY=viewRect.centerY();

            // Standard Camera2 TextureView transform: rotate sensor preview, then center-crop.
            if(rotation==Surface.ROTATION_90||rotation==Surface.ROTATION_270){
                RectF bufferRect=new RectF(0,0,previewSize.getHeight(),previewSize.getWidth());
                bufferRect.offset(centerX-bufferRect.centerX(),centerY-bufferRect.centerY());
                matrix.setRectToRect(viewRect,bufferRect,Matrix.ScaleToFit.FILL);

                float scale=Math.max(
                        viewH/(float)previewSize.getHeight(),
                        viewW/(float)previewSize.getWidth());
                matrix.postScale(scale,scale,centerX,centerY);

                float degrees=(rotation==Surface.ROTATION_90)?-90f:90f;
                if(sensorOrientation==270)degrees=-degrees;
                matrix.postRotate(degrees,centerX,centerY);
            }else{
                float srcW=previewSize.getWidth();
                float srcH=previewSize.getHeight();
                float scale=Math.max(viewW/srcW,viewH/srcH);
                matrix.postScale(scale,scale,centerX,centerY);
                if(rotation==Surface.ROTATION_180)matrix.postRotate(180f,centerX,centerY);
            }

            setTransform(matrix);
        }catch(Throwable t){
            lastError="transform: "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
        }
    }

    private void fail(String where,Throwable t){
        lastError=where+": "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
        status="ERROR";
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){
        postDelayed(this::start,200);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){
        applyPreviewTransform();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){stop();return true;}

    @Override public void onSurfaceTextureUpdated(SurfaceTexture s){
        try{tracker.predictOnly();}catch(Throwable ignored){}
        captureTrackerFrame();
    }

    public void selectTargetAt(float nx,float ny){
        nx=Math.max(0f,Math.min(1f,nx));
        ny=Math.max(0f,Math.min(1f,ny));
        try{
            YoloDetector.Detection d=detectEnabled?detector.pick(nx,ny):null;
            if(d!=null&&trackEnabled){
                tracker.requestDetectedLock(d);
            }else if(trackEnabled){
                tracker.requestLock(nx,ny);
            }
        }catch(Throwable t){
            if(trackEnabled)tracker.requestLock(nx,ny);
            lastError="select: "+t.getClass().getSimpleName();
        }
    }

    private void captureTrackerFrame(){
        if(!"PREVIEW OK".equals(status))return;

        long now=System.nanoTime();
        // Keep visual analysis near 60 Hz max. IMU/HUD prediction remains 120 Hz.
        if(now-lastTrackCaptureNs<16_000_000L)return;
        if(!trackerBusy.compareAndSet(false,true))return;
        lastTrackCaptureNs=now;

        try{
            float aspect=getHeight()>0?getWidth()/(float)getHeight():2.0f;
            int desiredW=Math.max(320,Math.min(640,Math.round(ANALYSIS_H*aspect)));
            desiredW=(desiredW/2)*2;
            analysisW=desiredW;
            analysisH=ANALYSIS_H;

            if(trackerBitmap==null||trackerBitmap.isRecycled()||
                    trackerBitmap.getWidth()!=analysisW||trackerBitmap.getHeight()!=analysisH){
                trackerBitmap=Bitmap.createBitmap(analysisW,analysisH,Bitmap.Config.ARGB_8888);
                trackerPixels=new int[analysisW*analysisH];
            }

            Bitmap out=getBitmap(trackerBitmap);
            if(out==null){
                trackerBusy.set(false);
                return;
            }
            out.getPixels(trackerPixels,0,analysisW,0,0,analysisW,analysisH);

            try{
                analysisFrameCounter++;

                if(detectEnabled){
                    // KCF Hybrid:
                    // - SEARCH: detector cadence is relaxed.
                    // - LOCK: verify roughly every third analysis frame.
                    // - COAST/REACQUIRE: detector runs as soon as it becomes free.
                    boolean tracking=tracker.locked||tracker.coasting||tracker.reacquiring;
                    boolean submit;
                    long intervalMs;

                    if(tracker.coasting||tracker.reacquiring){
                        submit=autoReacqEnabled;
                        intervalMs=0;
                        detector.setConfidenceThreshold(.18f);
                    }else if(tracker.locked){
                        submit=(analysisFrameCounter%3)==0;
                        intervalMs=0;
                        detector.setConfidenceThreshold(.24f);
                    }else{
                        submit=true;
                        intervalMs=80;
                        detector.setConfidenceThreshold(.28f);
                    }

                    if(submit){
                        detector.maybeSubmit(trackerPixels,analysisW,analysisH,intervalMs);
                    }

                    long serial=detector.resultSerial;
                    boolean canAssociate=tracker.locked||
                            (autoReacqEnabled&&(tracker.coasting||tracker.reacquiring));

                    if(canAssociate&&serial!=0&&serial!=lastAppliedYoloSerial){
                        YoloDetector.Detection best=null;
                        float bestScore=Float.MAX_VALUE;

                        for(YoloDetector.Detection d:detector.getDetections()){
                            float s=tracker.associationScore(d);
                            if(s<bestScore){bestScore=s;best=d;}
                        }

                        if(best!=null&&bestScore<Float.MAX_VALUE){
                            tracker.onYoloDetection(best);
                        }else{
                            tracker.onYoloMiss();
                        }
                        lastAppliedYoloSerial=serial;
                    }
                }else{
                    detector.clearDetections();
                }
            }catch(Throwable t){
                lastError="yolo submit: "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
            }

            trackerExecutor.execute(()->{
                try{
                    if(trackEnabled)tracker.processArgb(trackerPixels,analysisW,analysisH);
                    long done=System.nanoTime();
                    if(lastTrackDoneNs!=0){
                        float f=1e9f/Math.max(1,done-lastTrackDoneNs);
                        analysisGrabFps=analysisGrabFps==0?f:.86f*analysisGrabFps+.14f*f;
                    }
                    lastTrackDoneNs=done;
                }catch(Throwable t){
                    lastError="tracker: "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
                }finally{
                    trackerBusy.set(false);
                }
            });
        }catch(Throwable t){
            lastError="bitmap: "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
            trackerBusy.set(false);
        }
    }
}
