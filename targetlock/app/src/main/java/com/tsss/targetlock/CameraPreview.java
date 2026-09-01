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
    private long lastImageTs=0;

    public volatile String status="INIT";
    public volatile String lastError="";
    public volatile int requestedFps=60;
    public volatile float cameraFps=0f;
    public volatile boolean highSpeed120Supported=false;
    public volatile Size analysisSize=new Size(320,180);

    private final TargetTracker tracker;
    private final ImuCompensator imu;

    public TargetTracker getTracker(){return tracker;}
    public ImuCompensator getImu(){return imu;}

    public CameraPreview(Context c){
        super(c);
        tracker=new TargetTracker();
        imu=new ImuCompensator(c);
        tracker.setImu(imu);
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
            if(!imu.active)imu.start();
            if(isAvailable())open();
            else status="WAIT SURFACE";
        }catch(Throwable t){fail("start",t);}
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
            inspectHighSpeed(ch);

            if(getContext().checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                opening=false;status="NO CAMERA PERMISSION";return;
            }

            status="OPEN CAMERA";
            m.openCamera(id,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice c){
                    camera=c;opening=false;
                    createSafeSession(ch);
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
        }catch(Throwable t){opening=false;fail("open",t);}
    }

    private String chooseBackCamera(CameraManager m)throws CameraAccessException{
        String[] ids=m.getCameraIdList();
        if(ids.length==0)throw new IllegalStateException("No camera IDs");
        for(String id:ids){
            Integer f=m.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if(f!=null&&f==CameraCharacteristics.LENS_FACING_BACK)return id;
        }
        return ids[0];
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

    private void inspectHighSpeed(CameraCharacteristics c){
        highSpeed120Supported=false;
        try{
            int[] caps=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean hsCap=false;
            if(caps!=null)for(int v:caps)if(v==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)hsCap=true;
            if(!hsCap)return;
            StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map==null)return;
            Size[] sizes=map.getHighSpeedVideoSizes();
            if(sizes==null)return;
            for(Size s:sizes){
                Range<Integer>[] rs=map.getHighSpeedVideoFpsRangesFor(s);
                if(rs!=null)for(Range<Integer> r:rs)if(r.getUpper()>=120){highSpeed120Supported=true;return;}
            }
        }catch(Throwable ignored){}
    }

    private Range<Integer> chooseSafeFps(CameraCharacteristics c){
        try{
            Range<Integer>[] rs=c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if(rs==null||rs.length==0)return null;
            Range<Integer> best=null;
            for(Range<Integer> r:rs){
                int hi=r.getUpper();
                if(hi>60)continue; // normal session only
                if(best==null||hi>best.getUpper()||(hi==best.getUpper()&&r.getLower()>best.getLower()))best=r;
            }
            if(best!=null){requestedFps=best.getUpper();return best;}
            best=rs[0];
            for(Range<Integer> r:rs)if(r.getUpper()>best.getUpper())best=r;
            requestedFps=Math.min(60,best.getUpper());
            return best;
        }catch(Throwable t){requestedFps=30;return null;}
    }

    private void createSafeSession(CameraCharacteristics ch){
        try{
            SurfaceTexture st=getSurfaceTexture();
            if(st==null){status="NO SURFACE";return;}
            st.setDefaultBufferSize(1280,720);
            Surface preview=new Surface(st);

            analysisReader=ImageReader.newInstance(320,180,ImageFormat.YUV_420_888,2);
            analysisReader.setOnImageAvailableListener(r->{
                android.media.Image img=null;
                try{
                    img=r.acquireLatestImage();
                    if(img==null)return;
                    long ts=img.getTimestamp();
                    if(lastImageTs!=0&&ts>lastImageTs){
                        float f=1e9f/(ts-lastImageTs);
                        cameraFps=cameraFps==0?f:.9f*cameraFps+.1f*f;
                    }
                    lastImageTs=ts;
                    tracker.processImage(img);
                    img=null; // tracker owns and closes
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
            Range<Integer> fps=chooseSafeFps(ch);
            if(fps!=null)b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps);

            status="CONFIGURE SAFE";
            camera.createCaptureSession(Arrays.asList(preview,analysis),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession cs){
                    session=cs;
                    try{
                        session.setRepeatingRequest(b.build(),null,bg);
                        status="RUNNING SAFE";
                    }catch(Throwable t){fail("repeat",t);}
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs){
                    lastError="Safe capture session failed";
                    status="SESSION ERROR";
                }
            },bg);
        }catch(Throwable t){fail("session",t);}
    }

    private void fail(String where,Throwable t){
        lastError=where+": "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
        status="ERROR";
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){
        // Let the view finish attaching before opening camera.
        postDelayed(this::start,150);
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){stop();return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s){
        try{tracker.predictOnly();}catch(Throwable ignored){}
    }
}
