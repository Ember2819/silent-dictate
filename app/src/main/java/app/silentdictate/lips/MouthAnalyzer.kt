package app.silentdictate.lips

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs

/**
 * Camera image pipeline.
 *
 * - starts with the raw sensor frame (YUV),
 * - every [DETECT_EVERY] frames runs ML Kit face detection on a small upright
 *   NV21 thumbnail and tracks a mouth rectangle (fractions of the upright frame),
 * - every frame where a rectangle is known, samples + normalizes the mouth crop
 *   and hands it to [onFrame].
 */
class MouthAnalyzer(
    private val outW: Int,
    private val outH: Int,
    private val mean: Float,
    private val std: Float,
    private val onFrame: (FloatArray) -> Unit,
    private val onFacePresence: (Boolean) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.15f)
            .build()
    )

    @Volatile
    private var mouthRect: RectPct? = null

    @Volatile
    private var detectedAtLeastOnce = false

    private var framesSinceDetect = 0

    private var yBuf: ByteArray? = null
    private var uBuf: ByteArray? = null
    private var vBuf: ByteArray? = null

    override fun analyze(proxy: ImageProxy) {
        try {
            val w = proxy.width
            val h = proxy.height
            val rotation = proxy.imageInfo.rotationDegrees

            val yPlane = proxy.planes[0]
            val yBytes = ImageOps.copyPlane(
                yPlane.buffer, w, h, yPlane.rowStride, yPlane.pixelStride, yBuf
            ).also { yBuf = it }

            val rect = mouthRect
            if (rect != null) {
                val frameData = FloatArray(outW * outH)
                ImageOps.sampleMouth(
                    yBytes, yPlane.rowStride, yPlane.pixelStride,
                    w, h, rotation, rect, frameData, outW, outH, mean, std
                )
                onFrame(frameData)
            }

            if (framesSinceDetect-- <= 0) {
                framesSinceDetect = DETECT_EVERY
                runDetection(proxy, yBytes, w, h, rotation)
            }
        } finally {
            proxy.close()
        }
    }

    private fun runDetection(proxy: ImageProxy, yBytes: ByteArray, w: Int, h: Int, rotation: Int) {
        try {
            val uP = proxy.planes[1]
            val vP = proxy.planes[2]
            val uBytes = ImageOps.copyPlane(
                uP.buffer, w / 2, h / 2, uP.rowStride, uP.pixelStride, uBuf
            ).also { uBuf = it }
            val vBytes = ImageOps.copyPlane(
                vP.buffer, w / 2, h / 2, vP.rowStride, vP.pixelStride, vBuf
            ).also { vBuf = it }

            // yBytes/uBytes/vBytes are packed width-major, so their strides are
            // the source dimensions and 1 sample stride.
            val frame = ImageOps.detectNv21(
                yBytes, w, 1,
                uBytes, w / 2, 1,
                vBytes, w / 2, 1,
                w, h, rotation, targetW
            )
            val image = InputImage.fromByteArray(
                frame.bytes, frame.width, frame.height, 0, InputImage.IMAGE_FORMAT_NV21
            )
            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        if (detectedAtLeastOnce) onFacePresence(false)
                        return@addOnSuccessListener
                    }
                    detectedAtLeastOnce = true
                    onFacePresence(true)
                    val face = faces.maxBy {
                        val bb = it.boundingBox
                        bb.width().toLong() * bb.height().toLong()
                    }
                    mouthRect = computeMouth(face, frame.width, frame.height)
                }
                .addOnFailureListener { onFacePresence(false) }
        } catch (t: Throwable) {
            onFacePresence(false)
        }
    }

    /** Mouth patch as fractions of the (upright) detection frame; null if unusable. */
    private fun computeMouth(face: Face, fw: Int, fh: Int): RectPct? {
        val box = face.boundingBox ?: return null
        val fx = { x: Float -> (x / fw.toFloat()).coerceIn(0f, 1f) }
        val fy = { y: Float -> (y / fh.toFloat()).coerceIn(0f, 1f) }

        val left = face.getLandmark(FaceLandmark.MOUTH_LEFT)
        val right = face.getLandmark(FaceLandmark.MOUTH_RIGHT)
        val bottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)

        if (left != null && right != null && bottom != null) {
            val lx = left.position.x
            val rx = right.position.x
            val cornerAvgY = (left.position.y + right.position.y) / 2f
            val by = bottom.position.y
            val cx = (lx + rx) / 2f
            val cy = (cornerAvgY + by) / 2f
            val halfW = (abs(rx - lx) * 0.62f).coerceAtLeast(6f)
            val halfH = (abs(by - cornerAvgY) * 1.45f).coerceAtLeast(6f)
            return RectPct(fx(cx - halfW), fy(cy - halfH), fx(cx + halfW), fy(cy + halfH))
        }
        // bbox fallback: middle-lower band of the face box
        val cx = (box.left + box.right) / 2f
        val top = box.top + box.height() * 0.60f
        val bot = box.top + box.height() * 0.92f
        val halfW = box.width() * 0.26f
        return RectPct(fx(cx - halfW), fy(top), fx(cx + halfW), fy(bot))
    }

    fun isFaceTracked(): Boolean = detectedAtLeastOnce

    fun release() {
        detector.close()
    }

    companion object {
        private const val DETECT_EVERY = 4
        private const val targetW = 208
    }
}
