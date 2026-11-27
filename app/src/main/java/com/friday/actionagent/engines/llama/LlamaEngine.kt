package com.friday.actionagent.engines.llama

import com.friday.actionagent.engines.deepseek.DeepSeekEngine
import com.friday.actionagent.engines.hub.OpenAiCompatClient
import com.friday.actionagent.engines.memory.ChatTurn
import com.friday.actionagent.engines.offline.EmbeddedOfflineBrain

object LlamaEngine {
    private val localCandidates = listOf(
        EmbeddedOfflineBrain.EMBEDDED_CHAT_URL,
        "http://127.0.0.1:8080/v1/chat/completions",
        "http://127.0.0.1:11434/v1/chat/completions"
    )

    fun detectRunningLocalServer(customUrl: String): String? {
        val list = buildList {
            addAll(localCandidates)
            if (customUrl.isNotBlank()) add(customUrl.trim())
        }.distinct()

        for (chatUrl in list) {
            val base = chatUrl.substringBefore("/v1/")
            if (OpenAiCompatClient.quickPing("$base/health") || OpenAiCompatClient.quickPing("$base/v1/models")) {
                return chatUrl
            }
        }
        return null
    }

    /**
     * Calls embedded/local llama-server with automatic Gemma-2 system-role compatibility!
     */
    fun callLocalOffline(
        chatUrl: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        temp: Double
    ): Pair<Boolean, String> {
        val isGemma = EmbeddedOfflineBrain.loadedModelName.lowercase().contains("gemma")

        // Gemma-2 GGUF templates crash with HTTP 500 if role="system" is sent!
        if (isGemma) {
            val (ok, raw) = OpenAiCompatClient.chatNoSystemRole(chatUrl, "local", systemPrompt, history, userPrompt, temp, 360)
            if (!ok) return false to raw
            val (_, clean) = DeepSeekEngine.parseThinkingOutput(raw)
            return true to clean.ifBlank { raw }
        }

        val (ok, raw) = OpenAiCompatClient.chat(chatUrl, "", "local", systemPrompt, history, userPrompt, temp, 360)
        if (ok) {
            val (_, clean) = DeepSeekEngine.parseThinkingOutput(raw)
            return true to clean.ifBlank { raw }
        }

        // Auto-retry WITHOUT system role if any GGUF chat template rejects system role!
        val (ok2, raw2) = OpenAiCompatClient.chatNoSystemRole(chatUrl, "local", systemPrompt, history, userPrompt, temp, 360)
        if (ok2) {
            val (_, clean) = DeepSeekEngine.parseThinkingOutput(raw2)
            return true to clean.ifBlank { raw2 }
        }
        return false to raw2
    }

    fun callGroqFree(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        temp: Double
    ): Pair<Boolean, String> {
        if (apiKey.isBlank()) return false to "Groq key missing"
        val m = model.ifBlank { "llama-3.3-70b-versatile" }
        return OpenAiCompatClient.chat("https://api.groq.com/openai/v1/chat/completions", apiKey, m, systemPrompt, history, userPrompt, temp)
    }
}
