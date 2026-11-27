package com.friday.actionagent.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.friday.actionagent.engines.hub.AutoBrainRouter
import com.friday.actionagent.engines.memory.PersonalMemoryEngine
import java.util.Locale

object BackgroundVoiceController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile
    var liveBackgroundMode: Boolean = false
        private set

    @Volatile
    var isListening: Boolean = false
        private set

    @Volatile
    var isSpeaking: Boolean = false
        private set

    var onUserSpoken: ((String) -> Unit)? = null
    var onLogEvent: ((String) -> Unit)? = null
    var onAssistantReply: ((String, String) -> Unit)? = null
    var onStateChanged: ((String, Boolean) -> Unit)? = null
    var onRmsLevel: ((Float) -> Unit)? = null
    var onLiveSubtitle: ((String, String) -> Unit)? = null // (phase, text)

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale("ta", "IN")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                        onLiveSubtitle?.invoke("speaking", "")
                    }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        if (utteranceId == "friday_relisten" && liveBackgroundMode) {
                            mainHandler.postDelayed({ startListeningNow() }, 550L)
                        } else {
                            onLiveSubtitle?.invoke("idle", "")
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        if (utteranceId == "friday_relisten" && liveBackgroundMode) {
                            mainHandler.postDelayed({ startListeningNow() }, 700L)
                        }
                    }
                })
            }
        }
    }

    fun setLiveMode(context: Context, enabled: Boolean, showFloatingPill: Boolean = true) {
        init(context)
        liveBackgroundMode = enabled
        val ctx = context.applicationContext
        if (enabled) {
            FridayBackgroundService.start(ctx)
            if (showFloatingPill) {
                FridayAccessibilityService.instance?.overlay?.showHud("FRIDAY Live Ready • Tap 🎙", false)
            }
            onStateChanged?.invoke("Live Voice Active", false)
            startListeningNow()
        } else {
            stopListening()
            tts?.stop()
            isSpeaking = false
            AutoBrainRouter.stopAll()
            FridayAccessibilityService.instance?.overlay?.hide()
            onStateChanged?.invoke("Ready", false)
        }
    }

    fun speak(text: String, relistenAfter: Boolean = false) {
        val clean = text.replace(Regex("""[✅⚠️🚀🖱️⌨️🌐🔊🤖👤🧠⚙️🔒📎▶🟢🟡🔴🛑📞📄🎯⏰🗺️🔋🔦]"""), "").trim().take(420)
        if (clean.isEmpty()) {
            if (relistenAfter && liveBackgroundMode) {
                mainHandler.postDelayed({ startListeningNow() }, 550L)
            }
            return
        }
        val uid = if (relistenAfter) "friday_relisten" else "friday_say"
        if (ttsReady && tts != null) {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, uid)
        } else if (relistenAfter && liveBackgroundMode) {
            mainHandler.postDelayed({ startListeningNow() }, 850L)
        }
    }

    fun startListeningNow() {
        val ctx = appContext ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStateChanged?.invoke("Mic permission required", false)
            return
        }
        if (AutoBrainRouter.isRunning) return

        mainHandler.post {
            try {
                tts?.stop()
                isSpeaking = false
                recognizer?.destroy()
                recognizer = null

                if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                    onStateChanged?.invoke("Speech engine unavailable", false)
                    return@post
                }

                val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
                recognizer = sr
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        onStateChanged?.invoke("Listening...", true)
                        onLiveSubtitle?.invoke("listening", "பேசுங்கள்... (Listening)")
                        if (liveBackgroundMode) {
                            FridayAccessibilityService.instance?.overlay?.updateStatus("Listening...", true)
                        }
                    }
                    override fun onBeginningOfSpeech() {
                        if (liveBackgroundMode) {
                            FridayAccessibilityService.instance?.overlay?.updateStatus("Hearing you...", true)
                        }
                    }
                    override fun onRmsChanged(rmsdB: Float) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        onRmsLevel?.invoke(normalized)
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListening = false
                        onRmsLevel?.invoke(0f)
                        onStateChanged?.invoke("Thinking...", false)
                        onLiveSubtitle?.invoke("thinking", "யோசிக்கிறேன்...")
                        if (liveBackgroundMode) {
                            FridayAccessibilityService.instance?.overlay?.updateStatus("Thinking...", false)
                        }
                    }
                    override fun onError(error: Int) {
                        isListening = false
                        onRmsLevel?.invoke(0f)
                        if (liveBackgroundMode && !AutoBrainRouter.isRunning) {
                            val msg = if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                                "Tap orb or mic to speak"
                            } else "Mic paused • Tap 🎙"
                            onStateChanged?.invoke(msg, false)
                            onLiveSubtitle?.invoke("idle", msg)
                            FridayAccessibilityService.instance?.overlay?.updateStatus("Live Ready • Tap 🎙", false)
                        } else {
                            onStateChanged?.invoke("Ready", false)
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        isListening = false
                        onRmsLevel?.invoke(0f)
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                        if (!spoken.isNullOrEmpty()) {
                            onLiveSubtitle?.invoke("thinking", "நீ: $spoken")
                            handleSpokenCommand(ctx, spoken)
                        } else if (liveBackgroundMode) {
                            onLiveSubtitle?.invoke("idle", "Tap orb to speak")
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val part = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                        if (!part.isNullOrEmpty()) {
                            onLiveSubtitle?.invoke("listening", part)
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ta-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                sr.startListening(intent)
            } catch (_: Exception) {
                isListening = false
                onStateChanged?.invoke("Mic error", false)
            }
        }
    }

    fun stopListening() {
        isListening = false
        onRmsLevel?.invoke(0f)
        mainHandler.post {
            try {
                recognizer?.stopListening()
                recognizer?.cancel()
                recognizer?.destroy()
            } catch (_: Exception) {
            }
            recognizer = null
        }
    }

    fun handleSpokenCommand(context: Context, spoken: String, attachedFileName: String? = null, attachedFileText: String? = null) {
        init(context)
        val ctx = context.applicationContext
        val low = spoken.lowercase(Locale.ROOT)
        if (low == "stop" || low == "stop friday" || low == "நிறுத்து") {
            setLiveMode(ctx, false)
            onAssistantReply?.invoke("நிறுத்தப்பட்டது.", "FRIDAY")
            return
        }

        PersonalMemoryEngine.recordTurn(ctx, "user", spoken)
        onUserSpoken?.invoke(spoken)

        val stepBuf = StringBuilder()
        AutoBrainRouter.executeUnified(
            context = ctx,
            input = spoken,
            attachedFileName = attachedFileName,
            attachedFileText = attachedFileText,
            onLog = { line ->
                stepBuf.append("• ").append(line).append("\n")
                onLogEvent?.invoke(line)
            },
            onStatus = { st ->
                onStateChanged?.invoke(st, false)
                if (liveBackgroundMode) {
                    FridayAccessibilityService.instance?.overlay?.updateStatus(st, false)
                }
            },
            onFinish = { reply ->
                val eng = AutoBrainRouter.lastConnectedEngine
                PersonalMemoryEngine.recordTurn(ctx, "assistant", reply, eng, stepBuf.toString().trim())
                onAssistantReply?.invoke(reply, eng)
                onLiveSubtitle?.invoke("speaking", reply)
                if (liveBackgroundMode) {
                    FridayAccessibilityService.instance?.overlay?.hideCursorOnly()
                    FridayAccessibilityService.instance?.overlay?.updateStatus("Speaking...", false)
                    speak(reply, relistenAfter = true)
                } else {
                    FridayAccessibilityService.instance?.overlay?.hide()
                    speak(reply, relistenAfter = false)
                }
            }
        )
    }
}
