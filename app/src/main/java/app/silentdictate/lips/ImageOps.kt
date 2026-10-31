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
     * Cut the aligned mouth crop (see [FaceAlign]) out of a packed Y plane.
     *
     * [pose] maps upright-frame pixels (size [upW] x [upH]) onto the reference
     * face. Output is [outW] x [outH], bilinear, normalised as
     * (y / 255 - mean) / std, row-major.
     */
    fun sampleAligned(
        y: ByteArray,
        w: Int,
        h: Int,
        rotation: Int,
        pose: FaceAlign.Pose,
        upW: Int,
        upH: Int,
        out: FloatArray,
        outW: Int,
        outH: Int,
        mean: Float,
        std: Float,
    ) {
        val xy = DoubleArray(2)
        var idx = 0
        for (j in 0 until outH) {
            for (i in 0 until outW) {
                FaceAlign.cropToImage(pose, i, j, outW, outH, xy)
                val u = (xy[0] / upW).toFloat().coerceIn(0f, 1f)
                val v = (xy[1] / upH).toFloat().coerceIn(0f, 1f)
                val (su, sv) = uprightToSource(u, v, rotation)
                val fx = (su * w - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val fy = (sv * h - 0.5f).coerceIn(0f, (h - 1).toFloat())
                val x0 = fx.toInt()
                val y0 = fy.toInt()
                val x1 = if (x0 + 1 < w) x0 + 1 else x0
                val y1 = if (y0 + 1 < h) y0 + 1 else y0
                val ax = fx - x0
                val ay = fy - y0
                val p00 = y[y0 * w + x0].toInt() and 0xFF
                val p10 = y[y0 * w + x1].toInt() and 0xFF
                val p01 = y[y1 * w + x0].toInt() and 0xFF
                val p11 = y[y1 * w + x1].toInt() and 0xFF
                val top = p00 + (p10 - p00) * ax
                val bot = p01 + (p11 - p01) * ax
                val yy = top + (bot - top) * ay
                out[idx++] = (yy / 255f - mean) / std
            }
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
        // NV21 needs even dimensions (chroma is subsampled 2x2)
        val outH = ((targetW * upHf.toFloat() / upWf).toInt().coerceAtLeast(64)) and 1.inv()
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
