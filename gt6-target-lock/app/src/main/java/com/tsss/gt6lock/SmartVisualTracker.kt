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

    private val nativeNcc = NativeNccMatcher()

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
        nativeNcc.clear()
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
        nativeNcc.setAnchor(
            built.samples,
            built.halfW,
            built.halfH,
            built.step,
            built.sum,
            built.sumSq,
            built.widthNorm,
            built.heightNorm,
            copyToCurrent = true
        )
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
        softRescue: Boolean = false,
        shockRescue: Boolean = false,
        freezeAdaptive: Boolean = false
    ): Result? {
        val anchor = anchorTemplate ?: return null
        val current = currentTemplate ?: anchor

        val predictedX = (base.cx * frame.width).toInt()
        val predictedY = (base.cy * frame.height).toInt()
        val boxPx = max(base.width * frame.width, base.height * frame.height)

        // Strong LOCK = tiny ROI. Only uncertainty is allowed to grow search cost.
        val stable = state == State.LOCK && goodStreak >= 4 && badStreak == 0
        val radiusGain = when {
            shockRescue -> 2.25f
            softRescue -> 1.34f
            badStreak >= 4 -> 1.70f
            badStreak >= 2 -> 1.28f
            stable -> 0.66f
            else -> 0.92f
        }
        val radiusX = ((12f + boxPx * 0.34f) * radiusGain).toInt()
            .coerceIn(10, if (shockRescue) 150 else 92)
        val radiusY = ((9f + boxPx * 0.28f) * radiusGain).toInt()
            .coerceIn(8, if (shockRescue) 112 else 70)

        // Scale set remains identical to v4.2; C++ executes the coarse-to-fine scan.
        val coarseStep = when {
            shockRescue -> 4
            softRescue -> 2
            badStreak >= 3 -> 3
            stable -> 3
            else -> 2
        }

        val native = nativeNcc.nativeMatch(
            frame.pixels,
            frame.width,
            frame.height,
            predictedX,
            predictedY,
            radiusX,
            radiusY,
            coarseStep,
            shockRescue || badStreak >= 2
        )

        if (native.size < 6 || native[0] < 0.5f) return lost(base, shockRescue)

        val bestX = native[1].toInt()
        val bestY = native[2].toInt()
        val bestScore = native[3].toDouble()
        val secondScore = native[4].toDouble()

        val score = (((bestScore + 1.0) * 0.5).coerceIn(0.0, 1.0)).toFloat()
        val uniqueness = ((bestScore - secondScore).coerceIn(0.0, 1.0)).toFloat()
        lastScore = score

        // Hysteresis: one weak frame does not drop LOCK.
        val strong = score >= 0.69f && uniqueness >= 0.018f
        val usable = score >= 0.56f
        if (!usable) return lost(base, shockRescue)

        badStreak = 0
        goodStreak = (goodStreak + 1).coerceAtMost(30)

        state = when {
            strong && goodStreak >= 2 -> State.LOCK
            goodStreak >= 1 -> State.PREDICT
            else -> State.ACQUIRE
        }

        // Stable Box: scale is used only to FIND the target, not to resize the
        // HUD box every frame. This prevents cumulative shrink/grow feedback.
        val newW = anchor.widthNorm
        val newH = anchor.heightNorm

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
            !freezeAdaptive &&
            !shockRescue &&
            state == State.LOCK &&
            score >= 0.82f &&
            uniqueness >= 0.032f &&
            framesSinceAdaptiveRefresh >= 10
        ) {
            buildTemplate(frame, result)?.let {
                currentTemplate = it
                nativeNcc.setCurrent(
                    it.samples,
                    it.halfW,
                    it.halfH,
                    it.step,
                    it.sum,
                    it.sumSq,
                    it.widthNorm,
                    it.heightNorm
                )
            }
            framesSinceAdaptiveRefresh = 0
        }

        return Result(result, score, uniqueness, state)
    }

    private fun lost(base: Detection, shockRescue: Boolean = false): Result? {
        if (shockRescue) {
            // A short camera jerk must not poison the state machine or replace
            // the adaptive template with motion blur. Hold the prior briefly
            // while the widened NCC search tries to recover the same target.
            goodStreak = max(0, goodStreak - 1)
            lastScore *= 0.90f
            state = State.PREDICT
            return Result(
                base.copy(
                    confidence = (base.confidence * 0.96f).coerceAtLeast(0.20f),
                    predicted = true
                ),
                lastScore,
                0f,
                state
            )
        }

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
