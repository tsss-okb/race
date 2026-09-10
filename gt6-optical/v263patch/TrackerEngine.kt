package com.tsss.gt6optical

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * GT6 Visual Tracker v2.6.3 — STABLE 60 LOCK
 *
 * Design goal: preserve the sub-10 ms healthy path while preventing edge/template drift on Realme GT6.
 * Heavy identity/reacquire work is moved off the normal LOCK path.
 *
 * Normal frame:
 *   fast oriented Y downsample -> local NCC -> motion gate -> LOCK.
 * Periodic/uncertain frame:
 *   anchor verification + multi-scale check.
 * Lost target:
 *   progressively wider anchor search, with rare full-frame rescue.
 *
 * Important behavior: low uniqueness does NOT force ACQUIRE while a strong local,
 * motion-consistent match exists. In an ambiguous scene we prefer PRED/freeze over
 * a large identity switch.
 */
class TrackerEngine(
    private val onSnapshot: (TrackerOverlayView.Snapshot) -> Unit
) {
    @Volatile private var pendingTap: Pair<Float, Float>? = null

    private var box: RectF? = null
    private var state = "IDLE"
    private var confidence = 0f
    private var nccScore = 0f
    private var uniqueness = 0f
    private var lostFrames = 0
    private var goodFrames = 0
    private var frameIndex = 0
    private var framesSinceRefresh = 0

    private var vx = 0f
    private var vy = 0f
    private var ax = 0f
    private var ay = 0f
    private var lastDx = 0f
    private var lastDy = 0f
    private var frameW = 16
    private var frameH = 9
    private var lastGoodNs = 0L
    private var lastProcMs = 0f

    private var anchor: Template? = null
    private var contextAnchor: Template? = null
    private var adaptive: Template? = null

    fun tap(nx: Float, ny: Float) {
        pendingTap = nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    @Synchronized
    fun reset(reason: String = "RESET") {
        pendingTap = null
        box = null
        state = "IDLE"
        confidence = 0f
        nccScore = 0f
        uniqueness = 0f
        lostFrames = 0
        goodFrames = 0
        frameIndex = 0
        framesSinceRefresh = 0
        vx = 0f
        vy = 0f
        ax = 0f
        ay = 0f
        lastDx = 0f
        lastDy = 0f
        lastGoodNs = 0L
        anchor = null
        contextAnchor = null
        adaptive = null
        publish(reason)
    }

    @Synchronized
    fun process(image: ImageProxy) {
        val startedNs = System.nanoTime()
        frameIndex++

        val frame = extractOrientedLumaFast(image, targetWidth = 320)
        frameW = frame.width
        frameH = frame.height

        val tap = pendingTap
        if (tap != null) {
            pendingTap = null
            seed(frame, tap.first, tap.second)
            lastProcMs = elapsedMs(startedNs)
            publish(state)
            return
        }

        val r = box
        val a = anchor
        val ctx = contextAnchor
        val t = adaptive
        if (r == null || a == null || t == null) {
            lastProcMs = elapsedMs(startedNs)
            publish(state)
            return
        }

        // Constant-acceleration prediction helps on fast hand motion between frames.
        val predictedCx = (r.centerX() + vx + ax * 0.45f).coerceIn(0f, 1f)
        val predictedCy = (r.centerY() + vy + ay * 0.45f).coerceIn(0f, 1f)
        val predX = (predictedCx * frame.width).roundToInt().coerceIn(0, frame.width - 1)
        val predY = (predictedCy * frame.height).roundToInt().coerceIn(0, frame.height - 1)

        val bwPx = r.width() * frame.width
        val bhPx = r.height() * frame.height

        val motionPxX = (abs(vx) * frame.width + abs(ax) * frame.width * 0.75f)
        val motionPxY = (abs(vy) * frame.height + abs(ay) * frame.height * 0.75f)

        // Healthy LOCK stays cheap, but the ROI expands automatically when velocity/acceleration rises.
        val radiusX = when {
            lostFrames >= 20 -> min(155, max(60, (bwPx * 3.2f).roundToInt()))
            lostFrames >= 8 -> min(112, max(46, (bwPx * 2.25f).roundToInt()))
            lostFrames >= 3 -> min(82, max(34, (bwPx * 1.60f).roundToInt()))
            else -> min(46, max(14, (bwPx * 0.62f + motionPxX * 2.2f + 5f).roundToInt()))
        }
        val radiusY = when {
            lostFrames >= 20 -> min(112, max(46, (bhPx * 2.6f).roundToInt()))
            lostFrames >= 8 -> min(88, max(38, (bhPx * 1.95f).roundToInt()))
            lostFrames >= 3 -> min(64, max(28, (bhPx * 1.40f).roundToInt()))
            else -> min(36, max(12, (bhPx * 0.58f + motionPxY * 2.2f + 4f).roundToInt()))
        }

        val step = when {
            lostFrames >= 12 -> 4
            lostFrames >= 4 -> 3
            lostFrames == 0 && (radiusX > 34 || radiusY > 28) -> 3
            else -> 2
        }

        val baseScale = (r.width() / t.widthNorm.coerceAtLeast(0.001f)).coerceIn(0.62f, 1.60f)
        val scaleFactors = when {
            lostFrames > 0 -> floatArrayOf(0.90f, 1.00f, 1.10f)
            frameIndex % 12 == 0 -> floatArrayOf(0.96f, 1.00f, 1.04f)
            else -> floatArrayOf(1.00f)
        }
        val scales = FloatArray(scaleFactors.size) { i ->
            (baseScale * scaleFactors[i]).coerceIn(0.58f, 1.70f)
        }

        var result = search(
            frame = frame,
            template = t,
            predictedX = predX,
            predictedY = predY,
            radiusX = radiusX,
            radiusY = radiusY,
            step = step,
            scales = scales,
            motionWeight = if (lostFrames == 0) 0.14f else 0.06f
        )

        // Anchor verification is periodic in LOCK, immediate when uncertain/lost.
        val highMotion = motionPxX > 7f || motionPxY > 6f
        val needAnchorCheck =
            lostFrames > 0 || highMotion || frameIndex % 12 == 0 || result == null || result.best.ncc < 0.74f
        var anchorScore = 1f
        if (result != null && needAnchorCheck) {
            val targetWidth = t.widthNorm * result.best.scale
            val anchorScale = (targetWidth / a.widthNorm.coerceAtLeast(0.001f)).coerceIn(0.55f, 1.80f)
            anchorScore = ncc01(frame, a, result.best.cx, result.best.cy, anchorScale)
        }

        // If adaptive template is uncertain, try anchor in the same ROI before declaring loss.
        if ((result == null || result.best.ncc < 0.61f || anchorScore < 0.52f) && lostFrames < 18) {
            val anchorBaseScale = (r.width() / a.widthNorm.coerceAtLeast(0.001f)).coerceIn(0.55f, 1.80f)
            val anchorResult = search(
                frame = frame,
                template = a,
                predictedX = predX,
                predictedY = predY,
                radiusX = radiusX,
                radiusY = radiusY,
                step = max(2, step),
                scales = if (lostFrames > 0) {
                    floatArrayOf(anchorBaseScale * 0.90f, anchorBaseScale, anchorBaseScale * 1.10f)
                } else {
                    floatArrayOf(anchorBaseScale)
                },
                motionWeight = if (lostFrames == 0) 0.12f else 0.05f
            )
            if (anchorResult != null && (result == null || anchorResult.best.rank > result.best.rank + 0.015f)) {
                result = anchorResult
                anchorScore = anchorResult.best.ncc
            }
        }

        // Context-anchor verification is deliberately NOT on every frame.
        // It prevents an edge/flat patch from drifting to a nearby look-alike.
        var contextScore = 1f
        if (ctx != null && result != null &&
            (highMotion || lostFrames > 0 || frameIndex % 10 == 0 || result.best.ncc < 0.78f)
        ) {
            val ctxScale = (r.width() / a.widthNorm.coerceAtLeast(0.001f))
                .coerceIn(0.55f, 1.80f)
            contextScore = ncc01(frame, ctx, result.best.cx, result.best.cy, ctxScale)
        }

        // Immediate wider rescue on the FIRST uncertain frame.
        if (
            lostFrames == 0 &&
            (result == null || result.best.ncc < 0.66f || anchorScore < 0.52f || contextScore < 0.48f)
        ) {
            val rescueTemplate = ctx ?: a
            val rescueBaseScale = (r.width() / a.widthNorm.coerceAtLeast(0.001f))
                .coerceIn(0.55f, 1.80f)
            val rescue = search(
                frame = frame,
                template = rescueTemplate,
                predictedX = predX,
                predictedY = predY,
                radiusX = min(100, max(radiusX * 2, 44)),
                radiusY = min(76, max(radiusY * 2, 34)),
                step = 4,
                scales = floatArrayOf(
                    (rescueBaseScale * 0.92f).coerceIn(0.50f, 1.90f),
                    rescueBaseScale,
                    (rescueBaseScale * 1.08f).coerceIn(0.50f, 1.90f)
                ),
                motionWeight = 0.05f
            )
            if (rescue != null && (result == null || rescue.best.rank > result.best.rank + 0.02f)) {
                result = rescue
                anchorScore = if (rescueTemplate === a) rescue.best.ncc else {
                    ncc01(frame, a, rescue.best.cx, rescue.best.cy, rescueBaseScale)
                }
                contextScore = if (ctx != null) {
                    ncc01(frame, ctx, rescue.best.cx, rescue.best.cy, rescueBaseScale)
                } else 1f
            }
        }

        // Rare full-frame rescue. This may be slower, but only while genuinely lost.
        if (lostFrames >= 14 && lostFrames % 6 == 0) {
            val anchorScale = (r.width() / a.widthNorm.coerceAtLeast(0.001f)).coerceIn(0.55f, 1.80f)
            val full = search(
                frame = frame,
                template = a,
                predictedX = frame.width / 2,
                predictedY = frame.height / 2,
                radiusX = frame.width / 2 - 2,
                radiusY = frame.height / 2 - 2,
                step = 7,
                scales = floatArrayOf(anchorScale * 0.86f, anchorScale, anchorScale * 1.16f),
                motionWeight = 0f
            )
            if (full != null && (result == null || full.best.ncc > result.best.ncc + 0.045f)) {
                result = full
                anchorScore = full.best.ncc
            }
        }

        val accepted = result != null && accept(
            result = result,
            anchorScore = anchorScore,
            contextScore = contextScore,
            current = r,
            frame = frame,
            predictedCx = predictedCx,
            predictedCy = predictedCy
        )

        if (accepted && result != null) {
            val best = result.best
            val wasLost = lostFrames > 0
            val oldCx = r.centerX()
            val oldCy = r.centerY()
            val newCx = best.cx.toFloat() / frame.width
            val newCy = best.cy.toFloat() / frame.height
            val dx = newCx - oldCx
            val dy = newCy - oldCy

            val measuredAx = dx - lastDx
            val measuredAy = dy - lastDy
            ax = (ax * 0.72f + measuredAx * 0.28f).coerceIn(-0.045f, 0.045f)
            ay = (ay * 0.72f + measuredAy * 0.28f).coerceIn(-0.045f, 0.045f)
            vx = (vx * 0.48f + dx * 0.52f).coerceIn(-0.095f, 0.095f)
            vy = (vy * 0.48f + dy * 0.52f).coerceIn(-0.095f, 0.095f)
            lastDx = dx
            lastDy = dy

            val targetW = (
                if (ctx != null && best.template === ctx) r.width()
                else best.template.widthNorm * best.scale
            ).coerceIn(0.03f, 0.48f)
            val targetH = (
                if (ctx != null && best.template === ctx) r.height()
                else best.template.heightNorm * best.scale
            ).coerceIn(0.04f, 0.60f)
            resizeAndMoveBox(
                r = r,
                cx = newCx,
                cy = newCy,
                targetW = targetW,
                targetH = targetH,
                alpha = if (wasLost) 0.38f else 0.16f
            )

            nccScore = best.ncc
            uniqueness = result.uniqueness
            lostFrames = 0
            goodFrames = (goodFrames + 1).coerceAtMost(60)
            framesSinceRefresh++
            lastGoodNs = System.nanoTime()

            state = when {
                wasLost -> "REACQUIRED"
                best.ncc >= 0.63f -> "LOCK"
                else -> "ACQUIRE"
            }

            confidence = (
                0.28f + best.ncc * 0.58f + min(uniqueness, 0.60f) * 0.14f
            ).coerceIn(0.36f, 0.99f)

            // Very strict adaptive refresh: identity must be proven for a long time.
            if (
                state == "LOCK" &&
                best.ncc >= 0.90f &&
                anchorScore >= 0.82f &&
                contextScore >= 0.72f &&
                uniqueness >= 0.24f &&
                goodFrames >= 20 &&
                framesSinceRefresh >= 90
            ) {
                buildTemplate(frame, r)?.let { adaptive = it }
                framesSinceRefresh = 0
            }
        } else {
            lostFrames++
            goodFrames = max(0, goodFrames - 1)
            framesSinceRefresh++
            nccScore *= 0.91f
            uniqueness *= 0.82f
            ax *= 0.82f
            ay *= 0.82f

            if (lostFrames <= 7) {
                val decay = when {
                    lostFrames <= 2 -> 0.86f
                    lostFrames <= 4 -> 0.64f
                    else -> 0.38f
                }
                moveBox(r, r.centerX() + vx * decay, r.centerY() + vy * decay)
                state = "PRED"
                confidence = (confidence * 0.93f).coerceAtLeast(0.28f)
            } else if (lostFrames <= 150) {
                state = "REACQUIRE"
                confidence = (confidence * 0.985f).coerceAtLeast(0.14f)
            } else {
                state = "LOST HOLD"
                confidence = 0.08f
            }
        }

        lastProcMs = elapsedMs(startedNs)
        publish(state)
    }

    private fun accept(
        result: SearchResult,
        anchorScore: Float,
        contextScore: Float,
        current: RectF,
        frame: Gray,
        predictedCx: Float,
        predictedCy: Float
    ): Boolean {
        val best = result.best
        val nccFloor = when {
            lostFrames >= 12 -> 0.62f
            lostFrames >= 3 -> 0.60f
            else -> 0.62f
        }
        if (best.ncc < nccFloor) return false

        val cx = best.cx.toFloat() / frame.width
        val cy = best.cy.toFloat() / frame.height
        val jump = abs(cx - current.centerX()) + abs(cy - current.centerY())
        val predError = abs(cx - predictedCx) + abs(cy - predictedCy)

        if (lostFrames == 0) {
            if (predError > max(0.085f, current.width() * 1.25f) && best.ncc < 0.84f) return false
            if (anchorScore < 0.50f && best.ncc < 0.86f) return false
            if (contextScore < 0.44f && best.ncc < 0.88f) return false
            return true
        }

        if (jump > 0.12f) {
            if (anchorScore < 0.68f) return false
            if (contextScore < 0.54f) return false
            if (result.uniqueness < 0.10f) return false
            if (best.ncc < 0.72f) return false
        } else if ((anchorScore < 0.54f || contextScore < 0.46f) && best.ncc < 0.78f) {
            return false
        }

        return true
    }

    private data class Candidate(
        val cx: Int,
        val cy: Int,
        val scale: Float,
        val ncc: Float,
        val rank: Float,
        val template: Template
    )

    private data class SearchResult(
        val best: Candidate,
        val second: Candidate?,
        val uniqueness: Float
    )

    private fun search(
        frame: Gray,
        template: Template,
        predictedX: Int,
        predictedY: Int,
        radiusX: Int,
        radiusY: Int,
        step: Int,
        scales: FloatArray,
        motionWeight: Float
    ): SearchResult? {
        val coarse = ArrayList<Candidate>(8)

        for (scaleRaw in scales) {
            val scale = scaleRaw.coerceIn(0.50f, 1.90f)
            val hw = (template.halfW * scale).roundToInt().coerceAtLeast(4)
            val hh = (template.halfH * scale).roundToInt().coerceAtLeast(4)

            val minX = max(hw + 1, predictedX - radiusX)
            val maxX = min(frame.width - hw - 2, predictedX + radiusX)
            val minY = max(hh + 1, predictedY - radiusY)
            val maxY = min(frame.height - hh - 2, predictedY + radiusY)
            if (minX > maxX || minY > maxY) continue

            var y = minY
            while (y <= maxY) {
                var x = minX
                while (x <= maxX) {
                    val n = ncc01(frame, template, x, y, scale)
                    if (n >= 0.42f) {
                        val dx = (x - predictedX).toFloat() / max(8f, radiusX.toFloat())
                        val dy = (y - predictedY).toFloat() / max(8f, radiusY.toFloat())
                        val d2 = dx * dx + dy * dy
                        val motion = (1f - min(1f, d2)).coerceIn(0f, 1f)
                        val rank = n * (1f - motionWeight) + motion * motionWeight
                        addTop(coarse, Candidate(x, y, scale, n, rank, template), 8)
                    }
                    x += step
                }
                y += step
            }
        }

        if (coarse.isEmpty()) return null

        val refined = ArrayList<Candidate>(12)
        coarse.sortedByDescending { it.rank }.take(2).forEach { c ->
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val x = c.cx + dx
                    val y = c.cy + dy
                    if (!fits(frame, template, x, y, c.scale)) continue
                    val n = ncc01(frame, template, x, y, c.scale)
                    val mdx = (x - predictedX).toFloat() / max(8f, radiusX.toFloat())
                    val mdy = (y - predictedY).toFloat() / max(8f, radiusY.toFloat())
                    val d2 = mdx * mdx + mdy * mdy
                    val motion = (1f - min(1f, d2)).coerceIn(0f, 1f)
                    val rank = n * (1f - motionWeight) + motion * motionWeight
                    addTop(refined, Candidate(x, y, c.scale, n, rank, template), 12)
                }
            }
        }
        coarse.forEach { addTop(refined, it, 12) }

        val sorted = refined.sortedByDescending { it.rank }
        val best = sorted.firstOrNull() ?: return null

        val boxW = template.widthNorm * frame.width
        val boxH = template.heightNorm * frame.height
        val exclusion = max(8f, sqrt(boxW * boxW + boxH * boxH) * 0.42f)
        val exclusionSq = exclusion * exclusion

        val second = sorted.drop(1).firstOrNull { c ->
            val dx = (c.cx - best.cx).toFloat()
            val dy = (c.cy - best.cy).toFloat()
            (dx * dx + dy * dy) >= exclusionSq || abs(c.scale - best.scale) >= 0.14f
        }

        val uniq = if (second == null) {
            // No competitor in a local ROI is not proof of global uniqueness.
            ((best.ncc - 0.58f) / 0.34f).coerceIn(0f, 0.82f)
        } else {
            val gap = best.rank - second.rank
            ((gap - 0.006f) / 0.115f).coerceIn(0f, 1f)
        }

        return SearchResult(best, second, uniq)
    }

    private fun addTop(list: MutableList<Candidate>, candidate: Candidate, limit: Int) {
        val same = list.indexOfFirst {
            abs(it.cx - candidate.cx) <= 1 &&
                abs(it.cy - candidate.cy) <= 1 &&
                abs(it.scale - candidate.scale) <= 0.025f
        }
        if (same >= 0) {
            if (candidate.rank > list[same].rank) list[same] = candidate
            return
        }

        if (list.size < limit) {
            list.add(candidate)
            return
        }

        var worstIndex = 0
        var worstRank = list[0].rank
        for (i in 1 until list.size) {
            if (list[i].rank < worstRank) {
                worstRank = list[i].rank
                worstIndex = i
            }
        }
        if (candidate.rank > worstRank) list[worstIndex] = candidate
    }

    private data class Template(
        val halfW: Int,
        val halfH: Int,
        val offsetsX: IntArray,
        val offsetsY: IntArray,
        val centered: FloatArray,
        val variance: Float,
        val widthNorm: Float,
        val heightNorm: Float
    )

    private fun seed(frame: Gray, nx: Float, ny: Float) {
        val boxW = 0.075f
        val boxH = 0.095f
        val snapped = chooseTexturedCenter(frame, nx, ny, boxW, boxH)
        val initial = makeBox(snapped.first, snapped.second, boxW, boxH)
        val t = buildTemplate(frame, initial)
        box = initial

        if (t == null) {
            anchor = null
            contextAnchor = null
            adaptive = null
            state = "LOW TEXTURE"
            confidence = 0.12f
            nccScore = 0f
            uniqueness = 0f
            lostFrames = 0
            goodFrames = 0
            vx = 0f
            vy = 0f
            return
        }

        anchor = t
        contextAnchor = buildTemplate(frame, expandBox(initial, 1.75f, 1.65f))
        adaptive = t
        state = "LOCK"
        confidence = 0.95f
        nccScore = 1f
        uniqueness = 1f
        lostFrames = 0
        goodFrames = 2
        framesSinceRefresh = 0
        vx = 0f
        vy = 0f
        ax = 0f
        ay = 0f
        lastDx = 0f
        lastDy = 0f
        lastGoodNs = System.nanoTime()
    }

    private fun buildTemplate(frame: Gray, r: RectF): Template? {
        val cx = (r.centerX() * frame.width).roundToInt()
        val cy = (r.centerY() * frame.height).roundToInt()
        val halfW = (r.width() * frame.width * 0.30f).roundToInt().coerceIn(6, 28)
        val halfH = (r.height() * frame.height * 0.30f).roundToInt().coerceIn(6, 28)
        if (cx - halfW < 2 || cx + halfW >= frame.width - 2 ||
            cy - halfH < 2 || cy + halfH >= frame.height - 2
        ) return null

        val gridX = 9
        val gridY = 9
        val ox = IntArray(gridX * gridY)
        val oy = IntArray(gridX * gridY)
        val values = FloatArray(gridX * gridY)
        var index = 0
        var sum = 0f

        for (gy in 0 until gridY) {
            val fy = if (gridY == 1) 0f else gy.toFloat() / (gridY - 1)
            val dy = (-halfH + fy * (2f * halfH)).roundToInt()
            for (gx in 0 until gridX) {
                val fx = if (gridX == 1) 0f else gx.toFloat() / (gridX - 1)
                val dx = (-halfW + fx * (2f * halfW)).roundToInt()
                val v = grayAt(frame, cx + dx, cy + dy).toFloat()
                ox[index] = dx
                oy[index] = dy
                values[index] = v
                sum += v
                index++
            }
        }

        val mean = sum / values.size
        var variance = 0f
        for (i in values.indices) {
            values[i] -= mean
            variance += values[i] * values[i]
        }
        if (variance < values.size * 8f) return null

        return Template(
            halfW = halfW,
            halfH = halfH,
            offsetsX = ox,
            offsetsY = oy,
            centered = values,
            variance = variance,
            widthNorm = r.width(),
            heightNorm = r.height()
        )
    }

    private fun ncc01(frame: Gray, t: Template, cx: Int, cy: Int, scale: Float): Float {
        val raw = nccRaw(frame, t, cx, cy, scale)
        return ((raw + 1f) * 0.5f).coerceIn(0f, 1f)
    }

    private fun nccRaw(frame: Gray, t: Template, cx: Int, cy: Int, scale: Float): Float {
        val n = t.centered.size
        var sum = 0f
        var sumSq = 0f
        var dot = 0f

        if (abs(scale - 1f) <= 0.012f) {
            for (i in 0 until n) {
                val x = cx + t.offsetsX[i]
                val y = cy + t.offsetsY[i]
                if (x !in 0 until frame.width || y !in 0 until frame.height) return -1f
                val v = grayAt(frame, x, y).toFloat()
                sum += v
                sumSq += v * v
                dot += v * t.centered[i]
            }
        } else {
            for (i in 0 until n) {
                val x = cx + (t.offsetsX[i] * scale).roundToInt()
                val y = cy + (t.offsetsY[i] * scale).roundToInt()
                if (x !in 0 until frame.width || y !in 0 until frame.height) return -1f
                val v = grayAt(frame, x, y).toFloat()
                sum += v
                sumSq += v * v
                dot += v * t.centered[i]
            }
        }

        val variance = sumSq - (sum * sum / n)
        if (variance <= 1f || t.variance <= 1f) return -1f
        return (dot / sqrt(variance * t.variance)).coerceIn(-1f, 1f)
    }

    private fun fits(frame: Gray, t: Template, cx: Int, cy: Int, scale: Float): Boolean {
        val hw = (t.halfW * scale).roundToInt().coerceAtLeast(3)
        val hh = (t.halfH * scale).roundToInt().coerceAtLeast(3)
        return cx - hw >= 1 && cx + hw < frame.width - 1 &&
            cy - hh >= 1 && cy + hh < frame.height - 1
    }

    private data class Gray(val pixels: ByteArray, val width: Int, val height: Int)

    private data class LumaMapKey(
        val srcW: Int,
        val srcH: Int,
        val rowStride: Int,
        val pixelStride: Int,
        val rotation: Int,
        val outW: Int,
        val outH: Int
    )

    private var lumaMapKey: LumaMapKey? = null
    private var lumaIndexMap: IntArray = IntArray(0)
    private var lumaScratch: ByteArray = ByteArray(0)

    private fun extractOrientedLumaFast(image: ImageProxy, targetWidth: Int): Gray {
        val srcW = image.width
        val srcH = image.height
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val orientedW = if (rotation == 90 || rotation == 270) srcH else srcW
        val orientedH = if (rotation == 90 || rotation == 270) srcW else srcH

        val outW = min(targetWidth, orientedW).coerceAtLeast(1)
        val outH = max(1, (orientedH.toFloat() * outW / orientedW.coerceAtLeast(1)).roundToInt())

        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val key = LumaMapKey(srcW, srcH, rowStride, pixelStride, rotation, outW, outH)
        if (lumaMapKey != key || lumaIndexMap.size != outW * outH) {
            val map = IntArray(outW * outH)
            var o = 0
            for (y in 0 until outH) {
                val oy = ((y + 0.5f) * orientedH / outH).toInt().coerceIn(0, orientedH - 1)
                for (x in 0 until outW) {
                    val ox = ((x + 0.5f) * orientedW / outW).toInt().coerceIn(0, orientedW - 1)

                    val sx: Int
                    val sy: Int
                    when (rotation) {
                        90 -> {
                            sx = oy
                            sy = srcH - 1 - ox
                        }
                        180 -> {
                            sx = srcW - 1 - ox
                            sy = srcH - 1 - oy
                        }
                        270 -> {
                            sx = srcW - 1 - oy
                            sy = ox
                        }
                        else -> {
                            sx = ox
                            sy = oy
                        }
                    }
                    map[o++] = sy * rowStride + sx * pixelStride
                }
            }
            lumaIndexMap = map
            lumaScratch = ByteArray(map.size)
            lumaMapKey = key
        }

        val limit = buffer.limit()
        val out = lumaScratch
        for (i in lumaIndexMap.indices) {
            val sourceIndex = lumaIndexMap[i]
            out[i] = if (sourceIndex in 0 until limit) buffer.get(sourceIndex) else 0
        }

        return Gray(out, outW, outH)
    }

    private fun grayAt(frame: Gray, x: Int, y: Int): Byte =
        frame.pixels[y * frame.width + x]

    private fun chooseTexturedCenter(
        frame: Gray,
        nx: Float,
        ny: Float,
        w: Float,
        h: Float
    ): Pair<Float, Float> {
        val cx = (nx * frame.width).roundToInt()
        val cy = (ny * frame.height).roundToInt()
        val rx = max(4, (w * frame.width * 0.22f).roundToInt())
        val ry = max(4, (h * frame.height * 0.22f).roundToInt())

        var bestX = cx.coerceIn(2, frame.width - 3)
        var bestY = cy.coerceIn(2, frame.height - 3)
        var bestScore = -1f

        for (dy in -ry..ry step 3) {
            for (dx in -rx..rx step 3) {
                val x = (cx + dx).coerceIn(2, frame.width - 3)
                val y = (cy + dy).coerceIn(2, frame.height - 3)
                val gx = abs((grayAt(frame, x + 1, y).toInt() and 0xff) -
                    (grayAt(frame, x - 1, y).toInt() and 0xff))
                val gy = abs((grayAt(frame, x, y + 1).toInt() and 0xff) -
                    (grayAt(frame, x, y - 1).toInt() and 0xff))
                val score = (gx + gy).toFloat()
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
            }
        }

        return (bestX.toFloat() / frame.width) to (bestY.toFloat() / frame.height)
    }

    private fun expandBox(r: RectF, sx: Float, sy: Float): RectF {
        val w = (r.width() * sx).coerceIn(0.04f, 0.42f)
        val h = (r.height() * sy).coerceIn(0.05f, 0.52f)
        return makeBox(r.centerX(), r.centerY(), w, h)
    }

    private fun resizeAndMoveBox(
        r: RectF,
        cx: Float,
        cy: Float,
        targetW: Float,
        targetH: Float,
        alpha: Float
    ) {
        val w = (r.width() * (1f - alpha) + targetW * alpha).coerceIn(0.03f, 0.48f)
        val h = (r.height() * (1f - alpha) + targetH * alpha).coerceIn(0.04f, 0.60f)
        val safeCx = cx.coerceIn(w / 2f, 1f - w / 2f)
        val safeCy = cy.coerceIn(h / 2f, 1f - h / 2f)
        r.set(safeCx - w / 2f, safeCy - h / 2f, safeCx + w / 2f, safeCy + h / 2f)
    }

    private fun moveBox(r: RectF, cx: Float, cy: Float) {
        val w = r.width()
        val h = r.height()
        val safeCx = cx.coerceIn(w / 2f, 1f - w / 2f)
        val safeCy = cy.coerceIn(h / 2f, 1f - h / 2f)
        r.set(safeCx - w / 2f, safeCy - h / 2f, safeCx + w / 2f, safeCy + h / 2f)
    }

    private fun makeBox(cx: Float, cy: Float, w: Float, h: Float): RectF {
        val safeCx = cx.coerceIn(w / 2f, 1f - w / 2f)
        val safeCy = cy.coerceIn(h / 2f, 1f - h / 2f)
        return RectF(
            safeCx - w / 2f,
            safeCy - h / 2f,
            safeCx + w / 2f,
            safeCy + h / 2f
        )
    }

    private fun publish(label: String) {
        val ageMs = if (lastGoodNs > 0L) {
            (System.nanoTime() - lastGoodNs) / 1_000_000L
        } else 0L

        onSnapshot(
            TrackerOverlayView.Snapshot(
                state = label,
                box = box?.let { RectF(it) },
                confidence = confidence,
                nccScore = nccScore,
                uniqueness = uniqueness,
                frameW = frameW,
                frameH = frameH,
                ageMs = ageMs,
                lostFrames = lostFrames,
                processMs = lastProcMs
            )
        )
    }

    private fun elapsedMs(startedNs: Long): Float =
        (System.nanoTime() - startedNs) / 1_000_000f
}
