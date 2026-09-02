package com.tsss.gt6lock

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class SmartVisualTracker {
    enum class State { SEARCH, ACQUIRE, LOCK, PREDICT, REACQUIRE }

    data class Result(
        val detection: Detection,
        val score: Float,
        val uniqueness: Float,
        val state: State
    )

    private data class Template(
        val halfW: Int,
        val halfH: Int,
        val step: Int,
        val samples: FloatArray,
        val sum: Double,
        val sumSq: Double,
        val widthNorm: Float,
        val heightNorm: Float
    )

    private var template: Template? = null
    private var misses = 0
    private var lastScore = 0f
    @Volatile var state: State = State.SEARCH
        private set
    val score: Float @Synchronized get() = lastScore

    @Synchronized fun clear() {
        template = null; misses = 0; lastScore = 0f; state = State.SEARCH
    }

    @Synchronized
    fun seedAt(frame: FastLumaExtractor.GrayFrame, nx: Float, ny: Float): Detection? {
        val provisional = makeBox(nx, ny, 0.040f, 0.055f, 0.35f, -1, "SMART", false)
        if (!refreshTemplate(frame, provisional)) return null
        misses = 0
        state = State.ACQUIRE
        return provisional
    }

    @Synchronized
    fun refreshTemplate(frame: FastLumaExtractor.GrayFrame, detection: Detection): Boolean {
        if (frame.width < 80 || frame.height < 45) return false
        val cx = (detection.cx * frame.width).toInt().coerceIn(0, frame.width - 1)
        val cy = (detection.cy * frame.height).toInt().coerceIn(0, frame.height - 1)
        val halfW = ((detection.width * frame.width * 0.28f).toInt()).coerceIn(5, 20)
        val halfH = ((detection.height * frame.height * 0.28f).toInt()).coerceIn(5, 20)
        if (!fits(frame, cx, cy, halfW, halfH)) return false

        val step = if (halfW * halfH > 180) 2 else 1
        val values = ArrayList<Float>()
        var sum = 0.0
        var sumSq = 0.0
        var y = -halfH
        while (y <= halfH) {
            var x = -halfW
            while (x <= halfW) {
                val v = pixel(frame, cx + x, cy + y).toFloat()
                values += v
                sum += v
                sumSq += v * v
                x += step
            }
            y += step
        }
        if (values.size < 20) return false
        val variance = sumSq - (sum * sum / values.size)
        if (variance < values.size * 12.0) return false

        template = Template(
            halfW, halfH, step, values.toFloatArray(), sum, sumSq,
            detection.width.coerceIn(0.010f, 0.45f),
            detection.height.coerceIn(0.010f, 0.45f)
        )
        misses = 0
        lastScore = 1f
        state = State.LOCK
        return true
    }

    @Synchronized
    fun track(frame: FastLumaExtractor.GrayFrame, base: Detection): Result? {
        val t = template ?: return null
        val predictedX = (base.cx * frame.width).toInt()
        val predictedY = (base.cy * frame.height).toInt()

        val boxPx = max(base.width * frame.width, base.height * frame.height)
        val radiusX = (16 + boxPx * (0.42f + misses * 0.12f)).toInt().coerceIn(18, 86)
        val radiusY = (12 + boxPx * (0.34f + misses * 0.10f)).toInt().coerceIn(14, 66)
        val scanStep = if (misses >= 2) 2 else 1

        var bestCorr = -2.0
        var secondCorr = -2.0
        var bestX = predictedX
        var bestY = predictedY

        val xStart = max(t.halfW, predictedX - radiusX)
        val xEnd = min(frame.width - t.halfW - 1, predictedX + radiusX)
        val yStart = max(t.halfH, predictedY - radiusY)
        val yEnd = min(frame.height - t.halfH - 1, predictedY + radiusY)
        if (xStart >= xEnd || yStart >= yEnd) return lost(base)

        var y = yStart
        while (y <= yEnd) {
            var x = xStart
            while (x <= xEnd) {
                val corr = correlation(frame, x, y, t)
                if (corr > bestCorr) {
                    secondCorr = bestCorr
                    bestCorr = corr
                    bestX = x
                    bestY = y
                } else if (corr > secondCorr && kotlin.math.abs(x - bestX) + kotlin.math.abs(y - bestY) > 4) {
                    secondCorr = corr
                }
                x += scanStep
            }
            y += scanStep
        }

        val score = (((bestCorr + 1.0) * 0.5).coerceIn(0.0, 1.0)).toFloat()
        val uniqueness = ((bestCorr - secondCorr).coerceIn(0.0, 1.0)).toFloat()
        lastScore = score

        val accept = score >= if (misses == 0) 0.60f else 0.64f
        if (!accept) return lost(base)

        misses = 0
        state = if (score >= 0.72f || uniqueness >= 0.05f) State.LOCK else State.PREDICT
        val result = makeBox(
            bestX.toFloat() / frame.width,
            bestY.toFloat() / frame.height,
            t.widthNorm, t.heightNorm,
            (0.35f + score * 0.60f).coerceIn(0.35f, 0.96f),
            base.classId, base.label,
            predicted = state != State.LOCK
        )
        return Result(result, score, uniqueness, state)
    }

    private fun lost(base: Detection): Result? {
        misses++
        state = if (misses <= 2) State.PREDICT else State.REACQUIRE
        lastScore *= 0.72f
        if (misses > 8) return null
        return Result(
            base.copy(confidence = (base.confidence * 0.88f).coerceAtLeast(0.18f), predicted = true),
            lastScore, 0f, state
        )
    }

    private fun correlation(frame: FastLumaExtractor.GrayFrame, cx: Int, cy: Int, t: Template): Double {
        var sumC = 0.0
        var sumSqC = 0.0
        var dot = 0.0
        var i = 0
        var y = -t.halfH
        while (y <= t.halfH) {
            var x = -t.halfW
            while (x <= t.halfW) {
                val c = pixel(frame, cx + x, cy + y).toDouble()
                val tv = t.samples[i++].toDouble()
                sumC += c
                sumSqC += c * c
                dot += tv * c
                x += t.step
            }
            y += t.step
        }
        val n = i.toDouble().coerceAtLeast(1.0)
        val cov = dot - (t.sum * sumC / n)
        val varT = t.sumSq - (t.sum * t.sum / n)
        val varC = sumSqC - (sumC * sumC / n)
        val denom = sqrt(max(1e-9, varT * varC))
        return (cov / denom).coerceIn(-1.0, 1.0)
    }

    private fun makeBox(
        cx: Float, cy: Float, w: Float, h: Float,
        confidence: Float, classId: Int, label: String, predicted: Boolean
    ): Detection {
        val ww = w.coerceIn(0.010f, 0.60f)
        val hh = h.coerceIn(0.010f, 0.60f)
        var x1 = cx - ww * 0.5f
        var x2 = cx + ww * 0.5f
        var y1 = cy - hh * 0.5f
        var y2 = cy + hh * 0.5f
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

    private fun fits(frame: FastLumaExtractor.GrayFrame, cx: Int, cy: Int, hw: Int, hh: Int): Boolean =
        cx - hw >= 0 && cy - hh >= 0 && cx + hw < frame.width && cy + hh < frame.height

    private fun pixel(frame: FastLumaExtractor.GrayFrame, x: Int, y: Int): Int =
        frame.pixels[y * frame.width + x].toInt() and 0xFF
}
