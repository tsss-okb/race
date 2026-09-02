package com.tsss.gt6lock

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.graphics.Bitmap
import android.os.Build
import java.io.Closeable
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.max
import kotlin.math.min

/**
 * On-demand detector. It intentionally sleeps during a healthy native LOCK.
 * NNAPI FP16 is preferred; CPU is only a fallback.
 */
class Yolo26OnnxDetector(
    context: android.content.Context,
    private val inputSize: Int = 320,
    @Volatile var confidenceThreshold: Float = 0.28f
) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val pixelBuffer = IntArray(inputSize * inputSize)
    private val inputBuffer = FloatArray(inputSize * inputSize * 3)

    @Volatile var backendName: String = "OFF"
        private set
    @Volatile var lastInferenceMs: Double = 0.0
        private set

    private val session: OrtSession? = runCatching {
        val bytes = context.assets.open("yolo26n.onnx").use { it.readBytes() }
        createSession(bytes)
    }.getOrNull()

    val available: Boolean get() = session != null

    private fun createSession(bytes: ByteArray): OrtSession {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                    addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                }
                return env.createSession(bytes, opts).also { backendName = "NNAPI FP16" }
            }
        }
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        return env.createSession(bytes, opts).also { backendName = "ORT CPU2" }
    }

    @Synchronized
    fun detect(source: Bitmap): List<Detection> {
        val runner = session ?: return emptyList()
        val scaled = if (source.width == inputSize && source.height == inputSize) source
        else Bitmap.createScaledBitmap(source, inputSize, inputSize, false)

        scaled.getPixels(pixelBuffer, 0, inputSize, 0, 0, inputSize, inputSize)
        if (scaled !== source) scaled.recycle()

        val plane = inputSize * inputSize
        for (i in pixelBuffer.indices) {
            val px = pixelBuffer[i]
            inputBuffer[i] = ((px shr 16) and 0xFF) / 255f
            inputBuffer[plane + i] = ((px shr 8) and 0xFF) / 255f
            inputBuffer[plane * 2 + i] = (px and 0xFF) / 255f
        }

        val inputName = runner.inputNames.first()
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputBuffer),
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        ).use { tensor ->
            val t0 = System.nanoTime()
            runner.run(mapOf(inputName to tensor)).use { result ->
                lastInferenceMs = (System.nanoTime() - t0) / 1_000_000.0
                @Suppress("UNCHECKED_CAST")
                val batch = result[0].value as? Array<Array<FloatArray>> ?: return emptyList()
                val out = batch.firstOrNull() ?: return emptyList()
                if (out.isEmpty()) return emptyList()
                return when {
                    out[0].size == 6 -> parseEndToEnd(out)
                    out.size in 5..200 && out[0].size > out.size -> parseRawChannelsFirst(out)
                    out.size > 100 && out[0].size in 5..200 -> parseRawChannelsLast(out)
                    else -> emptyList()
                }
            }
        }
    }

    private fun parseEndToEnd(rows: Array<FloatArray>): List<Detection> =
        rows.mapNotNull { row ->
            if (row.size < 6) return@mapNotNull null
            val conf = row[4]
            if (!conf.isFinite() || conf < confidenceThreshold) return@mapNotNull null
            val cls = row[5].toInt()
            xyxy(row[0], row[1], row[2], row[3], conf, cls)
        }.sortedByDescending { it.confidence }.take(40)

    private fun parseRawChannelsFirst(ch: Array<FloatArray>): List<Detection> {
        val nc = ch.size - 4
        if (nc <= 0) return emptyList()
        val count = ch[0].size
        val candidates = ArrayList<Detection>(min(count, 256))
        for (i in 0 until count) {
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until nc) {
                val s = ch[4 + c][i]
                if (s > bestScore) { bestScore = s; bestClass = c }
            }
            if (bestScore < confidenceThreshold) continue
            raw(ch[0][i], ch[1][i], ch[2][i], ch[3][i], bestScore, bestClass)?.let(candidates::add)
        }
        return nms(candidates, 0.45f)
    }

    private fun parseRawChannelsLast(rows: Array<FloatArray>): List<Detection> {
        val candidates = ArrayList<Detection>(min(rows.size, 256))
        for (row in rows) {
            if (row.size < 5) continue
            var cls = -1
            var score = 0f
            for (c in 4 until row.size) {
                if (row[c] > score) { score = row[c]; cls = c - 4 }
            }
            if (score < confidenceThreshold) continue
            raw(row[0], row[1], row[2], row[3], score, cls)?.let(candidates::add)
        }
        return nms(candidates, 0.45f)
    }

    private fun raw(cx: Float, cy: Float, w: Float, h: Float, conf: Float, cls: Int): Detection? {
        val div = if (max(max(cx, cy), max(w, h)) <= 2f) 1f else inputSize.toFloat()
        return xyxy(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f, conf, cls, div)
    }

    private fun xyxy(x1: Float, y1: Float, x2: Float, y2: Float, conf: Float, cls: Int, forcedDiv: Float? = null): Detection? {
        val maxCoord = max(max(x1, y1), max(x2, y2))
        val div = forcedDiv ?: if (maxCoord <= 2f) 1f else inputSize.toFloat()
        val nx1 = (x1 / div).coerceIn(0f, 1f)
        val ny1 = (y1 / div).coerceIn(0f, 1f)
        val nx2 = (x2 / div).coerceIn(0f, 1f)
        val ny2 = (y2 / div).coerceIn(0f, 1f)
        if (nx2 <= nx1 || ny2 <= ny1) return null
        return Detection(nx1, ny1, nx2, ny2, conf, cls, "C$cls")
    }

    private fun nms(items: List<Detection>, threshold: Float): List<Detection> {
        val kept = ArrayList<Detection>()
        for (d in items.sortedByDescending { it.confidence }) {
            if (kept.none { it.classId == d.classId && iou(it, d) > threshold }) kept += d
            if (kept.size >= 40) break
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = max(0f, ix2 - ix1)
        val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() {
        session?.close()
    }
}
