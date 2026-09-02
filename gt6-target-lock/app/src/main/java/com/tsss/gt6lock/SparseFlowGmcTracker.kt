package com.tsss.gt6lock

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sparse flow + robust global motion compensation.
 * Point matches use forward/backward verification, then median/MAD outlier rejection.
 */
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
    private data class Move(val from: P, val dx: Float, val dy: Float)

    private var previous: FastLumaExtractor.GrayFrame? = null
    private var targetPoints: List<P> = emptyList()
    private var backgroundPoints: List<P> = emptyList()
    private var frameCount = 0

    @Synchronized
    fun clear() {
        previous = null
        targetPoints = emptyList()
        backgroundPoints = emptyList()
        frameCount = 0
    }

    @Synchronized
    fun seed(frame: FastLumaExtractor.GrayFrame, target: Detection) {
        previous = frame
        targetPoints = chooseTargetPoints(frame, target, 42)
        backgroundPoints = chooseBackgroundPoints(frame, target, 56)
        frameCount = 0
    }

    @Synchronized
    fun track(frame: FastLumaExtractor.GrayFrame, target: Detection): FlowResult? {
        val prev = previous ?: run {
            seed(frame, target)
            return null
        }

        if (prev.width != frame.width || prev.height != frame.height) {
            seed(frame, target)
            return null
        }

        if (targetPoints.size < 6 || backgroundPoints.size < 8) {
            seed(frame, target)
            return null
        }

        val rawTarget = trackPointsFb(prev, frame, targetPoints, 7)
        val rawBackground = trackPointsFb(prev, frame, backgroundPoints, 8)

        val targetMoves = robustMoves(rawTarget)
        val bgMoves = robustMoves(rawBackground)

        if (targetMoves.size < 5 || bgMoves.size < 7) {
            seed(frame, target)
            return null
        }

        val gdx = median(bgMoves.map { it.dx })
        val gdy = median(bgMoves.map { it.dy })
        val tdx = median(targetMoves.map { it.dx })
        val tdy = median(targetMoves.map { it.dy })

        val gCons = consistency(bgMoves, gdx, gdy)
        val tCons = consistency(targetMoves, tdx, tdy)

        previous = frame
        targetPoints = targetMoves.map {
            P(
                (it.from.x + it.dx).toInt().coerceIn(4, frame.width - 5),
                (it.from.y + it.dy).toInt().coerceIn(4, frame.height - 5)
            )
        }
        backgroundPoints = bgMoves.map {
            P(
                (it.from.x + it.dx).toInt().coerceIn(4, frame.width - 5),
                (it.from.y + it.dy).toInt().coerceIn(4, frame.height - 5)
            )
        }

        frameCount++
        if (
            frameCount % 8 == 0 ||
            targetPoints.size < 16 ||
            backgroundPoints.size < 22
        ) {
            targetPoints = chooseTargetPoints(frame, target, 42)
            backgroundPoints = chooseBackgroundPoints(frame, target, 56)
        }

        // Target flow includes camera motion. GMC is used to reject outliers and as fallback.
        val relativeDx = tdx - gdx
        val relativeDy = tdy - gdy
        val targetTrust = (
            0.55f * tCons +
                0.25f * gCons +
                0.20f * (targetMoves.size / 24f).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)

        val blend = ((targetTrust - 0.22f) / 0.58f).coerceIn(0f, 1f)
        val dx = gdx + blend * relativeDx
        val dy = gdy + blend * relativeDy

        return FlowResult(
            dx / frame.width.toFloat(),
            dy / frame.height.toFloat(),
            targetTrust,
            gCons,
            targetMoves.size,
            bgMoves.size
        )
    }

    private fun chooseTargetPoints(
        frame: FastLumaExtractor.GrayFrame,
        target: Detection,
        maxCount: Int
    ): List<P> {
        val insetX = target.width * 0.08f
        val insetY = target.height * 0.08f
        val x1 = ((target.x1 + insetX) * frame.width).toInt().coerceIn(4, frame.width - 5)
        val y1 = ((target.y1 + insetY) * frame.height).toInt().coerceIn(4, frame.height - 5)
        val x2 = ((target.x2 - insetX) * frame.width).toInt().coerceIn(x1 + 1, frame.width - 5)
        val y2 = ((target.y2 - insetY) * frame.height).toInt().coerceIn(y1 + 1, frame.height - 5)
        return chooseGradientPoints(frame, x1, y1, x2, y2, maxCount)
    }

    private fun chooseBackgroundPoints(
        frame: FastLumaExtractor.GrayFrame,
        target: Detection,
        maxCount: Int
    ): List<P> {
        val marginX = (target.width * frame.width * 1.15f).toInt().coerceAtLeast(30)
        val marginY = (target.height * frame.height * 1.15f).toInt().coerceAtLeast(24)

        val tx1 = (target.x1 * frame.width).toInt()
        val ty1 = (target.y1 * frame.height).toInt()
        val tx2 = (target.x2 * frame.width).toInt()
        val ty2 = (target.y2 * frame.height).toInt()

        val rx1 = (tx1 - marginX).coerceIn(4, frame.width - 5)
        val ry1 = (ty1 - marginY).coerceIn(4, frame.height - 5)
        val rx2 = (tx2 + marginX).coerceIn(rx1 + 1, frame.width - 5)
        val ry2 = (ty2 + marginY).coerceIn(ry1 + 1, frame.height - 5)

        val candidates = chooseGradientPoints(frame, rx1, ry1, rx2, ry2, maxCount * 3)
        return candidates.filterNot {
            it.x in (tx1 - 8)..(tx2 + 8) &&
                it.y in (ty1 - 8)..(ty2 + 8)
        }.take(maxCount)
    }

    private fun chooseGradientPoints(
        frame: FastLumaExtractor.GrayFrame,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        maxCount: Int
    ): List<P> {
        data class C(val p: P, val score: Int)
        val out = ArrayList<C>()

        val dx = max(1, x2 - x1)
        val dy = max(1, y2 - y1)
        val step = max(3, min(dx / 9, dy / 9))

        var y = y1
        while (y <= y2) {
            var x = x1
            while (x <= x2) {
                val gx = abs(pixel(frame, x + 1, y) - pixel(frame, x - 1, y))
                val gy = abs(pixel(frame, x, y + 1) - pixel(frame, x, y - 1))
                val g45a = abs(pixel(frame, x + 1, y + 1) - pixel(frame, x - 1, y - 1))
                val g45b = abs(pixel(frame, x + 1, y - 1) - pixel(frame, x - 1, y + 1))
                val score = gx + gy + (g45a + g45b) / 2
                if (score >= 30) out += C(P(x, y), score)
                x += step
            }
            y += step
        }

        return out.sortedByDescending { it.score }.take(maxCount).map { it.p }
    }

    private fun trackPointsFb(
        prev: FastLumaExtractor.GrayFrame,
        cur: FastLumaExtractor.GrayFrame,
        points: List<P>,
        searchRadius: Int
    ): List<Move> {
        val moves = ArrayList<Move>(points.size)

        for (p in points) {
            if (p.x !in 5 until prev.width - 5 || p.y !in 5 until prev.height - 5) continue

            val forward = bestMatch(prev, cur, p.x, p.y, searchRadius) ?: continue
            val cx = p.x + forward.first
            val cy = p.y + forward.second
            if (cx !in 5 until cur.width - 5 || cy !in 5 until cur.height - 5) continue

            // Cheap forward/backward validation: return search is only ±2 around original.
            val back = bestMatchAround(cur, prev, cx, cy, p.x, p.y, 2) ?: continue
            val backErr = abs(back.first - p.x) + abs(back.second - p.y)
            if (backErr > 2) continue

            moves += Move(p, forward.first.toFloat(), forward.second.toFloat())
        }

        return moves
    }

    private fun bestMatch(
        src: FastLumaExtractor.GrayFrame,
        dst: FastLumaExtractor.GrayFrame,
        x: Int,
        y: Int,
        radius: Int
    ): Pair<Int, Int>? {
        var best = Int.MAX_VALUE
        var second = Int.MAX_VALUE
        var bestDx = 0
        var bestDy = 0

        for (dy in -radius..radius) {
            val cy = y + dy
            if (cy !in 4 until dst.height - 4) continue
            for (dx in -radius..radius) {
                val cx = x + dx
                if (cx !in 4 until dst.width - 4) continue
                val sad = patchSad(src, dst, x, y, cx, cy)
                if (sad < best) {
                    second = best
                    best = sad
                    bestDx = dx
                    bestDy = dy
                } else if (sad < second) {
                    second = sad
                }
            }
        }

        if (best >= 620) return null
        if (second != Int.MAX_VALUE && best.toFloat() / max(1, second) >= 0.95f) return null
        return bestDx to bestDy
    }

    private fun bestMatchAround(
        src: FastLumaExtractor.GrayFrame,
        dst: FastLumaExtractor.GrayFrame,
        sx: Int,
        sy: Int,
        expectedX: Int,
        expectedY: Int,
        radius: Int
    ): Pair<Int, Int>? {
        var best = Int.MAX_VALUE
        var bx = expectedX
        var by = expectedY

        for (y in expectedY - radius..expectedY + radius) {
            if (y !in 4 until dst.height - 4) continue
            for (x in expectedX - radius..expectedX + radius) {
                if (x !in 4 until dst.width - 4) continue
                val sad = patchSad(src, dst, sx, sy, x, y)
                if (sad < best) {
                    best = sad
                    bx = x
                    by = y
                }
            }
        }

        return if (best < 680) bx to by else null
    }

    private fun patchSad(
        a: FastLumaExtractor.GrayFrame,
        b: FastLumaExtractor.GrayFrame,
        ax: Int,
        ay: Int,
        bx: Int,
        by: Int
    ): Int {
        var sad = 0
        for (py in -2..2 step 2) {
            for (px in -2..2 step 2) {
                val av = pixel(a, ax + px, ay + py)
                val bv = pixel(b, bx + px, by + py)
                sad += abs(av - bv)
            }
        }
        return sad
    }

    private fun robustMoves(moves: List<Move>): List<Move> {
        if (moves.size < 5) return moves

        val mdx = median(moves.map { it.dx })
        val mdy = median(moves.map { it.dy })
        val deviations = moves.map {
            abs(it.dx - mdx) + abs(it.dy - mdy)
        }
        val mad = median(deviations).coerceAtLeast(0.75f)
        val gate = (2.8f * mad + 1.0f).coerceIn(2.0f, 7.0f)

        return moves.filter {
            abs(it.dx - mdx) + abs(it.dy - mdy) <= gate
        }
    }

    private fun consistency(
        moves: List<Move>,
        mdx: Float,
        mdy: Float
    ): Float {
        if (moves.isEmpty()) return 0f
        val dev = moves.map {
            abs(it.dx - mdx) + abs(it.dy - mdy)
        }.sorted()
        val med = dev[dev.size / 2]
        return (1f - med / 4.5f).coerceIn(0f, 1f)
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
