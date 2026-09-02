package com.tsss.gt6lock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fusion v2:
 * - PlaneAimPhone CameraX/60fps/Y-plane architecture
 * - C++/NDK hot-path tracker
 * - YOLO26 only in SEARCH/REACQUIRE
 * - IMU is avionics/prediction data only and never rotates the video.
 */
class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detectorExecutor: ExecutorService

    private val nativeTracker = NativeTracker()
    private val luma = FastLumaExtractor(maxOutputWidth = 320, ringSize = 3)
    private val inferenceBusy = AtomicBoolean(false)
    private val prioritySet = AtomicBoolean(false)

    @Volatile private var detector: Yolo26OnnxDetector? = null
    @Volatile private var boundCamera: Camera? = null
    @Volatile private var tracking = false
    @Volatile private var latestDetections: List<Detection> = emptyList()
    @Volatile private var pendingTap: Pair<Float, Float>? = null
    @Volatile private var pendingReacquire: Detection? = null
    @Volatile private var manualSearchRequested = false
    @Volatile private var lastTrackDetection: Detection? = null
    @Volatile private var stateLabel = "SEARCH"

    private var lastFrameTs = 0L
    private var lastCameraTs = 0L
    private var cameraFpsEma = 0f
    private var trackCounter = 0
    private var trackWindowStart = SystemClock.elapsedRealtime()
    private var trackFps = 0f
    private var frameSerial = 0L
    private var lastYoloRunMs = 0L
    private var lastUiMs = 0L
    private var lastTapMs = 0L

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var rawRoll = 0f
    private var rawPitch = 0f
    private var rawHeading = 0f
    private var zeroRoll = 0f
    private var zeroPitch = 0f
    private var zeroHeading = 0f
    private var calibrated = false
    private var gyroP = 0f
    private var gyroQ = 0f
    private var gyroR = 0f
    private var gLoad = 1f

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        cameraExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "GT6-NDK-Track").apply { priority = Thread.MAX_PRIORITY }
        }
        detectorExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "GT6-YOLO-OnDemand").apply { priority = Thread.NORM_PRIORITY }
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            setBackgroundColor(Color.BLACK)
        }
        overlay = OverlayView(this)

        root.addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)

        overlay.onTapNormalized = { x, y ->
            val now = SystemClock.uptimeMillis()
            if (now - lastTapMs < 280L) {
                resetTracker()
            } else {
                pendingTap = x to y
            }
            lastTapMs = now
        }
        overlay.onReset = { resetTracker() }
        overlay.onSearch = {
            manualSearchRequested = true
            lastYoloRunMs = 0L
            overlay.yoloMode = "MANUAL"
            overlay.invalidate()
        }
        overlay.onCalibrate = {
            zeroRoll = rawRoll
            zeroPitch = rawPitch
            zeroHeading = rawHeading
            calibrated = true
            overlay.bodyCalibrated = true
            overlay.invalidate()
        }
        overlay.onHudToggle = {
            overlay.showAvionics = !overlay.showAvionics
            overlay.invalidate()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            if (!tryBind(provider, request60 = true)) {
                tryBind(provider, request60 = false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun tryBind(provider: ProcessCameraProvider, request60: Boolean): Boolean {
        return runCatching {
            provider.unbindAll()

            val previewBuilder = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            if (!request60) previewBuilder.setTargetFrameRate(Range(30, 60))
            val preview = previewBuilder.build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 180))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { image -> analyzeImage(image) }

            boundCamera = if (request60) {
                val session = SessionConfig(
                    useCases = listOf(preview, analysis),
                    frameRateRange = Range(60, 60)
                )
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, session)
            } else {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }

            overlay.yoloMode = if (request60) "IDLE / CAM60" else "IDLE / MAX"
            overlay.invalidate()
            true
        }.getOrElse {
            false
        }
    }

    private fun analyzeImage(image: ImageProxy) {
        try {
            if (prioritySet.compareAndSet(false, true)) {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            }

            val ts = image.imageInfo.timestamp
            if (lastCameraTs != 0L) {
                val dt = (ts - lastCameraTs) / 1e9
                if (dt in 0.004..0.1) {
                    val f = (1.0 / dt).toFloat()
                    cameraFpsEma = if (cameraFpsEma == 0f) f else cameraFpsEma * 0.90f + f * 0.10f
                }
            }
            lastCameraTs = ts

            val gray = luma.extract(image) ?: return
            frameSerial++
            overlay.sourceAspectRatio = gray.width.toFloat() / gray.height.toFloat()

            pendingReacquire?.let { d ->
                initFromDetection(gray, d)
                pendingReacquire = null
            }

            pendingTap?.let { tap ->
                val chosen = chooseDetectionAt(tap.first, tap.second)
                if (chosen != null) initFromDetection(gray, chosen)
                else initAtTap(gray, tap.first, tap.second)
                pendingTap = null
            }

            var trackerConf = 0f
            var jitter = 0f

            if (tracking) {
                val dt = if (lastFrameTs == 0L) 1.0 / 60.0
                else ((gray.timestampNs - lastFrameTs) / 1e9).coerceIn(1.0 / 180.0, 0.10)
                lastFrameTs = gray.timestampNs

                val out = nativeTracker.nativeProcess(gray.pixels, gray.width, gray.height, dt)
                val state = out[0].toInt()
                trackerConf = out[5]
                jitter = out[6]
                val misses = out[8].toInt()

                val det = nativeResultToDetection(out, gray.width, gray.height)
                lastTrackDetection = det
                overlay.locked = det

                stateLabel = when {
                    state == 2 -> "REACQUIRE"
                    trackerConf >= 0.60f && misses == 0 -> "LOCK"
                    else -> "PREDICT"
                }

                if (misses > 18) {
                    resetTrackerInternal(clearDetections = false)
                }

                trackCounter++
                val nowTrack = SystemClock.elapsedRealtime()
                if (nowTrack - trackWindowStart >= 1000L) {
                    trackFps = trackCounter * 1000f / (nowTrack - trackWindowStart).coerceAtLeast(1L)
                    trackCounter = 0
                    trackWindowStart = nowTrack
                }
            } else {
                stateLabel = "SEARCH"
                lastFrameTs = gray.timestampNs
            }

            maybeRunYolo(image)

            val now = SystemClock.elapsedRealtime()
            if (now - lastUiMs >= 65L) {
                lastUiMs = now
                overlay.stateLabel = stateLabel
                overlay.cameraFps = cameraFpsEma
                overlay.trackFps = trackFps
                overlay.trackConf = trackerConf
                overlay.jitterPx = jitter
                overlay.detections = if (tracking && stateLabel == "LOCK") emptyList() else latestDetections
                overlay.invalidate()
            }
        } finally {
            image.close()
        }
    }

    private fun maybeRunYolo(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val interval = when {
            manualSearchRequested -> 0L
            !tracking -> 950L
            stateLabel == "REACQUIRE" -> 300L
            stateLabel == "PREDICT" -> 520L
            else -> Long.MAX_VALUE
        }
        if (interval == Long.MAX_VALUE || now - lastYoloRunMs < interval) {
            if (stateLabel == "LOCK") {
                overlay.yoloMode = "IDLE"
                latestDetections = emptyList()
            }
            return
        }
        if (!inferenceBusy.compareAndSet(false, true)) return

        val rotation = image.imageInfo.rotationDegrees
        val raw = runCatching { image.toBitmap() }.getOrNull()
        if (raw == null) {
            inferenceBusy.set(false)
            return
        }
        val bitmap = if (rotation == 0) raw else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val b = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, false)
            if (b !== raw) raw.recycle()
            b
        }

        lastYoloRunMs = now
        manualSearchRequested = false
        overlay.yoloMode = if (tracking) "REACQUIRE" else "SEARCH"

        detectorExecutor.execute {
            try {
                val net = detector ?: Yolo26OnnxDetector(this).also { detector = it }
                val found = if (net.available) net.detect(bitmap) else emptyList()
                latestDetections = found
                overlay.yoloBackend = net.backendName
                overlay.yoloMs = net.lastInferenceMs

                if (tracking && stateLabel != "LOCK" && found.isNotEmpty()) {
                    val p = lastTrackDetection
                    val best = if (p != null) {
                        found.minByOrNull { d ->
                            hypot((d.cx - p.cx).toDouble(), (d.cy - p.cy).toDouble())
                        }
                    } else found.maxByOrNull { it.confidence }

                    if (best != null) {
                        val dist = p?.let {
                            hypot((best.cx - it.cx).toDouble(), (best.cy - it.cy).toDouble())
                        } ?: 0.0
                        if (p == null || dist < 0.32) pendingReacquire = best
                    }
                }

                runOnUiThread {
                    overlay.detections = found
                    overlay.yoloMode = if (tracking && stateLabel == "LOCK") "IDLE" else
                        if (tracking) "REACQUIRE" else "SEARCH"
                    overlay.invalidate()
                }
            } finally {
                bitmap.recycle()
                inferenceBusy.set(false)
            }
        }
    }

    private fun chooseDetectionAt(nx: Float, ny: Float): Detection? {
        val inside = latestDetections
            .filter { nx in it.x1..it.x2 && ny in it.y1..it.y2 }
            .maxByOrNull { it.confidence }
        if (inside != null) return inside

        return latestDetections.minByOrNull {
            hypot((it.cx - nx).toDouble(), (it.cy - ny).toDouble())
        }?.takeIf {
            hypot((it.cx - nx).toDouble(), (it.cy - ny).toDouble()) < 0.10
        }
    }

    private fun initFromDetection(gray: FastLumaExtractor.GrayFrame, d: Detection) {
        val cx = d.cx * gray.width
        val cy = d.cy * gray.height
        // Track the object's visual core, not the whole detector box.
        // This rejects background and makes the visible LOCK box much tighter.
        val bw = (d.width * gray.width * 0.68f).coerceIn(14f, gray.width * 0.28f)
        val bh = (d.height * gray.height * 0.68f).coerceIn(14f, gray.height * 0.34f)
        tracking = nativeTracker.nativeInit(gray.pixels, gray.width, gray.height, cx, cy, bw, bh)
        if (tracking) {
            lastTrackDetection = d
            overlay.locked = d
            stateLabel = "LOCK"
            latestDetections = emptyList()
            lastFrameTs = gray.timestampNs
        }
    }

    private fun initAtTap(gray: FastLumaExtractor.GrayFrame, nx: Float, ny: Float) {
        // Small manual ROI: about 8% of the short side instead of 18%.
        val box = (min(gray.width, gray.height) * 0.08f).coerceIn(14f, 26f)
        tracking = nativeTracker.nativeInit(
            gray.pixels,
            gray.width,
            gray.height,
            nx * gray.width,
            ny * gray.height,
            box * 1.12f,
            box
        )
        if (tracking) {
            val w = box * 1.12f / gray.width
            val h = box / gray.height
            val d = Detection(
                (nx - w * 0.5f).coerceIn(0f, 1f),
                (ny - h * 0.5f).coerceIn(0f, 1f),
                (nx + w * 0.5f).coerceIn(0f, 1f),
                (ny + h * 0.5f).coerceIn(0f, 1f),
                0.6f, -1, "SMART"
            )
            lastTrackDetection = d
            overlay.locked = d
            stateLabel = "LOCK"
            lastFrameTs = gray.timestampNs
        }
    }

    private fun nativeResultToDetection(out: FloatArray, w: Int, h: Int): Detection {
        val cx = out[1] / w
        val cy = out[2] / h
        val bw = out[3] / w
        val bh = out[4] / h
        return Detection(
            (cx - bw * 0.5f).coerceIn(0f, 1f),
            (cy - bh * 0.5f).coerceIn(0f, 1f),
            (cx + bw * 0.5f).coerceIn(0f, 1f),
            (cy + bh * 0.5f).coerceIn(0f, 1f),
            out[5].coerceIn(0f, 1f),
            -1,
            "LOCK",
            predicted = out[0].toInt() == 2 || out[8] > 0f
        )
    }

    private fun resetTracker() {
        cameraExecutor.execute { resetTrackerInternal(clearDetections = true) }
    }

    private fun resetTrackerInternal(clearDetections: Boolean) {
        nativeTracker.nativeReset()
        tracking = false
        pendingTap = null
        pendingReacquire = null
        lastTrackDetection = null
        lastFrameTs = 0L
        stateLabel = "SEARCH"
        overlay.locked = null
        if (clearDetections) latestDetections = emptyList()
        runOnUiThread {
            overlay.stateLabel = "SEARCH"
            overlay.locked = null
            if (clearDetections) overlay.detections = emptyList()
            overlay.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
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
                when (display?.rotation ?: Surface.ROTATION_0) {
                    Surface.ROTATION_90 ->
                        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
                    Surface.ROTATION_270 ->
                        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
                    Surface.ROTATION_180 ->
                        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped)
                    else -> System.arraycopy(r, 0, remapped, 0, 9)
                }
                val o = FloatArray(3)
                SensorManager.getOrientation(remapped, o)
                rawHeading = ((Math.toDegrees(o[0].toDouble()).toFloat() + 360f) % 360f)
                rawPitch = Math.toDegrees(o[1].toDouble()).toFloat()
                rawRoll = Math.toDegrees(o[2].toDouble()).toFloat()

                overlay.rollDeg = wrap180(rawRoll - zeroRoll)
                overlay.pitchDeg = wrap180(rawPitch - zeroPitch)
                overlay.headingDeg = ((rawHeading - zeroHeading + 360f) % 360f)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val k = (180.0 / Math.PI).toFloat()
                val gx = event.values[0] * k
                val gy = event.values[1] * k
                val gz = event.values[2] * k
                gyroP = 0.84f * gyroP + 0.16f * gz
                gyroQ = 0.84f * gyroQ + 0.16f * gx
                gyroR = 0.84f * gyroR - 0.16f * gy
                overlay.pDeg = if (abs(gyroP) < 0.25f) 0f else gyroP
                overlay.qDeg = if (abs(gyroQ) < 0.25f) 0f else gyroQ
                overlay.rDeg = if (abs(gyroR) < 0.25f) 0f else gyroR
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                gLoad = sqrt(x * x + y * y + z * z) / 9.80665f
                overlay.gLoad = gLoad
            }
        }
    }

    private fun wrap180(v: Float): Float {
        var x = v
        while (x > 180f) x -= 360f
        while (x < -180f) x += 360f
        return x
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        runCatching { nativeTracker.nativeReset() }
        runCatching { detector?.close() }
        cameraExecutor.shutdown()
        detectorExecutor.shutdown()
        super.onDestroy()
    }
}
