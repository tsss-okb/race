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
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Choreographer
import android.view.Surface
import android.view.View
import android.view.ViewGroup
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
import kotlin.math.sqrt

/**
 * Fusion v3.7 Strong Hold Adaptive Hold:
 * - CameraX 60 fps + 640x360 luma path from PlaneAimPhone
 * - robust FB-checked sparse flow/GMC + dual-template multi-scale NCC
 * - constant-acceleration image-space motion filter
 * - YOLO26 only for SEARCH / prolonged REACQUIRE
 * - IMU is HUD-only and never rotates the video.
 */
class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detectorExecutor: ExecutorService

    private val luma = FastLumaExtractor(maxOutputWidth = 640, ringSize = 4)
    private val sparseFlow = SparseFlowGmcTracker()
    private val visualTracker = SmartVisualTracker()
    private val motionTracker = TargetMotionTracker(maxLostFrames = 10)

    private val inferenceBusy = AtomicBoolean(false)
    private val prioritySet = AtomicBoolean(false)

    @Volatile private var detector: Yolo26OnnxDetector? = null
    @Volatile private var boundCamera: Camera? = null
    @Volatile private var latestDetections: List<Detection> = emptyList()
    @Volatile private var pendingTap: Pair<Float, Float>? = null
    @Volatile private var pendingReacquire: Detection? = null
    @Volatile private var manualSearchRequested = false
    @Volatile private var stateLabel = "SEARCH"

    private var lastCameraTs = 0L
    private var cameraFpsEma = 0f
    private var frameSerial = 0L
    private var trackCounter = 0
    private var trackWindowStart = SystemClock.elapsedRealtime()
    private var trackFps = 0f
    private var outputCounter = 0
    private var outputWindowStart = SystemClock.elapsedRealtime()
    private var outputFps = 0f
    private var predictionLoopRunning = false
    private var lastYoloRunMs = 0L
    private var lastUiMs = 0L
    private var lastTapMs = 0L
    private var currentFlowScore = 0f
    private var currentVisualScore = 0f
    private var lightFailStreak = 0
    private var strongLockStreak = 0
    private var jitterEma = 0f
    private var lastCenterX = Float.NaN
    private var lastCenterY = Float.NaN

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
    private var gyroP = 0f
    private var gyroQ = 0f
    private var gyroR = 0f

    private val predictionFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!predictionLoopRunning) return

            if (stateLabel != "SEARCH") {
                motionTracker.predictRealtime(System.nanoTime())?.let { predicted ->
                    overlay.locked = predicted
                    outputCounter++
                    val now = SystemClock.elapsedRealtime()
                    if (now - outputWindowStart >= 1000L) {
                        outputFps = outputCounter * 1000f /
                            (now - outputWindowStart).coerceAtLeast(1L)
                        outputCounter = 0
                        outputWindowStart = now
                        overlay.outputFps = outputFps
                        updateDisplayTelemetry()
                    }
                    overlay.postInvalidateOnAnimation()
                }
            }

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

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
            Thread(r, "GT6-Track-640").apply { priority = Thread.MAX_PRIORITY }
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
        request120HzDisplay()
        root.postDelayed({ request120HzDisplay() }, 600L)

        overlay.onTapNormalized = { x, y ->
            val now = SystemClock.uptimeMillis()
            if (now - lastTapMs < 280L) resetTracker()
            else pendingTap = x to y
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
            overlay.bodyCalibrated = true
            overlay.invalidate()
        }
        overlay.onHudToggle = {
            overlay.showAvionics = !overlay.showAvionics
            overlay.invalidate()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun request120HzDisplay() {
        // Request refresh only. Android recommends preferredRefreshRate over
        // preferredDisplayModeId when resolution must stay unchanged.
        val attrs = window.attributes
        attrs.preferredDisplayModeId = 0
        attrs.preferredRefreshRate = 120f
        window.attributes = attrs

        if (Build.VERSION.SDK_INT >= 35) {
            requestFrameRateRecursive(window.decorView, 120f)
        }
        updateDisplayTelemetry()
    }

    private fun requestFrameRateRecursive(view: View, rate: Float) {
        if (Build.VERSION.SDK_INT >= 35) {
            view.setRequestedFrameRate(rate)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                requestFrameRateRecursive(view.getChildAt(i), rate)
            }
        }
    }

    private fun updateDisplayTelemetry() {
        val d = display ?: return
        overlay.displayFps = d.refreshRate
        overlay.maxDisplayFps =
            d.supportedModes.maxOfOrNull { it.refreshRate } ?: d.refreshRate
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            if (!tryBind(provider, true)) tryBind(provider, false)
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
                .setTargetResolution(Size(640, 360))
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

            overlay.yoloMode = if (request60) "IDLE / CAM60 / STRONG640" else "IDLE / MAX / STRONG640"
            overlay.invalidate()
            true
        }.getOrElse { false }
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
                    cameraFpsEma = if (cameraFpsEma == 0f) f else 0.90f * cameraFpsEma + 0.10f * f
                }
            }
            lastCameraTs = ts

            val gray = luma.extract(image) ?: return
            frameSerial++
            overlay.sourceAspectRatio = gray.width.toFloat() / gray.height.toFloat()

            pendingReacquire?.let {
                initFromDetection(gray, it)
                pendingReacquire = null
            }

            pendingTap?.let { tap ->
                val chosen = chooseDetectionAt(tap.first, tap.second)
                if (chosen != null) initFromDetection(gray, chosen)
                else initAtTap(gray, tap.first, tap.second)
                pendingTap = null
            }

            var gotMeasurement = false
            var target = motionTracker.locked

            if (target != null) {
                // Flow is the cheap high-rate measurement. NCC is adaptive:
                // normal lock -> every other frame; strong lock -> about 10 Hz.
                val runFlow = (frameSerial and 1L) == 0L
                val flow = if (runFlow) sparseFlow.track(gray, target) else null

                if (runFlow) {
                    currentFlowScore = flow?.let {
                        (0.72f * it.targetConsistency + 0.28f * it.globalConsistency)
                            .coerceIn(0f, 1f)
                    } ?: currentFlowScore * 0.92f
                }

                var attemptedMeasurement = runFlow
                val flowMeasurement =
                    if (
                        flow != null &&
                        flow.targetConsistency >= 0.36f &&
                        abs(flow.dxNorm) + abs(flow.dyNorm) < 0.16f
                    ) {
                        shiftDetection(
                            target,
                            flow.dxNorm,
                            flow.dyNorm,
                            (0.50f + 0.46f * flow.targetConsistency)
                                .coerceIn(0.50f, 0.96f),
                            predicted = flow.targetConsistency < 0.60f
                        )
                    } else null

                target = motionTracker.locked ?: target
                val strongHold =
                    stateLabel == "LOCK" &&
                    currentFlowScore >= 0.68f &&
                    currentVisualScore >= 0.72f &&
                    lightFailStreak == 0

                val flowWeakThisFrame =
                    runFlow && (flow == null || flow.targetConsistency < 0.50f)

                // Soft Rescue only after a real failed measurement. No gyro trigger,
                // no relaxed confidence thresholds and no huge search window.
                val softRescue = lightFailStreak in 1..3
                overlay.softRescue = softRescue

                val runTemplate =
                    softRescue ||
                    (!runFlow && (!strongHold || frameSerial % 6L == 1L)) ||
                    flowWeakThisFrame

                val visualBase = flowMeasurement ?: target
                val visual =
                    if (runTemplate) visualTracker.track(gray, visualBase, softRescue)
                    else null
                if (runTemplate) {
                    attemptedMeasurement = true
                    currentVisualScore = visual?.score ?: visualTracker.score
                }

                // Never move the box twice in one camera frame.
                // Prefer a confident NCC correction; otherwise use robust flow.
                val chosenMeasurement = when {
                    visual != null && visual.score >= 0.64f -> visual.detection
                    flowMeasurement != null -> flowMeasurement
                    visual != null && visual.score >= 0.52f -> visual.detection
                    else -> null
                }

                if (chosenMeasurement != null) {
                    motionTracker.applyVisual(chosenMeasurement, ts)
                    gotMeasurement = true
                }

                if (gotMeasurement) {
                    lightFailStreak = 0
                    if (currentFlowScore >= 0.62f || currentVisualScore >= 0.70f) {
                        strongLockStreak = (strongLockStreak + 1).coerceAtMost(30)
                    } else {
                        strongLockStreak = 0
                    }
                } else if (attemptedMeasurement) {
                    // Only a real failed tracker attempt counts as a miss.
                    lightFailStreak = (lightFailStreak + 1).coerceAtMost(30)
                    strongLockStreak = 0
                    motionTracker.miss(ts)
                }

                val current = motionTracker.locked
                if (current == null) {
                    visualTracker.clear()
                    sparseFlow.clear()
                    lightFailStreak = 0
                    strongLockStreak = 0
                    stateLabel = "SEARCH"
                    overlay.locked = null
                } else {
                    val projected = motionTracker.predict(ts) ?: current
                    overlay.locked = projected
                    updateJitter(projected, gray.width, gray.height)

                    val bothLightTrackersWeak =
                        currentFlowScore < 0.34f && currentVisualScore < 0.52f

                    stateLabel = when {
                        lightFailStreak >= 4 && bothLightTrackersWeak -> "REACQUIRE"
                        lightFailStreak >= 2 ||
                            (projected.predicted && strongLockStreak == 0) -> "PREDICT"
                        strongLockStreak >= 2 || gotMeasurement -> "LOCK"
                        else -> "PREDICT"
                    }

                    trackCounter++
                    val nowTrack = SystemClock.elapsedRealtime()
                    if (nowTrack - trackWindowStart >= 1000L) {
                        trackFps = trackCounter * 1000f /
                            (nowTrack - trackWindowStart).coerceAtLeast(1L)
                        trackCounter = 0
                        trackWindowStart = nowTrack
                    }
                }
            } else {
                stateLabel = "SEARCH"
                overlay.softRescue = false
            }

            maybeRunYolo(image)

            val now = SystemClock.elapsedRealtime()
            if (now - lastUiMs >= 65L) {
                lastUiMs = now
                val fused = (
                    0.58f * currentVisualScore +
                        0.32f * currentFlowScore +
                        0.10f * if (stateLabel == "LOCK") 1f else 0.35f
                    ).coerceIn(0f, 1f)
                overlay.stateLabel = stateLabel
                overlay.cameraFps = cameraFpsEma
                overlay.trackFps = trackFps
                overlay.trackConf = fused
                overlay.jitterPx = jitterEma
                overlay.detections = if (stateLabel == "LOCK") emptyList() else latestDetections
                overlay.invalidate()
            }
        } finally {
            image.close()
        }
    }

    private fun maybeRunYolo(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val hasLock = motionTracker.locked != null
        val bothLightTrackersWeak =
            currentFlowScore < 0.34f && currentVisualScore < 0.52f

        val interval = when {
            manualSearchRequested -> 0L
            !hasLock -> 900L
            stateLabel == "REACQUIRE" &&
                bothLightTrackersWeak &&
                lightFailStreak >= 4 -> 420L
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
        overlay.yoloMode = if (hasLock) "REACQUIRE" else "SEARCH"

        detectorExecutor.execute {
            try {
                val net = detector ?: Yolo26OnnxDetector(this).also { detector = it }
                val found = if (net.available) net.detect(bitmap) else emptyList()
                latestDetections = found
                overlay.yoloBackend = net.backendName
                overlay.yoloMs = net.lastInferenceMs

                val base = motionTracker.locked
                if (base != null && stateLabel != "LOCK" && found.isNotEmpty()) {
                    val best = found.minByOrNull { d ->
                        val dist = hypot((d.cx - base.cx).toDouble(), (d.cy - base.cy).toDouble())
                        val size = abs(d.width - base.width) + abs(d.height - base.height)
                        dist + 0.35 * size
                    }
                    if (best != null) {
                        val dist = hypot((best.cx - base.cx).toDouble(), (best.cy - base.cy).toDouble())
                        if (dist < 0.30) pendingReacquire = best
                    }
                }

                runOnUiThread {
                    overlay.detections = found
                    overlay.yoloMode = if (motionTracker.locked == null) "SEARCH"
                        else if (stateLabel == "LOCK") "IDLE" else "REACQUIRE"
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

    private fun initFromDetection(gray: FastLumaExtractor.GrayFrame, d0: Detection) {
        // Use a tighter core for the lock so the box doesn't swallow background.
        val w = (d0.width * 0.72f).coerceIn(0.018f, 0.28f)
        val h = (d0.height * 0.72f).coerceIn(0.018f, 0.34f)
        val d = boxAt(d0.cx, d0.cy, w, h, d0.confidence, d0.classId, d0.label)

        if (visualTracker.refreshTemplate(gray, d)) {
            motionTracker.forceLock(d, gray.timestampNs)
            sparseFlow.seed(gray, d)
            overlay.locked = d
            stateLabel = "LOCK"
            currentVisualScore = 1f
            currentFlowScore = 0.7f
            lightFailStreak = 0
            strongLockStreak = 2
            latestDetections = emptyList()
        }
    }

    private fun initAtTap(gray: FastLumaExtractor.GrayFrame, nx: Float, ny: Float) {
        val d = visualTracker.seedAt(gray, nx, ny) ?: return
        motionTracker.forceLock(d, gray.timestampNs)
        sparseFlow.seed(gray, d)
        overlay.locked = d
        stateLabel = "LOCK"
        currentVisualScore = 1f
        currentFlowScore = 0.7f
        lightFailStreak = 0
        strongLockStreak = 2
    }

    private fun shiftDetection(
        d: Detection,
        dx: Float,
        dy: Float,
        confidence: Float,
        predicted: Boolean
    ): Detection {
        return boxAt(
            (d.cx + dx).coerceIn(0f, 1f),
            (d.cy + dy).coerceIn(0f, 1f),
            d.width, d.height,
            confidence, d.classId, d.label,
            predicted
        )
    }

    private fun boxAt(
        cx: Float, cy: Float, w: Float, h: Float,
        confidence: Float, classId: Int, label: String,
        predicted: Boolean = false
    ): Detection {
        var x1 = cx - w * 0.5f
        var x2 = cx + w * 0.5f
        var y1 = cy - h * 0.5f
        var y2 = cy + h * 0.5f
        if (x1 < 0f) { x2 -= x1; x1 = 0f }
        if (x2 > 1f) { x1 -= x2 - 1f; x2 = 1f }
        if (y1 < 0f) { y2 -= y1; y1 = 0f }
        if (y2 > 1f) { y1 -= y2 - 1f; y2 = 1f }
        return Detection(
            x1.coerceIn(0f,1f), y1.coerceIn(0f,1f),
            x2.coerceIn(0f,1f), y2.coerceIn(0f,1f),
            confidence, classId, label, predicted
        )
    }

    private fun updateJitter(d: Detection, w: Int, h: Int) {
        val x = d.cx * w
        val y = d.cy * h
        if (!lastCenterX.isNaN() && !lastCenterY.isNaN()) {
            val delta = hypot((x - lastCenterX).toDouble(), (y - lastCenterY).toDouble()).toFloat()
            jitterEma = 0.90f * jitterEma + 0.10f * delta
        }
        lastCenterX = x
        lastCenterY = y
    }

    private fun resetTracker() {
        cameraExecutor.execute {
            motionTracker.clear()
            visualTracker.clear()
            sparseFlow.clear()
            pendingTap = null
            pendingReacquire = null
            latestDetections = emptyList()
            currentFlowScore = 0f
            currentVisualScore = 0f
            overlay.softRescue = false
            outputFps = 0f
            overlay.outputFps = 0f
            lightFailStreak = 0
            strongLockStreak = 0
            jitterEma = 0f
            lastCenterX = Float.NaN
            lastCenterY = Float.NaN
            stateLabel = "SEARCH"
            runOnUiThread {
                overlay.locked = null
                overlay.detections = emptyList()
                overlay.stateLabel = "SEARCH"
                overlay.invalidate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        request120HzDisplay()
        window.decorView.postDelayed({ request120HzDisplay() }, 400L)
        if (!predictionLoopRunning) {
            predictionLoopRunning = true
            outputCounter = 0
            outputWindowStart = SystemClock.elapsedRealtime()
            Choreographer.getInstance().postFrameCallback(predictionFrameCallback)
        }
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        predictionLoopRunning = false
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
                overlay.gLoad = sqrt(x*x + y*y + z*z) / 9.80665f
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
        runCatching { detector?.close() }
        cameraExecutor.shutdown()
        detectorExecutor.shutdown()
        super.onDestroy()
    }
}
