package com.friday.actionagent.engines.gemma

import com.friday.actionagent.engines.hub.OpenAiCompatClient
import com.friday.actionagent.engines.memory.ChatTurn
import org.json.JSONArray
import org.json.JSONObject

object GemmaEngine {
    const val ENGINE_NAME = "Gemini"

    fun callGemini(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        temp: Double
    ): Pair<Boolean, String> {
        if (apiKey.isBlank()) return false to "Gemini key missing"
        // Always try active current Gemini models (never deprecated gemini-2.0-flash!)
        val modelsToTry = listOf(
            model.ifBlank { "gemini-2.5-flash" },
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3.8-flash"
        ).filter { !it.contains("2.0-flash") }.distinct()
        var lastErr = ""

        val contents = JSONArray()
        for (h in history.takeLast(6)) {
            val r = if (h.role == "user") "user" else "model"
            contents.put(JSONObject().put("role", r).put("parts", JSONArray().put(JSONObject().put("text", h.text))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userPrompt))))

        for (m in modelsToTry) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$apiKey"
            val payload = JSONObject().apply {
                put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                put("contents", contents)
                put("generationConfig", JSONObject().put("temperature", temp))
            }
            val (ok, body) = OpenAiCompatClient.postRaw(url, emptyMap(), payload.toString())
            if (ok) {
                try {
                    val txt = JSONObject(body)
                        .getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                        .getJSONObject(0).getString("text")
                    return true to txt
                } catch (_: Exception) {
                    lastErr = "Gemini parse error"
                }
            } else {
                lastErr = "Gemini ($m): ${body.take(120)}"
                if (!body.contains("404")) break
            }
        }
        return false to lastErr
    }
}
