package com.tsss.targetlock;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;

import java.util.*;

public class CameraPreview extends TextureView implements TextureView.SurfaceTextureListener {
    private CameraDevice camera;
    private CameraCaptureSession session;
    private HandlerThread thread;
    private Handler bg;
    private ImageReader analysisReader;
    private boolean opening=false;
    private int frameIndex=0;
    private long lastImageTs=0;

    public volatile String status="INIT";
    public volatile String lastError="";
    public volatile int requestedFps=30;
    public volatile float cameraFps=0f;
    public volatile Size analysisSize=new Size(640,360);

    private final TargetTracker tracker;
    private final YoloDetector detector;
    private final ImuCompensator imu;

    public TargetTracker getTracker(){return tracker;}
    public YoloDetector getDetector(){return detector;}
    public ImuCompensator getImu(){return imu;}

    public CameraPreview(Context c){
        super(c);
        tracker=new TargetTracker();
        imu=new ImuCompensator(c);
        tracker.setImu(imu);
        detector=new YoloDetector(c,tracker);

        setSurfaceTextureListener(this);
        setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_DOWN&&getWidth()>0&&getHeight()>0){
                tracker.requestLock(e.getX()/getWidth(),e.getY()/getHeight());
                return true;
            }
            return false;
        });
    }

    public void start(){
        try{
            status="STARTING";
            if(!imu.active)imu.start();
            if(isAvailable())open();
        }catch(Throwable t){
            fail("start",t);
        }
    }

    public void stop(){
        try{imu.stop();}catch(Throwable ignored){}
        try{if(session!=null)session.close();}catch(Throwable ignored){}
        try{if(camera!=null)camera.close();}catch(Throwable ignored){}
        try{if(analysisReader!=null)analysisReader.close();}catch(Throwable ignored){}
        session=null;camera=null;analysisReader=null;opening=false;
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
            String id=chooseBackCamera(m);
            CameraCharacteristics ch=m.getCameraCharacteristics(id);
            configureFov(ch);
            configureStream(ch);
            if(getContext().checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                opening=false;status="CAMERA PERMISSION";return;
            }
            status="OPENING CAMERA";
            m.openCamera(id,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice c){camera=c;opening=false;create(ch);}
                @Override public void onDisconnected(CameraDevice c){try{c.close();}catch(Throwable ignored){}camera=null;opening=false;status="DISCONNECTED";}
                @Override public void onError(CameraDevice c,int e){try{c.close();}catch(Throwable ignored){}camera=null;opening=false;lastError="Camera error "+e;status="CAMERA ERROR";}
            },bg);
        }catch(Throwable t){
            opening=false;fail("open",t);
        }
    }

    private String chooseBackCamera(CameraManager m)throws CameraAccessException{
        String[] ids=m.getCameraIdList();
        if(ids.length==0)throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,"No cameras");
        String fallback=ids[0];
        for(String id:ids){
            Integer facing=m.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK)return id;
        }
        return fallback;
    }

    private void configureFov(CameraCharacteristics c){
        try{
            SizeF physical=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focal=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if(physical!=null&&focal!=null&&focal.length>0&&focal[0]>0){
                float hfov=(float)Math.toDegrees(2.0*Math.atan(physical.getWidth()/(2.0*focal[0])));
                float vfov=(float)Math.toDegrees(2.0*Math.atan(physical.getHeight()/(2.0*focal[0])));
                imu.setFovDegrees(hfov,vfov);
            }
        }catch(Throwable ignored){}
    }

    private void configureStream(CameraCharacteristics c){
        try{
            StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map!=null){
                Size[] ys=map.getOutputSizes(ImageFormat.YUV_420_888);
                if(ys!=null&&ys.length>0)analysisSize=chooseAnalysisSize(ys);
            }
            Range<Integer>[] ranges=c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            requestedFps=chooseFps(ranges);
        }catch(Throwable t){
            requestedFps=30;
            analysisSize=new Size(640,360);
        }
    }

    private Size chooseAnalysisSize(Size[] sizes){
        Size best=null;
        for(Size s:sizes){
            if(s.getWidth()==640&&(s.getHeight()==360||s.getHeight()==480))return s;
            if(best==null||Math.abs(s.getWidth()-640)+Math.abs(s.getHeight()-360)<
                    Math.abs(best.getWidth()-640)+Math.abs(best.getHeight()-360))best=s;
        }
        return best!=null?best:new Size(640,360);
    }

    private int chooseFps(Range<Integer>[] ranges){
        if(ranges==null||ranges.length==0)return 30;
        int best=30;
        for(Range<Integer> r:ranges){
            int hi=r.getUpper();
            if(hi>=120)return 120;
            if(hi>=60)best=Math.max(best,60);
            else best=Math.max(best,hi);
        }
        return best;
    }

    private Range<Integer> chooseRange(CameraCharacteristics c,int target){
        try{
            Range<Integer>[] ranges=c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if(ranges==null||ranges.length==0)return null;
            Range<Integer> best=null;
            for(Range<Integer> r:ranges){
                if(r.getUpper()>=target){
                    if(best==null||r.getLower()>best.getLower())best=r;
                }
            }
            if(best!=null)return best;
            for(Range<Integer> r:ranges){
                if(best==null||r.getUpper()>best.getUpper())best=r;
            }
            return best;
        }catch(Throwable t){return null;}
    }

    private void create(CameraCharacteristics ch){
        try{
            SurfaceTexture st=getSurfaceTexture();
            if(st==null){status="NO SURFACE";return;}
            st.setDefaultBufferSize(1280,720);
            Surface preview=new Surface(st);

            int aw=analysisSize.getWidth(),ah=analysisSize.getHeight();
            analysisReader=ImageReader.newInstance(aw,ah,ImageFormat.YUV_420_888,3);
            analysisReader.setOnImageAvailableListener(r->{
                android.media.Image img=null;
                try{
                    img=r.acquireLatestImage();
                    if(img!=null){
                        long ts=img.getTimestamp();
                        if(lastImageTs!=0&&ts>lastImageTs){
                            float f=1e9f/(ts-lastImageTs);
                            cameraFps=cameraFps==0?f:.90f*cameraFps+.10f*f;
                        }
                        lastImageTs=ts;
                        detector.maybeSubmit(img,frameIndex++);
                        tracker.processImage(img);
                        img=null;
                    }
                }catch(Throwable t){
                    fail("frame",t);
                    if(img!=null)try{img.close();}catch(Throwable ignored){}
                }
            },bg);

            Surface analysis=analysisReader.getSurface();
            CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(preview);b.addTarget(analysis);
            b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);

            Range<Integer> fps=chooseRange(ch,requestedFps);
            if(fps!=null)b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps);

            status="CONFIGURING "+analysisSize.getWidth()+"x"+analysisSize.getHeight()+" @"+requestedFps;
            camera.createCaptureSession(Arrays.asList(preview,analysis),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession cs){
                    session=cs;
                    try{
                        session.setRepeatingRequest(b.build(),null,bg);
                        status="RUNNING";
                    }catch(Throwable t){fail("repeat",t);}
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs){
                    lastError="Capture session configuration failed";
                    status="SESSION ERROR";
                }
            },bg);
        }catch(Throwable t){
            fail("create",t);
        }
    }

    private void fail(String where,Throwable t){
        lastError=where+": "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
        status="ERROR";
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){start();}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){stop();return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s){try{tracker.predictOnly();}catch(Throwable ignored){}}
}
