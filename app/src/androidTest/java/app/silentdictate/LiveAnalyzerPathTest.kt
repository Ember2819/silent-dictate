package app.silentdictate

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.Image
import android.util.Log
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.silentdictate.lips.Frames
import app.silentdictate.lips.MouthAnalyzer
import app.silentdictate.lips.model.LipReaderEngine
import app.silentdictate.lips.model.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives the production [MouthAnalyzer] the way CameraX does, using real video:
 *  - frames are rotated into *sensor* orientation (front camera: rotationDegrees = 270),
 *  - Y has padded row strides, chroma is interleaved with pixelStride = 2,
 *  - timestamps are 30 fps (the model needs 25), paced in real time,
 *  - face detection runs asynchronously, as in the live app.
 * Then [Frames.resample] → model → text, scored against the true transcript.
 */
@RunWith(AndroidJUnit4::class)
class LiveAnalyzerPathTest {

    private val reference =
        "hi everybody in our house everybody knows that president is only the third most " +
            "important job in the family so this weekend i'm going to take a little extra time to say"

    private class Plane(val buf: ByteBuffer, val row: Int, val pixel: Int) : ImageProxy.PlaneProxy {
        override fun getRowStride() = row
        override fun getPixelStride() = pixel
        override fun getBuffer(): ByteBuffer = buf
    }

    private class FakeProxy(
        private val w: Int, private val h: Int, private val planes: Array<ImageProxy.PlaneProxy>,
        private val rotation: Int, private val ts: Long,
    ) : ImageProxy {
        private var crop = Rect(0, 0, w, h)
        override fun close() {}
        override fun getCropRect() = crop
        override fun setCropRect(rect: Rect?) { crop = rect ?: crop }
        override fun getFormat() = android.graphics.ImageFormat.YUV_420_888
        override fun getHeight() = h
        override fun getWidth() = w
        override fun getPlanes() = planes
        @androidx.camera.core.ExperimentalGetImage
        override fun getImage(): Image? = null
        override fun getImageInfo() = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
            override fun getTimestamp() = ts
            override fun getRotationDegrees() = rotation
            override fun populateExifData(exifBuilder: ExifData.Builder) {}
            override fun getSensorToBufferTransformMatrix() = android.graphics.Matrix()
        }
    }

    @Test
    fun liveAnalyzerPathReadsRealSpeech() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val data = File("/data/local/tmp/lip")
        val mm = ModelManager(ctx)
        if (!mm.status().installed) {
            val r = mm.installFromFiles(File(data, "lipread_ctc.int8.onnx"), File(data, "tokens.json"))
            assertTrue("install failed: $r", r is ModelManager.InstallResult.Ok)
        }
        val engine = LipReaderEngine(ctx)
        assertTrue(engine.prepare() is LipReaderEngine.Ready.Yes)
        val spec = engine.currentSpec()!!

        val collected = CopyOnWriteArrayList<Frames.Timed>()
        val analyzer = MouthAnalyzer(
            outW = spec.width, outH = spec.height, mean = spec.mean, std = spec.std,
            onFrame = { frame, ts -> collected.add(Frames.Timed(ts, frame)) },
            onFacePresence = {},
        )

        val files = File(data, "frames30").listFiles { f -> f.name.endsWith(".jpg") }!!.sortedBy { it.name }.take(300)
        val t0 = 5_000_000_000L
        for ((idx, file) in files.withIndex()) {
            val bmp = BitmapFactory.decodeFile(file.path)
            val uw = bmp.width and 1.inv()
            val uh = bmp.height and 1.inv()
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)

            // upright Y/U/V
            val y = ByteArray(uw * uh)
            val u = ByteArray((uw / 2) * (uh / 2))
            val v = ByteArray((uw / 2) * (uh / 2))
            for (j in 0 until uh) for (i in 0 until uw) {
                val c = px[j * bmp.width + i]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                y[j * uw + i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val k = (j / 2) * (uw / 2) + i / 2
                    u[k] = (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).toInt().coerceIn(0, 255).toByte()
                    v[k] = (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).toInt().coerceIn(0, 255).toByte()
                }
            }

            // sensor image = upright rotated 90° clockwise → CameraX would report rotationDegrees = 270
            val sw = uh; val sh = uw
            val yRow = sw + 32                       // padded rows, like real devices
            val yBuf = ByteArray(yRow * sh)
            for (j in 0 until uh) for (i in 0 until uw) yBuf[i * yRow + (uh - 1 - j)] = y[j * uw + i]
            val cw = sw / 2; val ch = sh / 2
            val cRow = sw + 32                       // interleaved V,U,V,U… (pixelStride 2)
            val cBuf = ByteArray(cRow * ch)
            for (j in 0 until uh / 2) for (i in 0 until uw / 2) {
                val sx = (uh / 2 - 1 - j); val sy = i
                cBuf[sy * cRow + sx * 2] = v[j * (uw / 2) + i]
                cBuf[sy * cRow + sx * 2 + 1] = u[j * (uw / 2) + i]
            }
            val vPlane = Plane(ByteBuffer.wrap(cBuf), cRow, 2)
            val uPlane = Plane(ByteBuffer.wrap(cBuf, 1, cBuf.size - 1).slice(), cRow, 2)

            analyzer.analyze(
                FakeProxy(sw, sh, arrayOf(Plane(ByteBuffer.wrap(yBuf), yRow, 1), uPlane, vPlane), 270, t0 + idx * 33_333_333L)
            )
            Thread.sleep(33) // real-time pacing: face detection is async, as live
        }
        analyzer.release()

        val resampled = Frames.resample(collected)
        Log.i(TAG, "fed=${files.size} cropped=${collected.size} resampled(25fps)=${resampled.size} faceTracked=${analyzer.isFaceTracked()}")
        assertTrue("face never tracked", analyzer.isFaceTracked())
        assertTrue("too few crops: ${collected.size}", collected.size > files.size * 0.7)

        val t1 = System.nanoTime()
        val text = engine.transcribe(resampled).lowercase()
        val ms = (System.nanoTime() - t1) / 1_000_000
        val wer = wer(reference, text)
        Log.i(TAG, "HYP: $text")
        Log.i(TAG, "WER=%.3f inference=%d ms for %d frames".format(wer, ms, resampled.size))
        assertTrue("WER $wer too high; got: $text", wer <= 0.25)
        engine.close()
    }

    private fun wer(ref: String, hyp: String): Double {
        val r = ref.split(" ").filter { it.isNotEmpty() }
        val h = hyp.split(" ").filter { it.isNotEmpty() }
        val d = Array(r.size + 1) { IntArray(h.size + 1) }
        for (i in 0..r.size) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..r.size) for (j in 1..h.size)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
        return d[r.size][h.size].toDouble() / r.size
    }

    companion object { private const val TAG = "LIVETEST" }
}
