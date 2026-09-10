package com.tsss.gt6optical

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.util.Range
import android.util.Size
import android.util.SizeF
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.AspectRatioStrategy
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionSelector
import androidx.camera.core.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.tsss.gt6optical.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraExecutor: ExecutorService
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    private var records: List<CameraRecord> = emptyList()
    private var recordById: Map<String, CameraRecord> = emptyMap()
    private var activeCameraId: String? = null
    private var forcedPhysicalId: String? = null
    private var activePhysicalIdFromResult: String? = null
    private var captureFocalMm: Float? = null
    private var captureZoomRatio: Float? = null
    private var captureFps = 0f
    private var lastSensorTimestampNs = 0L
    private var lastHudUpdateNs = 0L

    private var mainRearId: String? = null
    private var logicalRearId: String? = null
    private var teleDirectId: String? = null
    private var telePhysicalCandidateId: String? = null
    private var mainPhysicalCandidateId: String? = null
    private var requestedFpsRange: Range<Int>? = null
    private var diagnostics: String = ""
    private lateinit var trackerEngine: TrackerEngine

    private var currentZoomRatio = 1f
    private var minZoomRatio = 1f
    private var maxZoomRatio = 10f
    private var zoomAnimator: ValueAnimator? = null
    private var lastTrackerHudNs = 0L
    private var compactHud = false

    private data class CameraRecord(
        val id: String,
        val listed: Boolean,
        val parentLogicalId: String?,
        val facing: Int?,
        val focalLengths: FloatArray,
        val sensorSize: SizeF?,
        val physicalIds: Set<String>,
        val capabilities: IntArray,
        val zoomRatioRange: Range<Float>?,
        val aeFpsRanges: List<Range<Int>>,
        val highSpeedProfiles: List<String>,
        val hardwareLevel: Int?,
        val activePhysicalResultKey: Boolean
    ) {
        val maxFocal: Float get() = focalLengths.maxOrNull() ?: 0f
        val sensorWidth: Float get() = sensorSize?.width ?: 0f
        val sensorArea: Float get() = (sensorSize?.width ?: 0f) * (sensorSize?.height ?: 0f)
        val eq35Horizontal: Float
            get() = if (maxFocal > 0f && sensorWidth > 0f) maxFocal * 36f / sensorWidth else 0f
        val isLogical: Boolean
            get() = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraSystem() else binding.status.text = "CAMERA permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraExecutor = Executors.newSingleThreadExecutor()
        trackerEngine = TrackerEngine { snap ->
            binding.trackerOverlay.setSnapshot(snap)
            val now = System.nanoTime()
            if (now - lastTrackerHudNs >= 90_000_000L) {
                lastTrackerHudNs = now
                runOnUiThread { updateTrackerHud(snap) }
            }
        }
        binding.trackerOverlay.onTapNormalized = { nx, ny ->
            trackerEngine.tap(nx, ny)
        }
        binding.trackerOverlay.onZoomScale = { scale ->
            runOnUiThread {
                val target = (currentZoomRatio * scale).coerceIn(minZoomRatio, effectiveMaxZoom())
                requestZoom(target, reportError = false)
            }
        }

        binding.btnMain.setOnClickListener {
            mainRearId?.let { bindCamera(it, 1f, null) } ?: toast("Main camera не найдена")
        }
        binding.btnLogical2x.setOnClickListener {
            val id = logicalRearId ?: mainRearId
            if (id == null) toast("Logical/main camera не найдена") else bindCamera(id, 2f, null)
        }
        binding.btnTele.setOnClickListener {
            val id = teleDirectId
            if (id == null) toast("Публичный TELE cameraId не найден") else bindCamera(id, 1f, null)
        }
        binding.btnForcePhysical.setOnClickListener {
            val logical = logicalRearId
            val physical = telePhysicalCandidateId
            when {
                logical == null -> toast("Logical rear camera не найдена")
                physical == null -> toast("Physical TELE candidate не найден")
                else -> bindCamera(logical, 1f, physical)
            }
        }
        binding.btnTrackReset.setOnClickListener {
            trackerEngine.reset("RESET")
        }
        binding.btnRescan.setOnClickListener {
            scanAllCameras()
            toast("Камеры пересканированы")
        }
        binding.btnFpsAuto.setOnClickListener {
            requestedFpsRange = null
            rebindCurrentCamera()
        }
        binding.btnFps60.setOnClickListener {
            val id = activeCameraId ?: logicalRearId ?: mainRearId
            val rec = id?.let { recordById[it] }
            val range = rec?.let { selectFpsRange(it, 60) }
            if (range == null) {
                toast("60 FPS range не заявлен этой камерой")
            } else {
                requestedFpsRange = range
                rebindCurrentCamera()
            }
        }
        binding.btnZoom1.setOnClickListener { animateZoomTo(1f) }
        binding.btnZoom2.setOnClickListener { animateZoomTo(2f) }
        binding.btnZoom5.setOnClickListener { animateZoomTo(5f) }
        binding.btnZoom10.setOnClickListener { animateZoomTo(10f) }

        binding.btnDebug.setOnClickListener {
            binding.debugPanel.visibility =
                if (binding.debugPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnHud.setOnClickListener {
            compactHud = !compactHud
            binding.hudUniq.visibility = if (compactHud) View.GONE else View.VISIBLE
            binding.hudNcc.visibility = if (compactHud) View.GONE else View.VISIBLE
            binding.hudProc.visibility = if (compactHud) View.GONE else View.VISIBLE
            binding.targetCard.visibility = if (compactHud) View.GONE else View.VISIBLE
            binding.btnHud.text = if (compactHud) "HUD +" else "HUD MIN"
        }

        binding.btnDump.setOnClickListener { copyDiagnostics() }

        binding.zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val ratio = progressToZoom(progress)
                requestZoom(ratio, reportError = false)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                zoomAnimator?.cancel()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraSystem()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraSystem() {
        scanAllCameras()

        // GT6 advertises a fixed 60-60 AE range on the main logical rear camera.
        // Start there by default: doubling temporal sampling greatly reduces per-frame
        // displacement and makes the visual tracker more stable during quick hand motion.
        val startupRecord = logicalRearId?.let { recordById[it] }
            ?: mainRearId?.let { recordById[it] }
        requestedFpsRange = startupRecord?.let { selectFpsRange(it, 60) }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            mainRearId?.let { bindCamera(it, 1f, null) } ?: toast("Задняя камера не найдена")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scanAllCameras() {
        val listedIds = cameraManager.cameraIdList.toSet()
        val listedRecords = listedIds.mapNotNull { readRecord(it, listed = true, parentLogicalId = null) }
        val rearListed = listedRecords.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }

        val logical = rearListed
            .filter { it.isLogical && it.physicalIds.isNotEmpty() }
            .maxWithOrNull(compareBy<CameraRecord> { it.physicalIds.size }.thenBy { it.sensorArea })

        logicalRearId = logical?.id
        mainRearId = logical?.id ?: rearListed.maxByOrNull { it.sensorArea }?.id

        val physicalRecords = rearListed
            .filter { it.isLogical && it.physicalIds.isNotEmpty() }
            .flatMap { parent ->
                parent.physicalIds.mapNotNull { pid ->
                    readRecord(pid, listed = listedIds.contains(pid), parentLogicalId = parent.id)
                }
            }
            .distinctBy { it.id }

        records = (listedRecords + physicalRecords).distinctBy { "${it.id}|${it.parentLogicalId ?: "-"}" }
        recordById = records.groupBy { it.id }.mapValues { it.value.first() }

        val selectedLogicalPhysical = physicalRecords
            .filter { it.parentLogicalId == logicalRearId }
            .distinctBy { it.id }

        mainPhysicalCandidateId = selectedLogicalPhysical
            .filter { cameraRole(it) == "MAIN" }
            .minByOrNull { opticalDistanceFromMain(it) }
            ?.id

        telePhysicalCandidateId = selectedLogicalPhysical
            .filter { cameraRole(it) == "TELE" }
            .maxByOrNull { opticalStrength(it) }
            ?.id

        teleDirectId = telePhysicalCandidateId?.takeIf { listedIds.contains(it) }

        if (teleDirectId == null) {
            teleDirectId = rearListed
                .filter { it.id != mainRearId && cameraRole(it) == "TELE" }
                .maxByOrNull { opticalStrength(it) }
                ?.id
        }

        diagnostics = buildDiagnostics(records)
        binding.status.text = diagnostics
        updateLiveStatus()
    }

    private fun cameraRole(r: CameraRecord): String {
        val main = mainRearId?.let { recordById[it] }
        if (r.id == mainRearId || r.id == mainPhysicalCandidateId) return "MAIN"
        val baseEq = main?.eq35Horizontal ?: 0f
        val baseFocal = main?.maxFocal ?: 0f

        if (r.eq35Horizontal > 0f && baseEq > 0f) {
            val ratio = r.eq35Horizontal / baseEq
            return when {
                ratio < 0.86f -> "ULTRA"
                ratio > 1.25f -> "TELE"
                else -> "MAIN"
            }
        }
        if (r.maxFocal > 0f && baseFocal > 0f) {
            val ratio = r.maxFocal / baseFocal
            return when {
                ratio < 0.72f -> "ULTRA"
                ratio > 1.35f -> "TELE"
                else -> "MAIN"
            }
        }
        return "UNKNOWN"
    }

    private fun opticalDistanceFromMain(r: CameraRecord): Float {
        val main = mainRearId?.let { recordById[it] } ?: return Float.MAX_VALUE
        return if (r.eq35Horizontal > 0f && main.eq35Horizontal > 0f) {
            abs(r.eq35Horizontal - main.eq35Horizontal)
        } else abs(r.maxFocal - main.maxFocal)
    }

    private fun opticalStrength(r: CameraRecord): Float =
        if (r.eq35Horizontal > 0f) r.eq35Horizontal else r.maxFocal

    private fun readRecord(
        id: String,
        listed: Boolean,
        parentLogicalId: String?
    ): CameraRecord? = runCatching {
        val c = cameraManager.getCameraCharacteristics(id)
        val resultKeys = runCatching { c.availableCaptureResultKeys }.getOrNull()
        val streamMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val aeRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList() ?: emptyList()

        val hs = mutableListOf<String>()
        streamMap?.highSpeedVideoSizes?.forEach { s ->
            runCatching {
                streamMap.getHighSpeedVideoFpsRangesFor(s).forEach { r ->
                    hs += "${s.width}x${s.height}@${r.lower}-${r.upper}"
                }
            }
        }

        CameraRecord(
            id = id,
            listed = listed,
            parentLogicalId = parentLogicalId,
            facing = c.get(CameraCharacteristics.LENS_FACING),
            focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(),
            sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
            physicalIds = if (android.os.Build.VERSION.SDK_INT >= 28) c.physicalCameraIds else emptySet(),
            capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf(),
            zoomRatioRange = if (android.os.Build.VERSION.SDK_INT >= 30) {
                c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else null,
            aeFpsRanges = aeRanges,
            highSpeedProfiles = hs.distinct(),
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            activePhysicalResultKey = if (android.os.Build.VERSION.SDK_INT >= 29) {
                resultKeys?.contains(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) == true
            } else false
        )
    }.getOrNull()

    private fun buildDiagnostics(list: List<CameraRecord>): String = buildString {
        val publicRear = list.count { it.listed && it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val physical = list.filter { it.parentLogicalId != null }.distinctBy { it.id }
        appendLine("PUBLIC rear: $publicRear   PHYSICAL: ${physical.size}")
        appendLine("MAIN=$mainRearId  LOGICAL=$logicalRearId  MAIN_PHYS=${mainPhysicalCandidateId ?: "NONE"}")
        appendLine("TELE direct=${teleDirectId ?: "NONE"}  TELE physical=${telePhysicalCandidateId ?: "NONE"}")
        val main = mainRearId?.let { recordById[it] }
        if (main != null) {
            appendLine("BASE MAIN: f=${focalText(main.maxFocal)}mm eq≈${eqText(main.eq35Horizontal)}mm")
        }

        list.filter { it.listed && it.facing == CameraCharacteristics.LENS_FACING_BACK }.forEach { r ->
            appendLine(
                "ID ${r.id}: ${cameraRole(r)}  f=${focalText(r.maxFocal)}mm eq≈${eqText(r.eq35Horizontal)}mm" +
                    if (r.physicalIds.isNotEmpty()) " p=${r.physicalIds}" else ""
            )
        }
        physical.forEach { r ->
            appendLine("P ${r.id}: ${cameraRole(r)}  f=${focalText(r.maxFocal)}mm eq≈${eqText(r.eq35Horizontal)}mm")
        }
        val fpsRec = logicalRearId?.let { recordById[it] }
            ?: mainRearId?.let { recordById[it] }
        if (fpsRec != null) {
            appendLine("AE FPS: ${fpsRangesText(fpsRec.aeFpsRanges)}")
            append("HS: ")
            append(
                if (fpsRec.highSpeedProfiles.isEmpty()) "none"
                else fpsRec.highSpeedProfiles.take(4).joinToString()
            )
        }
    }

    private fun bindCamera(cameraId: String, initialZoom: Float, physicalId: String?) {
        val provider = provider ?: return

        if (physicalId != null) {
            val valid = recordById[cameraId]?.physicalIds ?: emptySet()
            if (physicalId !in valid) {
                toast("Physical $physicalId не принадлежит logical $cameraId")
                return
            }
        }

        runCatching {
            val selector = CameraSelector.Builder()
                .addCameraFilter { infos ->
                    infos.filter { info ->
                        runCatching { Camera2CameraInfo.from(info).cameraId == cameraId }
                            .getOrDefault(false)
                    }
                }
                .build()

            val previewResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()

            val analysisResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 360),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(previewResolutionSelector)

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    onCaptureResult(result)
                }
            }

            Camera2Interop.Extender(previewBuilder).apply {
                setSessionCaptureCallback(captureCallback)
                requestedFpsRange?.let {
                    setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
                if (physicalId != null) setPhysicalCameraId(physicalId)
            }

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            Camera2Interop.Extender(analysisBuilder).apply {
                requestedFpsRange?.let {
                    setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
                if (physicalId != null) setPhysicalCameraId(physicalId)
            }

            val analysis = analysisBuilder.build().also { useCase ->
                useCase.setAnalyzer(cameraExecutor) { image ->
                    try {
                        trackerEngine.process(image)
                    } finally {
                        image.close()
                    }
                }
            }

            camera?.cameraInfo?.zoomState?.removeObservers(this)
            provider.unbindAll()
            resetCaptureTelemetry()
            trackerEngine.reset("CAM SWITCH")

            camera = provider.bindToLifecycle(this, selector, preview, analysis)
            activeCameraId = cameraId
            forcedPhysicalId = physicalId
            observeZoom(cameraId, initialZoom)
            updateLiveStatus()
        }.onFailure { e ->
            toast("OPEN ERROR: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    private fun observeZoom(cameraId: String, initialZoom: Float) {
        val cam = camera ?: return
        cam.cameraInfo.zoomState.observe(this) { zs ->
            if (zs == null) return@observe
            minZoomRatio = zs.minZoomRatio.coerceAtLeast(0.1f)
            maxZoomRatio = zs.maxZoomRatio.coerceAtLeast(minZoomRatio)
            currentZoomRatio = zs.zoomRatio.coerceIn(minZoomRatio, maxZoomRatio)

            val uiMax = effectiveMaxZoom()
            binding.zoomLabel.text = "ZOOM  %.2f×   [%.1f–%.1f]".format(
                Locale.US, currentZoomRatio, minZoomRatio, uiMax
            )
            binding.hudZoom.text = "ZOOM %.2f×".format(Locale.US, currentZoomRatio)
            binding.zoomSeek.progress = zoomToProgress(currentZoomRatio)
        }

        val range = recordById[cameraId]?.zoomRatioRange
        if (range != null) {
            minZoomRatio = range.lower.coerceAtLeast(0.1f)
            maxZoomRatio = range.upper.coerceAtLeast(minZoomRatio)
        }
        val desired = initialZoom.coerceIn(minZoomRatio, effectiveMaxZoom())
        requestZoom(desired, reportError = true)
    }

    private fun effectiveMaxZoom(): Float = minOf(maxZoomRatio, 10f).coerceAtLeast(minZoomRatio)

    private fun progressToZoom(progress: Int): Float {
        val lo = minZoomRatio.coerceAtLeast(0.1f)
        val hi = effectiveMaxZoom()
        if (hi <= lo) return lo
        val t = (progress.coerceIn(0, 1000) / 1000f).toDouble()
        return (lo * exp(ln((hi / lo).toDouble()) * t)).toFloat().coerceIn(lo, hi)
    }

    private fun zoomToProgress(ratio: Float): Int {
        val lo = minZoomRatio.coerceAtLeast(0.1f)
        val hi = effectiveMaxZoom()
        if (hi <= lo) return 0
        val r = ratio.coerceIn(lo, hi)
        val t = ln((r / lo).toDouble()) / ln((hi / lo).toDouble())
        return (t * 1000.0).toInt().coerceIn(0, 1000)
    }

    private fun animateZoomTo(targetRaw: Float) {
        val target = targetRaw.coerceIn(minZoomRatio, effectiveMaxZoom())
        val start = currentZoomRatio.coerceIn(minZoomRatio, effectiveMaxZoom())
        if (kotlin.math.abs(target - start) < 0.01f) return

        zoomAnimator?.cancel()
        val distance = kotlin.math.abs(target - start)
        val duration = (220L + (distance * 55f).toLong()).coerceIn(240L, 720L)

        zoomAnimator = ValueAnimator.ofFloat(start, target).apply {
            this.duration = duration
            addUpdateListener { animator ->
                requestZoom(animator.animatedValue as Float, reportError = false)
            }
            start()
        }
    }

    private fun requestZoom(ratio: Float, reportError: Boolean) {
        val cam = camera ?: return
        val safeRatio = ratio.coerceIn(minZoomRatio, effectiveMaxZoom())
        currentZoomRatio = safeRatio
        binding.zoomLabel.text = "ZOOM  %.2f×   [%.1f–%.1f]".format(
            Locale.US, safeRatio, minZoomRatio, effectiveMaxZoom()
        )
        binding.hudZoom.text = "ZOOM %.2f×".format(Locale.US, safeRatio)
        val future = runCatching { cam.cameraControl.setZoomRatio(safeRatio) }.getOrElse { e ->
            if (reportError) toast("Zoom %.2f× rejected: %s".format(Locale.US, safeRatio, e.javaClass.simpleName))
            return
        }
        if (reportError) {
            future.addListener({
                runCatching { future.get() }.onFailure {
                    toast("Zoom request failed: ${it.javaClass.simpleName}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun updateTrackerHud(s: TrackerOverlayView.Snapshot) {
        val stateColor = when (s.state) {
            "LOCK", "REACQUIRED" -> Color.rgb(66, 255, 103)
            "ACQUIRE" -> Color.rgb(84, 221, 255)
            "PRED" -> Color.rgb(255, 211, 79)
            "REACQUIRE" -> Color.rgb(255, 146, 61)
            else -> Color.rgb(255, 82, 82)
        }

        binding.hudState.text = s.state
        binding.hudState.setTextColor(stateColor)
        binding.hudConf.text = "CONF %.0f".format(Locale.US, s.confidence * 100f)
        binding.hudUniq.text = "UNIQ %.1f".format(Locale.US, s.uniqueness * 100f)
        binding.hudNcc.text = "NCC %.0f".format(Locale.US, s.nccScore * 100f)
        binding.hudProc.text = "%.1f ms".format(Locale.US, s.processMs)

        binding.targetState.text = s.state
        binding.targetState.setTextColor(stateColor)
        binding.targetMetrics.text = buildString {
            append("CONF  %.0f%%\n".format(Locale.US, s.confidence * 100f))
            append("NCC   %.0f%%\n".format(Locale.US, s.nccScore * 100f))
            append("UNIQ  %.1f\n".format(Locale.US, s.uniqueness * 100f))
            append("LOST  ${s.lostFrames}")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                animateZoomTo(currentZoomRatio * 1.22f)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                animateZoomTo(currentZoomRatio / 1.22f)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun onCaptureResult(result: TotalCaptureResult) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            activePhysicalIdFromResult = runCatching {
                result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            }.getOrNull()
        }
        captureFocalMm = result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            captureZoomRatio = result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
        }

        val ts = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: 0L
        if (lastSensorTimestampNs > 0L && ts > lastSensorTimestampNs) {
            val dt = ts - lastSensorTimestampNs
            if (dt > 0L) {
                val instant = 1_000_000_000f / dt.toFloat()
                captureFps = if (captureFps <= 0f) instant else captureFps * 0.82f + instant * 0.18f
            }
        }
        if (ts > 0L) lastSensorTimestampNs = ts

        val now = System.nanoTime()
        if (now - lastHudUpdateNs > 180_000_000L) {
            lastHudUpdateNs = now
            runOnUiThread { updateLiveStatus() }
        }
    }

    private fun updateLiveStatus() {
        if (!::binding.isInitialized) return
        val activeId = activeCameraId
        val rec = activeId?.let { recordById[it] }
        val activePhys = activePhysicalIdFromResult
        val effectiveRecord = activePhys?.let { recordById[it] } ?: rec
        val activeRole = effectiveRecord?.let { cameraRole(it) } ?: "UNKNOWN"
        val confirm = when {
            forcedPhysicalId == null -> "n/a"
            activePhys == null -> "?"
            forcedPhysicalId == activePhys -> "YES"
            else -> "NO"
        }

        binding.hudLens.text = "LENS $activeRole"
        binding.hudFps.text = if (captureFps > 0f) "FPS %.0f".format(Locale.US, captureFps) else "FPS —"

        binding.liveStatus.text = buildString {
            appendLine("OPEN ${activeId ?: "-"} | ACTIVE ROLE=$activeRole")
            appendLine("FORCED PHYS=${forcedPhysicalId ?: "-"}  CONFIRM=$confirm")
            appendLine("ACTIVE PHYS=${activePhys ?: "unknown"}")
            append("FRAME f=${captureFocalMm?.let { focalText(it) } ?: "?"}mm")
            append("  zoom=${captureZoomRatio?.let { String.format(Locale.US, "%.2f×", it) } ?: "?"}")
            append("  CAP=${if (captureFps > 0f) String.format(Locale.US, "%.1f fps", captureFps) else "?"}")
            appendLine()
            append("FPS REQ=${requestedFpsRange?.let { "${it.lower}-${it.upper}" } ?: "AUTO"}")
        }
    }

    private fun resetCaptureTelemetry() {
        activePhysicalIdFromResult = null
        captureFocalMm = null
        captureZoomRatio = null
        captureFps = 0f
        lastSensorTimestampNs = 0L
        lastHudUpdateNs = 0L
    }

    private fun selectFpsRange(r: CameraRecord, target: Int): Range<Int>? {
        val candidates = r.aeFpsRanges.filter { it.lower <= target && it.upper >= target }
        return candidates.minWithOrNull(
            compareBy<Range<Int>>(
                { kotlin.math.abs(it.upper - it.lower) },
                { kotlin.math.abs(it.lower - target) },
                { kotlin.math.abs(it.upper - target) }
            )
        )
    }

    private fun rebindCurrentCamera() {
        val id = activeCameraId ?: logicalRearId ?: mainRearId ?: run {
            toast("Камера не выбрана")
            return
        }
        val zoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        bindCamera(id, zoom, forcedPhysicalId)
    }

    private fun fpsRangesText(ranges: List<Range<Int>>): String =
        ranges.joinToString(prefix = "[", postfix = "]") { "${it.lower}-${it.upper}" }

    private fun focalText(v: Float): String = String.format(Locale.US, "%.1f", v)
    private fun eqText(v: Float): String = if (v > 0f) String.format(Locale.US, "%.0f", v) else "?"

    private fun copyDiagnostics() {
        val text = buildString {
            appendLine("GT6 v2.5 HUD + tracker")
            appendLine(binding.liveStatus.text)
            appendLine()
            appendLine(diagnostics)
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GT6 camera diagnostics", text))
        toast("Диагностика скопирована")
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        zoomAnimator?.cancel()
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        provider?.unbindAll()
        cameraExecutor.shutdownNow()
        super.onDestroy()
    }
}
