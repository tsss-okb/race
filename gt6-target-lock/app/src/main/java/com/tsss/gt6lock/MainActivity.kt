package com.tsss.gt6lock

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

class MainActivity : Activity(), SensorEventListener {
    private lateinit var root: FrameLayout
    private lateinit var previewHost: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private lateinit var trackerThread: HandlerThread
    private lateinit var trackerHandler: Handler

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var avionicsHud = true

    private var sensorRollDeg = 0f
    private var sensorPitchDeg = 0f
    private var sensorHeadingDeg = 0f
    private var gyroPDeg = 0f
    private var gyroQDeg = 0f
    private var gyroRDeg = 0f
    private var gLoad = 1f

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var currentCameraId = "?"
    private var previewSize = Size(1280, 720)
    private var analysisSize = Size(320, 180)
    private var relativeRotation = 0
    private var openingCamera = false

    private var backCameraIds: List<String> = emptyList()
    private var cameraIndex = 0
    private var fps60 = true
    private var selectedFpsRange: Range<Int>? = null

    private val tracker = NativeTracker()
    @Volatile private var tracking = false
    @Volatile private var pendingTap: Pair<Float, Float>? = null
    private var lastTs = 0L
    private var fpsEma = 0f
    private var lastCameraTs = 0L
    private var cameraFpsEma = 0f
    private var cameraFrameCounter = 0
    private val requestCode = 42
    private var lastTapMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
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

        overlay.onCameraCycle = { runOnUiThread { switchCamera() } }

        overlay.onFpsToggle = {
            runOnUiThread {
                val cc = cameraCharacteristics
                val can60 = cc?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.any { it.upper >= 60 } == true
                fps60 = if (can60) !fps60 else false
                overlay.fpsModeLabel = if (fps60) "60" else if (can60) "30" else "MAX"
                overlay.invalidate()
                restartCamera()
            }
        }

        overlay.onAutoLevelToggle = {
            runOnUiThread {
                avionicsHud = !avionicsHud
                overlay.avionicsHudEnabled = avionicsHud
                overlay.invalidate()
            }
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

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, event.values)

                val remapped = FloatArray(9)
                val rot = display?.rotation ?: Surface.ROTATION_0
                when (rot) {
                    Surface.ROTATION_90 ->
                        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
                    Surface.ROTATION_270 ->
                        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
                    Surface.ROTATION_180 ->
                        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped)
                    else -> System.arraycopy(r, 0, remapped, 0, 9)
                }

                val orientation = FloatArray(3)
                SensorManager.getOrientation(remapped, orientation)
                sensorHeadingDeg = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
                sensorPitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
                sensorRollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
            }

            Sensor.TYPE_GYROSCOPE -> {
                val k = (180.0 / Math.PI).toFloat()
                gyroPDeg = event.values[0] * k
                gyroQDeg = event.values[1] * k
                gyroRDeg = event.values[2] * k
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                gLoad = (kotlin.math.sqrt(ax * ax + ay * ay + az * az) / 9.80665f)
            }
        }

        overlay.sensorRollDegrees = sensorRollDeg
        overlay.sensorPitchDegrees = sensorPitchDeg
        overlay.sensorHeadingDegrees = sensorHeadingDeg
        overlay.gyroPDeg = gyroPDeg
        overlay.gyroQDeg = gyroQDeg
        overlay.gyroRDeg = gyroRDeg
        overlay.gLoad = gLoad
        overlay.avionicsHudEnabled = avionicsHud
        overlay.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // IMU is avionics data only. It must never rotate/mirror the camera frame.
    private fun applySensorLevel() {
        texture.rotation = 0f
        texture.scaleX = 1f
        texture.scaleY = 1f
        overlay.levelCorrectionDegrees = 0f
        overlay.levelScale = 1f
        overlay.avionicsHudEnabled = avionicsHud
        overlay.invalidate()
    }

    private fun fitPreviewHost() {
        if (root.width <= 0 || root.height <= 0) return
        val lp = (previewHost.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(root.width, root.height)
        lp.width = root.width
        lp.height = root.height
        lp.gravity = Gravity.CENTER
        previewHost.layoutParams = lp
        configurePreviewTransform(root.width, root.height)
        applySensorLevel()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED &&
            texture.isAvailable
        ) openCamera()
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            }
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            configurePreviewTransform(w, h)
            applySensorLevel()
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private fun refreshCameraList(cm: CameraManager) {
        val ids = cm.cameraIdList.filter { id ->
            val cc = cm.getCameraCharacteristics(id)
            cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        fun score(id: String): Double {
            val cc = cm.getCameraCharacteristics(id)
            val focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
            val focal = focals.firstOrNull() ?: 0f
            val physical = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val area = if (physical != null) physical.width * physical.height else 0f
            val ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val has60 = ranges.any { it.upper >= 60 }

            val focalClass = when {
                focal in 3.0f..8.5f -> 200000.0
                focal in 2.5f..9.5f -> 80000.0
                else -> 0.0
            }
            val fpsClass = if (has60) 1000000.0 else 0.0
            return fpsClass + focalClass + area * 1000.0 + if (id == "0") 100.0 else 0.0
        }

        backCameraIds = ids.sortedByDescending { score(it) }
        if (backCameraIds.isEmpty()) return
        if (cameraIndex !in backCameraIds.indices) cameraIndex = 0
    }

    private fun switchCamera() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        refreshCameraList(cm)
        if (backCameraIds.size <= 1) {
            overlay.cameraLabel = "Одна задняя камера доступна через Camera2"
            overlay.invalidate()
            return
        }
        cameraIndex = (cameraIndex + 1) % backCameraIds.size
        restartCamera()
    }

    private fun restartCamera() {
        tracker.nativeReset()
        tracking = false
        pendingTap = null
        lastTs = 0L
        fpsEma = 0f
        lastCameraTs = 0L
        cameraFpsEma = 0f
        cameraFrameCounter = 0
        closeCamera()
        cameraHandler.postDelayed({ openCamera() }, 220)
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        openingCamera = false
    }

    private fun openCamera() {
        if (openingCamera || camera != null || !texture.isAvailable) return

        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        refreshCameraList(cm)
        if (backCameraIds.isEmpty()) {
            overlay.cameraLabel = "Задняя камера не найдена"
            overlay.invalidate()
            return
        }

        val id = backCameraIds[cameraIndex]
        currentCameraId = id
        val cc = cm.getCameraCharacteristics(id)
        cameraCharacteristics = cc
        val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            overlay.cameraLabel = "CAM $id: нет StreamConfigurationMap"
            overlay.invalidate()
            return
        }

        val yuv = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
        val previews = map.getOutputSizes(SurfaceTexture::class.java)
        if (yuv == null || previews == null) {
            overlay.cameraLabel = "CAM $id: нет совместимого видеорежима"
            overlay.invalidate()
            return
        }

        analysisSize = choose16x9Near(yuv, 320, 180)
        previewSize = choose16x9Near(previews, 1280, 720)

        overlay.imageW = analysisSize.width
        overlay.imageH = analysisSize.height
        relativeRotation = calculateRelativeRotation(cc)
        overlay.rotationDegrees = relativeRotation

        val ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val maxFps = ranges.maxOfOrNull { it.upper } ?: 0
        val has60 = maxFps >= 60
        fps60 = has60
        overlay.fpsModeLabel = if (has60) "60" else "MAX$maxFps"

        val caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val hs = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
        val highRanges = try { map.highSpeedVideoFpsRanges } catch (_: Exception) { emptyArray() }
        val highMax = highRanges.maxOfOrNull { it.upper } ?: 0

        val focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalText = focals?.joinToString("/") { "%.1f".format(it) } ?: "?"
        val sensorOrientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1

        overlay.cameraLabel =
            "CAM $id  " + focalText + "mm  SENSOR " + sensorOrientation + "°  • VIEW " +
            previewSize.width + "×" + previewSize.height +
            "  TRACK " + analysisSize.width + "×" + analysisSize.height +
            "  • AE MAX " + maxFps +
            if (hs) "  HS " + highMax else "  HS —"
        overlay.invalidate()

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

        openingCamera = true
        try {
            @Suppress("MissingPermission")
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    openingCamera = false
                    camera = c
                    configurePreviewTransform(texture.width, texture.height)
                    applySensorLevel()
                    startSession(cc)
                }

                override fun onDisconnected(c: CameraDevice) {
                    openingCamera = false
                    c.close()
                    if (camera === c) camera = null
                }

                override fun onError(c: CameraDevice, error: Int) {
                    openingCamera = false
                    c.close()
                    if (camera === c) camera = null
                    overlay.post {
                        overlay.cameraLabel = "CAM $id ERROR $error"
                        overlay.invalidate()
                    }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            openingCamera = false
            overlay.cameraLabel = "CAM $id open: " + e.javaClass.simpleName
            overlay.invalidate()
        }
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
        val cx = viewW / 2f
        val cy = viewH / 2f
        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())

        if (relativeRotation == 90 || relativeRotation == 270) {
            val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                viewH.toFloat() / previewSize.height.toFloat(),
                viewW.toFloat() / previewSize.width.toFloat()
            )
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(if (relativeRotation == 90) -90f else 90f, cx, cy)
        } else {
            val bufferAspect = previewSize.width.toFloat() / previewSize.height.toFloat()
            val viewAspect = viewW.toFloat() / viewH.toFloat()
            if (viewAspect > bufferAspect) {
                matrix.postScale(1f, viewAspect / bufferAspect, cx, cy)
            } else if (viewAspect < bufferAspect) {
                matrix.postScale(bufferAspect / viewAspect, 1f, cx, cy)
            }
            if (relativeRotation == 180) matrix.postRotate(180f, cx, cy)
        }

        texture.setTransform(matrix)
        applySensorLevel()
        overlay.invalidate()
    }

    private fun startSession(cc: CameraCharacteristics) {
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val preview = Surface(st)
        val analysis = reader?.surface ?: return
        val cam = camera ?: return

        try {
            cam.createCaptureSession(
                listOf(preview, analysis),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            selectedFpsRange = chooseFps(cc, fps60)
                            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(preview)
                                addTarget(analysis)

                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

                                val af = cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                                if (af.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                }

                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                selectedFpsRange?.let {
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                                }

                                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                            }.build()

                            s.setRepeatingRequest(req, null, cameraHandler)

                            val fpsText = selectedFpsRange?.let { it.lower.toString() + "-" + it.upper.toString() } ?: "AUTO"
                            overlay.post {
                                overlay.cameraHzLabel = fpsText
                                overlay.invalidate()
                            }
                        } catch (e: Exception) {
                            overlay.post {
                                overlay.cameraLabel = "Session: " + e.javaClass.simpleName
                                overlay.invalidate()
                            }
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        overlay.post {
                            overlay.cameraLabel = "Camera session configure failed"
                            overlay.invalidate()
                        }
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            overlay.cameraLabel = "Create session: " + e.javaClass.simpleName
            overlay.invalidate()
        }
    }

    private fun chooseFps(cc: CameraCharacteristics, want60: Boolean): Range<Int>? {
        val rs = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null

        if (want60) {
            val sixty = rs.filter { it.upper >= 60 }
            if (sixty.isNotEmpty()) {
                return sixty.maxByOrNull { it.lower * 1000 - abs(it.upper - 60) }
            }
        }

        return rs.maxByOrNull { it.upper * 1000 + it.lower }
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
        if (lastCameraTs != 0L) {
            val dtCam = (ts - lastCameraTs) / 1e9
            if (dtCam > 0.001 && dtCam < 0.2) {
                val inst = (1.0 / dtCam).toFloat()
                cameraFpsEma = if (cameraFpsEma == 0f) inst else 0.90f * cameraFpsEma + 0.10f * inst
            }
        }
        lastCameraTs = ts
        cameraFrameCounter++
        if (cameraFrameCounter % 4 == 0) {
            overlay.post {
                overlay.cameraFps = cameraFpsEma
                overlay.invalidate()
            }
        }

        trackerHandler.removeCallbacksAndMessages(null)
        trackerHandler.post { runTracker(y, w, h, ts) }
    }

    private fun runTracker(y: ByteArray, w: Int, h: Int, ts: Long) {
        pendingTap?.let { (x, yc) ->
            val box = (minOf(w, h) * 0.17f).coerceIn(30f, 80f)
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

        val dt = if (lastTs == 0L) {
            1.0 / 60.0
        } else {
            ((ts - lastTs) / 1e9).coerceIn(1.0 / 180.0, 0.12)
        }
        lastTs = ts

        val out = tracker.nativeProcess(y, w, h, dt)
        val instFps = (1.0 / dt).toFloat()
        fpsEma = if (fpsEma == 0f) instFps else 0.88f * fpsEma + 0.12f * instFps

        val ui = OverlayView.UiTrack(
            out[0].toInt(),
            out[1],
            out[2],
            out[3],
            out[4],
            out[5],
            out[6],
            out[7],
            out[8].toInt(),
            fpsEma
        )

        overlay.post {
            overlay.track = ui
            overlay.invalidate()
        }
    }

    override fun onDestroy() {
        closeCamera()
        tracker.nativeReset()
        cameraThread.quitSafely()
        trackerThread.quitSafely()
        super.onDestroy()
    }
}
