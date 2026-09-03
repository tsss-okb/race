package com.tsss.gt6lock

/**
 * NDK implementation of the v3.x sparse flow + GMC tracker.
 * Same public result shape as the Kotlin version, but the hot pixel loops
 * execute in C++ and the search radius adapts to recent inter-frame motion.
 */
class NativeSparseFlowGmcTracker {
    data class FlowResult(
        val dxNorm: Float,
        val dyNorm: Float,
        val targetConsistency: Float,
        val globalConsistency: Float,
        val targetPoints: Int,
        val backgroundPoints: Int,
        val globalDxNorm: Float,
        val globalDyNorm: Float,
        val blurRisk: Float,
        val pyramidUsed: Boolean
    )

    init { System.loadLibrary("gt6lock") }

    external fun nativeClear()
    external fun nativeSeed(
        y: ByteArray, w: Int, h: Int,
        x1: Float, y1: Float, x2: Float, y2: Float
    )
    external fun nativeTrack(
        y: ByteArray, w: Int, h: Int,
        x1: Float, y1: Float, x2: Float, y2: Float
    ): FloatArray

    @Synchronized
    fun clear() = nativeClear()

    @Synchronized
    fun seed(frame: FastLumaExtractor.GrayFrame, target: Detection) {
        nativeSeed(
            frame.pixels, frame.width, frame.height,
            target.x1, target.y1, target.x2, target.y2
        )
    }

    @Synchronized
    fun track(frame: FastLumaExtractor.GrayFrame, target: Detection): FlowResult? {
        val r = nativeTrack(
            frame.pixels, frame.width, frame.height,
            target.x1, target.y1, target.x2, target.y2
        )
        if (r.size < 11 || r[0] < 0.5f) return null
        return FlowResult(
            r[1], r[2], r[3], r[4],
            r[5].toInt(), r[6].toInt(),
            r[7], r[8], r[9].coerceIn(0f, 1f),
            r[10] >= 0.5f
        )
    }
}
