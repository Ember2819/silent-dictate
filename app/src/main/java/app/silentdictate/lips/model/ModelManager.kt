package app.silentdictate.lips.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The lip-reading neural net lives in {filesDir}/model as
 *   model.onnx   - exported ONNX graph
 *   config.json  - tensor + vocab layout (see tools/export_lip_model.py)
 *   vocab.txt    - one token per line, CTC
 *
 * Nothing is hardcoded to a specific architecture: everything the engine needs
 * is in config.json, so any small CTC VSR model can be exported and dropped in.
 *
 * Installs are atomic: files are unpacked into a staging directory, verified
 * by actually loading the model, and only then replace the current install.
 */
class ModelManager(context: Context) {

    private val context: Context = context.applicationContext
    private val dir = File(this.context.filesDir, "model")

    data class Status(
        val installed: Boolean,
        val message: String,
        val sizeMb: Float,
    )

    fun status(): Status {
        recoverInterruptedSwap()
        val onnx = File(dir, "model.onnx")
        val missing = REQUIRED.filter { !File(dir, it).exists() }
        return if (missing.isEmpty()) {
            Status(true, "Model installed", onnx.length() / 1024f / 1024f)
        } else {
            Status(false, "No model installed", 0f)
        }
    }

    /** Changes whenever the installed model is replaced; 0 when none is installed. */
    fun stamp(): Long {
        if (!status().installed) return 0L
        val f = File(dir, "model.onnx")
        return f.lastModified() xor (f.length() shl 1)
    }

    sealed class InstallResult {
        data class Ok(val status: Status) : InstallResult()
        data class Error(val message: String) : InstallResult()
    }

    /**
     * The model the app is built around: Auto-AVSR's LRS3 visual-only lip reader
     * (encoder + CTC head, int8 ONNX). ~28.5% WER on LRS3 with plain greedy
     * decoding. Weights are research / non-commercial (they inherit the LRS3 terms).
     */
    object Recommended {
        const val NAME = "Auto-AVSR lip reader (LRS3)"
        const val SIZE_MB = 203
        private const val BASE =
            "https://huggingface.co/eschmechel/auto-avsr-lrs3-vsr-int8-onnx/resolve/main/"
        const val MODEL_URL = BASE + "lipread_ctc.int8.onnx"
        const val MODEL_SHA256 = "da02d72ee472eb3d7d50f7dd309bfe81973acb4cac8bb770dd86aa97872f195b"
        const val TOKENS_URL = BASE + "tokens.json"
        const val TOKENS_SHA256 = "97f0f20cfebadaf51f7cdb9e7a26835fa0fbd6a956701434e899a4f9934d979c"

        /** Tensor contract of that export: video [1,1,T,88,88] → log_probs [T,5049]. */
        val CONFIG = """
            {
              "input_name": "video",
              "output_name": "log_probs",
              "input_shape": [1, 1, -1, 88, 88],
              "input_order": "ncthw",
              "mean": 0.421,
              "std": 0.165,
              "blank_id": 0,
              "ignore": ["<blank>", "<unk>", "<eos>"]
            }
        """.trimIndent()
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Streams [url] to [dest], checking the SHA-256 as it goes. [onBytes] gets bytes copied so far. */
    private fun fetch(url: String, dest: File, sha256: String, onBytes: (Long) -> Unit): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return "Download failed (HTTP ${resp.code})"
            val body = resp.body ?: return "Empty download"
            FileOutputStream(dest).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        onBytes(done)
                    }
                }
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        return if (got == sha256) null else "The download was corrupted — try again"
    }

    /** Download and install [Recommended]. [onProgress] gets 0..1. */
    suspend fun installRecommended(onProgress: (Float) -> Unit = {}): InstallResult =
        withContext(Dispatchers.IO) {
            // download (203 MB) + headroom; the old model, if any, is freed only after the swap
            val needMb = Recommended.SIZE_MB + 50
            val freeMb = context.filesDir.usableSpace / (1024 * 1024)
            if (freeMb < needMb) {
                return@withContext InstallResult.Error(
                    "Not enough storage — free up ${needMb - freeMb} MB and try again"
                )
            }
            val staging = File(context.filesDir, "model_staging")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                val onnx = File(staging, "model.onnx")
                val tokens = File(staging, "tokens.json")
                val total = Recommended.SIZE_MB * 1024f * 1024f
                fetch(Recommended.TOKENS_URL, tokens, Recommended.TOKENS_SHA256) {}
                    ?.let { return@withContext InstallResult.Error(it) }
                fetch(Recommended.MODEL_URL, onnx, Recommended.MODEL_SHA256) { done ->
                    onProgress((done / total).coerceAtMost(0.99f))
                }?.let { return@withContext InstallResult.Error(it) }
                installFromFiles(onnx, tokens, move = true).also { if (it is InstallResult.Ok) onProgress(1f) }
            } catch (e: java.io.IOException) {
                InstallResult.Error("Network problem — ${e.message ?: "check your connection"}")
            } catch (e: Exception) {
                InstallResult.Error(e.message ?: e.javaClass.simpleName)
            } finally {
                staging.deleteRecursively()
            }
        }

    /**
     * Installs [Recommended]-format files that are already on disk: writes
     * vocab.txt (from tokens.json) and config.json next to the model, proves it
     * runs, then swaps it in.
     */
    suspend fun installFromFiles(onnx: File, tokensJson: File, move: Boolean = false): InstallResult =
        withContext(Dispatchers.IO) {
            val staging = File(context.filesDir, "model_staging_files")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                val tokens = org.json.JSONArray(tokensJson.readText())
                File(staging, "vocab.txt").bufferedWriter().use { w ->
                    for (i in 0 until tokens.length()) {
                        w.write(tokens.getString(i))
                        w.write("\n")
                    }
                }
                File(staging, "config.json").writeText(Recommended.CONFIG)
                // moving (same filesystem) avoids holding two 200 MB copies at once
                val dst = File(staging, "model.onnx")
                if (!(move && onnx.renameTo(dst))) onnx.copyTo(dst, overwrite = true)
                commit(staging)
            } catch (e: Exception) {
                InstallResult.Error(e.message ?: e.javaClass.simpleName)
            } finally {
                staging.deleteRecursively()
            }
        }

    /** Verifies the three files in [staging] load and run, then replaces the current install. */
    private fun commit(staging: File): InstallResult {
        val missing = REQUIRED.filter { !File(staging, it).let { f -> f.exists() && f.length() > 0 } }
        if (missing.isNotEmpty()) {
            return InstallResult.Error("The zip is missing ${missing.joinToString(", ")}")
        }
        val check = LipReaderEngine(context).prepareFrom(staging)
        if (check is LipReaderEngine.Ready.No) return InstallResult.Error(check.message)

        // Swap whole directories so a crash can never leave old and new files mixed:
        // model -> model_old, staging -> model, then drop model_old.
        synchronized(SWAP_LOCK) {
            val old = File(context.filesDir, "model_old")
            old.deleteRecursively()
            if (dir.exists() && !dir.renameTo(old)) return InstallResult.Error("Couldn’t replace the old model")
            if (!staging.renameTo(dir)) {
                dir.mkdirs()
                for (name in REQUIRED) File(staging, name).copyTo(File(dir, name), overwrite = true)
            }
            old.deleteRecursively()
        }
        return InstallResult.Ok(status())
    }

    /** If the app died between the two renames above, put the previous model back. */
    private fun recoverInterruptedSwap() {
        synchronized(SWAP_LOCK) {
            val old = File(context.filesDir, "model_old")
            if (!old.exists()) return
            if (!dir.exists() || REQUIRED.any { !File(dir, it).exists() }) {
                dir.deleteRecursively()
                old.renameTo(dir)
            } else {
                old.deleteRecursively()
            }
        }
    }

    /** Install from a zip stream (file picker or download). */
    suspend fun install(zip: InputStream): InstallResult = withContext(Dispatchers.IO) {
        val staging = File(context.filesDir, "model_staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ZipInputStream(zip.buffered()).use { z ->
                var entry = z.nextEntry
                while (entry != null) {
                    // only the three known files, flattened: no path traversal possible
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name in REQUIRED) {
                        FileOutputStream(File(staging, name)).use { z.copyTo(it) }
                    }
                    entry = z.nextEntry
                }
            }
            commit(staging)
        } catch (e: Exception) {
            InstallResult.Error(
                if (e is java.util.zip.ZipException) "That file isn’t a valid .zip"
                else e.message ?: e.javaClass.simpleName
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    fun wipe() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private val REQUIRED = listOf("model.onnx", "config.json", "vocab.txt")

        /** Held while directories are swapped, so recovery never runs mid-swap. */
        private val SWAP_LOCK = Any()
    }
}
