package com.tsss.gt6optical

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * GT6 Visual Tracker v2.6 — UNIQUE TRACKER
 *
 * Stage 1: fast multi-scale luminance NCC search.
 * Stage 2: re-rank spatially distinct candidates using:
 *  - luminance NCC;
 *  - edge NCC;
 *  - local gradient/texture histogram;
 *  - YUV chroma histogram when CameraX exposes it;
 *  - anchor-template consistency;
 *  - motion prediction/gating.
 *
 * Anti-drift:
 *  - immutable anchor template;
 *  - small adaptive template bank;
 *  - adaptive templates are accepted only on clean, unique LOCK frames.
 *
 * Important: uniqueness compares the best match with the best SPATIALLY DISTINCT
 * competitor. Adjacent pixels on the same object no longer make UNIQ look falsely low.
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
    private var framesSinceRefresh = 0

    private var vx = 0f
    private var vy = 0f
    private var frameW = 16
    private var frameH = 9
    private var lastGoodNs = 0L
    private var lastProcMs = 0f

    private var nextTemplateId = 1
    private var anchor: Template? = null
    private var primary: Template? = null
    private val adaptiveBank = ArrayDeque<Template>()

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
        framesSinceRefresh = 0
        vx = 0f
        vy = 0f
        lastGoodNs = 0L
        anchor = null
        primary = null
        adaptiveBank.clear()
        publish(reason)
    }

    @Synchronized
    fun process(image: ImageProxy) {
        val startedNs = System.nanoTime()
        val frame = extractFrame640(image)
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
        val p = primary
        if (r == null || a == null || p == null) {
            lastProcMs = elapsedMs(startedNs)
            publish(state)
            return
        }

        val predictedCx = (r.centerX() + vx).coerceIn(0f, 1f)
        val predictedCy = (r.centerY() + vy).coerceIn(0f, 1f)
        val predX = (predictedCx * frame.width).roundToInt().coerceIn(0, frame.width - 1)
        val predY = (predictedCy * frame.height).roundToInt().coerceIn(0, frame.height - 1)

        val boxPx = max(r.width() * frame.width, r.height() * frame.height)
        val gain = when {
            lostFrames >= 60 -> 3.5f
            lostFrames >= 25 -> 2.6f
            lostFrames >= 10 -> 1.9f
            lostFrames >= 4 -> 1.45f
            goodFrames >= 5 -> 0.72f
            else -> 0.95f
        }

        val radiusX = (((0.42f * boxPx) + 14f) * gain).roundToInt()
            .coerceIn(12, if (lostFrames >= 20) 220 else 120)
        val radiusY = (((0.34f * boxPx) + 10f) * gain).roundToInt()
            .coerceIn(10, if (lostFrames >= 20) 160 else 92)

        val coarseStep = when {
            lostFrames >= 30 -> 6
            lostFrames >= 10 -> 4
            lostFrames >= 3 -> 3
            else -> 2
        }

        val scales = when {
            lostFrames >= 25 -> floatArrayOf(0.78f, 0.90f, 1.00f, 1.12f, 1.28f)
            lostFrames >= 6 -> floatArrayOf(0.86f, 0.94f, 1.00f, 1.08f, 1.18f)
            else -> floatArrayOf(0.94f, 1.00f, 1.06f)
        }

        val rawCandidates = ArrayList<Candidate>(24)
        collectSearchCandidates(
            frame, p, predX, predY, radiusX, radiusY, coarseStep, scales, rawCandidates
        )
        if (a.id != p.id) {
            collectSearchCandidates(
                frame, a, predX, predY, radiusX, radiusY, coarseStep + 1, scales, rawCandidates
            )
        }

        if (lostFrames >= 3 && (rawCandidates.isEmpty() || bestRaw(rawCandidates) < 0.68f)) {
            adaptiveBank.takeLast(2).forEach { t ->
                collectSearchCandidates(
                    frame, t, predX, predY, radiusX, radiusY,
                    (coarseStep + 1).coerceAtMost(7), scales, rawCandidates
                )
            }
        }

        if (lostFrames >= 20 && lostFrames % 5 == 0) {
            collectSearchCandidates(
                frame = frame,
                template = a,
                predictedX = frame.width / 2,
                predictedY = frame.height / 2,
                radiusX = frame.width / 2 - 3,
                radiusY = frame.height / 2 - 3,
                coarseStep = 8,
                scales = floatArrayOf(0.72f, 0.86f, 1.0f, 1.18f, 1.36f),
                out = rawCandidates
            )
        }

        val refined = refineAndRerank(
            frame = frame,
            raw = rawCandidates,
            anchor = a,
            predictedX = predX,
            predictedY = predY,
            radiusX = radiusX,
            radiusY = radiusY
        )

        val best = refined.firstOrNull()
        val uniq = computeSpatialUniqueness(refined, r, frame)
        val accepted = best != null && acceptCandidate(best, uniq, r, frame, predictedCx, predictedCy)

        if (accepted && best != null) {
            val wasLost = lostFrames > 0

            val oldCx = r.centerX()
            val oldCy = r.centerY()
            val newCx = best.cx.toFloat() / frame.width
            val newCy = best.cy.toFloat() / frame.height
            val dx = newCx - oldCx
            val dy = newCy - oldCy

            vx = (vx * 0.58f + dx * 0.42f).coerceIn(-0.075f, 0.075f)
            vy = (vy * 0.58f + dy * 0.42f).coerceIn(-0.075f, 0.075f)

            val targetW = (best.source.widthNorm * best.scale).coerceIn(0.035f, 0.46f)
            val targetH = (best.source.heightNorm * best.scale).coerceIn(0.045f, 0.58f)
            resizeAndMoveBox(r, newCx, newCy, targetW, targetH, sizeAlpha = if (wasLost) 0.42f else 0.22f)

            nccScore = best.rawNcc
            uniqueness = uniq
            lostFrames = 0
            goodFrames = (goodFrames + 1).coerceAtMost(40)
            framesSinceRefresh++
            lastGoodNs = System.nanoTime()

            state = when {
                best.finalScore >= 0.69f && uniq >= 0.20f && goodFrames >= 2 -> "LOCK"
                wasLost -> "REACQUIRED"
                else -> "ACQUIRE"
            }

            confidence = (
                0.34f +
                    best.finalScore * 0.48f +
                    uniq * 0.18f
                ).coerceIn(0.38f, 0.99f)

            if (
                state == "LOCK" &&
                best.finalScore >= 0.82f &&
                best.anchorScore >= 0.68f &&
                uniq >= 0.42f &&
                framesSinceRefresh >= 22
            ) {
                buildTemplate(frame, r)?.let { fresh ->
                    primary = fresh
                    adaptiveBank.addLast(fresh)
                    while (adaptiveBank.size > 3) adaptiveBank.removeFirst()
                }
                framesSinceRefresh = 0
            }
        } else {
            lostFrames++
            goodFrames = max(0, goodFrames - 1)
            framesSinceRefresh++
            nccScore *= 0.90f
            uniqueness *= 0.82f

            if (lostFrames <= 10) {
                val decay = when {
                    lostFrames <= 2 -> 0.86f
                    lostFrames <= 5 -> 0.66f
                    else -> 0.40f
                }
                moveBox(r, r.centerX() + vx * decay, r.centerY() + vy * decay)
                state = "PRED"
                confidence = (confidence * 0.94f).coerceAtLeast(0.28f)
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

    private fun acceptCandidate(
        c: RankedCandidate,
        uniq: Float,
        current: RectF,
        frame: Frame,
        predictedCx: Float,
        predictedCy: Float
    ): Boolean {
        val floor = when {
            lostFrames >= 20 -> 0.52f
            lostFrames >= 6 -> 0.55f
            else -> 0.58f
        }
        if (c.finalScore < floor) return false

        val cx = c.cx.toFloat() / frame.width
        val cy = c.cy.toFloat() / frame.height
        val jump = abs(cx - current.centerX()) + abs(cy - current.centerY())
        val predictionError = abs(cx - predictedCx) + abs(cy - predictedCy)

        if (jump > 0.18f && !(uniq >= 0.46f && c.anchorScore >= 0.68f && c.rawNcc >= 0.76f)) {
            return false
        }

        if (uniq < 0.10f) {
            val nearPrediction = predictionError <= 0.035f
            if (!(nearPrediction && c.rawNcc >= 0.80f && c.anchorScore >= 0.66f)) {
                return false
            }
        }

        return true
    }

    private fun seed(frame: Frame, nx: Float, ny: Float) {
        val boxW = 0.075f
        val boxH = 0.095f
        val snapped = chooseTexturedCenter(frame, nx, ny, boxW, boxH)
        val initial = makeBox(snapped.first, snapped.second, boxW, boxH)

        val t = buildTemplate(frame, initial)
        box = initial

        if (t == null) {
            anchor = null
            primary = null
            adaptiveBank.clear()
            state = "LOW TEXTURE"
            confidence = 0.14f
            nccScore = 0f
            uniqueness = 0f
            lostFrames = 0
            goodFrames = 0
            vx = 0f
            vy = 0f
            return
        }

        anchor = t
        primary = t
        adaptiveBank.clear()
        state = "LOCK"
        confidence = 0.95f
        nccScore = 1f
        uniqueness = 1f
        lostFrames = 0
        goodFrames = 2
        framesSinceRefresh = 0
        vx = 0f
        vy = 0f
        lastGoodNs = System.nanoTime()
    }

    private data class Candidate(
        val cx: Int,
        val cy: Int,
        val scale: Float,
        val rawNcc: Float,
        val source: Template
    )

    private data class RankedCandidate(
        val cx: Int,
        val cy: Int,
        val scale: Float,
        val rawNcc: Float,
        val edgeScore: Float,
        val textureScore: Float,
        val colorScore: Float?,
        val motionScore: Float,
        val anchorScore: Float,
        val finalScore: Float,
        val source: Template
    )

    private fun collectSearchCandidates(
        frame: Frame,
        template: Template,
        predictedX: Int,
        predictedY: Int,
        radiusX: Int,
        radiusY: Int,
        coarseStep: Int,
        scales: FloatArray,
        out: MutableList<Candidate>
    ) {
        val localTop = ArrayList<Candidate>(12)
        for (scale in scales) {
            val hw = (template.halfW * scale).roundToInt().coerceAtLeast(3)
            val hh = (template.halfH * scale).roundToInt().coerceAtLeast(3)

            val minX = max(hw + 1, predictedX - max(1, radiusX))
            val maxX = min(frame.width - hw - 2, predictedX + max(1, radiusX))
            val minY = max(hh + 1, predictedY - max(1, radiusY))
            val maxY = min(frame.height - hh - 2, predictedY + max(1, radiusY))
            if (minX > maxX || minY > maxY) continue

            var y = minY
            while (y <= maxY) {
                var x = minX
                while (x <= maxX) {
                    val raw = nccAt(frame, template, x, y, scale)
                    val n01 = ((raw + 1f) * 0.5f).coerceIn(0f, 1f)
                    if (n01 >= 0.44f) {
                        addTopCandidate(localTop, Candidate(x, y, scale, n01, template), 12)
                    }
                    x += coarseStep
                }
                y += coarseStep
            }
        }

        localTop.sortedByDescending { it.rawNcc }.take(4).forEach { c ->
            val fineScales = floatArrayOf(
                (c.scale * 0.975f).coerceIn(0.68f, 1.42f),
                c.scale,
                (c.scale * 1.025f).coerceIn(0.68f, 1.42f)
            )
            for (fs in fineScales) {
                for (dy in -3..3) {
                    for (dx in -3..3) {
                        val x = c.cx + dx
                        val y = c.cy + dy
                        if (!fits(frame, c.source, x, y, fs)) continue
                        val n01 = ((nccAt(frame, c.source, x, y, fs) + 1f) * 0.5f)
                            .coerceIn(0f, 1f)
                        addTopCandidate(out, Candidate(x, y, fs, n01, c.source), 28)
                    }
                }
            }
        }

        localTop.forEach { addTopCandidate(out, it, 28) }
    }

    private fun addTopCandidate(list: MutableList<Candidate>, c: Candidate, limit: Int) {
        val existing = list.indexOfFirst {
            abs(it.cx - c.cx) <= 2 &&
                abs(it.cy - c.cy) <= 2 &&
                abs(it.scale - c.scale) <= 0.035f &&
                it.source.id == c.source.id
        }
        if (existing >= 0) {
            if (c.rawNcc > list[existing].rawNcc) list[existing] = c
            return
        }

        if (list.size < limit) {
            list.add(c)
            return
        }
        var minIndex = 0
        var minScore = list[0].rawNcc
        for (i in 1 until list.size) {
            if (list[i].rawNcc < minScore) {
                minScore = list[i].rawNcc
                minIndex = i
            }
        }
        if (c.rawNcc > minScore) list[minIndex] = c
    }

    private fun refineAndRerank(
        frame: Frame,
        raw: List<Candidate>,
        anchor: Template,
        predictedX: Int,
        predictedY: Int,
        radiusX: Int,
        radiusY: Int
    ): List<RankedCandidate> {
        if (raw.isEmpty()) return emptyList()

        val dedup = raw.sortedByDescending { it.rawNcc }
            .filterDistinctCandidates(maxCount = 12)

        val ranked = ArrayList<RankedCandidate>(dedup.size)
        for (c in dedup) {
            val edge = edgeSimilarity(frame, c.source, c.cx, c.cy, c.scale)
            val texture = textureSimilarity(
                c.source.textureHist,
                gradientHistogram(frame, c.cx, c.cy, c.source, c.scale)
            )

            val candidateColor = colorHistogram(frame, c.cx, c.cy, c.source, c.scale)
            val color = if (candidateColor != null && c.source.colorHist != null) {
                histogramIntersection(c.source.colorHist, candidateColor)
            } else null

            val targetW = c.source.widthNorm * c.scale
            val anchorScale = (targetW / anchor.widthNorm.coerceAtLeast(0.001f))
                .coerceIn(0.62f, 1.55f)
            val anchorNcc = if (fits(frame, anchor, c.cx, c.cy, anchorScale)) {
                ((nccAt(frame, anchor, c.cx, c.cy, anchorScale) + 1f) * 0.5f)
                    .coerceIn(0f, 1f)
            } else 0f

            val dx = (c.cx - predictedX).toFloat() / max(8f, radiusX.toFloat())
            val dy = (c.cy - predictedY).toFloat() / max(8f, radiusY.toFloat())
            val motion = exp((-0.72f * (dx * dx + dy * dy)).toDouble()).toFloat()
                .coerceIn(0f, 1f)

            var weighted = 0f
            var sumW = 0f
            fun add(v: Float, w: Float) {
                weighted += v.coerceIn(0f, 1f) * w
                sumW += w
            }

            add(c.rawNcc, 0.46f)
            add(edge, 0.16f)
            add(texture, 0.11f)
            if (color != null) add(color, 0.11f)
            add(motion, if (lostFrames >= 12) 0.06f else 0.10f)
            add(anchorNcc, if (lostFrames >= 12) 0.12f else 0.06f)

            val finalScore = if (sumW > 0f) weighted / sumW else 0f

            ranked += RankedCandidate(
                cx = c.cx,
                cy = c.cy,
                scale = c.scale,
                rawNcc = c.rawNcc,
                edgeScore = edge,
                textureScore = texture,
                colorScore = color,
                motionScore = motion,
                anchorScore = anchorNcc,
                finalScore = finalScore,
                source = c.source
            )
        }

        return ranked.sortedByDescending { it.finalScore }
    }

    private fun List<Candidate>.filterDistinctCandidates(maxCount: Int): List<Candidate> {
        val out = ArrayList<Candidate>(maxCount)
        for (c in this) {
            val isNear = out.any {
                abs(it.cx - c.cx) <= 4 &&
                    abs(it.cy - c.cy) <= 4 &&
                    abs(it.scale - c.scale) <= 0.05f
            }
            if (!isNear) out += c
            if (out.size >= maxCount) break
        }
        return out
    }

    private fun computeSpatialUniqueness(
        ranked: List<RankedCandidate>,
        current: RectF,
        frame: Frame
    ): Float {
        val best = ranked.firstOrNull() ?: return 0f
        val boxDiagPx = sqrt(
            (current.width() * frame.width) * (current.width() * frame.width) +
                (current.height() * frame.height) * (current.height() * frame.height)
        )
        val exclusionPx = max(10f, boxDiagPx * 0.48f)

        val competitor = ranked.drop(1).firstOrNull { c ->
            val dx = (c.cx - best.cx).toFloat()
            val dy = (c.cy - best.cy).toFloat()
            val dist = sqrt(dx * dx + dy * dy)
            dist >= exclusionPx || abs(c.scale - best.scale) >= 0.16f
        }

        if (competitor == null) return 1f

        val gap = best.finalScore - competitor.finalScore
        return ((gap - 0.018f) / 0.17f).coerceIn(0f, 1f)
    }

    private fun bestRaw(list: List<Candidate>): Float =
        list.maxOfOrNull { it.rawNcc } ?: 0f

    private data class Template(
        val id: Int,
        val halfW: Int,
        val halfH: Int,
        val offsetsX: IntArray,
        val offsetsY: IntArray,
        val yCentered: FloatArray,
        val yVariance: Float,
        val edgeCentered: FloatArray?,
        val edgeVariance: Float,
        val textureHist: FloatArray,
        val colorHist: FloatArray?,
        val widthNorm: Float,
        val heightNorm: Float
    )

    private fun buildTemplate(frame: Frame, r: RectF): Template? {
        val cx = (r.centerX() * frame.width).roundToInt()
        val cy = (r.centerY() * frame.height).roundToInt()

        val halfW = (r.width() * frame.width * 0.31f).roundToInt().coerceIn(6, 30)
        val halfH = (r.height() * frame.height * 0.31f).roundToInt().coerceIn(6, 30)
        if (cx - halfW < 2 || cx + halfW >= frame.width - 2 ||
            cy - halfH < 2 || cy + halfH >= frame.height - 2
        ) return null

        val step = if (halfW * halfH > 120) 2 else 1
        val ox = ArrayList<Int>()
        val oy = ArrayList<Int>()
        val ys = ArrayList<Float>()
        val es = ArrayList<Float>()

        var yy = -halfH
        while (yy <= halfH) {
            var xx = -halfW
            while (xx <= halfW) {
                ox += xx
                oy += yy
                ys += yAt(frame, cx + xx, cy + yy).toFloat()
                es += edgeAt(frame, cx + xx, cy + yy)
                xx += step
            }
            yy += step
        }

        if (ys.size < 45) return null

        val yCentered = centerArray(ys)
        val yVar = yCentered.sumOf { (it * it).toDouble() }.toFloat()
        if (yVar < ys.size * 7.0f) return null

        val edgeCenteredRaw = centerArray(es)
        val edgeVar = edgeCenteredRaw.sumOf { (it * it).toDouble() }.toFloat()
        val edgeCentered = if (edgeVar >= es.size * 2.0f) edgeCenteredRaw else null

        return Template(
            id = nextTemplateId++,
            halfW = halfW,
            halfH = halfH,
            offsetsX = ox.toIntArray(),
            offsetsY = oy.toIntArray(),
            yCentered = yCentered,
            yVariance = yVar,
            edgeCentered = edgeCentered,
            edgeVariance = edgeVar,
            textureHist = gradientHistogram(frame, cx, cy, halfW, halfH),
            colorHist = colorHistogram(frame, cx, cy, halfW, halfH),
            widthNorm = r.width(),
            heightNorm = r.height()
        )
    }

    private fun centerArray(values: List<Float>): FloatArray {
        val mean = values.sum() / values.size.coerceAtLeast(1)
        return FloatArray(values.size) { i -> values[i] - mean }
    }

    private fun nccAt(frame: Frame, t: Template, cx: Int, cy: Int, scale: Float): Float {
        val n = t.yCentered.size
        var sum = 0f
        var sumSq = 0f
        var dot = 0f

        for (i in 0 until n) {
            val x = cx + (t.offsetsX[i] * scale).roundToInt()
            val y = cy + (t.offsetsY[i] * scale).roundToInt()
            if (x !in 1 until frame.width - 1 || y !in 1 until frame.height - 1) return -1f
            val v = yAt(frame, x, y).toFloat()
            sum += v
            sumSq += v * v
            dot += v * t.yCentered[i]
        }

        val variance = sumSq - (sum * sum / n.coerceAtLeast(1))
        if (variance <= 1f || t.yVariance <= 1f) return -1f
        return (dot / sqrt(variance * t.yVariance)).coerceIn(-1f, 1f)
    }

    private fun edgeSimilarity(frame: Frame, t: Template, cx: Int, cy: Int, scale: Float): Float {
        val ref = t.edgeCentered ?: return 0.5f
        val n = ref.size
        var sum = 0f
        var sumSq = 0f
        var dot = 0f

        for (i in 0 until n) {
            val x = cx + (t.offsetsX[i] * scale).roundToInt()
            val y = cy + (t.offsetsY[i] * scale).roundToInt()
            if (x !in 1 until frame.width - 1 || y !in 1 until frame.height - 1) return 0f
            val v = edgeAt(frame, x, y)
            sum += v
            sumSq += v * v
            dot += v * ref[i]
        }

        val variance = sumSq - sum * sum / n.coerceAtLeast(1)
        if (variance <= 1f || t.edgeVariance <= 1f) return 0.5f
        return (((dot / sqrt(variance * t.edgeVariance)).coerceIn(-1f, 1f) + 1f) * 0.5f)
    }

    private fun textureSimilarity(a: FloatArray, b: FloatArray): Float =
        histogramIntersection(a, b)

    private fun histogramIntersection(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var s = 0f
        for (i in a.indices) s += min(a[i], b[i])
        return s.coerceIn(0f, 1f)
    }

    private fun gradientHistogram(
        frame: Frame,
        cx: Int,
        cy: Int,
        t: Template,
        scale: Float
    ): FloatArray {
        val hw = (t.halfW * scale).roundToInt().coerceAtLeast(4)
        val hh = (t.halfH * scale).roundToInt().coerceAtLeast(4)
        return gradientHistogram(frame, cx, cy, hw, hh)
    }

    private fun gradientHistogram(frame: Frame, cx: Int, cy: Int, hw: Int, hh: Int): FloatArray {
        val hist = FloatArray(8)
        val step = 3
        var y = max(2, cy - hh)
        val yEnd = min(frame.height - 3, cy + hh)
        while (y <= yEnd) {
            var x = max(2, cx - hw)
            val xEnd = min(frame.width - 3, cx + hw)
            while (x <= xEnd) {
                val gx = yAt(frame, x + 1, y).toInt() - yAt(frame, x - 1, y).toInt()
                val gy = yAt(frame, x, y + 1).toInt() - yAt(frame, x, y - 1).toInt()
                val mag = (abs(gx) + abs(gy)).toFloat()
                if (mag > 8f) {
                    var angle = atan2(gy.toDouble(), gx.toDouble())
                    if (angle < 0.0) angle += Math.PI * 2.0
                    val bin = ((angle / (Math.PI * 2.0)) * 8.0).toInt().coerceIn(0, 7)
                    hist[bin] += mag
                }
                x += step
            }
            y += step
        }
        normalizeHist(hist)
        return hist
    }

    private fun colorHistogram(frame: Frame, cx: Int, cy: Int, t: Template, scale: Float): FloatArray? {
        val hw = (t.halfW * scale).roundToInt().coerceAtLeast(4)
        val hh = (t.halfH * scale).roundToInt().coerceAtLeast(4)
        return colorHistogram(frame, cx, cy, hw, hh)
    }

    private fun colorHistogram(frame: Frame, cx: Int, cy: Int, hw: Int, hh: Int): FloatArray? {
        val u = frame.u ?: return null
        val v = frame.v ?: return null
        if (frame.chromaW <= 0 || frame.chromaH <= 0) return null

        val hist = FloatArray(16)
        val x0 = max(0, ((cx - hw).toFloat() / frame.width * frame.chromaW).roundToInt())
        val x1 = min(frame.chromaW - 1, ((cx + hw).toFloat() / frame.width * frame.chromaW).roundToInt())
        val y0 = max(0, ((cy - hh).toFloat() / frame.height * frame.chromaH).roundToInt())
        val y1 = min(frame.chromaH - 1, ((cy + hh).toFloat() / frame.height * frame.chromaH).roundToInt())

        if (x0 >= x1 || y0 >= y1) return null
        var count = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val idx = y * frame.chromaW + x
                if (idx in u.indices && idx in v.indices) {
                    val ub = (u[idx].toInt() and 0xff) ushr 6
                    val vb = (v[idx].toInt() and 0xff) ushr 6
                    hist[(ub shl 2) or vb] += 1f
                    count++
                }
                x += 2
            }
            y += 2
        }
        if (count < 6) return null
        normalizeHist(hist)
        return hist
    }

    private fun normalizeHist(hist: FloatArray) {
        val s = hist.sum()
        if (s <= 0f) return
        for (i in hist.indices) hist[i] /= s
    }

    private fun fits(frame: Frame, t: Template, cx: Int, cy: Int, scale: Float): Boolean {
        val hw = (t.halfW * scale).roundToInt().coerceAtLeast(3)
        val hh = (t.halfH * scale).roundToInt().coerceAtLeast(3)
        return cx - hw >= 2 && cx + hw < frame.width - 2 &&
            cy - hh >= 2 && cy + hh < frame.height - 2
    }

    private fun resizeAndMoveBox(
        r: RectF,
        cx: Float,
        cy: Float,
        targetW: Float,
        targetH: Float,
        sizeAlpha: Float
    ) {
        val w = (r.width() * (1f - sizeAlpha) + targetW * sizeAlpha).coerceIn(0.03f, 0.48f)
        val h = (r.height() * (1f - sizeAlpha) + targetH * sizeAlpha).coerceIn(0.04f, 0.60f)
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

    private fun chooseTexturedCenter(
        frame: Frame,
        nx: Float,
        ny: Float,
        w: Float,
        h: Float
    ): Pair<Float, Float> {
        val cx = (nx * frame.width).roundToInt()
        val cy = (ny * frame.height).roundToInt()
        val rx = max(5, (w * frame.width * 0.24f).roundToInt())
        val ry = max(5, (h * frame.height * 0.24f).roundToInt())

        var bestX = cx
        var bestY = cy
        var bestTexture = -1f

        for (dy in -ry..ry step 3) {
            for (dx in -rx..rx step 3) {
                val x = (cx + dx).coerceIn(3, frame.width - 4)
                val y = (cy + dy).coerceIn(3, frame.height - 4)
                var score = 0f
                for (yy in -3..3 step 2) {
                    for (xx in -3..3 step 2) score += edgeAt(frame, x + xx, y + yy)
                }
                if (score > bestTexture) {
                    bestTexture = score
                    bestX = x
                    bestY = y
                }
            }
        }
        return (bestX.toFloat() / frame.width) to (bestY.toFloat() / frame.height)
    }

    private data class Frame(
        val y: ByteArray,
        val width: Int,
        val height: Int,
        val u: ByteArray?,
        val v: ByteArray?,
        val chromaW: Int,
        val chromaH: Int
    )

    private data class PlaneData(val data: ByteArray, val width: Int, val height: Int)

    private fun extractFrame640(image: ImageProxy): Frame {
        val srcW = image.width
        val srcH = image.height
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360

        val yRaw = readPlane(image.planes[0], srcW, srcH)
        val yRot = rotatePlane(yRaw, srcW, srcH, rotation)

        var uRot: PlaneData? = null
        var vRot: PlaneData? = null
        if (image.planes.size >= 3) {
            val cw = (srcW + 1) / 2
            val ch = (srcH + 1) / 2
            val uRaw = readPlane(image.planes[1], cw, ch)
            val vRaw = readPlane(image.planes[2], cw, ch)
            uRot = rotatePlane(uRaw, cw, ch, rotation)
            vRot = rotatePlane(vRaw, cw, ch, rotation)
        }

        val scale = min(1f, 640f / yRot.width.coerceAtLeast(1))
        val outW = max(1, (yRot.width * scale).roundToInt())
        val outH = max(1, (yRot.height * scale).roundToInt())

        val yOut = if (outW == yRot.width && outH == yRot.height) {
            yRot.data
        } else {
            resizeBilinear(yRot.data, yRot.width, yRot.height, outW, outH)
        }

        var uOut: ByteArray? = null
        var vOut: ByteArray? = null
        var outCw = 0
        var outCh = 0
        if (uRot != null && vRot != null) {
            outCw = max(1, (uRot.width * scale).roundToInt())
            outCh = max(1, (uRot.height * scale).roundToInt())
            uOut = if (outCw == uRot.width && outCh == uRot.height) uRot.data
            else resizeNearest(uRot.data, uRot.width, uRot.height, outCw, outCh)
            vOut = if (outCw == vRot.width && outCh == vRot.height) vRot.data
            else resizeNearest(vRot.data, vRot.width, vRot.height, outCw, outCh)
        }

        return Frame(yOut, outW, outH, uOut, vOut, outCw, outCh)
    }

    private fun readPlane(plane: ImageProxy.PlaneProxy, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        var o = 0
        for (y in 0 until height) {
            val rowBase = y * rowStride
            for (x in 0 until width) {
                val index = rowBase + x * pixelStride
                out[o++] = if (index >= 0 && index < buffer.limit()) buffer.get(index) else 0
            }
        }
        return out
    }

    private fun rotatePlane(src: ByteArray, w: Int, h: Int, rotation: Int): PlaneData {
        return when (rotation) {
            90 -> {
                val dst = ByteArray(src.size)
                val nw = h
                val nh = w
                for (y in 0 until h) for (x in 0 until w) {
                    val nx = h - 1 - y
                    val ny = x
                    dst[ny * nw + nx] = src[y * w + x]
                }
                PlaneData(dst, nw, nh)
            }
            180 -> {
                val dst = ByteArray(src.size)
                for (i in src.indices) dst[src.lastIndex - i] = src[i]
                PlaneData(dst, w, h)
            }
            270 -> {
                val dst = ByteArray(src.size)
                val nw = h
                val nh = w
                for (y in 0 until h) for (x in 0 until w) {
                    val nx = y
                    val ny = w - 1 - x
                    dst[ny * nw + nx] = src[y * w + x]
                }
                PlaneData(dst, nw, nh)
            }
            else -> PlaneData(src, w, h)
        }
    }

    private fun resizeNearest(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        val dst = ByteArray(dw * dh)
        for (y in 0 until dh) {
            val sy = (y.toLong() * sh / dh).toInt().coerceIn(0, sh - 1)
            for (x in 0 until dw) {
                val sx = (x.toLong() * sw / dw).toInt().coerceIn(0, sw - 1)
                dst[y * dw + x] = src[sy * sw + sx]
            }
        }
        return dst
    }

    private fun resizeBilinear(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        if (sw == dw && sh == dh) return src
        val dst = ByteArray(dw * dh)
        val xScale = if (dw > 1) (sw - 1).toFloat() / (dw - 1) else 0f
        val yScale = if (dh > 1) (sh - 1).toFloat() / (dh - 1) else 0f

        for (y in 0 until dh) {
            val fy = y * yScale
            val y0 = fy.toInt().coerceIn(0, sh - 1)
            val y1 = min(sh - 1, y0 + 1)
            val wy = fy - y0
            for (x in 0 until dw) {
                val fx = x * xScale
                val x0 = fx.toInt().coerceIn(0, sw - 1)
                val x1 = min(sw - 1, x0 + 1)
                val wx = fx - x0

                val p00 = src[y0 * sw + x0].toInt() and 0xff
                val p10 = src[y0 * sw + x1].toInt() and 0xff
                val p01 = src[y1 * sw + x0].toInt() and 0xff
                val p11 = src[y1 * sw + x1].toInt() and 0xff

                val top = p00 + (p10 - p00) * wx
                val bot = p01 + (p11 - p01) * wx
                val value = top + (bot - top) * wy
                dst[y * dw + x] = value.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return dst
    }

    private fun yAt(frame: Frame, x: Int, y: Int): Byte =
        frame.y[y * frame.width + x]

    private fun edgeAt(frame: Frame, x: Int, y: Int): Float {
        if (x <= 0 || x >= frame.width - 1 || y <= 0 || y >= frame.height - 1) return 0f
        val gx = (yAt(frame, x + 1, y).toInt() and 0xff) -
            (yAt(frame, x - 1, y).toInt() and 0xff)
        val gy = (yAt(frame, x, y + 1).toInt() and 0xff) -
            (yAt(frame, x, y - 1).toInt() and 0xff)
        return (abs(gx) + abs(gy)).coerceAtMost(510).toFloat()
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
