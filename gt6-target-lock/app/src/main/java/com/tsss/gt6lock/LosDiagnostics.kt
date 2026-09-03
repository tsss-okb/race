package com.tsss.gt6lock

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Read-only line-of-sight diagnostics.
 *
 * Screen convention:
 *  X > 0 -> target right of optical axis
 *  Y > 0 -> target above optical axis
 *
 * RAW rate is measured from image motion.
 * COMP rate removes platform yaw/pitch rate in the same screen-axis convention.
 * No flight-control commands are produced here.
 */
class LosDiagnostics(
    private val deadZoneXDeg: Float = 0.35f,
    private val deadZoneYDeg: Float = 0.35f
) {
    data class Snapshot(
        val losXDeg: Float = 0f,
        val losYDeg: Float = 0f,
        val rawRateXDegS: Float = 0f,
        val rawRateYDegS: Float = 0f,
        val compRateXDegS: Float = 0f,
        val compRateYDegS: Float = 0f,
        val motionDegS: Float = 0f,
        val jitterDegS: Float = 0f,
        val insideDeadZone: Boolean = true,
        val deadZoneNormX: Float = 0f,
        val deadZoneNormY: Float = 0f
    )

    private var lastTsNs = 0L
    private var lastXDeg = 0f
    private var lastYDeg = 0f
    private var motionX = 0f
    private var motionY = 0f
    private var jitterEma = 0f
    private var snapshot = Snapshot()

    fun clear() {
        lastTsNs = 0L
        lastXDeg = 0f
        lastYDeg = 0f
        motionX = 0f
        motionY = 0f
        jitterEma = 0f
        snapshot = Snapshot()
    }

    fun current(): Snapshot = snapshot

    fun update(
        targetCx: Float,
        targetCy: Float,
        timestampNs: Long,
        horizontalFovDeg: Float,
        verticalFovDeg: Float,
        ownYawRateDegS: Float,
        ownPitchRateDegS: Float
    ): Snapshot {
        val hfov = horizontalFovDeg.coerceIn(15f, 150f)
        val vfov = verticalFovDeg.coerceIn(10f, 120f)

        val xDeg = normalizedToAngleDeg(targetCx - 0.5f, hfov)
        val yDeg = normalizedToAngleDeg(0.5f - targetCy, vfov)

        var rawX = 0f
        var rawY = 0f
        if (lastTsNs != 0L && timestampNs > lastTsNs) {
            val dt = ((timestampNs - lastTsNs) / 1e9)
                .toFloat()
                .coerceIn(1f / 240f, 0.12f)
            rawX = wrapDeltaDeg(xDeg - lastXDeg) / dt
            rawY = wrapDeltaDeg(yDeg - lastYDeg) / dt
            rawX = rawX.coerceIn(-360f, 360f)
            rawY = rawY.coerceIn(-360f, 360f)
        }

        // A positive platform yaw/pitch moves a stationary target in the
        // opposite image direction, so adding own rate removes that component.
        val compX = (rawX + ownYawRateDegS).coerceIn(-360f, 360f)
        val compY = (rawY + ownPitchRateDegS).coerceIn(-360f, 360f)

        val compMag = hypot(compX.toDouble(), compY.toDouble()).toFloat()
        val motionAlpha = (0.08f + 0.22f * (compMag / 30f).coerceIn(0f, 1f))
        motionX += motionAlpha * (compX - motionX)
        motionY += motionAlpha * (compY - motionY)

        val residualX = compX - motionX
        val residualY = compY - motionY
        val residual = hypot(residualX.toDouble(), residualY.toDouble()).toFloat()
        jitterEma += 0.14f * (residual - jitterEma)

        val motion = hypot(motionX.toDouble(), motionY.toDouble()).toFloat()
        val inside =
            abs(xDeg) <= deadZoneXDeg && abs(yDeg) <= deadZoneYDeg

        snapshot = Snapshot(
            losXDeg = xDeg,
            losYDeg = yDeg,
            rawRateXDegS = rawX,
            rawRateYDegS = rawY,
            compRateXDegS = compX,
            compRateYDegS = compY,
            motionDegS = motion,
            jitterDegS = jitterEma.coerceAtLeast(0f),
            insideDeadZone = inside,
            deadZoneNormX = angleToNormalized(deadZoneXDeg, hfov),
            deadZoneNormY = angleToNormalized(deadZoneYDeg, vfov)
        )

        lastTsNs = timestampNs
        lastXDeg = xDeg
        lastYDeg = yDeg
        return snapshot
    }

    private fun normalizedToAngleDeg(offset: Float, fovDeg: Float): Float {
        val half = Math.toRadians((fovDeg * 0.5).toDouble())
        val angle = atan(2.0 * offset * tan(half))
        return Math.toDegrees(angle).toFloat()
    }

    private fun angleToNormalized(angleDeg: Float, fovDeg: Float): Float {
        val a = Math.toRadians(angleDeg.toDouble())
        val half = Math.toRadians((fovDeg * 0.5).toDouble())
        return (tan(a) / (2.0 * tan(half))).toFloat().coerceIn(0f, 0.25f)
    }

    private fun wrapDeltaDeg(v0: Float): Float {
        var v = v0
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }
}
