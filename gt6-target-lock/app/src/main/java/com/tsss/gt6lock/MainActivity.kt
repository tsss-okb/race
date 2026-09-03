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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Fusion v4.9 Strong Hold + ArduPilot + Shock Hold:
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
    private val sparseFlow = NativeSparseFlowGmcTracker()
    private val visualTracker = SmartVisualTracker()
    private val motionTracker = TargetMotionTracker(maxLostFrames = 10)
    private val mavlink = MavlinkTelemetry(port = 14550)
    private val profiler = PerformanceProfiler()
    private val losDiagnostics = LosDiagnostics()

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
    private var lastMavHudMs = 0L
    private var lastPerfHudMs = 0L
    private var lastYoloRunMs = 0L
    private var lastUiMs = 0L
    private var lastTapMs = 0L
    private var currentFlowScore = 0f
    private var currentVisualScore = 0f
    private var nccRescueFrames = 0
    private var nccRescueCooldown = 0
    private var lightFailStreak = 0
    private var strongLockStreak = 0
    private var jitterEma = 0f
    private var lastCenterX = Float.NaN
    private var lastCenterY = Float.NaN

    private var cameraHfovDeg = 70f
    private var cameraVfovDeg = 43f
    private var cameraFovEstimated = false

    private var shockFrames = 0
    private var shockGraceFrames = 0
    private var shockCooldown = 0
    private var lastShockYawRate = 0f
    private var lastShockPitchRate = 0f

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
            val choreoStartNs = System.nanoTime()

            val now = SystemClock.elapsedRealtime()
            val mavFresh = updateMavlinkHud(now)

            overlay.flowMs = profiler.flowMs
            overlay.nccMs = profiler.nccMs
            overlay.trackLoopMs = profiler.trackMs
            overlay.cpuPct = profiler.cpuPct
            overlay.ramMb = profiler.ramMb

            if (now - lastPerfHudMs >= 250L) {
                lastPerfHudMs = now
                overlay.perfLine = String.format(
                    Locale.US,
                    "PERF F %.2fms/%.0fHz  N %.2fms/%.0fHz  CPU %.0f%%  RAM %.0fMB",
                    profiler.flowMs, profiler.flowHz,
                    profiler.nccMs, profiler.nccHz,
                    profiler.cpuPct, profiler.ramMb
                )
                overlay.perfLine2 = String.format(
                    Locale.US,
                    "LOOP A %.2f  P95 %.2f  MAX %.2fms  HEAD %+.0f%% @60",
                    profiler.loopAvgMs, profiler.loopP95Ms,
                    profiler.loopMaxMs, profiler.frameHeadroomPct
                )

                val visionPct = (
                    profiler.lumaMs * cameraFpsEma +
                    profiler.flowMs * profiler.flowHz +
                    profiler.nccMs * profiler.nccHz +
                    profiler.fusionMs * trackFps
                ) / 10f
                val hudPct =
                    overlay.hudDrawMs * overlay.displayFps.coerceAtLeast(1f) / 10f
                val choreoPct =
                    profiler.choreoMs * profiler.choreoHz / 10f
                val sensorPct =
                    profiler.sensorMs * profiler.sensorHz / 10f
                val uiPct =
                    profiler.uiMs * profiler.uiHz / 10f
                val measuredPct =
                    visionPct + hudPct + choreoPct + sensorPct + uiPct
                val frameworkPct =
                    (profiler.cpuPct - measuredPct).coerceAtLeast(0f)

                overlay.perfLine3 = String.format(
                    Locale.US,
                    "STAGE L %.2f  F %.2f  N %.2f  X %.2f  HUD %.2fms | VIS %.0f%%",
                    profiler.lumaMs, profiler.flowMs, profiler.nccMs,
                    profiler.fusionMs, overlay.hudDrawMs, visionPct
                )
                overlay.perfLine4 = String.format(
                    Locale.US,
                    "FW CB %.0fHz/%.1fms  CH %.2f/%.0fHz  SNS %.2f/%.0fHz  UI %.2f/%.0fHz | CAMX/PREV EST %.0f%%",
                    profiler.cameraCallbackHz, profiler.cameraCallbackGapMs,
                    profiler.choreoMs, profiler.choreoHz,
                    profiler.sensorMs, profiler.sensorHz,
                    profiler.uiMs, profiler.uiHz,
                    frameworkPct
                )
            }

            var needDraw = mavFresh
            if (stateLabel != "SEARCH") {
                motionTracker.predictRealtime(System.nanoTime())?.let { predicted ->
                    overlay.locked = predicted
                    outputCounter++
                    if (now - outputWindowStart >= 1000L) {
                        outputFps = outputCounter * 1000f /
                            (now - outputWindowStart).coerceAtLeast(1L)
                        outputCounter = 0
                        outputWindowStart = now
                        overlay.outputFps = outputFps
                        updateDisplayTelemetry()
                    }
                    needDraw = true
                }
            }

            if (needDraw) overlay.postInvalidateOnAnimation()
            profiler.recordChoreographer(System.nanoTime() - choreoStartNs)
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
        estimateBackCameraFov()

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

    private fun updateMavlinkHud(now: Long): Boolean {
        val age = mavlink.ageMs(now)
        val fresh = age <= 1200L
        overlay.mavConnected = fresh
        if (!fresh) return false

        // Attitude is copied directly from the latest MAVLink sample.
        // No queue/interpolation allocation: newest packet always wins.
        overlay.mavRollDeg = mavlink.rollDeg
        overlay.mavPitchDeg = mavlink.pitchDeg
        overlay.mavHeadingDeg = mavlink.yawDeg
        overlay.rollDeg = mavlink.rollDeg
        overlay.pitchDeg = mavlink.pitchDeg
        overlay.headingDeg = mavlink.yawDeg
        overlay.pDeg = mavlink.rollRateDeg
        overlay.qDeg = mavlink.pitchRateDeg
        overlay.rDeg = mavlink.yawRateDeg

        // Text is refreshed at 20 Hz; the moving horizon still redraws at VSync.
        if (now - lastMavHudMs >= 50L) {
            lastMavHudMs = now
            val state = if (mavlink.armed) "ARMED" else "SAFE"
            val gps = gpsFixName(mavlink.gpsFix)
            val batPct = if (mavlink.batteryPct >= 0)
                "${mavlink.batteryPct}%"
            else "--"

            overlay.mavLine1 = String.format(
                Locale.US,
                "MAV %.0fHz  %dms  %s  %s  SYS %d:%d",
                mavlink.rxHz, age, state, mavlink.modeName(),
                mavlink.sysId, mavlink.compId
            )
            overlay.mavLine2 = String.format(
                Locale.US,
                "R %+.1f  P %+.1f  HDG %03.0f  AS %.1f  GS %.1f  ALT %.1fm",
                mavlink.rollDeg, mavlink.pitchDeg, mavlink.yawDeg,
                mavlink.airspeed, mavlink.groundSpeed, mavlink.altitudeM
            )
            overlay.mavLine3 = String.format(
                Locale.US,
                "CLB %+.1f  THR %d%%  GPS %s/%d  BAT %.2fV %.1fA %s",
                mavlink.climbMs, mavlink.throttlePct,
                gps, mavlink.satellites,
                mavlink.batteryV, mavlink.batteryA, batPct
            )
        }
        return true
    }

    private fun gpsFixName(fix: Int): String = when (fix) {
        2 -> "2D"
        3 -> "3D"
        4 -> "DGPS"
        5 -> "RTK-F"
        6 -> "RTK"
        else -> if (fix > 0) "F$fix" else "--"
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

    private fun estimateBackCameraFov() {
        runCatching {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull { cameraId ->
                manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: return@runCatching

            val c = manager.getCameraCharacteristics(id)
            val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()

            if (sensor != null && focal != null && focal > 0f) {
                cameraHfovDeg = (
                    2.0 * Math.toDegrees(
                        Math.atan(sensor.width.toDouble() / (2.0 * focal))
                    )
                ).toFloat().coerceIn(20f, 140f)
                cameraVfovDeg = (
                    2.0 * Math.toDegrees(
                        Math.atan(sensor.height.toDouble() / (2.0 * focal))
                    )
                ).toFloat().coerceIn(15f, 110f)
                cameraFovEstimated = true
            }
        }
    }

    private fun displayRotationDegrees(): Int = when (display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
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

            // Axis-safe lean preview:
            // keep the 720p cap from v4.6, but explicitly bind Preview rotation
            // to the current display orientation. ImageAnalysis stays untouched,
            // preserving the proven v4.5/v4.6 tracking coordinate system.
            val previewRotation = display?.rotation ?: Surface.ROTATION_0
            val previewBuilder = Preview.Builder()
                .setTargetRotation(previewRotation)
                .setTargetResolution(Size(1280, 720))
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

            overlay.yoloMode = if (request60)
                "IDLE / CAM60 / NATIVE-FLOW+NCC"
            else
                "IDLE / MAX / NATIVE-FLOW+NCC"
            overlay.invalidate()
            true
        }.getOrElse { false }
    }

    private fun analyzeImage(image: ImageProxy) {
        val callbackNowNs = System.nanoTime()
        profiler.recordCameraCallback(callbackNowNs)
        val trackStartNs = callbackNowNs
        try {
            if (prioritySet.compareAndSet(false, true)) {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            }

            val ts = image.imageInfo.timestamp
            var frameDtSec = 1f / 60f
            if (lastCameraTs != 0L) {
                val dt = (ts - lastCameraTs) / 1e9
                if (dt in 0.004..0.1) {
                    frameDtSec = dt.toFloat()
                    val f = (1.0 / dt).toFloat()
                    cameraFpsEma = if (cameraFpsEma == 0f) f else 0.90f * cameraFpsEma + 0.10f * f
                }
            }
            lastCameraTs = ts

            val lumaStartNs = System.nanoTime()
            val gray = luma.extract(image)
            profiler.recordLuma(System.nanoTime() - lumaStartNs)
            if (gray == null) return
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
                val mavFreshForShock = mavlink.ageMs() <= 1200L
                val ownYawRate = if (mavFreshForShock) mavlink.yawRateDeg else overlay.rDeg
                val ownPitchRate = if (mavFreshForShock) mavlink.pitchRateDeg else overlay.qDeg

                val ownRateMag = hypot(
                    ownYawRate.toDouble(),
                    ownPitchRate.toDouble()
                ).toFloat()
                val rateJerk = hypot(
                    (ownYawRate - lastShockYawRate).toDouble(),
                    (ownPitchRate - lastShockPitchRate).toDouble()
                ).toFloat() / frameDtSec.coerceAtLeast(1f / 240f)
                val previousLosJitter = losDiagnostics.current().jitterDegS

                val shockEvent =
                    ownRateMag >= 45f ||
                    rateJerk >= 850f ||
                    (previousLosJitter >= 10f && ownRateMag >= 16f)

                if (shockEvent && shockCooldown == 0) {
                    shockFrames = 6
                    shockGraceFrames = 2
                    shockCooldown = 12
                }

                val shockActive = shockFrames > 0
                overlay.shockHold = shockActive
                lastShockYawRate = ownYawRate
                lastShockPitchRate = ownPitchRate

                // Gyro prior is used only as the center of the widened rescue
                // search. It is never emitted as a flight-control command.
                val gyroDx = if (shockActive) {
                    -angleDeltaToNormalized(
                        ownYawRate * frameDtSec,
                        cameraHfovDeg
                    ).coerceIn(-0.10f, 0.10f)
                } else 0f
                val gyroDy = if (shockActive) {
                    angleDeltaToNormalized(
                        ownPitchRate * frameDtSec,
                        cameraVfovDeg
                    ).coerceIn(-0.10f, 0.10f)
                } else 0f
                val gyroPrior = if (shockActive) {
                    shiftDetection(
                        target,
                        gyroDx,
                        gyroDy,
                        (target.confidence * 0.97f).coerceAtLeast(0.25f),
                        predicted = true
                    )
                } else target

                // v3.8: native sparse flow/GMC runs on every camera frame.
                // NCC cadence stays equivalent to v3.7.
                val runFlow = true
                val flowStartNs = System.nanoTime()
                val flow = sparseFlow.track(gray, target)
                profiler.recordFlow(System.nanoTime() - flowStartNs)

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

                // v4.2 NCC Scheduler:
                // Native Flow remains ~60 Hz. A merely "not perfect" flow sample
                // must NOT force an expensive NCC scan on every camera frame.
                val hardFlowFail =
                    flow == null || flow.targetConsistency < 0.36f

                if (
                    hardFlowFail &&
                    nccRescueFrames == 0 &&
                    nccRescueCooldown == 0
                ) {
                    // Short 3-frame burst at full camera rate, then cooldown.
                    nccRescueFrames = 3
                    nccRescueCooldown = 9
                }

                val rescueBurst = nccRescueFrames > 0 || shockActive
                val softRescue = rescueBurst || lightFailStreak in 1..3
                overlay.softRescue = softRescue

                val nccPeriod = when {
                    shockActive -> 1L
                    rescueBurst -> 1L                         // ~60 Hz, short burst
                    stateLabel != "LOCK" -> 2L               // ~30 Hz
                    hardFlowFail -> 2L                        // ~30 Hz after burst
                    currentFlowScore < 0.52f -> 2L            // ~30 Hz
                    currentVisualScore < 0.64f -> 3L          // ~20 Hz
                    strongHold -> 5L                          // ~12 Hz
                    else -> 4L                                // ~15 Hz
                }

                val runTemplate =
                    rescueBurst || frameSerial % nccPeriod == 1L

                if (nccRescueFrames > 0) nccRescueFrames--
                if (nccRescueCooldown > 0) nccRescueCooldown--

                val visualBase = flowMeasurement ?: gyroPrior
                val nccStartNs = if (runTemplate) System.nanoTime() else 0L
                val visual =
                    if (runTemplate) {
                        visualTracker.track(
                            gray,
                            visualBase,
                            softRescue = softRescue,
                            shockRescue = shockActive,
                            freezeAdaptive = shockActive
                        )
                    } else null
                if (runTemplate) {
                    profiler.recordNcc(System.nanoTime() - nccStartNs)
                }
                if (runTemplate) {
                    attemptedMeasurement = true
                    currentVisualScore = visual?.score ?: visualTracker.score
                }

                val fusionStartNs = System.nanoTime()

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
                    if (shockActive && shockGraceFrames > 0) {
                        // Keep the last motion model for the first shock frames.
                        // This prevents a camera jerk from immediately becoming
                        // a normal miss/reacquire sequence.
                        shockGraceFrames--
                        strongLockStreak = 0
                    } else {
                        // Only a real failed tracker attempt counts as a miss.
                        lightFailStreak = (lightFailStreak + 1).coerceAtMost(30)
                        strongLockStreak = 0
                        motionTracker.miss(ts)
                    }
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

                    val mavFreshForLos = mavlink.ageMs() <= 1200L
                    val ownYawRate = if (mavFreshForLos) {
                        mavlink.yawRateDeg
                    } else {
                        overlay.rDeg
                    }
                    val ownPitchRate = if (mavFreshForLos) {
                        mavlink.pitchRateDeg
                    } else {
                        overlay.qDeg
                    }

                    val los = losDiagnostics.update(
                        targetCx = current.cx,
                        targetCy = current.cy,
                        timestampNs = ts,
                        horizontalFovDeg = cameraHfovDeg,
                        verticalFovDeg = cameraVfovDeg,
                        ownYawRateDegS = ownYawRate,
                        ownPitchRateDegS = ownPitchRate
                    )
                    overlay.losErrorXDeg = los.losXDeg
                    overlay.losErrorYDeg = los.losYDeg
                    overlay.losRateXDegS = los.compRateXDegS
                    overlay.losRateYDegS = los.compRateYDegS
                    overlay.losInsideDeadZone = los.insideDeadZone
                    overlay.losDeadZoneNormX = los.deadZoneNormX
                    overlay.losDeadZoneNormY = los.deadZoneNormY
                    overlay.losDiagnosticsActive = true
                    overlay.losCompSource = if (mavFreshForLos) "MAV" else "PHONE"

                    val bothLightTrackersWeak =
                        currentFlowScore < 0.34f && currentVisualScore < 0.52f

                    stateLabel = when {
                        shockActive && !gotMeasurement -> "PREDICT"
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

                if (shockFrames > 0) shockFrames--
                if (shockCooldown > 0) shockCooldown--
                if (shockFrames == 0) overlay.shockHold = false

                profiler.recordFusion(System.nanoTime() - fusionStartNs)
            } else {
                stateLabel = "SEARCH"
                overlay.softRescue = false
                overlay.shockHold = false
                overlay.losDiagnosticsActive = false
            }

            maybeRunYolo(image)

            val now = SystemClock.elapsedRealtime()
            if (now - lastUiMs >= 65L) {
                val uiStartNs = System.nanoTime()
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

                // Cache all formatted HUD strings here (~15 Hz), never in onDraw().
                overlay.statusLine1 = String.format(
                    Locale.US,
                    "CAM %.1f  MEAS %.1f  OUT %.1f  JIT %.1fpx  YOLO %s",
                    cameraFpsEma, trackFps, outputFps, jitterEma, overlay.yoloMode
                )
                overlay.statusLine2 = String.format(
                    Locale.US,
                    "DISP %.1f/MAX %.0f  • REQ120 • PREV720/R%d  • %s %.1fms",
                    overlay.displayFps, overlay.maxDisplayFps,
                    displayRotationDegrees(),
                    overlay.yoloBackend, overlay.yoloMs
                )
                overlay.phoneImuLine = String.format(
                    Locale.US,
                    "PHONE IMU  R %+.1f  P %+.1f  HDG %03d  G %.2f  rates %+.0f/%+.0f/%+.0f",
                    overlay.rollDeg, overlay.pitchDeg, overlay.headingDeg.toInt(),
                    overlay.gLoad, overlay.pDeg, overlay.qDeg, overlay.rDeg
                )

                val los = losDiagnostics.current()
                val rangeAge = mavlink.rangeAgeMs(now)
                val rangeFresh = mavlink.rangeValid && rangeAge <= 700L
                val rangeText = if (rangeFresh) {
                    String.format(
                        Locale.US,
                        "%.2fm/%dms/%s",
                        mavlink.rangeM, rangeAge, mavlink.rangeTypeName()
                    )
                } else {
                    "--"
                }
                val fovTag = if (cameraFovEstimated) "EST" else "APPROX"
                overlay.losLine1 = String.format(
                    Locale.US,
                    "LOS X %+.2f° Y %+.2f° | RAW %+.1f/%+.1f°/s | COMP(%s) %+.1f/%+.1f°/s",
                    los.losXDeg, los.losYDeg,
                    los.rawRateXDegS, los.rawRateYDegS,
                    overlay.losCompSource,
                    los.compRateXDegS, los.compRateYDegS
                )
                overlay.losLine2 = String.format(
                    Locale.US,
                    "MOTION %.2f°/s  JITTER %.2f°/s  DZ %s | RANGE %s | FOV %.1fx%.1f° %s",
                    los.motionDegS, los.jitterDegS,
                    if (los.insideDeadZone) "IN" else "OUT",
                    rangeText,
                    cameraHfovDeg, cameraVfovDeg, fovTag
                )

                overlay.invalidate()
                profiler.recordUi(System.nanoTime() - uiStartNs)
            }
        } finally {
            profiler.recordTrack(System.nanoTime() - trackStartNs)
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

    private fun angleDeltaToNormalized(angleDeg: Float, fovDeg: Float): Float {
        val halfFov = Math.toRadians((fovDeg.coerceIn(10f, 150f) * 0.5).toDouble())
        val angle = Math.toRadians(angleDeg.toDouble())
        return (Math.tan(angle) / (2.0 * Math.tan(halfFov))).toFloat()
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
            nccRescueFrames = 0
            nccRescueCooldown = 0
            shockFrames = 0
            shockGraceFrames = 0
            shockCooldown = 0
            lastShockYawRate = 0f
            lastShockPitchRate = 0f
            overlay.softRescue = false
            overlay.shockHold = false
            outputFps = 0f
            overlay.outputFps = 0f
            lightFailStreak = 0
            strongLockStreak = 0
            jitterEma = 0f
            losDiagnostics.clear()
            overlay.losDiagnosticsActive = false
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
        profiler.start()
        mavlink.start()
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
        profiler.stop()
        mavlink.stop()
        overlay.mavConnected = false
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sensorStartNs = System.nanoTime()
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

                if (mavlink.ageMs() > 1200L) {
                    overlay.rollDeg = wrap180(rawRoll - zeroRoll)
                    overlay.pitchDeg = wrap180(rawPitch - zeroPitch)
                    overlay.headingDeg = ((rawHeading - zeroHeading + 360f) % 360f)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val k = (180.0 / Math.PI).toFloat()
                val gx = event.values[0] * k
                val gy = event.values[1] * k
                val gz = event.values[2] * k
                gyroP = 0.84f * gyroP + 0.16f * gz
                gyroQ = 0.84f * gyroQ + 0.16f * gx
                gyroR = 0.84f * gyroR - 0.16f * gy
                if (mavlink.ageMs() > 1200L) {
                    overlay.pDeg = if (abs(gyroP) < 0.25f) 0f else gyroP
                    overlay.qDeg = if (abs(gyroQ) < 0.25f) 0f else gyroQ
                    overlay.rDeg = if (abs(gyroR) < 0.25f) 0f else gyroR
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                overlay.gLoad = sqrt(x*x + y*y + z*z) / 9.80665f
            }
        }
        profiler.recordSensor(System.nanoTime() - sensorStartNs)
    }

    private fun wrap180(v: Float): Float {
        var x = v
        while (x > 180f) x -= 360f
        while (x < -180f) x += 360f
        return x
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        profiler.stop()
        mavlink.stop()
        runCatching { detector?.close() }
        cameraExecutor.shutdown()
        detectorExecutor.shutdown()
        super.onDestroy()
    }
}
