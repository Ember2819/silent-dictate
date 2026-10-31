package app.silentdictate.lips.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs an exported CTC visual speech recognition model with ONNX Runtime.
 *
 * Input contract (from config.json):
 *   input_shape : full tensor shape, e.g. [1, 29, 1, 88, 88]
 *   input_order : axis order string over n,c,t,h,w, e.g. "ntchw"
 *   mean / std  : pixel normalization (typical lip crops: 0.421 / 0.165)
 *
 * Frames arrive as gray, c,h,w per frame. If the model wants RGB, the gray
 * channel is replicated.
 */
class LipReaderEngine(private val context: Context) {

    data class Spec(
        val inputName: String,
        val outputName: String,
        val shape: List<Int>,
        val order: String,
        val mean: Float,
        val std: Float,
        val blankId: Int,
        val ignore: Set<String>,
        val fixedT: Int,
        val channels: Int,
        val width: Int,   // model input W
        val height: Int,  // model input H
    )

    sealed class Ready {
        data class Yes(val spec: Spec) : Ready()
        data class No(val message: String) : Ready()
    }

    private var session: OrtSession? = null
    private var spec: Spec? = null

    /** Scans the model dir and builds a session if possible. Call from IO. */
    fun prepare(): Ready {
        try {
            session?.close()
            session = null
            spec = null

            val dir = File(context.filesDir, "model")
            val onnx = File(dir, "model.onnx")
            val configFile = File(dir, "config.json")
            val vocabFile = File(dir, "vocab.txt")
            if (!onnx.exists() || !configFile.exists() || !vocabFile.exists()) {
                return Ready.No("Lip model not installed")
            }

            val config = org.json.JSONObject(configFile.readText())
            val shape = buildList {
                val arr = config.getJSONArray("input_shape")
                for (i in 0 until arr.length()) add(arr.getInt(i))
            }
            val order = config.optString("input_order", "ntchw")
            if (order.length != shape.size || order.any { it !in "ncthw" }) {
                return Ready.No("config.json input_order invalid")
            }
            fun dimOf(letter: Char): Int = shape[order.indexOf(letter)]
            val t = dimOf('t')
            val channels = dimOf('c')
            val h = dimOf('h')
            val w = dimOf('w')
            if (channels != 1 && channels != 3) return Ready.No("channels must be 1 or 3")

            val spec = Spec(
                inputName = config.optString("input_name", "input"),
                outputName = config.optString("output_name", "output"),
                shape = shape,
                order = order,
                mean = (config.optDouble("mean", 0.421)).toFloat(),
                std = (config.optDouble("std", 0.165)).toFloat(),
                blankId = config.optInt("blank_id", 0),
                ignore = config.optJSONArray("ignore")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                } ?: setOf("<pad>", "<unk>"),
                fixedT = if (t > 0) t else 0,
                channels = channels,
                width = w,
                height = h,
            )

            val env = OrtEnvironment.getEnvironment()
            val bytes = onnx.readBytes()
            session = env.createSession(bytes)
            this.spec = spec
            return Ready.Yes(spec)
        } catch (e: Exception) {
            return Ready.No("Model failed to load: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun ready(): Boolean = session != null && spec != null

    fun currentSpec(): Spec? = spec

    /**
     * Greedy CTC decode over the model logits.
     * [frames] - most-recent-last gray frames, each FloatArray(h*w), normalized.
     */
    suspend fun transcribe(frames: List<FloatArray>): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val s = requireNotNull(session) { "engine not ready" }
            val sp = requireNotNull(spec)
            synchronized(s) {
                if (frames.isEmpty()) return@withContext ""

                var use = frames
                if (sp.fixedT > 0) {
                    use = when {
                        frames.size > sp.fixedT -> frames.takeLast(sp.fixedT)
                        frames.size < sp.fixedT -> {
                            val pad = MutableList<FloatArray>(sp.fixedT - frames.size) { frames.first() }
                            pad + frames
                        }
                        else -> frames
                    }
                }

                val t = use.size
                val env = OrtEnvironment.getEnvironment()
                val order = sp.order
                val shapeLongs = LongArray(sp.shape.size) { i ->
                    val letter = order[i]
                    when (letter) {
                        'n' -> 1L
                        't' -> t.toLong()
                        else -> sp.shape[i].toLong()
                    }
                }
                val dimsLocal = order.map { c ->
                    when (c) {
                        'n' -> 1
                        't' -> t
                        else -> sp.shape[order.indexOf(c)]
                    }
                }

                val total = shapeLongs.fold(1L) { a, b -> a * b }.toInt()
                val outBuf = FloatArray(total)

                val posN = order.indexOf('n')
                val posT = order.indexOf('t')
                val posC = order.indexOf('c')
                val posH = order.indexOf('h')
                val posW = order.indexOf('w')

                fun stride(pos: Int): Int {
                    if (pos < 0) return 0
                    var acc = 1
                    for (k in pos + 1 until dimsLocal.size) acc *= dimsLocal[k]
                    return acc
                }
                val sN = stride(posN)
                val sT = stride(posT)
                val sC = stride(posC)
                val sH = stride(posH)
                val sW = stride(posW)

                var ti = 0
                val plane = sp.height * sp.width
                while (ti < t) {
                    val frame = use[ti]
                    var hi = 0
                    while (hi < sp.height) {
                        val rowBase = sT * ti + sH * hi
                        var wi = 0
                        while (wi < sp.width) {
                            val v = frame[hi * sp.width + wi]
                            val base = rowBase + sW * wi
                            // channel 0
                            outBuf[base] = v
                            if (sp.channels == 3) {
                                outBuf[base + sC] = v
                                outBuf[base + 2 * sC] = v
                            }
                            wi++
                        }
                        hi++
                    }
                    ti++
                }

                val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(outBuf), shapeLongs)
                val outputs = try {
                    s.run(java.util.Collections.singletonMap(sp.inputName, tensor))
                } finally {
                    tensor.close()
                }
                outputs.use { res ->
                    val v = res[0].value
                    @Suppress("UNCHECKED_CAST")
                    val logitsPerStep: List<FloatArray> =
                        if (v is FloatArray) {
                            listOf(v)
                        } else if (v is Array<*> && v.isArrayOf<FloatArray>()) {
                            (v as Array<FloatArray>).toList()
                        } else if (v is Array<*> && v.first() is Array<*> &&
                            (v.first() as Array<*>).isArrayOf<FloatArray>()
                        ) {
                            (v[0] as Array<FloatArray>).toList()
                        } else {
                            emptyList()
                        }
                    decode(logitsPerStep, sp)
                }
            }
        }

    private fun decode(logits: List<FloatArray>, sp: Spec): String {
        val vocab = loadVocab()
        if (vocab.isEmpty()) return ""
        val sb = StringBuilder()
        var prev = -1
        for (step in logits) {
            var best = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (i in step.indices) {
                if (step[i] > bestVal) {
                    bestVal = step[i]
                    best = i
                }
            }
            if (best == sp.blankId) {
                // a blank resets repeat-merging (same token again = two tokens)
                prev = -1
                continue
            }
            if (best == prev) continue
            prev = best
            val tok = vocab.getOrNull(best) ?: continue
            if (tok in sp.ignore || tok.isEmpty()) continue
            sb.append(tok.first())
        }
        return sb.toString().trim()
    }

    private var vocabCache: List<String>? = null

    private fun loadVocab(): List<String> {
        vocabCache?.let { return it }
        val f = File(context.filesDir, "model/vocab.txt")
        val lines = if (f.exists()) {
            f.readLines().map { it.trimEnd('\n', '\r') }
        } else emptyList()
        vocabCache = lines
        return lines
    }

    fun close() {
        val old: OrtSession?
        synchronized(this) {
            old = session
            session = null
        }
        try {
            old?.close()
        } catch (_: Exception) {
        }
    }
}
