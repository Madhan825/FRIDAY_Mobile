package com.friday.actionagent.engines.deepseek

import com.friday.actionagent.engines.hub.OpenAiCompatClient
import com.friday.actionagent.engines.memory.ChatTurn

object DeepSeekEngine {
    const val ENGINE_NAME = "DeepSeek-R1"

    fun parseThinkingOutput(raw: String): Pair<String, String> {
        val regex = Regex("""<think>([\s\S]*?)</think>""", RegexOption.IGNORE_CASE)
        val match = regex.find(raw)
        val thought = match?.groupValues?.get(1)?.trim() ?: ""
        val answer = raw.replace(regex, "").trim()
        return thought to answer
    }

    fun callViaOpenRouter(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        temp: Double
    ): Pair<Boolean, String> {
        if (apiKey.isBlank()) return false to "OpenRouter key missing"
        val m = model.ifBlank { "deepseek/deepseek-r1:free" }
        val (ok, raw) = OpenAiCompatClient.chat("https://openrouter.ai/api/v1/chat/completions", apiKey, m, systemPrompt, history, userPrompt, temp)
        if (!ok) return false to raw
        val (_, clean) = parseThinkingOutput(raw)
        return true to clean.ifBlank { raw }
    }
}
