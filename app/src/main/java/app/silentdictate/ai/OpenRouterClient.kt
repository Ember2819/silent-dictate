package app.silentdictate.ai

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
        .readTimeout(30, TimeUnit.SECONDS)
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
            put("model", model.ifBlank { "meta-llama/llama-3.2-3b-instruct:free" })
            put("temperature", 0.2)
            put("max_tokens", 200)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put(
                    "content",
                    "You reconstruct intended messages from imperfect lip-reading transcripts. " +
                        "The raw transcript comes from an on-device lip-reading model that reads " +
                        "silent speech from mouth movements: it is noisy, may contain phonetic " +
                        "fragments, missing words or repeated syllables. " +
                        "Respond with ONLY the most likely intended message, as plain text, " +
                        "punctuation included, no quotes, no commentary, no explanations. " +
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
                        append(rawLipRead.take(600))
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
                    val msg = when (resp.code) {
                        401, 403 -> "OpenRouter rejected the API key"
                        402 -> "OpenRouter: model needs credits, pick a free one"
                        else -> "OpenRouter error ${resp.code}"
                    }
                    return@withContext Result.Error(msg)
                }
                val content = JSONObject(text)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                if (content.isBlank()) {
                    Result.Error("Cleanup returned nothing")
                } else {
                    Result.Ok(content.trim().trim('"', '’', '“', '”', '.', ' '))
                }
            }
        } catch (e: Exception) {
            Result.Error("Network error: ${e.message ?: "no connection"}")
        }
    }
}
