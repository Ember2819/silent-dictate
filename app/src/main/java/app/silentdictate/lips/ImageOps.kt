package app.silentdictate.lips

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Pixel math for the lip pipeline.
 *
 * Camera frames arrive as YUV_420_888 in sensor orientation. Everywhere else in
 * the app we work in "upright space": the sensor image rotated (by the
 * frame's rotationDegrees) so faces are upright. Horizontal mirroring is
 * intentionally NOT applied, so what the lip model sees matches a normal video
 * stream, which is what public lip-reading models are trained on.
 */

/** Rectangle as fractions (0..1) of a frame: left, top, right, bottom. */
data class RectPct(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun inset(fx: Float, fy: Float): RectPct {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        return RectPct(
            (cx - width / 2f * fx).coerceIn(0f, 1f),
            (cy - height / 2f * fy).coerceIn(0f, 1f),
            (cx + width / 2f * fx).coerceIn(0f, 1f),
            (cy + height / 2f * fy).coerceIn(0f, 1f),
        )
    }
}

object ImageOps {

    /** Dimensions in upright space for a sensor frame w x h rotated [rotation] degrees. */
    fun uprightSize(w: Int, h: Int, rotation: Int): Pair<Int, Int> =
        if (rotation == 90 || rotation == 270) h to w else w to h

    /**
     * Map a position (u, v) in [0..1] of the upright frame to (sx, sy)
     * fractions of the raw sensor frame.
     */
    fun uprightToSource(u: Float, v: Float, rotation: Int): Pair<Float, Float> =
        when (rotation) {
            90 -> v to (1f - u)
            180 -> (1f - u) to (1f - v)
            270 -> (1f - v) to u
            else -> u to v
        }

    private fun clampIdx(v: Int, max: Int): Int =
        if (v < 0) 0 else if (v >= max) max - 1 else v

    /**
     * Sample a mouth patch (fractions of the upright frame in [rect]) out of the
     * Y plane, rotated upright and resized to outW x outH, normalized as
     * (y / 255 - mean) / std. Output layout is c,h,w with a single channel.
     */
    fun sampleMouth(
        y: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        w: Int,
        h: Int,
        rotation: Int,
        rect: RectPct,
        out: FloatArray,
        outW: Int,
        outH: Int,
        mean: Float,
        std: Float,
    ) {
        var idx = 0
        var j = 0
        while (j < outH) {
            val vp = (rect.top + ((j + 0.5f) / outH) * rect.height).coerceIn(0f, 1f)
            var i = 0
            while (i < outW) {
                val up = (rect.left + ((i + 0.5f) / outW) * rect.width).coerceIn(0f, 1f)
                val (su, sv) = uprightToSource(up, vp, rotation)
                val sx = clampIdx((su * w).roundToInt(), w)
                val sy = clampIdx((sv * h).roundToInt(), h)
                val yi = sy * rowStride + sx * pixelStride
                val yy = if (yi < y.size) (y[yi].toInt() and 0xFF) else 128
                out[idx++] = ((yy / 255f) - mean) / std
                i++
            }
            j++
        }
    }

    data class Nv21Frame(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * Build a downscaled upright NV21 buffer for face detection. Output is
     * [targetW] px wide, aspect preserved.
     */
    fun detectNv21(
        yPlane: ByteArray, rowStride: Int, pixelStride: Int,
        uPlane: ByteArray, uRowStride: Int, uPixelStride: Int,
        vPlane: ByteArray, vRowStride: Int, vPixelStride: Int,
        w: Int, h: Int, rotation: Int, targetW: Int,
    ): Nv21Frame {
        val (upWf, upHf) = uprightSize(w, h, rotation)
        val outW = targetW
        val outH = (targetW * upHf.toFloat() / upWf).toInt().coerceAtLeast(64)
        val outY = ByteArray(outW * outH)
        val outC = ByteArray((outW / 2) * (outH / 2) * 2) // pairs: V,U per 2x2 block

        var idx = 0
        var j = 0
        while (j < outH) {
            val vp = ((j + 0.5f) / outH).coerceIn(0f, 1f)
            var i = 0
            while (i < outW) {
                val (su, sv) = uprightToSource(((i + 0.5f) / outW).coerceIn(0f, 1f), vp, rotation)
                val sx = clampIdx((su * w).roundToInt(), w)
                val sy = clampIdx((sv * h).roundToInt(), h)
                val yi = sy * rowStride + sx * pixelStride
                outY[idx++] = if (yi < yPlane.size) yPlane[yi] else 128.toByte()
                i++
            }
            j++
        }

        val cw = w / 2
        val ch = h / 2
        val ocW = outW / 2
        val ocH = outH / 2
        var cIdx = 0
        j = 0
        while (j < ocH) {
            val vp = ((2 * j + 1).toFloat() / (2 * ocH)).coerceIn(0f, 1f)
            var i = 0
            while (i < ocW) {
                val (su, sv) =
                    uprightToSource(((2 * i + 1).toFloat() / (2 * ocW)).coerceIn(0f, 1f), vp, rotation)
                val cx = clampIdx((su * cw).roundToInt(), cw)
                val cy = clampIdx((sv * ch).roundToInt(), ch)
                val u = if (cy * uRowStride + cx * uPixelStride < uPlane.size) uPlane[cy * uRowStride + cx * uPixelStride] else 128.toByte()
                val v = if (cy * vRowStride + cx * vPixelStride < vPlane.size) vPlane[cy * vRowStride + cx * vPixelStride] else 128.toByte()
                outC[cIdx++] = v // NV21 is V,U interleaved
                outC[cIdx++] = u
                i++
            }
            j++
        }

        val bytes = ByteArray(outY.size + outC.size)
        outY.copyInto(bytes)
        outC.copyInto(bytes, outY.size)
        return Nv21Frame(bytes, outW, outH)
    }

    /**
     * Copy a single image plane into a (reusable) packed byte array of
     * [width] x [height] samples.
     */
    fun copyPlane(
        src: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        reuse: ByteArray?,
    ): ByteArray {
        val dst = reuse?.takeIf { it.size >= width * height } ?: ByteArray(width * height)
        var outIdx = 0
        var row = 0
        while (row < height) {
            val rowStart = row * rowStride
            if (pixelStride == 1 && rowStride >= width) {
                src.position(rowStart)
                src.get(dst, outIdx, width)
                outIdx += width
            } else {
                var x = 0
                while (x < width) {
                    val pos = rowStart + x * pixelStride
                    dst[outIdx] = if (pos < src.capacity()) src.get(pos) else 0
                    x++
                    outIdx++
                }
            }
            row++
        }
        src.rewind()
        return dst
    }
}
