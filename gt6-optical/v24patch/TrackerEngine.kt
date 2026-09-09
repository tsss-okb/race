package com.tsss.gt6optical

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * GT6 Visual Tracker v2.4
 *
 * Primary: normalized cross correlation (NCC) template tracker.
 * Motion: constant-velocity predictor.
 * Recovery: progressively wider anchor-template search; after long occlusion the
 * visible box becomes LOST HOLD but is never silently deleted.
 *
 * No JNI is used in this test core. That intentionally removes the v2.3 failure
 * mode where sparse optical flow returned T0/B0 and killed the lock.
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
    private var lastGoodNs = 0L
    private var frameW = 16
    private var frameH = 9
    private var lastProcMs = 0f

    private var anchor: Template? = null
    private var current: Template? = null

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
        current = null
        publish(reason)
    }

    @Synchronized
    fun process(image: ImageProxy) {
        val startedNs = System.nanoTime()
        val gray = extractLuma640(image)
        frameW = gray.width
        frameH = gray.height

        val tap = pendingTap
        if (tap != null) {
            pendingTap = null
            seed(gray, tap.first, tap.second)
            lastProcMs = (System.nanoTime() - startedNs) / 1_000_000f
            publish(state)
            return
        }

        val r = box
        val a = anchor
        if (r == null || a == null) {
            lastProcMs = (System.nanoTime() - startedNs) / 1_000_000f
            publish(state)
            return
        }

        val predictedCx = (r.centerX() + vx).coerceIn(0f, 1f)
        val predictedCy = (r.centerY() + vy).coerceIn(0f, 1f)
        val px = max(r.width() * gray.width, r.height() * gray.height)

        val gain = when {
            lostFrames >= 90 -> 3.4f
            lostFrames >= 45 -> 2.8f
            lostFrames >= 18 -> 2.15f
            lostFrames >= 7 -> 1.62f
            lostFrames >= 2 -> 1.25f
            goodFrames >= 5 -> 0.72f
            else -> 0.92f
        }

        val radiusX = (((0.34f * px) + 12f) * gain).toInt()
            .coerceIn(10, if (lostFrames >= 45) 210 else 120)
        val radiusY = (((0.28f * px) + 9f) * gain).toInt()
            .coerceIn(8, if (lostFrames >= 45) 145 else 90)

        val predX = (predictedCx * gray.width).toInt().coerceIn(0, gray.width - 1)
        val predY = (predictedCy * gray.height).toInt().coerceIn(0, gray.height - 1)

        val coarse = when {
            lostFrames >= 45 -> 6
            lostFrames >= 12 -> 4
            lostFrames >= 3 -> 3
            else -> 2
        }

        var match = current?.let {
            matchTemplate(gray, it, predX, predY, radiusX, radiusY, coarse)
        }

        if (match == null || match.score < visualFloor()) {
            val anchorMatch = matchTemplate(gray, a, predX, predY, radiusX, radiusY, coarse)
            if (anchorMatch != null && (match == null || anchorMatch.score > match.score + 0.015f)) {
                match = anchorMatch
            }
        }

        if (
            (match == null || match.score < reacquireFloor()) &&
            lostFrames >= 35 && lostFrames % 5 == 0
        ) {
            val full = matchTemplate(
                gray = gray,
                template = a,
                predictedX = gray.width / 2,
                predictedY = gray.height / 2,
                radiusX = gray.width / 2 - a.halfW - 2,
                radiusY = gray.height / 2 - a.halfH - 2,
                coarseStep = 8
            )
            if (full != null && (match == null || full.score > match.score)) match = full
        }

        val accepted = match?.let { acceptMatch(gray, r, it) } == true
        if (accepted) {
            val wasLost = lostFrames > 0
            lostFrames = 0
            goodFrames = (goodFrames + 1).coerceAtMost(30)
            framesSinceRefresh++
            lastGoodNs = System.nanoTime()

            state = when {
                nccScore >= 0.70f && goodFrames >= 2 -> "LOCK"
                wasLost -> "REACQUIRED"
                else -> "ACQUIRE"
            }
            confidence = (0.30f + 0.70f * nccScore).coerceIn(0.40f, 0.98f)

            if (nccScore >= 0.86f && goodFrames >= 5 && framesSinceRefresh >= 18) {
                buildTemplate(gray, r, variancePerSample = 5.0)?.let { current = it }
                framesSinceRefresh = 0
            }
        } else {
            lostFrames++
            goodFrames = max(0, goodFrames - 1)
            framesSinceRefresh++
            nccScore *= 0.90f
            uniqueness *= 0.80f

            if (lostFrames <= 10) {
                val decay = when {
                    lostFrames <= 2 -> 0.82f
                    lostFrames <= 5 -> 0.62f
                    else -> 0.38f
                }
                moveBoxTo(r, r.centerX() + vx * decay, r.centerY() + vy * decay)
                state = "PRED"
                confidence = (confidence * 0.94f).coerceAtLeast(0.30f)
            } else if (lostFrames <= 120) {
                state = "REACQUIRE"
                confidence = (confidence * 0.985f).coerceAtLeast(0.16f)
            } else {
                state = "LOST HOLD"
                confidence = 0.10f
            }
        }

        lastProcMs = (System.nanoTime() - startedNs) / 1_000_000f
        publish(state)
    }

    private fun visualFloor(): Float = if (lostFrames >= 6) 0.52f else 0.55f
    private fun reacquireFloor(): Float = if (lostFrames >= 20) 0.50f else 0.53f

    private fun seed(gray: Gray, nx: Float, ny: Float) {
        val baseW = 0.075f
        val baseH = 0.095f
        val snapped = chooseTexturedCenter(gray, nx, ny, baseW, baseH)
        val initial = makeBox(snapped.first, snapped.second, baseW, baseH)

        var t = buildTemplate(gray, initial, variancePerSample = 5.0)
        if (t == null) {
            t = buildTemplate(gray, initial, coreFactor = 0.46f, variancePerSample = 1.8)
        }

        box = initial
        if (t == null) {
            anchor = null
            current = null
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
        current = t
        state = "LOCK"
        confidence = 0.94f
        nccScore = 1f
        uniqueness = 1f
        lostFrames = 0
        goodFrames = 2
        framesSinceRefresh = 0
        vx = 0f
        vy = 0f
        lastGoodNs = System.nanoTime()
    }

    private fun acceptMatch(gray: Gray, r: RectF, m: Match): Boolean {
        val floor = visualFloor()
        if (m.score < floor) return false

        val oldCx = r.centerX()
        val oldCy = r.centerY()
        val newCx = m.x.toFloat() / gray.width
        val newCy = m.y.toFloat() / gray.height
        val dx = newCx - oldCx
        val dy = newCy - oldCy
        val jump = abs(dx) + abs(dy)

        if (jump > 0.20f && !(lostFrames >= 8 && m.score >= 0.72f)) return false

        vx = (vx * 0.55f + dx * 0.45f).coerceIn(-0.06f, 0.06f)
        vy = (vy * 0.55f + dy * 0.45f).coerceIn(-0.06f, 0.06f)
        moveBoxTo(r, newCx, newCy)
        nccScore = m.score
        uniqueness = m.uniqueness
        return true
    }

    private data class Gray(val pixels: ByteArray, val width: Int, val height: Int)

    private data class Template(
        val halfW: Int,
        val halfH: Int,
        val sampleStep: Int,
        val offsetsX: IntArray,
        val offsetsY: IntArray,
        val centered: FloatArray,
        val variance: Float,
        val widthNorm: Float,
        val heightNorm: Float
    )

    private data class Match(
        val x: Int,
        val y: Int,
        val score: Float,
        val uniqueness: Float
    )

    private fun buildTemplate(
        gray: Gray,
        r: RectF,
        coreFactor: Float = 0.30f,
        variancePerSample: Double
    ): Template? {
        if (gray.width < 120 || gray.height < 68) return null
        val cx = (r.centerX() * gray.width).toInt().coerceIn(0, gray.width - 1)
        val cy = (r.centerY() * gray.height).toInt().coerceIn(0, gray.height - 1)
        val hw = (r.width() * gray.width * coreFactor).toInt().coerceIn(5, 24)
        val hh = (r.height() * gray.height * coreFactor).toInt().coerceIn(5, 24)
        if (!fits(gray, cx, cy, hw, hh)) return null

        val step = if (hw * hh >= 90) 2 else 1
        val countX = (hw * 2) / step + 1
        val countY = (hh * 2) / step + 1
        val n = countX * countY
        if (n < 35) return null

        val ox = IntArray(n)
        val oy = IntArray(n)
        val vals = FloatArray(n)
        var idx = 0
        var sum = 0.0
        var yy = -hh
        while (yy <= hh) {
            var xx = -hw
            while (xx <= hw) {
                val v = grayAt(gray, cx + xx, cy + yy).toFloat()
                ox[idx] = xx
                oy[idx] = yy
                vals[idx] = v
                sum += v
                idx++
                xx += step
            }
            yy += step
        }

        val mean = (sum / idx).toFloat()
        var variance = 0f
        for (i in 0 until idx) {
            vals[i] -= mean
            variance += vals[i] * vals[i]
        }
        if (variance < idx * variancePerSample) return null

        return Template(
            halfW = hw,
            halfH = hh,
            sampleStep = step,
            offsetsX = if (idx == ox.size) ox else ox.copyOf(idx),
            offsetsY = if (idx == oy.size) oy else oy.copyOf(idx),
            centered = if (idx == vals.size) vals else vals.copyOf(idx),
            variance = variance,
            widthNorm = r.width(),
            heightNorm = r.height()
        )
    }

    private fun matchTemplate(
        gray: Gray,
        template: Template,
        predictedX: Int,
        predictedY: Int,
        radiusX: Int,
        radiusY: Int,
        coarseStep: Int
    ): Match? {
        val minX = max(template.halfW, predictedX - max(1, radiusX))
        val maxX = min(gray.width - 1 - template.halfW, predictedX + max(1, radiusX))
        val minY = max(template.halfH, predictedY - max(1, radiusY))
        val maxY = min(gray.height - 1 - template.halfH, predictedY + max(1, radiusY))
        if (minX > maxX || minY > maxY) return null

        var bestX = predictedX.coerceIn(minX, maxX)
        var bestY = predictedY.coerceIn(minY, maxY)
        var best = -2f
        var second = -2f

        var y = minY
        while (y <= maxY) {
            var x = minX
            while (x <= maxX) {
                val s = nccAt(gray, template, x, y)
                if (s > best) {
                    second = best
                    best = s
                    bestX = x
                    bestY = y
                } else if (s > second) {
                    second = s
                }
                x += coarseStep
            }
            y += coarseStep
        }

        if (best <= -1.5f) return null

        val fineR = max(2, coarseStep + 1)
        val fMinX = max(template.halfW, bestX - fineR)
        val fMaxX = min(gray.width - 1 - template.halfW, bestX + fineR)
        val fMinY = max(template.halfH, bestY - fineR)
        val fMaxY = min(gray.height - 1 - template.halfH, bestY + fineR)

        var fy = fMinY
        while (fy <= fMaxY) {
            var fx = fMinX
            while (fx <= fMaxX) {
                val s = nccAt(gray, template, fx, fy)
                if (s > best) {
                    second = best
                    best = s
                    bestX = fx
                    bestY = fy
                } else if (s > second && abs(fx - bestX) + abs(fy - bestY) > 2) {
                    second = s
                }
                fx++
            }
            fy++
        }

        val score01 = ((best + 1f) * 0.5f).coerceIn(0f, 1f)
        val uniq = (best - second).coerceIn(0f, 1f)
        return Match(bestX, bestY, score01, uniq)
    }

    private fun nccAt(gray: Gray, t: Template, cx: Int, cy: Int): Float {
        val n = t.centered.size
        var sum = 0f
        var sumSq = 0f
        var dot = 0f
        for (i in 0 until n) {
            val v = grayAt(gray, cx + t.offsetsX[i], cy + t.offsetsY[i]).toFloat()
            sum += v
            sumSq += v * v
            dot += t.centered[i] * v
        }
        val varC = sumSq - (sum * sum / n.toFloat())
        if (varC <= 1f || t.variance <= 1f) return -1f
        return (dot / sqrt(t.variance * varC)).coerceIn(-1f, 1f)
    }

    private fun chooseTexturedCenter(
        gray: Gray,
        nx: Float,
        ny: Float,
        boxW: Float,
        boxH: Float
    ): Pair<Float, Float> {
        val baseX = (nx * gray.width).toInt()
        val baseY = (ny * gray.height).toInt()
        val searchX = (gray.width * 0.025f).toInt().coerceIn(8, 18)
        val searchY = (gray.height * 0.030f).toInt().coerceIn(6, 14)
        val hw = (boxW * gray.width * 0.24f).toInt().coerceIn(5, 13)
        val hh = (boxH * gray.height * 0.24f).toInt().coerceIn(5, 12)

        var bestX = baseX.coerceIn(hw, gray.width - 1 - hw)
        var bestY = baseY.coerceIn(hh, gray.height - 1 - hh)
        var bestValue = localVariance(gray, bestX, bestY, hw, hh) * 1.05f

        var y = baseY - searchY
        while (y <= baseY + searchY) {
            var x = baseX - searchX
            while (x <= baseX + searchX) {
                if (x in hw until gray.width - hw && y in hh until gray.height - hh) {
                    val variance = localVariance(gray, x, y, hw, hh)
                    val dist = abs(x - baseX) + abs(y - baseY)
                    val value = variance - dist * 1.8f
                    if (value > bestValue) {
                        bestValue = value
                        bestX = x
                        bestY = y
                    }
                }
                x += 4
            }
            y += 4
        }
        return bestX.toFloat() / gray.width to bestY.toFloat() / gray.height
    }

    private fun localVariance(gray: Gray, cx: Int, cy: Int, hw: Int, hh: Int): Float {
        var n = 0
        var sum = 0f
        var sumSq = 0f
        var y = -hh
        while (y <= hh) {
            var x = -hw
            while (x <= hw) {
                val v = grayAt(gray, cx + x, cy + y).toFloat()
                sum += v
                sumSq += v * v
                n++
                x += 2
            }
            y += 2
        }
        return if (n > 0) sumSq - (sum * sum / n) else 0f
    }

    private fun grayAt(gray: Gray, x: Int, y: Int): Int =
        gray.pixels[y * gray.width + x].toInt() and 0xff

    private fun fits(gray: Gray, cx: Int, cy: Int, hw: Int, hh: Int): Boolean =
        cx - hw >= 0 && cy - hh >= 0 && cx + hw < gray.width && cy + hh < gray.height

    private fun makeBox(cx: Float, cy: Float, w: Float, h: Float): RectF {
        val hw = w * 0.5f
        val hh = h * 0.5f
        val x = cx.coerceIn(hw, 1f - hw)
        val y = cy.coerceIn(hh, 1f - hh)
        return RectF(x - hw, y - hh, x + hw, y + hh)
    }

    private fun moveBoxTo(r: RectF, cx: Float, cy: Float) {
        val hw = r.width() * 0.5f
        val hh = r.height() * 0.5f
        val x = cx.coerceIn(hw, 1f - hw)
        val y = cy.coerceIn(hh, 1f - hh)
        r.set(x - hw, y - hh, x + hw, y + hh)
    }

    private fun publish(displayState: String) {
        val ageMs = if (lastGoodNs > 0L) {
            (System.nanoTime() - lastGoodNs) / 1_000_000L
        } else 0L

        onSnapshot(
            TrackerOverlayView.Snapshot(
                state = displayState,
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

    private fun extractLuma640(image: ImageProxy): Gray {
        val plane = image.planes[0]
        val srcW = image.width
        val srcH = image.height
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360

        val rotW = if (rotation == 90 || rotation == 270) srcH else srcW
        val rotH = if (rotation == 90 || rotation == 270) srcW else srcH
        val scale = min(1f, 640f / rotW.toFloat())
        val outW = max(2, (rotW * scale).toInt())
        val outH = max(2, (rotH * scale).toInt())
        val out = ByteArray(outW * outH)

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buf = plane.buffer.duplicate()
        val base = buf.position()

        var oy = 0
        while (oy < outH) {
            val ry = ((rotH.toLong() * oy) / outH).toInt().coerceIn(0, rotH - 1)
            var ox = 0
            while (ox < outW) {
                val rx = ((rotW.toLong() * ox) / outW).toInt().coerceIn(0, rotW - 1)
                val sx: Int
                val sy: Int
                when (rotation) {
                    90 -> { sx = ry; sy = srcH - 1 - rx }
                    180 -> { sx = srcW - 1 - rx; sy = srcH - 1 - ry }
                    270 -> { sx = srcW - 1 - ry; sy = rx }
                    else -> { sx = rx; sy = ry }
                }
                val xx = sx.coerceIn(0, srcW - 1)
                val yy = sy.coerceIn(0, srcH - 1)
                out[oy * outW + ox] = buf.get(base + yy * rowStride + xx * pixelStride)
                ox++
            }
            oy++
        }
        return Gray(out, outW, outH)
    }
}
