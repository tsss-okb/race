package ru.racelab.phone.video

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.effects.Frame
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.arch.core.util.Function
import androidx.lifecycle.LifecycleOwner
import ru.racelab.phone.data.RaceRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackgroundCameraRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val app = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(app)
    private var provider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var effect: OverlayEffect? = null
    private var settings: VideoSettings = VideoSettings()
    private var bound = false
    private var currentLapNo = 0
    private var pendingLapNo: Int? = null
    private var stopping = false

    fun start(settings: VideoSettings, sessionId: String?) {
        if (recording != null || bound) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            RaceRuntime.updateVideoState(false, "Нет разрешения камеры")
            return
        }
        this.settings = settings
        currentLapNo = 0
        stopping = false
        bindAndRecord(settings, sessionId)
    }

    private fun bindAndRecord(requested: VideoSettings, sessionId: String?) {
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            val p = runCatching { future.get() }.getOrElse {
                RaceRuntime.updateVideoState(false, "CameraX provider: " + it.message)
                return@addListener
            }
            provider = p
            val configured = runCatching {
                p.unbindAll()
                val qualityOrder = if (requested.quality == VideoQualityMode.UHD) {
                    listOf(Quality.UHD, Quality.FHD, Quality.HD)
                } else {
                    listOf(Quality.FHD, Quality.HD, Quality.SD)
                }
                val recorderBuilder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            qualityOrder,
                            FallbackStrategy.lowerQualityOrHigherThan(qualityOrder.last())
                        )
                    )
                    .setTargetVideoEncodingBitRate(requested.bitrateMbps * 1_000_000)

                when (requested.codec) {
                    VideoCodecMode.H264 -> recorderBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    VideoCodecMode.H265 -> recorderBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    VideoCodecMode.AUTO -> Unit
                }
                val recorder = recorderBuilder.build()
                val capture = VideoCapture.Builder(recorder)
                    .setTargetFrameRate(Range(requested.fps, requested.fps))
                    .setVideoStabilizationEnabled(requested.stabilization)
                    .build()

                val groupBuilder = UseCaseGroup.Builder().addUseCase(capture)
                if (requested.burnHud) {
                    val overlay = makeHudEffect()
                    effect = overlay
                    groupBuilder.addEffect(overlay)
                }
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, groupBuilder.build())
                bound = true
                capture
            }

            if (configured.isFailure) {
                if (requested != fallbackSettings()) {
                    RaceRuntime.updateVideoState(false, "Видео fallback: " + configured.exceptionOrNull()?.message)
                    this.settings = fallbackSettings()
                    bindAndRecord(this.settings, sessionId)
                } else {
                    RaceRuntime.updateVideoState(false, "Камера не поддерживает профиль")
                }
                return@addListener
            }
            beginRecording(configured.getOrThrow(), sessionId, currentLapNo)
        }, executor)
    }

    private fun beginRecording(capture: VideoCapture<Recorder>, sessionId: String?, lapNo: Int) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val clip = if (lapNo > 0) "_lap" + lapNo else "_prelap"
        val name = "RaceLab_" + (sessionId ?: stamp) + clip + "_" + settings.quality.name + "_" + settings.fps + "fps"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RaceLab")
        }
        val options = MediaStoreOutputOptions.Builder(
            app.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = capture.output.prepareRecording(app, options)
        if (settings.audio && ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(executor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> RaceRuntime.updateVideoState(true, name)
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    if (!event.hasError()) {
                        recordVideoRef(sessionId, name, event.outputResults.outputUri.toString())
                    }
                    RaceRuntime.updateVideoState(false, if (event.hasError()) "Video error " + event.error else name)
                    val next = pendingLapNo
                    pendingLapNo = null
                    if (!stopping && next != null && settings.perLapClips) {
                        currentLapNo = next
                        rebindForNextClip(sessionId)
                    }
                }
            }
        }
    }

    private fun recordVideoRef(sessionId: String?, name: String, uri: String) {
        if (sessionId.isNullOrBlank() || uri.isBlank()) return
        runCatching {
            val root = File(app.getExternalFilesDir(null), "RaceLab/sessions/" + sessionId).apply { mkdirs() }
            File(root, "videos.txt").appendText(name + "\t" + uri + "\n")
        }
    }

    fun onLapStarted(lapNo: Int, sessionId: String?) {
        if (!settings.perLapClips || recording == null || currentLapNo == lapNo) return
        pendingLapNo = lapNo
        recording?.stop()
    }

    fun onLapCompleted(lapNo: Int, sessionId: String?) {
        if (!settings.perLapClips || recording == null) return
        pendingLapNo = lapNo + 1
        recording?.stop()
    }

    private fun rebindForNextClip(sessionId: String?) {
        bound = false
        effect?.close()
        effect = null
        provider?.unbindAll()
        bindAndRecord(settings, sessionId)
    }

    fun stop() {
        stopping = true
        pendingLapNo = null
        recording?.stop()
        recording = null
        bound = false
        effect?.close()
        effect = null
        runCatching { provider?.unbindAll() }
        provider = null
        RaceRuntime.updateVideoState(false, "Видео остановлено")
    }

    private fun fallbackSettings() = VideoSettings(
        quality = VideoQualityMode.FHD,
        fps = 30,
        codec = VideoCodecMode.AUTO,
        bitrateMbps = 18,
        stabilization = false,
        audio = settings.audio,
        autoRecord = settings.autoRecord,
        perLapClips = settings.perLapClips,
        burnHud = settings.burnHud
    )

    private fun makeHudEffect(): OverlayEffect {
        val handler = Handler(Looper.getMainLooper())
        val overlay = OverlayEffect(
            CameraEffect.VIDEO_CAPTURE,
            0,
            handler,
            Consumer<Throwable> { throwable -> RaceRuntime.markMessage("HUD effect: " + (throwable.message ?: "error")) }
        )
        overlay.setOnDrawListener(Function<Frame, Boolean> { frame ->
            val canvas = frame.overlayCanvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val st = RaceRuntime.state.value
            val w = frame.size.width.toFloat()
            val base = (w / 28f).coerceIn(28f, 72f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = base
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setShadowLayer(5f, 2f, 2f, Color.BLACK)
            }
            val small = Paint(paint).apply { textSize = base * .55f }
            val x = base * .5f
            var y = base * 1.3f
            canvas.drawText(st.speedKmh.toInt().toString() + " km/h", x, y, paint)
            y += base * .8f
            canvas.drawText("LAP " + formatMs(st.lapElapsedMs) + "   " + formatDelta(st.deltaMs), x, y, small)
            y += base * .65f
            canvas.drawText("G L " + "%+.2f".format(st.longitudinalG) + "  LAT " + "%+.2f".format(st.lateralG), x, y, small)
            y += base * .65f
            canvas.drawText("RPM " + (st.obd.rpm?.toInt()?.toString() ?: "—") + "  THR " + (st.obd.throttlePct?.let { "%.0f%%".format(it) } ?: "—"), x, y, small)
            true
        })
        return overlay
    }

    private fun formatMs(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val x = ms % 1000
        return "%02d:%02d.%03d".format(m, s, x)
    }

    private fun formatDelta(ms: Long?): String =
        if (ms == null) "Δ —" else if (ms < 0) "Δ -%.3f".format(-ms / 1000.0) else "Δ +%.3f".format(ms / 1000.0)
}
