package com.tsss.gt6lock

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
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
    private lateinit var root: FrameLayout
    private lateinit var previewHost: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private lateinit var trackerThread: HandlerThread
    private lateinit var trackerHandler: Handler

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var previewSize = Size(1920, 1080)
    private var analysisSize = Size(1280, 720)
    private var relativeRotation = 0

    private val tracker = NativeTracker()
    @Volatile private var tracking = false
    @Volatile private var pendingTap: Pair<Float, Float>? = null
    private var lastTs = 0L
    private var fpsEma = 0f
    private val requestCode = 42
    private var lastTapMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(5, 9, 11)) }
        previewHost = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this)
        overlay = OverlayView(this)

        previewHost.addView(texture, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        previewHost.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(previewHost)
        setContentView(root)

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreviewHost() }

        overlay.onTapImage = { x, y ->
            val now = SystemClock.uptimeMillis()
            if (now - lastTapMs < 280) {
                tracker.nativeReset()
                tracking = false
                pendingTap = null
            } else {
                pendingTap = x to y
            }
            lastTapMs = now
        }

        cameraThread = HandlerThread("camera").also { it.start() }
        cameraHandler = Handler(cameraThread.looper)
        trackerThread = HandlerThread("tracker").also { it.start() }
        trackerHandler = Handler(trackerThread.looper)

        texture.surfaceTextureListener = surfaceListener
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), requestCode)
        }
    }

    private fun fitPreviewHost() {
        if (root.width <= 0 || root.height <= 0) return
        val target = 16f / 9f
        val screen = root.width.toFloat() / root.height.toFloat()
        val w: Int
        val h: Int
        if (screen >= target) {
            h = root.height
            w = (h * target).toInt()
        } else {
            w = root.width
            h = (w / target).toInt()
        }
        val lp = (previewHost.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(w, h)
        if (lp.width != w || lp.height != h || lp.gravity != Gravity.CENTER) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            previewHost.layoutParams = lp
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED &&
            texture.isAvailable
        ) openCamera()
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            configurePreviewTransform(w, h)
        }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private fun selectMainBackCamera(cm: CameraManager): String? {
        return cm.cameraIdList.mapNotNull { id ->
            val cc = cm.getCameraCharacteristics(id)
            if (cc.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null

            val caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val logical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            val phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val area = if (phys != null) phys.width * phys.height else 0f
            val focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
            val minFocal = focals.minOrNull() ?: 0f

            val score = (if (logical) 100000.0 else 0.0) + area * 100.0 + minFocal
            Triple(id, cc, score)
        }.maxByOrNull { it.third }?.first
    }

    private fun openCamera() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = selectMainBackCamera(cm) ?: return

        val cc = cm.getCameraCharacteristics(id)
        cameraCharacteristics = cc
        val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val yuv = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) ?: return
        val previews = map.getOutputSizes(SurfaceTexture::class.java) ?: return

        analysisSize = choose16x9Near(yuv, 1280, 720)
        previewSize = choose16x9Near(previews, 1920, 1080)

        overlay.imageW = analysisSize.width
        overlay.imageH = analysisSize.height
        overlay.cameraLabel = "MAIN • " + previewSize.width + "×" + previewSize.height +
            " VIEW / " + analysisSize.width + "×" + analysisSize.height + " TRACK"

        relativeRotation = calculateRelativeRotation(cc)
        overlay.rotationDegrees = relativeRotation

        reader?.close()
        reader = ImageReader.newInstance(
            analysisSize.width,
            analysisSize.height,
            android.graphics.ImageFormat.YUV_420_888,
            2
        ).apply {
            setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.use { processImage(it) }
            }, cameraHandler)
        }

        @Suppress("MissingPermission")
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) {
                camera = c
                configurePreviewTransform(texture.width, texture.height)
                startSession(cc)
            }
            override fun onDisconnected(c: CameraDevice) { c.close() }
            override fun onError(c: CameraDevice, error: Int) { c.close() }
        }, cameraHandler)
    }

    private fun choose16x9Near(sizes: Array<Size>, wantW: Int, wantH: Int): Size {
        val exactAspect = sizes.filter { it.width * 9 == it.height * 16 }
        val pool = if (exactAspect.isNotEmpty()) exactAspect else sizes.toList()
        return pool.minByOrNull {
            abs(it.width - wantW) + abs(it.height - wantH)
        } ?: Size(wantW, wantH)
    }

    private fun calculateRelativeRotation(cc: CameraCharacteristics): Int {
        val sensor = cc.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensor - displayDegrees + 360) % 360
    }

    private fun configurePreviewTransform(viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) return
        val cc = cameraCharacteristics ?: return
        relativeRotation = calculateRelativeRotation(cc)
        overlay.rotationDegrees = relativeRotation

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()

        if (relativeRotation == 180) {
            matrix.postRotate(180f, cx, cy)
        } else if (relativeRotation == 90 || relativeRotation == 270) {
            val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                viewH.toFloat() / previewSize.height.toFloat(),
                viewW.toFloat() / previewSize.width.toFloat()
            )
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(if (relativeRotation == 90) -90f else 90f, cx, cy)
        }
        texture.setTransform(matrix)
    }

    private fun startSession(cc: CameraCharacteristics) {
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val preview = Surface(st)
        val analysis = reader?.surface ?: return

        camera?.createCaptureSession(listOf(preview, analysis), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val req = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(preview)
                    addTarget(analysis)

                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    chooseFps(cc)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

                    val antibanding = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: intArrayOf()
                    if (antibanding.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)) {
                        set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
                    }

                    val videoStab = cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
                    if (videoStab.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    }

                    val ois = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
                    if (ois.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    }

                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                }.build()
                s.setRepeatingRequest(req, null, cameraHandler)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {}
        }, cameraHandler)
    }

    private fun chooseFps(cc: CameraCharacteristics): Range<Int>? {
        val rs = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return rs.filter { it.upper >= 60 }
            .minByOrNull { if (it.lower <= 60) 0 else it.lower - 60 }
            ?: rs.maxByOrNull { it.upper }
    }

    private fun processImage(img: Image) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val w = img.width
        val h = img.height
        val y = ByteArray(w * h)

        if (plane.pixelStride == 1 && plane.rowStride == w) {
            buf.get(y)
        } else {
            val row = ByteArray(plane.rowStride)
            var out = 0
            for (r in 0 until h) {
                val n = minOf(plane.rowStride, buf.remaining())
                if (n <= 0) break
                buf.get(row, 0, n)
                var c = 0
                while (c < w && c * plane.pixelStride < n) {
                    y[out++] = row[c * plane.pixelStride]
                    c++
                }
                while (c < w && out < y.size) {
                    y[out++] = 0
                    c++
                }
            }
        }

        val ts = img.timestamp
        trackerHandler.removeCallbacksAndMessages(null)
        trackerHandler.post { runTracker(y, w, h, ts) }
    }

    private fun runTracker(y: ByteArray, w: Int, h: Int, ts: Long) {
        pendingTap?.let { (x, yc) ->
            val box = (minOf(w, h) * 0.12f).coerceIn(48f, 180f)
            tracking = tracker.nativeInit(y, w, h, x, yc, box * 1.35f, box)
            pendingTap = null
            lastTs = ts
        }

        if (!tracking) {
            overlay.post {
                overlay.track = OverlayView.UiTrack()
                overlay.invalidate()
            }
            return
        }

        val dt = if (lastTs == 0L) 1.0 / 60.0 else ((ts - lastTs) / 1e9).coerceIn(1.0 / 120.0, 0.08)
        lastTs = ts
        val out = tracker.nativeProcess(y, w, h, dt)

        val instFps = (1.0 / dt).toFloat()
        fpsEma = if (fpsEma == 0f) instFps else 0.9f * fpsEma + 0.1f * instFps

        val ui = OverlayView.UiTrack(
            out[0].toInt(), out[1], out[2], out[3], out[4],
            out[5], out[6], out[7], out[8].toInt(), fpsEma
        )
        overlay.post {
            overlay.track = ui
            overlay.invalidate()
        }
    }

    override fun onDestroy() {
        session?.close()
        camera?.close()
        reader?.close()
        tracker.nativeReset()
        cameraThread.quitSafely()
        trackerThread.quitSafely()
        super.onDestroy()
    }
}
