package com.tsss.gt6lock

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SparseFlowGmcTracker {
    data class FlowResult(
        val dxNorm: Float,
        val dyNorm: Float,
        val targetConsistency: Float,
        val globalConsistency: Float,
        val targetPoints: Int,
        val backgroundPoints: Int
    )

    private data class P(val x: Int, val y: Int)
    private var previous: FastLumaExtractor.GrayFrame? = null
    private var targetPoints: List<P> = emptyList()
    private var backgroundPoints: List<P> = emptyList()
    private var frameCount = 0

    @Synchronized fun clear() {
        previous = null
        targetPoints = emptyList()
        backgroundPoints = emptyList()
        frameCount = 0
    }

    @Synchronized fun seed(frame: FastLumaExtractor.GrayFrame, target: Detection) {
        previous = frame
        targetPoints = chooseTargetPoints(frame, target, 36)
        backgroundPoints = chooseBackgroundPoints(frame, target, 48)
        frameCount = 0
    }

    @Synchronized
    fun track(frame: FastLumaExtractor.GrayFrame, target: Detection): FlowResult? {
        val prev = previous ?: run { seed(frame, target); return null }
        if (prev.width != frame.width || prev.height != frame.height) {
            seed(frame, target); return null
        }
        if (targetPoints.size < 5 || backgroundPoints.size < 6) {
            seed(frame, target); return null
        }

        val targetMoves = trackPoints(prev, frame, targetPoints, 7)
        val bgMoves = trackPoints(prev, frame, backgroundPoints, 8)
        if (targetMoves.size < 4 || bgMoves.size < 5) {
            seed(frame, target); return null
        }

        val gdx = median(bgMoves.map { it.first })
        val gdy = median(bgMoves.map { it.second })
        val tdx = median(targetMoves.map { it.first })
        val tdy = median(targetMoves.map { it.second })
        val gCons = consistency(bgMoves, gdx, gdy)
        val tCons = consistency(targetMoves, tdx, tdy)

        previous = frame
        targetPoints = targetPoints.mapIndexedNotNull { i, p ->
            val mv = targetMoves.getOrNull(i) ?: return@mapIndexedNotNull null
            P((p.x + mv.first).toInt().coerceIn(3, frame.width - 4),
              (p.y + mv.second).toInt().coerceIn(3, frame.height - 4))
        }
        backgroundPoints = backgroundPoints.mapIndexedNotNull { i, p ->
            val mv = bgMoves.getOrNull(i) ?: return@mapIndexedNotNull null
            P((p.x + mv.first).toInt().coerceIn(3, frame.width - 4),
              (p.y + mv.second).toInt().coerceIn(3, frame.height - 4))
        }

        frameCount++
        if (frameCount % 10 == 0 || targetPoints.size < 12 || backgroundPoints.size < 16) {
            targetPoints = chooseTargetPoints(frame, target, 36)
            backgroundPoints = chooseBackgroundPoints(frame, target, 48)
        }

        // If target points are weak, fall back toward global camera flow rather than jumping.
        val blend = ((tCons - 0.25f) / 0.55f).coerceIn(0f, 1f)
        val dx = gdx + blend * (tdx - gdx)
        val dy = gdy + blend * (tdy - gdy)

        return FlowResult(
            dx / frame.width.toFloat(),
            dy / frame.height.toFloat(),
            tCons, gCons, targetMoves.size, bgMoves.size
        )
    }

    private fun chooseTargetPoints(frame: FastLumaExtractor.GrayFrame, target: Detection, maxCount: Int): List<P> {
        val x1 = (target.x1 * frame.width).toInt().coerceIn(4, frame.width - 5)
        val y1 = (target.y1 * frame.height).toInt().coerceIn(4, frame.height - 5)
        val x2 = (target.x2 * frame.width).toInt().coerceIn(x1 + 1, frame.width - 5)
        val y2 = (target.y2 * frame.height).toInt().coerceIn(y1 + 1, frame.height - 5)
        return chooseGradientPoints(frame, x1, y1, x2, y2, maxCount)
    }

    private fun chooseBackgroundPoints(frame: FastLumaExtractor.GrayFrame, target: Detection, maxCount: Int): List<P> {
        val marginX = (target.width * frame.width * 0.9f).toInt().coerceAtLeast(22)
        val marginY = (target.height * frame.height * 0.9f).toInt().coerceAtLeast(18)
        val tx1 = (target.x1 * frame.width).toInt()
        val ty1 = (target.y1 * frame.height).toInt()
        val tx2 = (target.x2 * frame.width).toInt()
        val ty2 = (target.y2 * frame.height).toInt()
        val rx1 = (tx1 - marginX).coerceIn(4, frame.width - 5)
        val ry1 = (ty1 - marginY).coerceIn(4, frame.height - 5)
        val rx2 = (tx2 + marginX).coerceIn(rx1 + 1, frame.width - 5)
        val ry2 = (ty2 + marginY).coerceIn(ry1 + 1, frame.height - 5)
        val candidates = chooseGradientPoints(frame, rx1, ry1, rx2, ry2, maxCount * 2)
        return candidates.filterNot {
            it.x in (tx1 - 5)..(tx2 + 5) && it.y in (ty1 - 5)..(ty2 + 5)
        }.take(maxCount)
    }

    private fun chooseGradientPoints(
        frame: FastLumaExtractor.GrayFrame,
        x1: Int, y1: Int, x2: Int, y2: Int, maxCount: Int
    ): List<P> {
        data class C(val p: P, val score: Int)
        val out = ArrayList<C>()
        val step = max(3, min((x2 - x1) / 8, (y2 - y1) / 8))
        var y = y1
        while (y <= y2) {
            var x = x1
            while (x <= x2) {
                val gx = abs(pixel(frame, x + 1, y) - pixel(frame, x - 1, y))
                val gy = abs(pixel(frame, x, y + 1) - pixel(frame, x, y - 1))
                val score = gx + gy
                if (score >= 22) out += C(P(x, y), score)
                x += step
            }
            y += step
        }
        return out.sortedByDescending { it.score }.take(maxCount).map { it.p }
    }

    private fun trackPoints(
        prev: FastLumaExtractor.GrayFrame,
        cur: FastLumaExtractor.GrayFrame,
        points: List<P>,
        searchRadius: Int
    ): List<Pair<Float, Float>> {
        val moves = ArrayList<Pair<Float, Float>>(points.size)
        for (p in points) {
            if (p.x !in 4 until prev.width - 4 || p.y !in 4 until prev.height - 4) continue
            var best = Int.MAX_VALUE
            var second = Int.MAX_VALUE
            var bestDx = 0
            var bestDy = 0
            for (dy in -searchRadius..searchRadius) {
                val cy = p.y + dy
                if (cy !in 3 until cur.height - 3) continue
                for (dx in -searchRadius..searchRadius) {
                    val cx = p.x + dx
                    if (cx !in 3 until cur.width - 3) continue
                    var sad = 0
                    for (py in -2..2 step 2) {
                        for (px in -2..2 step 2) {
                            sad += abs(pixel(prev, p.x + px, p.y + py) -
                                       pixel(cur, cx + px, cy + py))
                        }
                    }
                    if (sad < best) {
                        second = best; best = sad; bestDx = dx; bestDy = dy
                    } else if (sad < second) {
                        second = sad
                    }
                }
            }
            if (best < 540 && (second == Int.MAX_VALUE || best.toFloat() / max(1, second) < 0.94f)) {
                moves += bestDx.toFloat() to bestDy.toFloat()
            }
        }
        return moves
    }

    private fun consistency(moves: List<Pair<Float, Float>>, mdx: Float, mdy: Float): Float {
        if (moves.isEmpty()) return 0f
        val dev = moves.map { abs(it.first - mdx) + abs(it.second - mdy) }.sorted()
        val med = dev[dev.size / 2]
        return (1f - med / 5.5f).coerceIn(0f, 1f)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) * 0.5f
    }

    private fun pixel(frame: FastLumaExtractor.GrayFrame, x: Int, y: Int): Int =
        frame.pixels[y * frame.width + x].toInt() and 0xFF
}
