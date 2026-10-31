package app.silentdictate.ai

import app.silentdictate.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Calls openrouter.ai chat completions to turn raw (noisy) lip-reading output
 * into a plausible sentence. Only a short text excerpt ever leaves the device.
 */
class OpenRouterClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // free models can be slow
        .build()

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Error(val userMessage: String) : Result()
    }

    suspend fun cleanUp(
        key: String,
        model: String,
        rawLipRead: String,
        contextBefore: String
    ): Result = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext Result.Error(
            "No OpenRouter API key set"
        )

        val body = JSONObject().apply {
            put("model", model.ifBlank { Prefs.DEFAULT_OPENROUTER_MODEL })
            put("temperature", 0.2)
            put("max_tokens", 2000) // a minute of dictation plus room for reasoning models behind openrouter/free
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put(
                    "content",
                    "You reconstruct intended messages from imperfect lip-reading transcripts. " +
                        "The raw transcript comes from an on-device lip-reading model that reads " +
                        "silent speech from mouth movements: it is noisy, may contain phonetic " +
                        "fragments, missing words or repeated syllables. " +
                        "Respond with ONLY the most likely intended message, as plain text, " +
                        "with natural capitalisation and punctuation, no quotes, no commentary, " +
                        "no explanations. Stay close to what was read; do not invent extra content. " +
                        "If you truly cannot infer anything, respond with an empty string."
                ))
                put(JSONObject().put("role", "user").put(
                    "content",
                    buildString {
                        if (contextBefore.isNotBlank()) {
                            append("Recent text typed before this (context, may be cut mid-word):\n\"")
                            append(contextBefore.take(300))
                            append("\"\n\n")
                        }
                        append("Raw lip-reading transcript to reconstruct:\n\"")
                        append(rawLipRead.take(MAX_RAW_CHARS))
                        append('"')
                    }
                ))
            })
        }

        try {
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer $key")
                .header("HTTP-Referer", "https://github.com/silent-dictate")
                .header("X-Title", "Silent Dictate")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.Error(errorMessage(resp.code, text))
                }
                val message = JSONObject(text)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                // JSON null must not become the literal string "null"
                val content = if (message == null || message.isNull("content")) ""
                else message.optString("content")
                val cleaned = tidy(content)
                if (cleaned.isBlank()) Result.Error("Cleanup returned nothing")
                else Result.Ok(cleaned)
            }
        } catch (e: Exception) {
            Result.Error("Network error: ${e.message ?: "no connection"}")
        }
    }

    private fun errorMessage(code: Int, body: String): String = when (code) {
        401, 403 -> "OpenRouter rejected the API key"
        402 -> "OpenRouter: this model needs credits — try openrouter/free"
        404 -> "OpenRouter couldn’t find that model"
        429 -> "OpenRouter is rate-limiting — try again in a moment"
        else -> {
            val detail = try {
                JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (detail.isNotBlank()) "OpenRouter: ${detail.take(90)}" else "OpenRouter error $code"
        }
    }

    /** Trim, and drop a single pair of wrapping quotes; keep the sentence's own punctuation. */
    private fun tidy(raw: String): String {
        var t = raw.trim()
        val pairs = listOf('"' to '"', '“' to '”', '\'' to '\'')
        for ((open, close) in pairs) {
            if (t.length >= 2 && t.first() == open && t.last() == close) {
                t = t.substring(1, t.length - 1).trim()
                break
            }
        }
        return t
    }

    companion object {
        /** ~60 s of dictation is well under this; it only guards against runaway input. */
        private const val MAX_RAW_CHARS = 4000
    }
}
