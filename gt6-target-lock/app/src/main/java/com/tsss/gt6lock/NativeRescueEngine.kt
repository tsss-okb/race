package com.tsss.gt6lock

/**
 * Thin Kotlin wrapper around the v5.2 native rescue engine.
 * All shock / blur / pyramid GMC / wide candidate confirmation state lives in C++.
 */
class NativeRescueEngine {
    data class Result(
        val flowValid: Boolean,
        val dxNorm: Float,
        val dyNorm: Float,
        val targetConsistency: Float,
        val globalConsistency: Float,
        val globalDxNorm: Float,
        val globalDyNorm: Float,
        val blurRisk: Float,
        val blurHigh: Boolean,
        val pyramidUsed: Boolean,
        val shockActive: Boolean,
        val wideActive: Boolean,
        val confirmHits: Int,
        val accept: Boolean,
        val candidateCx: Float,
        val candidateCy: Float,
        val candidateScore: Float,
        val candidateUnique: Float,
        val targetPoints: Int,
        val backgroundPoints: Int
    )

    init { System.loadLibrary("gt6lock") }

    external fun nativeClear()

    external fun nativeSeedFlow(
        y: ByteArray, w: Int, h: Int,
        x1: Float, y1: Float, x2: Float, y2: Float
    )

    external fun nativeSetAnchor(
        samples: FloatArray,
        halfW: Int, halfH: Int, step: Int,
        sum: Double, sumSq: Double,
        widthNorm: Float, heightNorm: Float
    )

    external fun nativeSetCurrent(
        samples: FloatArray,
        halfW: Int, halfH: Int, step: Int,
        sum: Double, sumSq: Double,
        widthNorm: Float, heightNorm: Float
    )

    external fun nativeSetContext(
        samples: FloatArray,
        halfW: Int, halfH: Int, step: Int,
        sum: Double, sumSq: Double,
        widthNorm: Float, heightNorm: Float
    )

    external fun nativeProcess(
        y: ByteArray, w: Int, h: Int,
        x1: Float, y1: Float, x2: Float, y2: Float,
        yawRateDegS: Float,
        pitchRateDegS: Float,
        dtSec: Float,
        losJitterDegS: Float,
        visualScore: Float
    ): FloatArray

    fun clear() = nativeClear()

    fun seed(frame: FastLumaExtractor.GrayFrame, target: Detection) =
        nativeSeedFlow(
            frame.pixels, frame.width, frame.height,
            target.x1, target.y1, target.x2, target.y2
        )

    fun process(
        frame: FastLumaExtractor.GrayFrame,
        target: Detection,
        yawRateDegS: Float,
        pitchRateDegS: Float,
        dtSec: Float,
        losJitterDegS: Float,
        visualScore: Float
    ): Result {
        val r = nativeProcess(
            frame.pixels, frame.width, frame.height,
            target.x1, target.y1, target.x2, target.y2,
            yawRateDegS, pitchRateDegS,
            dtSec, losJitterDegS, visualScore
        )
        return Result(
            flowValid = r.size > 0 && r[0] >= 0.5f,
            dxNorm = r.getOrElse(1) { 0f },
            dyNorm = r.getOrElse(2) { 0f },
            targetConsistency = r.getOrElse(3) { 0f },
            globalConsistency = r.getOrElse(4) { 0f },
            globalDxNorm = r.getOrElse(5) { 0f },
            globalDyNorm = r.getOrElse(6) { 0f },
            blurRisk = r.getOrElse(7) { 0f },
            blurHigh = r.getOrElse(8) { 0f } >= 0.5f,
            pyramidUsed = r.getOrElse(9) { 0f } >= 0.5f,
            shockActive = r.getOrElse(10) { 0f } >= 0.5f,
            wideActive = r.getOrElse(11) { 0f } >= 0.5f,
            confirmHits = r.getOrElse(12) { 0f }.toInt(),
            accept = r.getOrElse(13) { 0f } >= 0.5f,
            candidateCx = r.getOrElse(14) { 0f },
            candidateCy = r.getOrElse(15) { 0f },
            candidateScore = r.getOrElse(16) { 0f },
            candidateUnique = r.getOrElse(17) { 0f },
            targetPoints = r.getOrElse(18) { 0f }.toInt(),
            backgroundPoints = r.getOrElse(19) { 0f }.toInt()
        )
    }
}
