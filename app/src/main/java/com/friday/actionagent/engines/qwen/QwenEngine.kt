package com.friday.actionagent.engines.qwen

import com.friday.actionagent.engines.hub.OpenAiCompatClient
import com.friday.actionagent.engines.memory.ChatTurn

object QwenEngine {
    const val ENGINE_NAME = "OpenAI"

    fun callViaOpenAiCompat(
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        temp: Double
    ): Pair<Boolean, String> {
        if (apiKey.isBlank()) return false to "API key missing"
        return OpenAiCompatClient.chat(endpoint, apiKey, model.ifBlank { "gpt-4o-mini" }, systemPrompt, history, userPrompt, temp)
    }

    /**
     * Built-in Zero-Key Free Cloud AI Engine (Always auto-connected when internet is ON!)
     */
    fun callFreeCloudDefault(
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        temp: Double
    ): Pair<Boolean, String> {
        return OpenAiCompatClient.chat(
            url = "https://text.pollinations.ai/openai",
            apiKey = "",
            model = "openai",
            systemPrompt = systemPrompt,
            history = history,
            userPrompt = userPrompt,
            temperature = temp,
            maxTokens = 450
        )
    }
}
