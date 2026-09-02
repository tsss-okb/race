package com.tsss.gt6lock

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Strong Hold appearance tracker:
 * - immutable anchor template prevents drift to background;
 * - adaptive current template follows slow appearance change;
 * - multi-scale NCC handles range/size changes;
 * - dynamic ROI is small in LOCK and expands only after uncertainty;
 * - hysteresis avoids state flapping on one bad frame.
 */
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

    private var anchorTemplate: Template? = null
    private var currentTemplate: Template? = null
    private var badStreak = 0
    private var goodStreak = 0
    private var framesSinceAdaptiveRefresh = 0
    private var lastScore = 0f

    @Volatile var state: State = State.SEARCH
        private set

    val score: Float @Synchronized get() = lastScore

    @Synchronized
    fun clear() {
        anchorTemplate = null
        currentTemplate = null
        badStreak = 0
        goodStreak = 0
        framesSinceAdaptiveRefresh = 0
        lastScore = 0f
        state = State.SEARCH
    }

    @Synchronized
    fun seedAt(frame: FastLumaExtractor.GrayFrame, nx: Float, ny: Float): Detection? {
        val provisional = makeBox(nx, ny, 0.040f, 0.055f, 0.45f, -1, "SMART", false)
        if (!refreshTemplate(frame, provisional)) return null
        state = State.ACQUIRE
        return provisional
    }

    @Synchronized
    fun refreshTemplate(frame: FastLumaExtractor.GrayFrame, detection: Detection): Boolean {
        val built = buildTemplate(frame, detection) ?: return false
        anchorTemplate = built
        currentTemplate = built
        badStreak = 0
        goodStreak = 0
        framesSinceAdaptiveRefresh = 0
        lastScore = 1f
        state = State.LOCK
        return true
    }

    @Synchronized
    fun track(
        frame: FastLumaExtractor.GrayFrame,
        base: Detection,
        softRescue: Boolean = false
    ): Result? {
        val anchor = anchorTemplate ?: return null
        val current = currentTemplate ?: anchor

        val predictedX = (base.cx * frame.width).toInt()
        val predictedY = (base.cy * frame.height).toInt()
        val boxPx = max(base.width * frame.width, base.height * frame.height)

        // Strong LOCK = tiny ROI. Only uncertainty is allowed to grow search cost.
        val stable = state == State.LOCK && goodStreak >= 4 && badStreak == 0
        val radiusGain = when {
            softRescue -> 1.34f
            badStreak >= 4 -> 1.70f
            badStreak >= 2 -> 1.28f
            stable -> 0.66f
            else -> 0.92f
        }
        val radiusX = ((12f + boxPx * 0.34f) * radiusGain).toInt().coerceIn(10, 92)
        val radiusY = ((9f + boxPx * 0.28f) * radiusGain).toInt().coerceIn(8, 70)

        // Three scales cost less than old 1px exhaustive search by using coarse->fine.
        val scales = if (badStreak >= 2) {
            floatArrayOf(0.88f, 1.00f, 1.12f)
        } else {
            floatArrayOf(0.94f, 1.00f, 1.06f)
        }
        val coarseStep = when {
            softRescue -> 2
            badStreak >= 3 -> 3
            stable -> 3
            else -> 2
        }

        var bestScore = -2.0
        var secondScore = -2.0
        var bestX = predictedX
        var bestY = predictedY
        var bestScale = 1f

        for (scale in scales) {
            val hw = max(anchor.halfW, current.halfW)
            val hh = max(anchor.halfH, current.halfH)
            val scaledHw = (hw * scale).toInt().coerceAtLeast(3)
            val scaledHh = (hh * scale).toInt().coerceAtLeast(3)

            val xStart = max(scaledHw + 2, predictedX - radiusX)
            val xEnd = min(frame.width - scaledHw - 3, predictedX + radiusX)
            val yStart = max(scaledHh + 2, predictedY - radiusY)
            val yEnd = min(frame.height - scaledHh - 3, predictedY + radiusY)
            if (xStart >= xEnd || yStart >= yEnd) continue

            var y = yStart
            while (y <= yEnd) {
                var x = xStart
                while (x <= xEnd) {
                    val curCorr = correlation(frame, x, y, current, scale)
                    val anchorCorr = if (anchor === current) curCorr
                        else correlation(frame, x, y, anchor, scale)

                    // Anchor resists drift. Current template can still win if viewpoint changed.
                    val fused = max(
                        0.64 * curCorr + 0.36 * anchorCorr,
                        0.90 * curCorr - 0.03
                    )

                    if (fused > bestScore) {
                        secondScore = bestScore
                        bestScore = fused
                        bestX = x
                        bestY = y
                        bestScale = scale
                    } else if (
                        fused > secondScore &&
                        abs(x - bestX) + abs(y - bestY) > 5
                    ) {
                        secondScore = fused
                    }
                    x += coarseStep
                }
                y += coarseStep
            }
        }

        if (bestScore <= -1.5) return lost(base)

        // Fine refinement only around the best coarse candidate.
        for (dy in -2..2) {
            for (dx in -2..2) {
                val x = bestX + dx
                val y = bestY + dy
                if (!fitsScaled(frame, x, y, current, bestScale)) continue
                val curCorr = correlation(frame, x, y, current, bestScale)
                val anchorCorr = if (anchor === current) curCorr
                    else correlation(frame, x, y, anchor, bestScale)
                val fused = max(
                    0.64 * curCorr + 0.36 * anchorCorr,
                    0.90 * curCorr - 0.03
                )
                if (fused > bestScore) {
                    bestScore = fused
                    bestX = x
                    bestY = y
                }
            }
        }

        val score = (((bestScore + 1.0) * 0.5).coerceIn(0.0, 1.0)).toFloat()
        val uniqueness = ((bestScore - secondScore).coerceIn(0.0, 1.0)).toFloat()
        lastScore = score

        // Hysteresis: one weak frame does not drop LOCK.
        val strong = score >= 0.69f && uniqueness >= 0.018f
        val usable = score >= 0.56f
        if (!usable) return lost(base)

        badStreak = 0
        goodStreak = (goodStreak + 1).coerceAtMost(30)

        state = when {
            strong && goodStreak >= 2 -> State.LOCK
            goodStreak >= 1 -> State.PREDICT
            else -> State.ACQUIRE
        }

        val scaleBlend = 0.18f
        val newW = (base.width * (1f - scaleBlend) + base.width * bestScale * scaleBlend)
            .coerceIn(0.010f, 0.60f)
        val newH = (base.height * (1f - scaleBlend) + base.height * bestScale * scaleBlend)
            .coerceIn(0.010f, 0.60f)

        val result = makeBox(
            bestX.toFloat() / frame.width,
            bestY.toFloat() / frame.height,
            newW,
            newH,
            (0.40f + score * 0.57f).coerceIn(0.40f, 0.97f),
            base.classId,
            base.label,
            predicted = state != State.LOCK
        )

        framesSinceAdaptiveRefresh++
        // Update only adaptive template, never the anchor, and only on very clean LOCK.
        if (
            state == State.LOCK &&
            score >= 0.82f &&
            uniqueness >= 0.032f &&
            framesSinceAdaptiveRefresh >= 10
        ) {
            buildTemplate(frame, result)?.let { currentTemplate = it }
            framesSinceAdaptiveRefresh = 0
        }

        return Result(result, score, uniqueness, state)
    }

    private fun lost(base: Detection): Result? {
        badStreak++
        goodStreak = max(0, goodStreak - 2)
        lastScore *= 0.78f

        state = when {
            badStreak <= 1 && goodStreak >= 2 -> State.LOCK
            badStreak <= 3 -> State.PREDICT
            else -> State.REACQUIRE
        }

        if (badStreak > 10) return null
        return Result(
            base.copy(
                confidence = (base.confidence * 0.90f).coerceAtLeast(0.18f),
                predicted = true
            ),
            lastScore,
            0f,
            state
        )
    }

    private fun buildTemplate(
        frame: FastLumaExtractor.GrayFrame,
        detection: Detection
    ): Template? {
        if (frame.width < 80 || frame.height < 45) return null

        val cx = (detection.cx * frame.width).toInt().coerceIn(0, frame.width - 1)
        val cy = (detection.cy * frame.height).toInt().coerceIn(0, frame.height - 1)

        // Use the visual core. A little context remains for texture but not enough to swallow background.
        val halfW = (detection.width * frame.width * 0.30f).toInt().coerceIn(5, 24)
        val halfH = (detection.height * frame.height * 0.30f).toInt().coerceIn(5, 24)
        if (!fits(frame, cx, cy, halfW, halfH)) return null

        val step = if (halfW * halfH > 220) 2 else 1
        val countX = (halfW * 2) / step + 1
        val countY = (halfH * 2) / step + 1
        val values = FloatArray(countX * countY)

        var i = 0
        var sum = 0.0
        var sumSq = 0.0
        var y = -halfH
        while (y <= halfH) {
            var x = -halfW
            while (x <= halfW) {
                val v = pixel(frame, cx + x, cy + y).toFloat()
                values[i++] = v
                sum += v
                sumSq += v * v
                x += step
            }
            y += step
        }

        if (i < 20) return null
        val variance = sumSq - (sum * sum / i)
        if (variance < i * 15.0) return null

        return Template(
            halfW,
            halfH,
            step,
            if (i == values.size) values else values.copyOf(i),
            sum,
            sumSq,
            detection.width.coerceIn(0.010f, 0.45f),
            detection.height.coerceIn(0.010f, 0.45f)
        )
    }

    private fun correlation(
        frame: FastLumaExtractor.GrayFrame,
        cx: Int,
        cy: Int,
        t: Template,
        scale: Float
    ): Double {
        if (!fitsScaled(frame, cx, cy, t, scale)) return -2.0

        var sumC = 0.0
        var sumSqC = 0.0
        var dot = 0.0
        var i = 0

        var ty = -t.halfH
        while (ty <= t.halfH) {
            var tx = -t.halfW
            while (tx <= t.halfW && i < t.samples.size) {
                val sx = cx + (tx * scale).toInt()
                val sy = cy + (ty * scale).toInt()
                val c = pixel(frame, sx, sy).toDouble()
                val tv = t.samples[i++].toDouble()
                sumC += c
                sumSqC += c * c
                dot += tv * c
                tx += t.step
            }
            ty += t.step
        }

        val n = i.toDouble().coerceAtLeast(1.0)
        val cov = dot - t.sum * sumC / n
        val varT = t.sumSq - t.sum * t.sum / n
        val varC = sumSqC - sumC * sumC / n
        val denom = sqrt(max(1e-9, varT * varC))
        return (cov / denom).coerceIn(-1.0, 1.0)
    }

    private fun fitsScaled(
        frame: FastLumaExtractor.GrayFrame,
        cx: Int,
        cy: Int,
        t: Template,
        scale: Float
    ): Boolean {
        val hw = (t.halfW * scale).toInt() + 2
        val hh = (t.halfH * scale).toInt() + 2
        return cx - hw >= 0 && cy - hh >= 0 &&
            cx + hw < frame.width && cy + hh < frame.height
    }

    private fun makeBox(
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        confidence: Float,
        classId: Int,
        label: String,
        predicted: Boolean
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
            x1.coerceIn(0f, 1f),
            y1.coerceIn(0f, 1f),
            x2.coerceIn(0f, 1f),
            y2.coerceIn(0f, 1f),
            confidence,
            classId,
            label,
            predicted
        )
    }

    private fun fits(
        frame: FastLumaExtractor.GrayFrame,
        cx: Int,
        cy: Int,
        hw: Int,
        hh: Int
    ): Boolean =
        cx - hw >= 0 && cy - hh >= 0 && cx + hw < frame.width && cy + hh < frame.height

    private fun pixel(frame: FastLumaExtractor.GrayFrame, x: Int, y: Int): Int =
        frame.pixels[y * frame.width + x].toInt() and 0xFF
}
