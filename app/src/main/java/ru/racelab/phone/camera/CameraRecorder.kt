package ru.racelab.phone.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

class CameraRecorder(private val context: Context) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    var isBound = false
        private set
    var isRecording = false
        private set

    fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner, onReady: (Boolean, String?) -> Unit = { _, _ -> }) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val provider = future.get()
                provider.unbindAll()

                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.UHD, Quality.FHD, Quality.HD),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                        )
                    )
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                videoCapture = capture
                isBound = true
            }.onSuccess { onReady(true, null) }
                .onFailure { onReady(false, it.message) }
        }, mainExecutor)
    }

    fun switchCamera(previewView: PreviewView, lifecycleOwner: LifecycleOwner, onReady: (Boolean, String?) -> Unit = { _, _ -> }) {
        if (isRecording) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bind(previewView, lifecycleOwner, onReady)
    }

    fun start(audio: Boolean, onEvent: (VideoRecordEvent) -> Unit) {
        if (isRecording) return
        val capture = videoCapture ?: return
        val name = "RaceLab_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RaceLab")
        }
        val options = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        var pending: PendingRecording = capture.output.prepareRecording(context, options)
        if (audio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> isRecording = true
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    recording = null
                }
            }
            onEvent(event)
        }
    }

    fun stop() {
        recording?.stop()
    }

    fun pause() = recording?.pause()
    fun resume() = recording?.resume()
}
