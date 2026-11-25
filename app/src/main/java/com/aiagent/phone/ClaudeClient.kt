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

/** Minimal client for the Anthropic Messages API. */
object ClaudeClient {
    const val DEFAULT_MODEL = "claude-sonnet-5-5"
    const val FALLBACK_MODEL = "claude-sonnet-4-6"
    private const val URL = "https://api.anthropic.com/v1/messages"

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

    suspend fun ask(
        apiKey: String,
        system: String,
        user: String,
        maxTokens: Int = 1024
    ): ApiResult = withContext(Dispatchers.IO) {
        val key = sanitizeKey(apiKey)
        if (key.isEmpty()) return@withContext ApiResult.Err("API key is empty")
        try {
            var model = activeModel
            AgentLog.add("API", "POST /v1/messages model=$model")
            var raw = post(key, model, system, user, maxTokens)

            if (raw.code == 404 && model != FALLBACK_MODEL) {
                AgentLog.add("ERROR", "HTTP 404 for model $model (${shortError(raw.body)}). Falling back to $FALLBACK_MODEL")
                activeModel = FALLBACK_MODEL
                model = FALLBACK_MODEL
                raw = post(key, model, system, user, maxTokens)
            }
            if (raw.code == 500 || raw.code == 502 || raw.code == 503 || raw.code == 529) {
                AgentLog.add("ERROR", "HTTP ${raw.code} (server busy), retrying once...")
                delay(1500)
                raw = post(key, model, system, user, maxTokens)
            }

            if (raw.code in 200..299) {
                val text = extractText(raw.body)
                if (text.isBlank()) {
                    ApiResult.Err("Empty response from API")
                } else {
                    AgentLog.add("API", "OK (${text.length} chars, model=$model)")
                    ApiResult.Ok(text)
                }
            } else {
                ApiResult.Err(describeHttpError(raw))
            }
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

    private fun post(key: String, model: String, system: String, user: String, maxTokens: Int): Raw {
        val json = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", user))
            )
        val req = Request.Builder()
            .url(URL)
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            return Raw(resp.code, body)
        }
    }

    private fun extractText(body: String): String {
        return try {
            val content = JSONObject(body).optJSONArray("content") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
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
        val hint = when (raw.code) {
            400 -> "bad request / insufficient credit balance"
            401 -> "invalid API key"
            403 -> "key not permitted or region blocked (try VPN)"
            404 -> "model or endpoint not found"
            413 -> "request too large"
            429 -> "rate limit or quota exceeded"
            500, 502, 503 -> "Anthropic server error"
            529 -> "Anthropic API overloaded"
            else -> "error"
        }
        return "HTTP ${raw.code} ($hint): $msg"
    }
}
