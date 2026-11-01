package app.silentdictate.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updates from GitHub releases:
 *
 *   check  → the "latest" web redirect gives the current tag (no API, no
 *            rate limits; the JSON API is a fallback and is CDN-cached,
 *            which makes it flaky);
 *   verify → a HEAD on the release asset URL for this phone's ABI;
 *   fetch  → the APK is streamed into app storage;
 *   install→ opened with the system package installer via FileProvider.
 *
 * One-time approval ("install unknown apps" for this app) is requested by the
 * settings screen. Nothing needs exotic permissions, nothing leaves the device
 * except these public URLs.
 */
class Updater(private val context: Context) {

    sealed class Check {
        /** A newer release exists. */
        data class Available(
            val version: String,
            val assetName: String,
            val assetUrl: String,
            val bytesTotal: Long,          // 0 = unknown
        ) : Check()

        data class UpToDate(val version: String) : Check()
        data class Failed(val message: String) : Check()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val noRedirects = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun installedVersion(): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    suspend fun check(): Check = withContext(Dispatchers.IO) {
        // Fast path: https://github.com/OWNER/REPO/releases/latest redirects to …/tag/vX.Y.Z
        val tag = try {
            val req = Request.Builder()
                .url("$REPO_WEB/releases/latest")
                .header("User-Agent", USER_AGENT)
                .head()
                .build()
            noRedirects.newCall(req).execute().use { resp ->
                val loc = resp.header("location") ?: return@use null
                loc.substringAfterLast("/releases/tag/", "").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
        if (!tag.isNullOrEmpty()) return@withContext fromTag(tag)
        // Fallback: the JSON API (fine when its CDN isn't serving a stale 404)
        viaApi()
    }

    /** Confirm the ABI-matched asset exists for the tag and read its size. */
    private fun fromTag(tag: String): Check {
        return try {
            if (!tag.startsWith("v")) {
                Check.Failed("Release “$tag” has an unexpected tag")
            } else {
                val version = tag.removePrefix("v")
                val wanted = assetForAbi()
                var chosen: Pair<String, Long>? = null
                for (name in listOf("${wanted}.apk", "silent-dictate-universal.apk")) {
                    val url = "$REPO_WEB/releases/download/$tag/$name"
                    val head = Request.Builder().url(url).header("User-Agent", USER_AGENT).head().build()
                    val resp = http.newCall(head).execute()
                    val code = resp.code
                    val len = resp.body?.contentLength() ?: -1L
                    resp.close()
                    if (code in 200..299) {
                        chosen = name to (len.takeIf { it > 0 } ?: 0L)
                        break
                    }
                    if (code == 404) continue else return Check.Failed("GitHub returned $code")
                }
                val asset = chosen ?: return Check.Failed(
                    "No APK attached to $tag — install manually from GitHub"
                )
                val current = installedVersion()
                if (isOlder(current, version)) {
                    Check.Available(
                        version = version,
                        assetName = asset.first,
                        assetUrl = "$REPO_WEB/releases/download/$tag/${asset.first}",
                        bytesTotal = asset.second,
                    )
                } else {
                    Check.UpToDate(current)
                }
            }
        } catch (e: Exception) {
            Check.Failed("Couldn’t reach GitHub: ${e.message ?: "offline"}")
        }
    }

    /** JSON API path (fallback). */
    private fun viaApi(): Check = try {
        val req = Request.Builder()
            .url("$REPO_API/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return Check.Failed(
                    if (resp.code == 404)
                        "No published release reachable — is the repo public and the release published?"
                    else "GitHub returned ${resp.code}"
                )
            }
            val release = JSONObject(resp.body!!.string())
            val tag = release.optString("tag_name").orEmpty()
            if (tag.isBlank()) Check.Failed("No published release found")
            else fromTag(tag)
        }
    } catch (e: Exception) {
        Check.Failed("Couldn’t reach GitHub: ${e.message ?: "offline"}")
    }

    /**
     * Streams the release APK into app storage so the installer can read it
     * via FileProvider. Deletes any previous download first.
     */
    suspend fun download(
        url: String,
        onProgress: (bytesRead: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // make room for the new one
        val dest = File(dir, DOWNLOADED_APK)

        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .build()
            .newCall(req)
            .execute()
            .use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("download failed: HTTP ${resp.code}")
                val body = resp.body ?: throw RuntimeException("empty download")
                val total = body.contentLength().takeIf { it > 0 } ?: 0L
                var read = 0L
                var lastSent = -1L
                val input = body.byteStream()
                dest.outputStream().use { out ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        val probe = if (total > 0) read * 1000 / total else read shr 17
                        if (probe != lastSent) {
                            lastSent = probe
                            onProgress(read, total)
                        }
                    }
                }
                dest
            }
    }

    /** Opens the system package installer for a downloaded APK. */
    fun install(apk: File): Boolean {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.updates", apk
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** APK name(s) the CI publishes for this phone's primary ABI. */
    private fun assetForAbi(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return when {
            abi.startsWith("arm64") -> "silent-dictate-arm64-v8a"
            abi.startsWith("x86_64") -> "silent-dictate-x86_64"
            abi.startsWith("x86") -> "silent-dictate-x86_64"
            else -> "silent-dictate-universal"
        }
    }

    /** "0.2.1" vs "0.3.0" segment compare; non-numeric suffixes never trigger an install. */
    private fun isOlder(current: String, latest: String): Boolean {
        val c = current.split('.').map { it.trim().takeWhile { ch -> ch.isDigit() } }
            .map { it.toIntOrNull() ?: 0 }
        val l = latest.split('.').map { it.trim().takeWhile { ch -> ch.isDigit() } }
            .map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(c.size, l.size)) {
            val a = c.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a < b
        }
        return false
    }

    /** Android asks for approval before this app may trigger installs. */
    fun canRequestInstalls(): Boolean = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O -> true   // install prompts directly
        else -> context.packageManager.canRequestPackageInstalls()
    }

    /** When approval is missing: take the user to the toggle, they return and retry. */
    fun requestInstallApproval() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val REPO_WEB = "https://github.com/Ember2819/silent-dictate"
        private const val REPO_API = "https://api.github.com/repos/Ember2819/silent-dictate/releases"
        private const val USER_AGENT = "silent-dictate"
        private const val DOWNLOADED_APK = "update.apk"
    }
}
