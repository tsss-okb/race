package com.tsss.gt6lock

import androidx.camera.core.ImageProxy
import kotlin.math.min

/**
 * Low-allocation 60 Hz luma path borrowed from PlaneAimPhone.
 * Only the Y plane is touched during normal tracking.
 */
class FastLumaExtractor(
    private val maxOutputWidth: Int = 320,
    ringSize: Int = 3
) {
    data class GrayFrame(
        val width: Int,
        val height: Int,
        val pixels: ByteArray,
        val timestampNs: Long
    )

    private val buffers = arrayOfNulls<ByteArray>(ringSize.coerceAtLeast(3))
    private var ringIndex = 0

    @Synchronized
    fun extract(image: ImageProxy): GrayFrame? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val yPlane = planes[0]
        val srcW = image.width
        val srcH = image.height
        if (srcW < 2 || srcH < 2) return null

        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val rotW = if (rotation == 90 || rotation == 270) srcH else srcW
        val rotH = if (rotation == 90 || rotation == 270) srcW else srcH
        val scale = min(1f, maxOutputWidth.toFloat() / rotW.toFloat())
        val outW = (rotW * scale).toInt().coerceAtLeast(2)
        val outH = (rotH * scale).toInt().coerceAtLeast(2)
        val needed = outW * outH

        var out = buffers[ringIndex]
        if (out == null || out.size != needed) {
            out = ByteArray(needed)
            buffers[ringIndex] = out
        }
        ringIndex = (ringIndex + 1) % buffers.size

        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val buf = yPlane.buffer.duplicate()
        val base = buf.position()

        if (rotation == 0 && outW == srcW && outH == srcH && pixelStride == 1) {
            for (y in 0 until srcH) {
                buf.position(base + y * rowStride)
                buf.get(out, y * outW, outW)
            }
            return GrayFrame(outW, outH, out, image.imageInfo.timestamp)
        }

        for (oy in 0 until outH) {
            val ry = (oy.toLong() * rotH / outH).toInt().coerceIn(0, rotH - 1)
            val rowOut = oy * outW
            for (ox in 0 until outW) {
                val rx = (ox.toLong() * rotW / outW).toInt().coerceIn(0, rotW - 1)
                val sx: Int
                val sy: Int
                when (rotation) {
                    90 -> { sx = ry; sy = srcH - 1 - rx }
                    180 -> { sx = srcW - 1 - rx; sy = srcH - 1 - ry }
                    270 -> { sx = srcW - 1 - ry; sy = rx }
                    else -> { sx = rx; sy = ry }
                }
                val index = base +
                    sy.coerceIn(0, srcH - 1) * rowStride +
                    sx.coerceIn(0, srcW - 1) * pixelStride
                out[rowOut + ox] = buf.get(index)
            }
        }
        return GrayFrame(outW, outH, out, image.imageInfo.timestamp)
    }
}
