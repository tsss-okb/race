package com.tsss.gt6lock

/**
 * Native coarse-to-fine NCC matcher.
 * Templates are copied to C++ only when seeded/refreshed; per-frame match sends
 * only the luma frame plus a few scalar search parameters.
 */
class NativeNccMatcher {
    init { System.loadLibrary("gt6lock") }

    external fun nativeClear()

    external fun nativeSetAnchor(
        samples: FloatArray,
        halfW: Int,
        halfH: Int,
        step: Int,
        sum: Double,
        sumSq: Double,
        widthNorm: Float,
        heightNorm: Float,
        copyToCurrent: Boolean
    )

    external fun nativeSetCurrent(
        samples: FloatArray,
        halfW: Int,
        halfH: Int,
        step: Int,
        sum: Double,
        sumSq: Double,
        widthNorm: Float,
        heightNorm: Float
    )

    external fun nativeClearContext()

    external fun nativeSetContext(
        samples: FloatArray,
        halfW: Int,
        halfH: Int,
        step: Int,
        sum: Double,
        sumSq: Double,
        widthNorm: Float,
        heightNorm: Float
    )

    external fun nativeMatchContext(
        y: ByteArray,
        w: Int,
        h: Int,
        predictedX: Int,
        predictedY: Int,
        radiusX: Int,
        radiusY: Int,
        coarseStep: Int,
        wideScales: Boolean
    ): FloatArray

    external fun nativeMatch(
        y: ByteArray,
        w: Int,
        h: Int,
        predictedX: Int,
        predictedY: Int,
        radiusX: Int,
        radiusY: Int,
        coarseStep: Int,
        wideScales: Boolean
    ): FloatArray

    fun clear() {
        nativeClear()
        nativeClearContext()
    }

    fun setAnchor(
        samples: FloatArray,
        halfW: Int,
        halfH: Int,
        step: Int,
        sum: Double,
        sumSq: Double,
        widthNorm: Float,
        heightNorm: Float,
        copyToCurrent: Boolean
    ) = nativeSetAnchor(
        samples, halfW, halfH, step, sum, sumSq,
        widthNorm, heightNorm, copyToCurrent
    )

    fun setContext(
        samples: FloatArray,
        halfW: Int,
        halfH: Int,
        step: Int,
        sum: Double,
        sumSq: Double,
        widthNorm: Float,
        heightNorm: Float
    ) = nativeSetContext(
        samples, halfW, halfH, step, sum, sumSq,
        widthNorm, heightNorm
    )

    fun setCurrent(
        samples: FloatArray,
        halfW: Int,
        halfH: Int,
        step: Int,
        sum: Double,
        sumSq: Double,
        widthNorm: Float,
        heightNorm: Float
    ) = nativeSetCurrent(
        samples, halfW, halfH, step, sum, sumSq,
        widthNorm, heightNorm
    )
}
