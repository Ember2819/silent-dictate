package app.silentdictate.lips

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

/** Builds the face detector the lip pipeline uses. */
fun createLipFaceDetector(): FaceDetector = FaceDetection.getClient(
    FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setMinFaceSize(0.15f)
        .build()
)

/**
 * Turns face detections into a smoothed [FaceAlign.Pose]. Shared by the live
 * camera path and the real-video test so both run exactly the same maths.
 */
class FaceAligner {
    @Volatile
    var pose: FaceAlign.Pose? = null
        private set

    private var missed = 0

    /**
     * [face] was detected on a thumbnail of [thumbW] x [thumbH]; the full upright
     * frame is [upW] x [upH]. Returns false when the face had no usable landmarks.
     */
    fun onFace(face: Face, thumbW: Int, thumbH: Int, upW: Int, upH: Int): Boolean {
        val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val ml = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mr = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        if (le == null || re == null || nose == null || ml == null || mr == null) return false

        // landmarks are in thumbnail pixels; the crop is cut from the full frame
        val kx = upW.toFloat() / thumbW
        val ky = upH.toFloat() / thumbH
        val pts = floatArrayOf(
            re.x * kx, re.y * ky,
            le.x * kx, le.y * ky,
            nose.x * kx, nose.y * ky,
            (ml.x + mr.x) / 2f * kx, (ml.y + mr.y) / 2f * ky,
        )
        val fresh = FaceAlign.estimate(pts) ?: return false
        missed = 0
        val old = pose
        pose = if (old == null) fresh else old.blendTo(fresh, SMOOTH)
        return true
    }

    /** No face this time. Returns true once it has been gone long enough to drop the pose. */
    fun onNoFace(): Boolean {
        if (++missed >= MAX_MISSED) {
            pose = null
            return true
        }
        return false
    }

    fun reset() {
        pose = null
        missed = 0
    }

    companion object {
        private const val SMOOTH = 0.5
        private const val MAX_MISSED = 3
    }
}

/**
 * Camera image pipeline.
 *
 * - every [DETECT_EVERY] frames runs ML Kit face detection on a small upright
 *   NV21 thumbnail and updates the face pose,
 * - every frame where a pose is known, cuts + normalises the aligned mouth crop
 *   and hands it to [onFrame] together with the camera timestamp (nanoseconds).
 */
class MouthAnalyzer(
    private val outW: Int,
    private val outH: Int,
    private val mean: Float,
    private val std: Float,
    private val onFrame: (frame: FloatArray, timestampNs: Long) -> Unit,
    private val onFacePresence: (Boolean) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = createLipFaceDetector()
    private val aligner = FaceAligner()

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
            // packed copy: rows are exactly w bytes from here on
            val yBytes = ImageOps.copyPlane(
                yPlane.buffer, w, h, yPlane.rowStride, yPlane.pixelStride, yBuf
            ).also { yBuf = it }

            val pose = aligner.pose
            if (pose != null) {
                val (upW, upH) = ImageOps.uprightSize(w, h, rotation)
                val frame = FloatArray(outW * outH)
                ImageOps.sampleAligned(yBytes, w, h, rotation, pose, upW, upH, frame, outW, outH, mean, std)
                onFrame(frame, proxy.imageInfo.timestamp)
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

            // all three are packed copies now: stride = row length, sample stride 1
            val thumb = ImageOps.detectNv21(
                yBytes, w, 1,
                uBytes, w / 2, 1,
                vBytes, w / 2, 1,
                w, h, rotation, DETECT_WIDTH
            )
            val (upW, upH) = ImageOps.uprightSize(w, h, rotation)
            val image = InputImage.fromByteArray(
                thumb.bytes, thumb.width, thumb.height, 0, InputImage.IMAGE_FORMAT_NV21
            )
            detector.process(image)
                .addOnSuccessListener { faces ->
                    val face = faces.maxByOrNull {
                        it.boundingBox.width().toLong() * it.boundingBox.height().toLong()
                    }
                    if (face != null && aligner.onFace(face, thumb.width, thumb.height, upW, upH)) {
                        detectedAtLeastOnce = true
                        onFacePresence(true)
                    } else if (aligner.onNoFace()) {
                        onFacePresence(false)
                    }
                }
                .addOnFailureListener { onFacePresence(false) }
        } catch (t: Throwable) {
            onFacePresence(false)
        }
    }

    fun isFaceTracked(): Boolean = detectedAtLeastOnce

    fun release() {
        detector.close()
    }

    companion object {
        private const val DETECT_EVERY = 3
        const val DETECT_WIDTH = 320
    }
}
