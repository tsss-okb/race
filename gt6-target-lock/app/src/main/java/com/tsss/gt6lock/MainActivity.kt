package com.tsss.gt6lock

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.FrameLayout
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var texture: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private lateinit var trackerThread: HandlerThread
    private lateinit var trackerHandler: Handler
    private var camera: CameraDevice?=null
    private var session: CameraCaptureSession?=null
    private var reader: ImageReader?=null
    private val tracker=NativeTracker()
    @Volatile private var tracking=false
    @Volatile private var pendingTap: Pair<Float,Float>?=null
    private var lastTs=0L
    private var fpsEma=0f
    private val requestCode=42
    private var lastTapMs=0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        val root=FrameLayout(this).apply{ setBackgroundColor(Color.rgb(5,9,11)) }
        texture=TextureView(this)
        overlay=OverlayView(this)
        root.addView(texture,FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(overlay,FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)

        overlay.onTapImage={x,y->
            val now=SystemClock.uptimeMillis()
            if(now-lastTapMs<280){ tracker.nativeReset(); tracking=false; pendingTap=null }
            else pendingTap=x to y
            lastTapMs=now
        }
        cameraThread=HandlerThread("camera").also{it.start()}; cameraHandler=Handler(cameraThread.looper)
        trackerThread=HandlerThread("tracker").also{it.start()}; trackerHandler=Handler(trackerThread.looper)
        texture.surfaceTextureListener=surfaceListener
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA),requestCode)
    }

    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==this.requestCode && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED && texture.isAvailable) openCamera()
    }

    private val surfaceListener=object:TextureView.SurfaceTextureListener{
        override fun onSurfaceTextureAvailable(st:SurfaceTexture,w:Int,h:Int){ if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) openCamera() }
        override fun onSurfaceTextureSizeChanged(st:SurfaceTexture,w:Int,h:Int){}
        override fun onSurfaceTextureDestroyed(st:SurfaceTexture)=true
        override fun onSurfaceTextureUpdated(st:SurfaceTexture){}
    }

    private fun openCamera(){
        val cm=getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id=cm.cameraIdList.firstOrNull{ cid ->
            val cc=cm.getCameraCharacteristics(cid)
            cc.get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK
        } ?: return
        val cc=cm.getCameraCharacteristics(id)
        val map=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val yuv=map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
        val size=chooseSize(yuv)
        overlay.imageW=size.width; overlay.imageH=size.height
        reader=ImageReader.newInstance(size.width,size.height,android.graphics.ImageFormat.YUV_420_888,2).apply{
            setOnImageAvailableListener({r-> r.acquireLatestImage()?.use{processImage(it)} },cameraHandler)
        }
        @Suppress("MissingPermission") cm.openCamera(id,object:CameraDevice.StateCallback(){
            override fun onOpened(c:CameraDevice){ camera=c; startSession(cc,size) }
            override fun onDisconnected(c:CameraDevice){c.close()}
            override fun onError(c:CameraDevice,error:Int){c.close()}
        },cameraHandler)
    }

    private fun chooseSize(sizes:Array<Size>):Size = sizes.filter{it.width*9==it.height*16}.minByOrNull{abs(it.width-1280)} ?: sizes.minByOrNull{abs(it.width-1280)} ?: Size(1280,720)

    private fun startSession(cc:CameraCharacteristics,size:Size){
        val st=texture.surfaceTexture ?: return
        st.setDefaultBufferSize(size.width,size.height)
        val preview=Surface(st); val analysis=reader?.surface ?: return
        camera?.createCaptureSession(listOf(preview,analysis),object:CameraCaptureSession.StateCallback(){
            override fun onConfigured(s:CameraCaptureSession){
                session=s
                val req=camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply{
                    addTarget(preview); addTarget(analysis)
                    set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
                    chooseFps(cc)?.let{ set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,it) }
                    set(CaptureRequest.NOISE_REDUCTION_MODE,CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                    set(CaptureRequest.EDGE_MODE,CaptureRequest.EDGE_MODE_FAST)
                }.build()
                s.setRepeatingRequest(req,null,cameraHandler)
            }
            override fun onConfigureFailed(s:CameraCaptureSession){}
        },cameraHandler)
    }

    private fun chooseFps(cc:CameraCharacteristics):Range<Int>?{
        val rs=cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return rs.filter{it.upper>=60}.minByOrNull{ if(it.lower<=60) 0 else it.lower-60 } ?: rs.maxByOrNull{it.upper}
    }

    private fun processImage(img:Image){
        val plane=img.planes[0]; val buf=plane.buffer
        val w=img.width; val h=img.height
        val y=ByteArray(w*h)
        if(plane.pixelStride==1 && plane.rowStride==w) buf.get(y)
        else {
            val row=ByteArray(plane.rowStride)
            var out=0
            for(r in 0 until h){
                val n=minOf(plane.rowStride,buf.remaining())
                if(n<=0) break
                buf.get(row,0,n)
                var c=0
                while(c<w && c*plane.pixelStride<n){ y[out++]=row[c*plane.pixelStride]; c++ }
                while(c<w && out<y.size){ y[out++]=0; c++ }
            }
        }
        val ts=img.timestamp
        trackerHandler.removeCallbacksAndMessages(null)
        trackerHandler.post{ runTracker(y,w,h,ts) }
    }

    private fun runTracker(y:ByteArray,w:Int,h:Int,ts:Long){
        pendingTap?.let{(x,yc)->
            val box=(minOf(w,h)*0.12f).coerceIn(48f,180f)
            tracking=tracker.nativeInit(y,w,h,x,yc,box*1.35f,box)
            pendingTap=null; lastTs=ts
        }
        if(!tracking){ overlay.post{overlay.track=OverlayView.UiTrack();overlay.invalidate()}; return }
        val dt=if(lastTs==0L)1.0/60.0 else ((ts-lastTs)/1e9).coerceIn(1.0/120.0,0.08)
        lastTs=ts
        val out=tracker.nativeProcess(y,w,h,dt)
        val instFps=(1.0/dt).toFloat(); fpsEma=if(fpsEma==0f)instFps else 0.9f*fpsEma+0.1f*instFps
        val ui=OverlayView.UiTrack(out[0].toInt(),out[1],out[2],out[3],out[4],out[5],out[6],out[7],out[8].toInt(),fpsEma)
        overlay.post{ overlay.track=ui; overlay.invalidate() }
    }

    override fun onDestroy(){
        session?.close();camera?.close();reader?.close();tracker.nativeReset()
        cameraThread.quitSafely();trackerThread.quitSafely();super.onDestroy()
    }
}
