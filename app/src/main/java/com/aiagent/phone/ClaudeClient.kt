package com.aiagent.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.math.max

/**
 * The agent's "brain". Name kept as ClaudeClient so MainActivity compiles unchanged,
 * but it now talks to Google Gemini (generateContent, text + optional screenshot).
 */
object ClaudeClient {
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    /** Tried in order when a model returns 404 (retired). The first one that works is remembered. */
    private val FALLBACK_MODELS = listOf("gemini-3.5-flash", "gemini-flash-latest")
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

    @Volatile
    private var activeModel = DEFAULT_MODEL

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed class ApiResult {
        data class Ok(val text: String) : ApiResult()
        data class Err(val message: String) : ApiResult()
    }

    private class Raw(val code: Int, val body: String)

    /** Trims whitespace/newlines/quotes and drops any non-printable or non-ASCII character. */
    fun sanitizeKey(raw: String): String =
        raw.trim().filter { it.code in 33..126 && it != '"' && it != '\'' }

    /**
     * Picks the Gemini key (AIza...) out of whatever is passed in - a single key or the whole
     * combined field such as "gsk_..., AIza...". Falls back to the first non-Groq, non-Anthropic token.
     */
    fun resolveGeminiKey(raw: String): String {
        val tokens = raw
            .split(Regex("[,;\\s]+"))
            .flatMap { it.split(Regex("(?=gsk_|sk-ant-|AIza)")) }
            .map { sanitizeKey(it) }
            .filter { it.length >= 8 }
        return tokens.firstOrNull { it.startsWith("AIza") }
            ?: tokens.firstOrNull { !it.startsWith("gsk_") && !it.startsWith("sk-ant-") }
            ?: ""
    }

    /** Text-only request (used by MainActivity's "Test Voice & API"). */
    suspend fun ask(
        apiKey: String,
        system: String,
        user: String,
        maxTokens: Int = 1024
    ): ApiResult = generate(apiKey, system, user, null, maxTokens, jsonOutput = false)

    /** Multimodal request: text + optional JPEG screenshot (Base64, NO_WRAP). Reply is forced to JSON. */
    suspend fun askWithVision(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String?,
        maxTokens: Int = 600
    ): ApiResult = generate(apiKey, systemPrompt, userPrompt, screenshotBase64, maxTokens, jsonOutput = true)

    // ------------------------------------------------------------------ core

    private suspend fun generate(
        apiKey: String,
        system: String,
        user: String,
        imageB64: String?,
        maxTokens: Int,
        jsonOutput: Boolean
    ): ApiResult = withContext(Dispatchers.IO) {
        val key = resolveGeminiKey(apiKey)
        if (key.isEmpty()) {
            return@withContext ApiResult.Err("No Google Gemini key (AIza...) found in the API key field")
        }
        try {
            val models = (listOf(activeModel, DEFAULT_MODEL) + FALLBACK_MODELS).distinct()
            var last: Raw? = null
            var usedModel = activeModel

            for (model in models) {
                usedModel = model
                val kind = if (imageB64 != null) "text+image ${imageB64.length / 1024} KB" else "text"
                AgentLog.add("API", "Gemini $model ($kind)")
                val body = buildBody(model, system, user, imageB64, maxTokens, jsonOutput)
                var raw = post(key, model, body)

                if (raw.code == 429) {
                    val wait = retryDelaySeconds(raw.body)
                    if (wait in 1..30) {
                        AgentLog.add("ERROR", "Gemini rate limit (HTTP 429) - waiting ${wait}s and retrying")
                        delay(wait * 1000L + 500L)
                        raw = post(key, model, body)
                    }
                }
                if (raw.code == 500 || raw.code == 503 || raw.code == 504) {
                    AgentLog.add("ERROR", "Gemini HTTP ${raw.code} (server busy), retrying once...")
                    delay(1500)
                    raw = post(key, model, body)
                }
                last = raw
                if (raw.code == 404) {
                    AgentLog.add("ERROR", "Model $model not available (HTTP 404) - trying next model")
                    continue
                }
                break
            }

            val raw = last ?: return@withContext ApiResult.Err("No response from Gemini")
            if (raw.code !in 200..299) return@withContext ApiResult.Err(describeHttpError(raw))

            if (usedModel != activeModel) {
                activeModel = usedModel
                AgentLog.add("API", "Using model $usedModel from now on")
            }
            extractText(raw.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            ApiResult.Err("No internet connection or DNS failure (check Wi-Fi/VPN): ${e.message}")
        } catch (e: SocketTimeoutException) {
            ApiResult.Err("Request timed out (check internet/VPN): ${e.message}")
        } catch (e: SSLException) {
            ApiResult.Err("TLS/SSL error (check VPN/proxy/date & time): ${e.message}")
        } catch (e: IOException) {
            ApiResult.Err("Network error: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Exception) {
            ApiResult.Err("Unexpected error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun buildBody(
        model: String,
        system: String,
        user: String,
        imageB64: String?,
        maxTokens: Int,
        jsonOutput: Boolean
    ): String {
        val parts = JSONArray()
        if (imageB64 != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", imageB64)
                )
            )
        }
        parts.put(JSONObject().put("text", user))

        val gen = JSONObject().put("temperature", 0.2)
        if (model.startsWith("gemini-2.5")) {
            // 2.5 Flash "thinks" by default and thinking tokens eat maxOutputTokens -> empty replies.
            // Budget 0 disables thinking: faster and the whole budget goes to the answer.
            gen.put("maxOutputTokens", maxTokens)
            gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        } else {
            // Newer models may not accept thinkingBudget=0, so leave room for thinking instead.
            gen.put("maxOutputTokens", max(maxTokens, 4096))
        }
        if (jsonOutput) gen.put("responseMimeType", "application/json")

        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gen)
            .toString()
    }

    private fun post(key: String, model: String, json: String): Raw {
        val req = Request.Builder()
            .url("$BASE_URL$model:generateContent?key=$key")
            .header("content-type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            return Raw(resp.code, body)
        }
    }

    private fun extractText(body: String): ApiResult {
        return try {
            val root = JSONObject(body)
            val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            if (block.isNotEmpty()) return ApiResult.Err("Gemini blocked the request ($block)")

            val cand = root.optJSONArray("candidates")?.optJSONObject(0)
                ?: return ApiResult.Err("Gemini returned no candidates")
            val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.optBoolean("thought", false)) continue
                    sb.append(p.optString("text"))
                }
            }
            val text = sb.toString().trim()
            if (text.isEmpty()) {
                val reason = cand.optString("finishReason")
                ApiResult.Err("Empty response from Gemini (finishReason=$reason)")
            } else {
                AgentLog.add("API", "OK (${text.length} chars, model=$activeModel)")
                ApiResult.Ok(text)
            }
        } catch (e: Exception) {
            ApiResult.Err("Could not read Gemini response: ${e.message}")
        }
    }

    /** Reads "retryDelay": "17s" from a 429 error body. */
    private fun retryDelaySeconds(body: String): Int {
        val m = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(body) ?: return 10
        return m.groupValues[1].toIntOrNull() ?: 10
    }

    private fun shortError(body: String): String {
        return try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty().take(200)
        } catch (e: Exception) {
            body.take(200)
        }
    }

    private fun describeHttpError(raw: Raw): String {
        val msg = shortError(raw.body).ifBlank { raw.body.take(200) }
        val lower = msg.lowercase()
        val hint = when {
            lower.contains("location is not supported") -> "Gemini is not available in your country - use a VPN"
            lower.contains("api key not valid") -> "invalid Gemini API key"
            raw.code == 400 -> "bad request"
            raw.code == 401 -> "invalid API key"
            raw.code == 403 -> "key restricted / API not enabled / region blocked (try VPN)"
            raw.code == 404 -> "model not found"
            raw.code == 413 -> "request too large"
            raw.code == 429 -> "free-tier quota or rate limit exceeded - wait a minute"
            raw.code == 500 || raw.code == 503 || raw.code == 504 -> "Google server busy"
            else -> "error"
        }
        return "HTTP ${raw.code} ($hint): $msg"
    }
}
