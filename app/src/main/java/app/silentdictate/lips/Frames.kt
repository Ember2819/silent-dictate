package app.silentdictate.lips

/** Frame-rate and text helpers for the lip pipeline. */
object Frames {
    /** Frame rate the model was trained at; accuracy falls off quickly below it. */
    const val MODEL_FPS = 25

    class Timed(val timestampNs: Long, val data: FloatArray)

    /**
     * Resamples to [MODEL_FPS] by nearest timestamp. A camera that delivers 30 fps
     * (or irregular frames under load) otherwise makes mouths move too slowly or
     * unevenly for the model.
     */
    fun resample(frames: List<Timed>): List<FloatArray> {
        if (frames.size < 2) return frames.map { it.data }
        val t0 = frames.first().timestampNs
        val span = frames.last().timestampNs - t0
        if (span <= 0L) return frames.map { it.data }
        val stepNs = 1_000_000_000L / MODEL_FPS
        val count = (span / stepNs).toInt() + 1
        val out = ArrayList<FloatArray>(count)
        var j = 0
        for (k in 0 until count) {
            val target = t0 + k * stepNs
            while (j + 1 < frames.size &&
                kotlin.math.abs(frames[j + 1].timestampNs - target) <= kotlin.math.abs(frames[j].timestampNs - target)
            ) j++
            out.add(frames[j].data)
        }
        return out
    }

    /** "HI EVERYBODY, I'M HERE" → "Hi everybody, I'm here". The model writes capitals only. */
    fun sentenceCase(raw: String): String {
        val t = raw.trim().lowercase()
        if (t.isEmpty()) return t
        val fixedI = t.replace(Regex("\\bi\\b"), "I").replace(Regex("\\bi'(m|ll|ve|d)\\b"), "I'$1")
        return fixedI.replaceFirstChar { it.uppercaseChar() }
    }
}

/**
 * Splits a long clip into pieces the model can read within a safe memory budget,
 * cutting at pauses so words aren't sliced in half.
 *
 * The model reads a whole piece at once and its memory grows with length
 * (~700 MB at 8 s), so no piece is longer than [maxFrames]. Within the window
 * [minFrames, maxFrames] of each piece, the cut goes where the mouth moves least
 * (averaged over ~0.4 s): usually the gap between words or sentences.
 */
object Chunker {
    const val MAX_FRAMES = 175 // 7 s at 25 fps
    const val MIN_FRAMES = 100 // 4 s

    fun split(
        frames: List<FloatArray>,
        maxFrames: Int = MAX_FRAMES,
        minFrames: Int = MIN_FRAMES,
    ): List<List<FloatArray>> {
        if (frames.size <= maxFrames) return listOf(frames)
        val motion = smoothedMotion(frames)
        val pieces = ArrayList<List<FloatArray>>()
        var start = 0
        while (frames.size - start > maxFrames) {
            var cut = start + maxFrames
            var best = Float.MAX_VALUE
            for (i in start + minFrames..start + maxFrames) {
                if (motion[i] < best) {
                    best = motion[i]
                    cut = i
                }
            }
            pieces.add(frames.subList(start, cut))
            start = cut
        }
        pieces.add(frames.subList(start, frames.size))
        return pieces
    }

    /** Mean absolute pixel change into each frame, averaged over a ±5-frame window. */
    private fun smoothedMotion(frames: List<FloatArray>): FloatArray {
        val n = frames.size
        val raw = FloatArray(n)
        for (i in 1 until n) {
            val a = frames[i - 1]
            val b = frames[i]
            var sum = 0f
            // every 4th pixel is plenty to measure movement
            var k = 0
            while (k < b.size) {
                sum += kotlin.math.abs(b[k] - a[k])
                k += 4
            }
            raw[i] = sum / (b.size / 4)
        }
        raw[0] = raw.getOrElse(1) { 0f }
        val out = FloatArray(n)
        val half = 5
        for (i in 0 until n) {
            var s = 0f
            var c = 0
            for (j in maxOf(0, i - half)..minOf(n - 1, i + half)) {
                s += raw[j]; c++
            }
            out[i] = s / c
        }
        return out
    }
}
