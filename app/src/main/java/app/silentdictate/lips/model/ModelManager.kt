package app.silentdictate.lips.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
 * See README.md for the export recipe (AutoAVSR / LRW recipes).
 */
class ModelManager(context: Context) {

    private val context: Context = context.applicationContext
    private val dir = File(context.filesDir, "model")

    data class Status(
        val installed: Boolean,
        val message: String,
        val sizeMb: Float,
    )

    fun status(): Status {
        val onnx = File(dir, "model.onnx")
        val config = File(dir, "config.json")
        val vocab = File(dir, "vocab.txt")
        val missing = buildList {
            if (!onnx.exists()) add("model.onnx")
            if (!config.exists()) add("config.json")
            if (!vocab.exists()) add("vocab.txt")
        }
        return if (missing.isEmpty()) {
            Status(true, "Model installed", onnx.length() / 1024f / 1024f)
        } else {
            Status(false, "Not installed (missing ${missing.joinToString(", ")})", 0f)
        }
    }

    sealed class DownloadResult {
        data class Ok(val status: Status) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    /** Download a zip containing model.onnx / config.json / vocab.txt from [url]. */
    suspend fun download(url: String): DownloadResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext DownloadResult.Error("No model URL set")
        dir.mkdirs()
        val tmp = File(context.cacheDir, "lip_model.zip")
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DownloadResult.Error("HTTP ${resp.code}")
                }
                FileOutputStream(tmp).use { out ->
                    resp.body?.byteStream()?.copyTo(out)
                }
            }
            ZipInputStream(tmp.inputStream()).use { zip ->
                var seen = 0
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (name == "model.onnx" || name == "config.json" || name == "vocab.txt") {
                        if (entry.size == 0L && name.endsWith(".onnx")) {
                            // zero-length entries are nonsense; skip
                        } else {
                            FileOutputStream(File(dir, name)).use { zip.copyTo(it) }
                            seen++
                        }
                    }
                    entry = zip.nextEntry
                }
                if (seen < 3) {
                    wipe()
                    return@withContext DownloadResult.Error(
                        "Zip did not contain all model files"
                    )
                }
            }
            tmp.delete()
            DownloadResult.Ok(status())
        } catch (e: Exception) {
            wipe()
            DownloadResult.Error("${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun wipe() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
