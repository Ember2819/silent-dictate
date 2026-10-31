package app.silentdictate.lips

/**
 * Mouth-crop geometry used by the Auto-AVSR family of lip-reading models.
 *
 * Those models were trained on crops produced like this: take four face points
 * (right eye, left eye, nose base, mouth centre), fit a similarity transform
 * (rotation + uniform scale + shift) that maps them onto the same four points of
 * a canonical 256x256 face, and cut an 88x88 window centred on the mouth.
 * Cropping "a box around the lips" instead gives a different scale and ignores
 * head tilt, which the model reads far worse.
 *
 * Reference points come from auto_avsr's `20words_mean_face.npy`
 * (eyes: mean of landmarks 36-41 / 42-47, nose: 31-35, mouth: 48-67).
 */
object FaceAlign {
    /** x,y pairs: image-left eye, image-right eye, nose base, mouth centre. */
    private val REF = doubleArrayOf(
        102.07394, 94.27230,
        156.36131, 93.57816,
        129.00373, 135.90343,
        129.31337, 157.82299,
    )

    /** Side of the crop window, in reference-face units (= pixels of the 256px face). */
    const val WINDOW = 88f

    /**
     * Similarity transform from image space to reference space, as the complex
     * numbers `ref = a * img + t`.
     */
    class Pose(val ar: Double, val ai: Double, val tr: Double, val ti: Double) {
        fun blendTo(o: Pose, k: Double) = Pose(
            ar + (o.ar - ar) * k, ai + (o.ai - ai) * k,
            tr + (o.tr - tr) * k, ti + (o.ti - ti) * k,
        )
    }

    /**
     * Least-squares similarity fit of [p] (8 numbers, same point order as [REF],
     * any eye order, in isotropic image pixels) onto the reference face.
     */
    fun estimate(p: FloatArray): Pose? {
        if (p.size < 8) return null
        // eyes are given in any order; the reference expects image-left first
        val swap = p[0] > p[2]
        val src = DoubleArray(8) { p[it].toDouble() }
        if (swap) {
            src[0] = p[2].toDouble(); src[1] = p[3].toDouble()
            src[2] = p[0].toDouble(); src[3] = p[1].toDouble()
        }
        var zr = 0.0; var zi = 0.0; var wr = 0.0; var wi = 0.0
        for (k in 0 until 4) {
            zr += src[2 * k]; zi += src[2 * k + 1]
            wr += REF[2 * k]; wi += REF[2 * k + 1]
        }
        zr /= 4; zi /= 4; wr /= 4; wi /= 4

        // a = sum(conj(z) * w) / sum(|z|^2) over centred points
        var numR = 0.0; var numI = 0.0; var den = 0.0
        for (k in 0 until 4) {
            val cr = src[2 * k] - zr
            val ci = src[2 * k + 1] - zi
            val dr = REF[2 * k] - wr
            val di = REF[2 * k + 1] - wi
            numR += cr * dr + ci * di
            numI += cr * di - ci * dr
            den += cr * cr + ci * ci
        }
        if (den < 1e-6) return null
        val ar = numR / den
        val ai = numI / den
        // t = mean(w) - a * mean(z)
        val tr = wr - (ar * zr - ai * zi)
        val ti = wi - (ar * zi + ai * zr)
        val scale = Math.hypot(ar, ai)
        if (scale < 1e-4 || scale > 20.0) return null // degenerate / absurd face size
        return Pose(ar, ai, tr, ti)
    }

    /**
     * Where output pixel (i, j) of an [outW] x [outH] crop lives in image space.
     * Writes x,y into [xy].
     */
    fun cropToImage(pose: Pose, i: Int, j: Int, outW: Int, outH: Int, xy: DoubleArray) {
        val dx = (i + 0.5) / outW * WINDOW - WINDOW / 2.0
        val dy = (j + 0.5) / outH * WINDOW - WINDOW / 2.0
        val nr = REF[6] + dx - pose.tr
        val ni = REF[7] + dy - pose.ti
        val den = pose.ar * pose.ar + pose.ai * pose.ai
        xy[0] = (nr * pose.ar + ni * pose.ai) / den
        xy[1] = (ni * pose.ar - nr * pose.ai) / den
    }
}
