package com.nerosovereign.vision

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

class ApiClient(context: Context) {
    private val appContextRef = WeakReference(context.applicationContext)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class RuntimeConfig(
        val geminiApiKey: String,
        val openRouterApiKey: String,
        val useOllama: Boolean
    )

    data class ProviderResponse(
        val provider: String,
        val text: String
    )

    companion object {
        private const val TAG = "ApiClient"
        private const val PREFS_NAME = "nero_secure_prefs"
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_OPENROUTER = "openrouter_api_key"
        private const val KEY_OLLAMA_ENABLED = "ollama_enabled"

        fun getSecurePrefs(context: Context): SharedPreferences {
            val appContext = context.applicationContext
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        fun saveRuntimeConfig(
            context: Context,
            geminiApiKey: String,
            openRouterApiKey: String,
            useOllama: Boolean
        ) {
            getSecurePrefs(context).edit()
                .putString(KEY_GEMINI, geminiApiKey.trim())
                .putString(KEY_OPENROUTER, openRouterApiKey.trim())
                .putBoolean(KEY_OLLAMA_ENABLED, useOllama)
                .apply()
        }

        fun loadRuntimeConfig(context: Context): RuntimeConfig {
            val prefs = getSecurePrefs(context)
            return RuntimeConfig(
                geminiApiKey = prefs.getString(KEY_GEMINI, "").orEmpty(),
                openRouterApiKey = prefs.getString(KEY_OPENROUTER, "").orEmpty(),
                useOllama = prefs.getBoolean(KEY_OLLAMA_ENABLED, false)
            )
        }
    }

    suspend fun sendText(prompt: String): Result<ProviderResponse> = withContext(Dispatchers.IO) {
        val context = appContextRef.get() ?: return@withContext Result.failure(
            IllegalStateException("Context is not available")
        )
        val config = loadRuntimeConfig(context)

        runCatching { geminiText(prompt, config.geminiApiKey) }
            .recoverCatching {
                if (config.useOllama) {
                    ollamaText(prompt)
                } else {
                    throw it
                }
            }
            .recoverCatching {
                openRouterText(prompt, config.openRouterApiKey)
            }
    }

    suspend fun analyzeScreen(
        base64Jpeg: String,
        prompt: String = "Analyze this screen and summarize the most important actionable insights."
    ): Result<ProviderResponse> = withContext(Dispatchers.IO) {
        val context = appContextRef.get() ?: return@withContext Result.failure(
            IllegalStateException("Context is not available")
        )
        val config = loadRuntimeConfig(context)

        runCatching { geminiVision(prompt, base64Jpeg, config.geminiApiKey) }
            .recoverCatching {
                if (config.useOllama) {
                    ollamaVision(prompt, base64Jpeg)
                } else {
                    throw it
                }
            }
            .recoverCatching {
                openRouterVision(prompt, base64Jpeg, config.openRouterApiKey)
            }
    }

    private fun geminiText(prompt: String, apiKey: String): ProviderResponse {
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.2))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeJson(request)
        val text = response.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
        require(text.isNotBlank()) { "Gemini returned an empty response" }
        return ProviderResponse(provider = "gemini", text = text)
    }

    private fun geminiVision(prompt: String, base64Jpeg: String, apiKey: String): ProviderResponse {
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", base64Jpeg)
                )
            )
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.2))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeJson(request)
        val text = response.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
        require(text.isNotBlank()) { "Gemini vision returned an empty response" }
        return ProviderResponse(provider = "gemini", text = text)
    }

    private fun ollamaText(prompt: String): ProviderResponse {
        val body = JSONObject()
            .put("model", "llama3.2:3b")
            .put("prompt", prompt)
            .put("stream", false)

        val request = Request.Builder()
            .url("http://127.0.0.1:11434/api/generate")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeJson(request)
        val text = response.optString("response")
        require(text.isNotBlank()) { "Ollama returned an empty response" }
        return ProviderResponse(provider = "ollama", text = text)
    }

    private fun ollamaVision(prompt: String, base64Jpeg: String): ProviderResponse {
        val body = JSONObject()
            .put("model", "llava")
            .put("prompt", prompt)
            .put("stream", false)
            .put("images", JSONArray().put(base64Jpeg))

        val request = Request.Builder()
            .url("http://127.0.0.1:11434/api/generate")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeJson(request)
        val text = response.optString("response")
        require(text.isNotBlank()) { "Ollama vision returned an empty response" }
        return ProviderResponse(provider = "ollama", text = text)
    }

    private fun openRouterText(prompt: String, apiKey: String): ProviderResponse {
        require(apiKey.isNotBlank()) { "OpenRouter API key is missing" }
        val body = JSONObject()
            .put("model", "meta-llama/llama-3.2-3b-instruct:free")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt)
                )
            )

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeJson(request)
        val text = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        require(text.isNotBlank()) { "OpenRouter returned an empty response" }
        return ProviderResponse(provider = "openrouter", text = text)
    }

    private fun openRouterVision(prompt: String, base64Jpeg: String, apiKey: String): ProviderResponse {
        require(apiKey.isNotBlank()) { "OpenRouter API key is missing" }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$base64Jpeg")
                    )
            )

        val body = JSONObject()
            .put("model", "qwen/qwen2.5-vl-72b-instruct:free")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content)
                )
            )

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeJson(request)
        val text = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        require(text.isNotBlank()) { "OpenRouter vision returned an empty response" }
        return ProviderResponse(provider = "openrouter", text = text)
    }

    private fun executeJson(request: Request): JSONObject {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.body?.string().orEmpty()}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("Empty response body")
            return try {
                JSONObject(body)
            } catch (jsonException: Exception) {
                Log.e(TAG, "Failed to parse JSON body: $body", jsonException)
                throw IOException("Invalid JSON response", jsonException)
            }
        }
    }
}
