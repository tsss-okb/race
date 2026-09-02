package com.tsss.gt6lock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Real Camera2 constrained-high-speed path.
 *
 * Camera -> OES SurfaceTexture -> GLES3
 *   -> screen preview
 *   -> R8 640x360 FBO -> glReadPixels(GL_RED) -> tracker
 *
 * No CPU YUV ImageReader is attached to the constrained high-speed session.
 */
class HighSpeedCameraSurface(
    context: Context,
    private val profile: Profile,
    private val onGrayFrame: (FastLumaExtractor.GrayFrame) -> Unit,
    private val onRenderedFps: (Float) -> Unit,
    private val onFailure: (String) -> Unit
) : GLSurfaceView(context) {

    data class Profile(
        val cameraId: String,
        val size: Size,
        val fpsRange: Range<Int>,
        val sensorOrientation: Int,
        val maxAvailableFps: Int
    ) {
        val fps: Int get() = fpsRange.upper
    }

    companion object {
        fun findBest120Profile(context: Context): Profile? {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var best: Profile? = null
            var globalMax = 0

            val candidates = ArrayList<Profile>()
            for (id in manager.cameraIdList) {
                val cc = manager.getCameraCharacteristics(id)
                if (cc.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val orientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                for (size in map.highSpeedVideoSizes.orEmpty()) {
                    val ranges = runCatching { map.getHighSpeedVideoFpsRangesFor(size) }.getOrDefault(emptyArray())
                    for (range in ranges) {
                        globalMax = maxOf(globalMax, range.upper)
                        // Fixed-rate high-speed range is the stable choice for tracking.
                        if (range.upper >= 120 && range.lower == range.upper) {
                            candidates += Profile(id, size, range, orientation, 0)
                        }
                    }
                }
            }

            if (candidates.isEmpty()) return null

            // Prefer true 120 first; among those take the largest frame.
            // If only 240/480 fixed ranges are exposed, use the lowest >=120.
            val chosenFps = candidates.minOf { it.fps }
            val sameFps = candidates.filter { it.fps == chosenFps }
            val chosen = sameFps.maxByOrNull { it.size.width.toLong() * it.size.height.toLong() } ?: return null
            best = chosen.copy(maxAvailableFps = globalMax)
            return best
        }
    }

    private val displayDegrees = when ((context as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    private val relativeRotation = ((profile.sensorOrientation - displayDegrees) % 360 + 360) % 360
    private val rendererImpl = CameraRenderer(profile, onGrayFrame, onRenderedFps, relativeRotation)
    private val cameraThread = HandlerThread("GT6-HS-Camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var cameraSurface: Surface? = null
    private val started = AtomicBoolean(false)

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(rendererImpl)
        renderMode = RENDERMODE_WHEN_DIRTY
        rendererImpl.onSurfaceTextureReady = { st ->
            post { startCamera(st) }
        }
        rendererImpl.requestRenderCallback = { requestRender() }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera(surfaceTexture: SurfaceTexture) {
        if (!started.compareAndSet(false, true)) return
        try {
            surfaceTexture.setDefaultBufferSize(profile.size.width, profile.size.height)
            val surface = Surface(surfaceTexture)
            cameraSurface = surface
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.openCamera(profile.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    createHighSpeedSession(device, surface)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    onFailure("HS camera disconnected")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    onFailure("HS camera error $error")
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            started.set(false)
            onFailure("HS open: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun createHighSpeedSession(device: CameraDevice, surface: Surface) {
        try {
            device.createConstrainedHighSpeedCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(base: CameraCaptureSession) {
                        val hs = base as? CameraConstrainedHighSpeedCaptureSession
                        if (hs == null) {
                            onFailure("HAL did not return high-speed session")
                            return
                        }
                        session = hs
                        try {
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
                            }.build()

                            val burst = hs.createHighSpeedRequestList(req)
                            hs.setRepeatingBurst(burst, null, cameraHandler)
                        } catch (t: Throwable) {
                            onFailure("HS request: ${t.message ?: t.javaClass.simpleName}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onFailure("HS configure failed")
                    }
                },
                cameraHandler
            )
        } catch (t: Throwable) {
            onFailure("HS session: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun requestRgbSnapshot(callback: (Bitmap) -> Unit) {
        rendererImpl.snapshotRequest.set(callback)
        requestRender()
    }

    fun shutdown() {
        rendererImpl.active.set(false)
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { cameraSurface?.release() }
        cameraSurface = null
        cameraThread.quitSafely()
    }

    private class CameraRenderer(
        private val profile: Profile,
        private val onGrayFrame: (FastLumaExtractor.GrayFrame) -> Unit,
        private val onRenderedFps: (Float) -> Unit,
        private val relativeRotation: Int
    ) : Renderer, SurfaceTexture.OnFrameAvailableListener {

        var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null
        var requestRenderCallback: (() -> Unit)? = null
        val snapshotRequest = AtomicReference<((Bitmap) -> Unit)?>(null)
        val active = AtomicBoolean(true)

        private var oesTexture = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var program = 0
        private var aPos = -1
        private var aTex = -1
        private var uTexMatrix = -1
        private var uMode = -1

        private var viewW = 1
        private var viewH = 1

        private var lumaFbo = 0
        private var lumaTex = 0
        private val trackW = 640
        private val trackH = 360
        private val lumaRead = ByteBuffer.allocateDirect(trackW * trackH).order(ByteOrder.nativeOrder())
        private val grayRing = Array(4) { ByteArray(trackW * trackH) }
        private var grayRingIndex = 0

        private var rgbFbo = 0
        private var rgbTex = 0
        private val snapW = 320
        private val snapH = 180
        private val rgbRead = ByteBuffer.allocateDirect(snapW * snapH * 4).order(ByteOrder.nativeOrder())

        private val stMatrix = FloatArray(16)
        private val posBuffer: FloatBuffer = floatBuffer(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        )
        private val texBuffer: FloatBuffer = floatBuffer(textureCoordsForRotation(relativeRotation))

        private var lastFrameTimestamp = 0L
        private var fpsEma = 0f

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = createProgram(VERT, FRAG)
            aPos = GLES30.glGetAttribLocation(program, "aPos")
            aTex = GLES30.glGetAttribLocation(program, "aTex")
            uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
            uMode = GLES30.glGetUniformLocation(program, "uMode")

            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            oesTexture = textures[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            GLES30.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE
            )

            createLumaFbo()
            createRgbFbo()

            val st = SurfaceTexture(oesTexture)
            surfaceTexture = st
            st.setOnFrameAvailableListener(this)
            onSurfaceTextureReady?.invoke(st)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width.coerceAtLeast(1)
            viewH = height.coerceAtLeast(1)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (!active.get()) return
            val st = surfaceTexture ?: return

            runCatching { st.updateTexImage() }.getOrElse { return }
            st.getTransformMatrix(stMatrix)
            val timestamp = st.timestamp
            if (timestamp <= 0L) return

            if (lastFrameTimestamp != 0L) {
                val dt = (timestamp - lastFrameTimestamp) / 1e9
                if (dt in 0.001..0.05) {
                    val fps = (1.0 / dt).toFloat()
                    fpsEma = if (fpsEma == 0f) fps else 0.90f * fpsEma + 0.10f * fps
                    onRenderedFps(fpsEma)
                }
            }
            lastFrameTimestamp = timestamp

            // Preview first.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewW, viewH)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            drawOes(mode = 0)

            // GPU luma at tracking resolution.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lumaFbo)
            GLES30.glViewport(0, 0, trackW, trackH)
            drawOes(mode = 1)
            lumaRead.clear()
            GLES30.glReadPixels(
                0, 0, trackW, trackH,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, lumaRead
            )

            val gray = grayRing[grayRingIndex]
            grayRingIndex = (grayRingIndex + 1) % grayRing.size
            for (y in 0 until trackH) {
                val srcRow = trackH - 1 - y
                lumaRead.position(srcRow * trackW)
                lumaRead.get(gray, y * trackW, trackW)
            }
            lumaRead.rewind()

            onGrayFrame(
                FastLumaExtractor.GrayFrame(
                    trackW, trackH, gray, timestamp
                )
            )

            snapshotRequest.getAndSet(null)?.let { cb ->
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rgbFbo)
                GLES30.glViewport(0, 0, snapW, snapH)
                drawOes(mode = 0)
                rgbRead.clear()
                GLES30.glReadPixels(
                    0, 0, snapW, snapH,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgbRead
                )
                val pixels = IntArray(snapW * snapH)
                for (y in 0 until snapH) {
                    val srcY = snapH - 1 - y
                    for (x in 0 until snapW) {
                        val i = (srcY * snapW + x) * 4
                        val r = rgbRead.get(i).toInt() and 0xFF
                        val g = rgbRead.get(i + 1).toInt() and 0xFF
                        val b = rgbRead.get(i + 2).toInt() and 0xFF
                        pixels[y * snapW + x] =
                            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                rgbRead.rewind()
                cb(Bitmap.createBitmap(pixels, snapW, snapH, Bitmap.Config.ARGB_8888))
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            if (active.get()) requestRenderCallback?.invoke()
        }

        private fun drawOes(mode: Int) {
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)

            posBuffer.position(0)
            texBuffer.position(0)
            GLES30.glEnableVertexAttribArray(aPos)
            GLES30.glEnableVertexAttribArray(aTex)
            GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, posBuffer)
            GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 0, texBuffer)
            GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, stMatrix, 0)
            GLES30.glUniform1i(uMode, mode)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(aPos)
            GLES30.glDisableVertexAttribArray(aTex)
        }

        private fun createLumaFbo() {
            val f = IntArray(1)
            val t = IntArray(1)
            GLES30.glGenFramebuffers(1, f, 0)
            GLES30.glGenTextures(1, t, 0)
            lumaFbo = f[0]
            lumaTex = t[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lumaTex)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8,
                trackW, trackH, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lumaFbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, lumaTex, 0
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
        }

        private fun createRgbFbo() {
            val f = IntArray(1)
            val t = IntArray(1)
            GLES30.glGenFramebuffers(1, f, 0)
            GLES30.glGenTextures(1, t, 0)
            rgbFbo = f[0]
            rgbTex = t[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgbTex)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                snapW, snapH, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rgbFbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, rgbTex, 0
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
        }

        private fun createProgram(vs: String, fs: String): Int {
            fun compile(type: Int, src: String): Int {
                val shader = GLES30.glCreateShader(type)
                GLES30.glShaderSource(shader, src)
                GLES30.glCompileShader(shader)
                val status = IntArray(1)
                GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) {
                    val log = GLES30.glGetShaderInfoLog(shader)
                    GLES30.glDeleteShader(shader)
                    error("shader: $log")
                }
                return shader
            }

            val v = compile(GLES30.GL_VERTEX_SHADER, vs)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, v)
            GLES30.glAttachShader(p, f)
            GLES30.glLinkProgram(p)
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            val status = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) error("program: ${GLES30.glGetProgramInfoLog(p)}")
            return p
        }

        private fun textureCoordsForRotation(rotation: Int): FloatArray {
            return when (((rotation % 360) + 360) % 360) {
                90 -> floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f)
                180 -> floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f)
                270 -> floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
                else -> floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            }
        }

        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(data); position(0) }

        companion object {
            private const val VERT = """#version 300 es
                in vec2 aPos;
                in vec2 aTex;
                uniform mat4 uTexMatrix;
                out vec2 vTex;
                void main() {
                    gl_Position = vec4(aPos, 0.0, 1.0);
                    vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
                }
            """

            private const val FRAG = """#version 300 es
                #extension GL_OES_EGL_image_external_essl3 : require
                precision mediump float;
                uniform samplerExternalOES sTexture;
                uniform int uMode;
                in vec2 vTex;
                out vec4 fragColor;
                void main() {
                    vec3 c = texture(sTexture, vTex).rgb;
                    if (uMode == 1) {
                        float y = dot(c, vec3(0.299, 0.587, 0.114));
                        fragColor = vec4(y, 0.0, 0.0, 1.0);
                    } else {
                        fragColor = vec4(c, 1.0);
                    }
                }
            """
        }
    }
}
