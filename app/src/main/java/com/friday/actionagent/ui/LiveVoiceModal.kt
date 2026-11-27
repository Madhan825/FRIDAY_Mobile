package com.friday.actionagent.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.friday.actionagent.core.BackgroundVoiceController
import com.friday.actionagent.engines.offline.EmbeddedOfflineBrain
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class LiveVoiceModal(
    private val context: Context,
    private val onMinimizeToBackground: () -> Unit,
    private val onEndSession: () -> Unit
) {
    private var dialog: Dialog? = null
    private var orbView: ChatGptOrbView? = null
    private var stateLabel: TextView? = null
    private var subtitleText: TextView? = null
    private var muteBtn: TextView? = null
    private var isMuted = false

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun show() {
        if (dialog?.isShowing == true) return
        val d = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            setPadding(dp(24), dp(36), dp(24), dp(34))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Top Bar
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(context).apply {
            text = "FRIDAY Live"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        })
        val brainTag = EmbeddedOfflineBrain.loadedModelName.ifBlank { "Auto-Connected AI" }
        stateLabel = TextView(context).apply {
            text = "Connected • $brainTag"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 12.5f
        }
        titleCol.addView(stateLabel)

        val bgPill = TextView(context).apply {
            text = "Background ↗"
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 12f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1F1F23"))
                cornerRadius = dp(20).toFloat()
                setStroke(2, Color.parseColor("#333338"))
            }
            setOnClickListener {
                dismissOnlyUi()
                onMinimizeToBackground()
            }
        }
        top.addView(titleCol)
        top.addView(bgPill)
        root.addView(top)

        // Center Glowing ChatGPT Voice Orb
        val orbWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val orb = ChatGptOrbView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(250), dp(250))
            setOnClickListener {
                if (!BackgroundVoiceController.isListening) {
                    isMuted = false
                    updateMuteVisual()
                    BackgroundVoiceController.startListeningNow()
                }
            }
        }
        orbView = orb
        orbWrap.addView(orb)

        subtitleText = TextView(context).apply {
            text = "பேசுங்கள்... (Tap orb anytime)"
            setTextColor(Color.parseColor("#D4D4D8"))
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 4
            setLineSpacing(6f, 1.0f)
            setPadding(dp(14), dp(24), dp(14), dp(8))
        }
        orbWrap.addView(subtitleText)
        root.addView(orbWrap)

        // Bottom ChatGPT Voice Controls Dock (Mute | Background | End Red X)
        val dock = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val mBtn = TextView(context).apply {
            text = "🎙"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(34) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#262629"))
            }
            setOnClickListener {
                isMuted = !isMuted
                updateMuteVisual()
                if (isMuted) {
                    BackgroundVoiceController.stopListening()
                    updatePhase("idle", "Muted • Tap 🎙 to talk")
                } else {
                    BackgroundVoiceController.startListeningNow()
                }
            }
        }
        muteBtn = mBtn

        val endBtn = TextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EF4444"))
            }
            setOnClickListener {
                dismissOnlyUi()
                onEndSession()
            }
        }

        dock.addView(mBtn)
        dock.addView(endBtn)
        root.addView(dock)

        d.setContentView(root)
        d.setOnCancelListener {
            orbView?.stopAnim()
            onMinimizeToBackground()
        }
        d.show()
        dialog = d
        orbView?.startAnim()
    }

    private fun updateMuteVisual() {
        muteBtn?.apply {
            text = if (isMuted) "🔇" else "🎙"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(if (isMuted) "#3F3F46" else "#262629"))
            }
        }
    }

    fun updateRms(level: Float) {
        orbView?.targetAmplitude = level
    }

    fun updatePhase(phase: String, caption: String) {
        orbView?.phase = phase
        stateLabel?.text = when (phase) {
            "listening" -> "● Listening..."
            "thinking" -> "◌ Thinking..."
            "speaking" -> "◉ Speaking..."
            else -> "Ready • Tap Orb"
        }
        if (caption.isNotBlank()) {
            subtitleText?.text = caption
        }
    }

    fun dismissOnlyUi() {
        orbView?.stopAnim()
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
    }

    private class ChatGptOrbView(context: Context) : View(context) {
        @Volatile var targetAmplitude = 0f
        @Volatile var phase = "listening"

        private var smoothAmp = 0f
        private var tick = 0f
        private var running = false
        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        private val frameTask = object : Runnable {
            override fun run() {
                if (!running) return
                tick += when (phase) {
                    "thinking" -> 0.14f
                    "speaking" -> 0.10f
                    else -> 0.06f
                }
                val desired = when (phase) {
                    "speaking" -> 0.45f + 0.35f * sin(tick * 2.2f)
                    "thinking" -> 0.25f + 0.15f * sin(tick * 1.5f)
                    else -> targetAmplitude
                }
                smoothAmp += (desired - smoothAmp) * 0.22f
                invalidate()
                handler.postDelayed(this, 24L)
            }
        }

        fun startAnim() {
            if (running) return
            running = true
            handler.post(frameTask)
        }

        fun stopAnim() {
            running = false
            handler.removeCallbacks(frameTask)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val baseR = min(width, height) * 0.31f
            val dynR = baseR * (1f + smoothAmp * 0.24f)

            // Outer fluid aura rings (ChatGPT Advanced Voice blue-cyan-white aesthetic)
            for (i in 1..3) {
                val wave = sin(tick + i * 1.4f) * 8f
                ringPaint.color = when (phase) {
                    "thinking" -> Color.argb((75 - i * 18).coerceAtLeast(15), 167, 139, 250)
                    "speaking" -> Color.argb((95 - i * 20).coerceAtLeast(20), 56, 189, 248)
                    else -> Color.argb((80 - i * 18).coerceAtLeast(18), 96, 165, 250)
                }
                canvas.drawCircle(
                    cx + cos(tick * 0.9f + i) * 5f,
                    cy + sin(tick * 1.1f + i) * 5f,
                    dynR + i * 16f + wave,
                    ringPaint
                )
            }

            // Main luminous fluid orb
            val shiftX = cx + cos(tick) * dynR * 0.22f
            val shiftY = cy + sin(tick * 0.8f) * dynR * 0.22f
            val c1 = if (phase == "thinking") Color.parseColor("#E0E7FF") else Color.parseColor("#FFFFFF")
            val c2 = if (phase == "thinking") Color.parseColor("#6366F1") else Color.parseColor("#38BDF8")
            val c3 = if (phase == "speaking") Color.parseColor("#0284C7") else Color.parseColor("#1D4ED8")

            paint.shader = RadialGradient(shiftX, shiftY, dynR * 1.15f, intArrayOf(c1, c2, c3), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, dynR, paint)
        }
    }
}
