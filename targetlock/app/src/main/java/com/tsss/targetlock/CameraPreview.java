package com.tsss.targetlock;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
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
    private boolean opening = false;
    private int frameIndex = 0;
    private final TargetTracker tracker = new TargetTracker();
    private final YoloDetector detector;

    public TargetTracker getTracker() { return tracker; }
    public YoloDetector getDetector() { return detector; }

    public CameraPreview(Context c) {
        super(c);
        detector = new YoloDetector(c, tracker);
        setSurfaceTextureListener(this);
        setOnTouchListener((v,e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN && getWidth() > 0 && getHeight() > 0) {
                tracker.requestLock(e.getX()/getWidth(), e.getY()/getHeight());
                return true;
            }
            return false;
        });
    }

    public void start() { if (isAvailable()) open(); }

    public void stop() {
        try { if (session != null) session.close(); } catch(Exception ignored) {}
        try { if (camera != null) camera.close(); } catch(Exception ignored) {}
        try { if (analysisReader != null) analysisReader.close(); } catch(Exception ignored) {}
        session = null; camera = null; analysisReader = null; opening = false;
        if (thread != null) { thread.quitSafely(); thread = null; bg = null; }
    }

    private void open() {
        if (opening || camera != null) return;
        opening = true;
        thread = new HandlerThread("target-lock-camera"); thread.start(); bg = new Handler(thread.getLooper());
        try {
            CameraManager m = (CameraManager)getContext().getSystemService(Context.CAMERA_SERVICE);
            String id = chooseBackCamera(m);
            if (getContext().checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) { opening=false; return; }
            m.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) { camera=c; opening=false; create(); }
                @Override public void onDisconnected(CameraDevice c) { c.close(); camera=null; opening=false; }
                @Override public void onError(CameraDevice c,int e) { c.close(); camera=null; opening=false; }
            }, bg);
        } catch(Exception e) { opening=false; }
    }

    private String chooseBackCamera(CameraManager m) throws CameraAccessException {
        String fallback = m.getCameraIdList()[0];
        for (String id : m.getCameraIdList()) {
            Integer facing = m.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return fallback;
    }

    private void create() {
        try {
            SurfaceTexture st = getSurfaceTexture();
            st.setDefaultBufferSize(1280,720);
            Surface preview = new Surface(st);
            analysisReader = ImageReader.newInstance(640,360,ImageFormat.YUV_420_888,3);
            analysisReader.setOnImageAvailableListener(r -> {
                android.media.Image img = r.acquireLatestImage();
                if (img != null) {
                    detector.maybeSubmit(img, frameIndex++);
                    tracker.processImage(img);
                }
            }, bg);
            Surface analysis = analysisReader.getSurface();
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(preview); b.addTarget(analysis);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            camera.createCaptureSession(Arrays.asList(preview,analysis), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession cs) {
                    session=cs;
                    try { session.setRepeatingRequest(b.build(), null, bg); } catch(Exception ignored) {}
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs) {}
            }, bg);
        } catch(Exception ignored) {}
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){ start(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){ stop(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s){ tracker.predictOnly(); }
}
