package com.friday.actionagent.engines.hub

import android.content.Context
import com.friday.actionagent.core.FridayAccessibilityService
import com.friday.actionagent.core.SecureVault
import com.friday.actionagent.engines.action.OfflineActionEngine
import com.friday.actionagent.engines.deepseek.DeepSeekEngine
import com.friday.actionagent.engines.gemma.GemmaEngine
import com.friday.actionagent.engines.llama.LlamaEngine
import com.friday.actionagent.engines.memory.PersonalMemoryEngine
import com.friday.actionagent.engines.nlp.ParsedIntent
import com.friday.actionagent.engines.nlp.SmartIntentEngine
import com.friday.actionagent.engines.offline.EmbeddedOfflineBrain
import com.friday.actionagent.engines.qwen.QwenEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

object AutoBrainRouter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var lastConnectedEngine: String = "FRIDAY"
        private set

    @Volatile
    var lastBrainError: String = ""
        private set

    // Single-Pass Conversational + Universal Tool-Calling Prompt!
    // One fast pass handles BOTH natural chat AND thousands of natural phone actions!
    private val UNIVERSAL_BRAIN_PROMPT = """
You are FRIDAY, a warm, intelligent personal AI assistant on Android.
Respond naturally, concisely, and helpfully in the user's language (Tamil, Tanglish, or English).
If the user naturally asks to perform a phone action, include ONE tool tag at the end of your short friendly reply:
- YouTube search: [[TOOL:youtube|clean search query]]
- Web/Chrome search: [[TOOL:chrome|clean search query]]
- Open any app: [[TOOL:open_app|app name]]
- Phone call: [[TOOL:call|name or number]]
- Save contact: [[TOOL:save_contact|name|number]]
- Flashlight: [[TOOL:torch|on or off]]
- Volume percent: [[TOOL:volume|0-100]]
- Maps place: [[TOOL:maps|place name]]
- Scroll screen: [[TOOL:scroll|down or up]]
- Click button on screen: [[TOOL:click|visible text]]
If the user is just chatting, asking questions, math, advice, or coding, DO NOT output any [[TOOL:...]] tag—just answer directly and naturally!
""".trimIndent()

    fun stopAll() {
        isRunning = false
        activeJob?.cancel()
        activeJob = null
        FridayAccessibilityService.instance?.overlay?.hideCursorOnly()
    }

    fun autoQueryLLM(
        context: Context,
        systemPrompt: String,
        userPrompt: String,
        temp: Double,
        includeHistory: Boolean = true
    ): Pair<Boolean, String> {
        val prefs = SecureVault.prefs(context)
        val localUrl = prefs.getString("local_url", "") ?: ""
        val preferred = prefs.getString("preferred_brain", "auto") ?: "auto"
        val allowFreeCloud = prefs.getBoolean("free_cloud_enabled", true)
        val history = if (includeHistory) PersonalMemoryEngine.activeConversation.dropLast(1) else emptyList()
        val errors = mutableListOf<String>()

        // 1. Embedded / Local Offline GGUF Brain (ZERO API Key, Always-On!)
        if (preferred == "auto" || preferred == "offline") {
            val liveLocal = LlamaEngine.detectRunningLocalServer(localUrl)
            if (liveLocal != null) {
                val (ok, resp) = LlamaEngine.callLocalOffline(liveLocal, systemPrompt, history, userPrompt, temp)
                if (ok) {
                    val mName = EmbeddedOfflineBrain.loadedModelName.ifBlank { "Offline AI" }
                    lastConnectedEngine = "Offline • $mName"
                    lastBrainError = ""
                    return true to resp
                } else {
                    errors.add("Offline: $resp")
                }
            }
            if (preferred == "offline") {
                lastBrainError = "Offline .gguf மாடல் இணைக்கப்படவில்லை."
                return false to lastBrainError
            }
        }

        // 2. User's Own Online Keys (Gemini -> Groq -> OpenRouter DeepSeek -> OpenAI)
        val gemKey = prefs.getString("gemini_key", "")?.trim() ?: ""
        if (gemKey.isNotEmpty() && (preferred == "auto" || preferred == "gemini")) {
            val (ok, resp) = GemmaEngine.callGemini(gemKey, prefs.getString("gemini_model", "gemini-2.5-flash") ?: "", systemPrompt, history, userPrompt, temp)
            if (ok) {
                lastConnectedEngine = GemmaEngine.ENGINE_NAME
                lastBrainError = ""
                return true to resp
            } else {
                errors.add(resp)
            }
        }

        val groqKey = prefs.getString("groq_key", "")?.trim() ?: ""
        if (groqKey.isNotEmpty() && (preferred == "auto" || preferred == "groq")) {
            val (ok, resp) = LlamaEngine.callGroqFree(groqKey, prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "", systemPrompt, history, userPrompt, temp)
            if (ok) {
                lastConnectedEngine = "Groq Llama 70B"
                lastBrainError = ""
                return true to resp
            } else {
                errors.add("Groq: $resp")
            }
        }

        val orKey = prefs.getString("openrouter_key", "")?.trim() ?: ""
        if (orKey.isNotEmpty() && (preferred == "auto" || preferred == "deepseek")) {
            val (ok, resp) = DeepSeekEngine.callViaOpenRouter(orKey, prefs.getString("openrouter_model", "deepseek/deepseek-r1:free") ?: "", systemPrompt, history, userPrompt, temp)
            if (ok) {
                lastConnectedEngine = DeepSeekEngine.ENGINE_NAME
                lastBrainError = ""
                return true to resp
            } else {
                errors.add("OpenRouter: $resp")
            }
        }

        val oaiKey = prefs.getString("openai_key", "")?.trim() ?: ""
        if (oaiKey.isNotEmpty() && (preferred == "auto" || preferred == "openai")) {
            val (ok, resp) = QwenEngine.callViaOpenAiCompat("https://api.openai.com/v1/chat/completions", oaiKey, prefs.getString("openai_model", "gpt-4o-mini") ?: "", systemPrompt, history, userPrompt, temp)
            if (ok) {
                lastConnectedEngine = "OpenAI"
                lastBrainError = ""
                return true to resp
            } else {
                errors.add("OpenAI: $resp")
            }
        }

        // 3. Built-In Free Cloud Default Engine (ZERO API Key Needed! Always Auto-Connected!)
        if (allowFreeCloud && (preferred == "auto" || preferred == "free_cloud")) {
            val (ok, resp) = QwenEngine.callFreeCloudDefault(systemPrompt, history, userPrompt, temp)
            if (ok && resp.isNotBlank()) {
                lastConnectedEngine = "FRIDAY Free Cloud"
                lastBrainError = ""
                return true to resp
            } else {
                errors.add("FreeCloud: $resp")
            }
        }

        lastConnectedEngine = "Offline Core"
        lastBrainError = errors.joinToString(" | ")
        return false to lastBrainError
    }

    fun executeUnified(
        context: Context,
        input: String,
        attachedFileName: String?,
        attachedFileText: String?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onFinish: (String) -> Unit
    ) {
        stopAll()
        isRunning = true

        activeJob = scope.launch {
            try {
                // Step 1: Instant (<5ms) direct action check
                val fast = SmartIntentEngine.understandFast(context, input)
                if (fast.intent != "chat") {
                    onStatus("Working...")
                    val result = OfflineActionEngine.executeParsedIntent(context, fast, onLog)
                    if (result != null) {
                        lastConnectedEngine = "FRIDAY Action"
                        onFinish(result)
                        return@launch
                    }
                }

                // Step 2: Instant offline math check
                tryOfflineMath(input)?.let { mathAns ->
                    lastConnectedEngine = "FRIDAY Math"
                    onFinish(mathAns)
                    return@launch
                }

                // Step 3: Ensure persistent offline brain is running if models exist
                if (!EmbeddedOfflineBrain.isRunning() && EmbeddedOfflineBrain.scanAllGgufFiles(context).isNotEmpty()) {
                    onStatus("Connecting Offline AI...")
                    EmbeddedOfflineBrain.autoStartPersistent(context)
                }

                // Step 4: Single-Pass Universal Brain (Natural Chat + Tool Calling!)
                onStatus("Thinking...")
                val mem = PersonalMemoryEngine.buildMemoryContextPrompt(context)
                val sys = if (mem.isNotBlank()) "$UNIVERSAL_BRAIN_PROMPT\n\n$mem" else UNIVERSAL_BRAIN_PROMPT
                val fullPrompt = if (attachedFileText.isNullOrBlank()) input
                else "$input\n\nAttached file (${attachedFileName ?: "file"}):\n${attachedFileText.take(14000)}"

                val (ok, rawReply) = autoQueryLLM(context, sys, fullPrompt, 0.55, includeHistory = true)
                if (ok && rawReply.isNotBlank()) {
                    val toolRegex = Regex("""\[\[TOOL:([^\]|]+)(?:\|([^\]|]*))?(?:\|([^\]|]*))?\]\]""")
                    val match = toolRegex.find(rawReply)
                    val cleanText = rawReply.replace(toolRegex, "").trim()

                    if (match != null) {
                        val toolName = match.groupValues[1].trim().lowercase(Locale.ROOT)
                        val arg1 = match.groupValues.getOrNull(2)?.trim() ?: ""
                        val arg2 = match.groupValues.getOrNull(3)?.trim() ?: ""
                        val toolIntent = mapAiToolToIntent(toolName, arg1, arg2)
                        val execMsg = OfflineActionEngine.executeParsedIntent(context, toolIntent, onLog)
                        val combined = listOf(cleanText, execMsg ?: "").filter { it.isNotBlank() }.joinToString("\n")
                        onFinish(combined.ifBlank { "✅ முடிந்தது!" })
                    } else {
                        onFinish(cleanText.ifBlank { rawReply.trim() })
                    }
                } else {
                    onFinish(fallbackFriendlyMessage(context, input, attachedFileName, attachedFileText))
                }
            } catch (_: CancellationException) {
                // Silent clean cancellation (NEVER show "StandaloneCoroutine was cancelled"!)
            } catch (e: Exception) {
                onFinish("⚠️ பிழை: ${e.message}")
            } finally {
                isRunning = false
                FridayAccessibilityService.instance?.overlay?.hideCursorOnly()
            }
        }
    }

    private fun mapAiToolToIntent(name: String, arg1: String, arg2: String): ParsedIntent {
        return when (name) {
            "youtube", "youtube_search" -> ParsedIntent("youtube_search", app = "YouTube", query = arg1)
            "chrome", "web", "search" -> ParsedIntent("chrome_search", app = "Chrome", query = arg1)
            "open_app", "app" -> ParsedIntent("open_app", app = arg1)
            "call", "call_phone" -> {
                val digits = arg1.replace(Regex("""[^\d+]"""), "")
                if (digits.length >= 6) ParsedIntent("call_phone", phoneNumber = digits)
                else ParsedIntent("call_phone", contactName = arg1)
            }
            "save_contact" -> ParsedIntent("save_contact", contactName = arg1, phoneNumber = arg2)
            "torch", "flashlight" -> ParsedIntent("flashlight", flag = !arg1.equals("off", ignoreCase = true))
            "volume" -> ParsedIntent("volume", volume = arg1.toIntOrNull()?.coerceIn(0, 100) ?: 50)
            "maps" -> ParsedIntent("maps", query = arg1)
            "scroll" -> ParsedIntent("scroll", direction = arg1.ifBlank { "down" })
            "click" -> ParsedIntent("click", target = arg1)
            else -> ParsedIntent("chat")
        }
    }

    private fun tryOfflineMath(prompt: String): String? {
        var s = prompt.lowercase(Locale.ROOT)
        mapOf(
            "ஒன்" to "1", "ஒன்னு" to "1", "டூ" to "2", "ரெண்டு" to "2", "த்ரீ" to "3", "மூணு" to "3",
            "ஃபோர்" to "4", "நாலு" to "4", "ஃபைவ்" to "5", "அஞ்சு" to "5", "பிளஸ்" to "+", "plus" to "+",
            "மைனஸ்" to "-", "minus" to "-", "இன்டு" to "*", "into" to "*"
        ).forEach { (k, v) -> s = s.replace(k, " $v ") }
        val m = Regex("""^[^\d]{0,12}(\d+(?:\.\d+)?)\s*([+\-*/x×])\s*(\d+(?:\.\d+)?)[^\d]{0,16}$""").find(s.trim()) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val b = m.groupValues[3].toDoubleOrNull() ?: return null
        val r = when (m.groupValues[2]) {
            "+" -> a + b
            "-" -> a - b
            "*", "x", "×" -> a * b
            "/" -> if (b != 0.0) a / b else return "0-ஆல் வகுக்க முடியாது."
            else -> return null
        }
        return if (r == r.toLong().toDouble()) r.toLong().toString() else String.format(Locale.US, "%.2f", r)
    }

    private fun fallbackFriendlyMessage(context: Context, prompt: String, fileName: String?, fileText: String?): String {
        val low = prompt.lowercase(Locale.ROOT).trim()
        if (!fileText.isNullOrBlank()) {
            return "📄 ${fileName ?: "File"}:\n${fileText.take(450).trim()}"
        }
        if (low in listOf("hi", "hello", "hey", "friday", "வணக்கம்", "ஹாய்", "ஹலோ")) {
            return "வணக்கம்! நான் FRIDAY. என்ன உதவி வேண்டும்?"
        }
        val models = EmbeddedOfflineBrain.scanAllGgufFiles(context)
        return if (models.isEmpty()) {
            "இணையமும் ஆஃப்லைன் மூளையும் இப்போது இல்லை. மேலே 'FRIDAY ▾' அழுத்தி 'Qwen 2.5 1.5B' டவுன்லோட் செய்தால் 100% ஆஃப்லைனில் எப்போதும் பேசுவேன்."
        } else {
            "இப்போது Offline மூளை மீண்டும் இணைகிறது — இன்னும் 2 நொடிகளில் மீண்டும் கேள்!"
        }
    }
}
