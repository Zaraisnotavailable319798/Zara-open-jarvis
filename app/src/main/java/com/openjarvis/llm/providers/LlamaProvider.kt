package com.openjarvis.llm.providers

import com.openjarvis.llm.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LlamaProvider(
    private val modelPath: String,
    private val modelName: String = "phi3-mini"
) : LLMProvider {

    override val name: String = "Local Llama"
    override val baseUrl: String = "http://localhost:8080"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun complete(
        systemPrompt: String,
        userMessage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = JSONObject().apply {
                put("model", modelName)
                put(
                    "messages",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("role", "system")
                                put("content", systemPrompt)
                            }
                        )
                        put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", userMessage)
                            }
                        )
                    }
                )
                put("stream", false)
                put("max_tokens", 1024)
                put("temperature", 0.1)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .addHeader("Content-Type", "application/json")
                .post(
                    requestBody.toRequestBody(
                        "application/json".toMediaType()
                    )
                )
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                    ?: throw Exception("Empty response")

                val json = JSONObject(body)

                when {
                    json.has("choices") -> {
                        json.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                    }

                    json.has("error") -> {
                        throw Exception(
                            json.getJSONObject("error")
                                .getString("message")
                        )
                    }

                    else -> {
                        throw Exception("Unknown response: $body")
                    }
                }
            }
        }
    }

    override suspend fun testConnection(): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val startTime = System.currentTimeMillis()

                val requestBody = JSONObject().apply {
                    put("model", modelName)
                    put(
                        "messages",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", "ping")
                                }
                            )
                        }
                    )
                    put("max_tokens", 1)
                }.toString()

                val request = Request.Builder()
                    .url("$baseUrl/v1/chat/completions")
                    .addHeader("Content-Type", "application/json")
                    .post(
                        requestBody.toRequestBody(
                            "application/json".toMediaType()
                        )
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}")
                    }
                }

                System.currentTimeMillis() - startTime
            }
        }

    companion object {
        const val DEFAULT_HOST = "localhost"
        const val DEFAULT_PORT = 8080
    }
}
