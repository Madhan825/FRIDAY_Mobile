package com.friday.actionagent.engines.hub

import com.friday.actionagent.engines.memory.ChatTurn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OpenAiCompatClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(18, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(350, TimeUnit.MILLISECONDS)
        .readTimeout(450, TimeUnit.MILLISECONDS)
        .build()

    fun quickPing(url: String): Boolean {
        return try {
            val req = Request.Builder().url(url).get().build()
            pingClient.newCall(req).execute().use { it.code in 200..499 }
        } catch (_: Exception) {
            false
        }
    }

    fun postRaw(url: String, headers: Map<String, String>, jsonBody: String): Pair<Boolean, String> {
        return try {
            val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            val builder = Request.Builder().url(url).post(body)
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) true to text else false to "HTTP ${resp.code}: ${text.take(220)}"
            }
        } catch (e: Exception) {
            false to "Network Error: ${e.message}"
        }
    }

    fun chat(
        url: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int = 512
    ): Pair<Boolean, String> {
        val msgs = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            for (h in history.takeLast(6)) {
                val r = if (h.role == "user") "user" else "assistant"
                put(JSONObject().put("role", r).put("content", h.text.take(600)))
            }
            put(JSONObject().put("role", "user").put("content", userPrompt))
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", msgs)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
        }
        val headers = mutableMapOf<String, String>()
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
        val (ok, body) = postRaw(url, headers, payload.toString())
        if (!ok) return false to body
        return parseChoiceText(body)
    }

    /**
     * Strictly alternates user/assistant WITHOUT any system role (required for Gemma-2 GGUF templates!).
     */
    fun chatNoSystemRole(
        url: String,
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int = 360
    ): Pair<Boolean, String> {
        val msgs = JSONArray()
        val combinedUser = "Instructions:\n$systemPrompt\n\nUser: $userPrompt"
        val recent = history.takeLast(4)
        var lastRole = ""
        for (h in recent) {
            val r = if (h.role == "user") "user" else "assistant"
            if (r == lastRole) continue
            if (msgs.length() == 0 && r != "user") continue
            msgs.put(JSONObject().put("role", r).put("content", h.text.take(400)))
            lastRole = r
        }
        if (lastRole == "user" && msgs.length() > 0) {
            msgs.remove(msgs.length() - 1)
        }
        msgs.put(JSONObject().put("role", "user").put("content", combinedUser))

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", msgs)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
        }
        val (ok, body) = postRaw(url, emptyMap(), payload.toString())
        if (!ok) return false to body
        return parseChoiceText(body)
    }

    private fun parseChoiceText(body: String): Pair<Boolean, String> {
        return try {
            val text = JSONObject(body)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            true to text
        } catch (_: Exception) {
            false to "Parse error"
        }
    }
}
