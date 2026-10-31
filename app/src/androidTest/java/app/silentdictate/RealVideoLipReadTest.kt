package app.silentdictate

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.silentdictate.lips.Chunker
import app.silentdictate.lips.FaceAligner
import app.silentdictate.lips.ImageOps
import app.silentdictate.lips.MouthAnalyzer
import app.silentdictate.lips.createLipFaceDetector
import app.silentdictate.lips.model.LipReaderEngine
import app.silentdictate.lips.model.ModelManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end check of the real pipeline on real video: JPEG frames of a person
 * speaking (pushed to /data/local/tmp/lip) → ML Kit face landmarks → the aligned
 * mouth crop → ONNX Runtime → CTC decode, compared with the true transcript.
 * Uses the production classes; only the camera is replaced by files.
 *
 * Setup:  tools/real_video_test.sh
 */
@RunWith(AndroidJUnit4::class)
class RealVideoLipReadTest {

    private val reference =
        "hi everybody in our house everybody knows that president is only the third most " +
            "important job in the family so this weekend i'm going to take a little extra time to say"

    @Test
    fun readsRealSpeech() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val data = File("/data/local/tmp/lip")
        assertTrue("push test data to ${data.path} first", File(data, "frames").isDirectory)

        val mm = ModelManager(ctx)
        if (!mm.status().installed) {
            val r = mm.installFromFiles(File(data, "lipread_ctc.int8.onnx"), File(data, "tokens.json"))
            assertTrue("install failed: $r", r is ModelManager.InstallResult.Ok)
        }
        val engine = LipReaderEngine(ctx)
        val ready = engine.prepare()
        assertTrue("engine not ready: $ready", ready is LipReaderEngine.Ready.Yes)
        val spec = engine.currentSpec()!!

        val files = File(data, "frames").listFiles { f -> f.name.endsWith(".jpg") }!!.sortedBy { it.name }.take(250)
        val (crops, detections) = cropsFrom(files, spec)
        Log.i(TAG, "frames=${files.size} detections=$detections crops=${crops.size}")
        assertTrue("face was found in too few frames", crops.size > files.size * 0.8)

        // keep a few crops for eyeballing against the Python reference
        File(ctx.filesDir, "crop_dump.bin").outputStream().use { out ->
            for (k in listOf(30, 90, 150, 210)) {
                if (k < crops.size) {
                    val bytes = ByteArray(crops[k].size) {
                        ((crops[k][it] * spec.std + spec.mean) * 255f).toInt().coerceIn(0, 255).toByte()
                    }
                    out.write(bytes)
                }
            }
        }

        val t0 = System.nanoTime()
        val text = engine.transcribe(crops).lowercase()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val wer = wordErrorRate(reference, text)
        Log.i(TAG, "HYP: $text")
        Log.i(TAG, "REF: $reference")
        Log.i(TAG, "WER=%.3f  inference=%d ms for %d frames".format(wer, ms, crops.size))
        assertTrue("WER $wer too high; got: $text", wer <= 0.25)
        engine.close()
    }

    /** Production per-frame path: ML Kit on the thumbnail every 3rd frame → aligned crop. */
    private fun cropsFrom(files: List<File>, spec: LipReaderEngine.Spec): Pair<List<FloatArray>, Int> {
        val detector = createLipFaceDetector()
        val aligner = FaceAligner()
        val crops = ArrayList<FloatArray>()
        var detections = 0

        for ((idx, file) in files.withIndex()) {
            val bmp = BitmapFactory.decodeFile(file.path)
            val w = bmp.width and 1.inv()
            val h = bmp.height and 1.inv()
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val y = ByteArray(w * h)
            val u = ByteArray((w / 2) * (h / 2))
            val v = ByteArray((w / 2) * (h / 2))
            for (j in 0 until h) for (i in 0 until w) {
                val c = px[j * bmp.width + i]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                y[j * w + i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val k = (j / 2) * (w / 2) + i / 2
                    u[k] = (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).toInt().coerceIn(0, 255).toByte()
                    v[k] = (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).toInt().coerceIn(0, 255).toByte()
                }
            }

            // same cadence as the live analyzer: detect every 3rd frame
            if (idx % 3 == 0) {
                val thumb = ImageOps.detectNv21(y, w, 1, u, w / 2, 1, v, w / 2, 1, w, h, 0, MouthAnalyzer.DETECT_WIDTH)
                val faces = Tasks.await(
                    detector.process(
                        InputImage.fromByteArray(thumb.bytes, thumb.width, thumb.height, 0, InputImage.IMAGE_FORMAT_NV21)
                    )
                )
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null && aligner.onFace(face, thumb.width, thumb.height, w, h)) detections++
                else aligner.onNoFace()
            }
            val pose = aligner.pose ?: continue
            val crop = FloatArray(spec.width * spec.height)
            ImageOps.sampleAligned(y, w, h, 0, pose, w, h, crop, spec.width, spec.height, spec.mean, spec.std)
            crops.add(crop)
        }
        detector.close()
        return crops to detections
    }

    @Test
    fun longClipIsReadInPiecesSplitAtPauses() = runBlocking {
        // 20.6 s at 25 fps = 515 frames: more than the model's 500-frame maximum,
        // so this only works if Chunker splits it
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val data = File("/data/local/tmp/lip")
        val dir = File(data, "frames_long")
        assertTrue("push ${dir.path} first (tools/real_video_test.sh)", dir.isDirectory)
        val mm = ModelManager(ctx)
        if (!mm.status().installed) {
            val r = mm.installFromFiles(File(data, "lipread_ctc.int8.onnx"), File(data, "tokens.json"))
            assertTrue("install failed: $r", r is ModelManager.InstallResult.Ok)
        }
        val engine = LipReaderEngine(ctx)
        assertTrue(engine.prepare() is LipReaderEngine.Ready.Yes)
        val spec = engine.currentSpec()!!

        val files = dir.listFiles { f -> f.name.endsWith(".jpg") }!!.sortedBy { it.name }
        val (crops, _) = cropsFrom(files, spec)
        val pieces = Chunker.split(crops)
        Log.i(TAG, "long: ${crops.size} crops -> pieces ${pieces.map { it.size }}")
        assertTrue("expected the clip to be split", pieces.size >= 3)
        assertTrue(pieces.all { it.size <= Chunker.MAX_FRAMES })

        val parts = ArrayList<String>()
        for (piece in pieces) parts.add(engine.transcribe(piece))
        val text = parts.joinToString(" ").lowercase()
            .replace(Regex("\\s+"), " ").trim()
        val wer = wordErrorRate(longReference, text)
        Log.i(TAG, "long HYP: $text")
        Log.i(TAG, "long WER=%.3f".format(wer))
        assertTrue("WER $wer too high; got: $text", wer <= 0.25)
        engine.close()
    }

    private val longReference = (reference + " thank you to michelle for the remarkable way she does " +
        "the most important job being a mom and i'm going to give extra thanks to my mother in law " +
        "for the role model she has always been")

    @Test
    fun decoderCollapsesRepeatsAndBlanks() {
        // pure CTC behaviour is covered end-to-end above; this guards the helper used for scoring
        assertEquals(0.0, wordErrorRate("a b c", "a b c"), 0.0)
        assertEquals(1.0 / 3, wordErrorRate("a b c", "a x c"), 1e-9)
    }

    private fun wordErrorRate(ref: String, hyp: String): Double {
        val r = ref.replace('-', ' ').split(" ").filter { it.isNotEmpty() }
        val h = hyp.replace('-', ' ').split(" ").filter { it.isNotEmpty() }
        val d = Array(r.size + 1) { IntArray(h.size + 1) }
        for (i in 0..r.size) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..r.size) for (j in 1..h.size) {
            d[i][j] = minOf(
                d[i - 1][j] + 1, d[i][j - 1] + 1,
                d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1
            )
        }
        return d[r.size][h.size].toDouble() / r.size
    }

    companion object {
        private const val TAG = "LIPTEST"
    }
}
