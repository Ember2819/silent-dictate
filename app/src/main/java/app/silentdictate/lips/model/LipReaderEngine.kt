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

    @Volatile
    private var session: OrtSession? = null
    @Volatile
    private var spec: Spec? = null

    /** Loads the installed model, replacing any previous session. Call from IO. */
    fun prepare(): Ready {
        close()
        spec = null
        val result = load(File(context.filesDir, "model"))
        if (result is Loaded.Ok) {
            session = result.session
            spec = result.spec
            vocab = result.vocab
            return Ready.Yes(result.spec)
        }
        return Ready.No((result as Loaded.Fail).message)
    }

    /**
     * Verifies a model directory without touching the live session: it must
     * load, and one dummy clip must run through it. Call from IO.
     */
    fun prepareFrom(dir: File): Ready {
        val result = load(dir)
        if (result is Loaded.Fail) return Ready.No(result.message)
        result as Loaded.Ok
        return try {
            val sp = result.spec
            val t = if (sp.fixedT > 0) sp.fixedT else 8
            val blank = List(t) { FloatArray(sp.width * sp.height) }
            vocab = result.vocab
            infer(result.session, sp, blank)
            Ready.Yes(sp)
        } catch (e: Exception) {
            Ready.No("Model loads but doesn’t run: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                result.session.close()
            } catch (_: Exception) {
            }
        }
    }

    private sealed class Loaded {
        class Ok(val session: OrtSession, val spec: Spec, val vocab: List<String>) : Loaded()
        class Fail(val message: String) : Loaded()
    }

    private fun load(dir: File): Loaded {
        try {
            val onnx = File(dir, "model.onnx")
            val configFile = File(dir, "config.json")
            val vocabFile = File(dir, "vocab.txt")
            if (!onnx.exists() || !configFile.exists() || !vocabFile.exists()) {
                return Loaded.Fail("Lip model not installed")
            }

            val config = org.json.JSONObject(configFile.readText())
            val shape = buildList {
                val arr = config.getJSONArray("input_shape")
                for (i in 0 until arr.length()) add(arr.getInt(i))
            }
            val order = config.optString("input_order", "ntchw")
            if (order.length != shape.size || order.any { it !in "ncthw" }) {
                return Loaded.Fail("config.json: input_order doesn’t match input_shape")
            }
            fun dimOf(letter: Char): Int = shape[order.indexOf(letter)]
            val t = dimOf('t')
            val channels = dimOf('c')
            val h = dimOf('h')
            val w = dimOf('w')
            if (channels != 1 && channels != 3) return Loaded.Fail("config.json: model must take 1 or 3 channels")

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
            val newSession = OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                env.createSession(onnx.absolutePath, opts)
            }
            val vocab = vocabFile.readLines().map { it.trimEnd('\n', '\r') }
            return Loaded.Ok(newSession, spec, vocab)
        } catch (e: Exception) {
            return Loaded.Fail("Model failed to load: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun ready(): Boolean = session != null && spec != null

    fun currentSpec(): Spec? = spec

    /**
     * Greedy CTC decode over the model logits.
     * [frames] - oldest-first gray frames, each FloatArray(h*w), already normalized.
     *
     * Fixed-length models (`t` baked into input_shape) get the clip in
     * consecutive windows, so a long sentence isn't truncated to the last second.
     */
    suspend fun transcribe(frames: List<FloatArray>): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val s = requireNotNull(session) { "engine not ready" }
            val sp = requireNotNull(spec)
            if (frames.isEmpty()) return@withContext ""
            synchronized(s) {
                val windows: List<List<FloatArray>> = when {
                    sp.fixedT <= 0 -> listOf(frames)
                    // pad a short tail by holding the last frame
                    else -> frames.chunked(sp.fixedT).map { it + List(sp.fixedT - it.size) { _ -> it.last() } }
                }
                windows.joinToString(" ") { infer(s, sp, it) }
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
        }

    private fun infer(s: OrtSession, sp: Spec, use: List<FloatArray>): String {
        val t = use.size
        val env = OrtEnvironment.getEnvironment()
        val order = sp.order
        val dims = IntArray(order.length) { i ->
            when (order[i]) {
                'n' -> 1
                't' -> t
                else -> sp.shape[i]
            }
        }
        val shapeLongs = LongArray(dims.size) { dims[it].toLong() }
        val buf = FloatArray(dims.fold(1) { a, b -> a * b })

        fun stride(letter: Char): Int {
            val pos = order.indexOf(letter)
            var acc = 1
            for (k in pos + 1 until dims.size) acc *= dims[k]
            return acc
        }
        val sT = stride('t')
        val sC = stride('c')
        val sH = stride('h')
        val sW = stride('w')

        for (ti in 0 until t) {
            val frame = use[ti]
            for (hi in 0 until sp.height) {
                val rowBase = sT * ti + sH * hi
                for (wi in 0 until sp.width) {
                    val v = frame[hi * sp.width + wi]
                    val base = rowBase + sW * wi
                    buf[base] = v
                    if (sp.channels == 3) {
                        buf[base + sC] = v
                        buf[base + 2 * sC] = v
                    }
                }
            }
        }

        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), shapeLongs)
        val outputs = try {
            s.run(java.util.Collections.singletonMap(sp.inputName, tensor))
        } finally {
            tensor.close()
        }
        return outputs.use { res ->
            val v = res[0].value
            @Suppress("UNCHECKED_CAST")
            val steps: List<FloatArray> = when {
                v is FloatArray -> listOf(v)
                v is Array<*> && v.isArrayOf<FloatArray>() -> (v as Array<FloatArray>).toList()
                v is Array<*> && v.isNotEmpty() && v[0] is Array<*> &&
                    (v[0] as Array<*>).isArrayOf<FloatArray>() -> (v[0] as Array<FloatArray>).toList()
                else -> emptyList()
            }
            decode(steps, sp)
        }
    }

    /** Greedy CTC: argmax per step, collapse repeats, drop blanks, join tokens. */
    private fun decode(logits: List<FloatArray>, sp: Spec): String {
        val vocab = this.vocab
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
                prev = -1 // a blank separates genuine repeats
                continue
            }
            if (best == prev) continue
            prev = best
            val tok = vocab.getOrNull(best) ?: continue
            if (tok.isEmpty() || tok in sp.ignore) continue
            sb.append(
                when (tok) {
                    "<space>", "|", "_" -> " "
                    else -> tok.replace('\u2581', ' ') // sentencepiece word marker
                }
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private var vocab: List<String> = emptyList()

    fun close() {
        val old: OrtSession?
        synchronized(this) {
            old = session
            session = null
        }
        if (old == null) return
        // transcribe() holds this lock for the whole run: wait for it rather than
        // freeing native memory under a running inference
        synchronized(old) {
            try {
                old.close()
            } catch (_: Exception) {
            }
        }
    }
}
