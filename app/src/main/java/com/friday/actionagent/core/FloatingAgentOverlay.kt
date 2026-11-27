package com.friday.actionagent.core

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

class FloatingAgentOverlay(
    private val context: Context,
    private val onMicClick: () -> Unit,
    private val onStopClick: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cursorView: CursorPointerView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var hudView: LinearLayout? = null
    private var statusText: TextView? = null
    private var micPillBtn: TextView? = null

    private var curX = 360f
    private var curY = 640f
    private var hudShown = false
    private var cursorShown = false

    fun showHud(initialStatus: String, listening: Boolean = false) {
        if (!Settings.canDrawOverlays(context)) return
        mainHandler.post {
            if (hudShown) {
                updateStatus(initialStatus, listening)
                return@post
            }
            try {
                val hp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 86
                }

                val pill = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(18, 12, 18, 12)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F2171717"))
                        cornerRadius = 60f
                        setStroke(2, Color.parseColor("#3F3F46"))
                    }
                }

                val mBtn = TextView(context).apply {
                    text = if (listening) "●" else "🎙"
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(68, 68)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(if (listening) "#EF4444" else "#2F2F2F"))
                    }
                    setOnClickListener { onMicClick() }
                }

                val tv = TextView(context).apply {
                    text = initialStatus
                    setTextColor(Color.parseColor("#ECECEC"))
                    textSize = 12.5f
                    maxWidth = 480
                    setPadding(18, 0, 18, 0)
                }

                val stopBtn = TextView(context).apply {
                    text = "✕"
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#D4D4D8"))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(64, 64)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#2F2F2F"))
                    }
                    setOnClickListener { onStopClick() }
                }

                pill.addView(mBtn)
                pill.addView(tv)
                pill.addView(stopBtn)
                wm.addView(pill, hp)

                hudView = pill
                statusText = tv
                micPillBtn = mBtn
                hudShown = true
            } catch (_: Exception) {
            }
        }
    }

    fun showCursor() {
        if (!Settings.canDrawOverlays(context)) return
        mainHandler.post {
            if (cursorShown) return@post
            try {
                val cp = WindowManager.LayoutParams(
                    130, 130,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (curX - 24f).toInt()
                    y = (curY - 24f).toInt()
                }
                val cv = CursorPointerView(context)
                wm.addView(cv, cp)
                cursorView = cv
                cursorParams = cp
                cursorShown = true
            } catch (_: Exception) {
            }
        }
    }

    fun show(initialStatus: String) {
        showHud(initialStatus, false)
        showCursor()
    }

    fun updateStatus(text: String, listening: Boolean = false) {
        mainHandler.post {
            statusText?.text = text
            micPillBtn?.apply {
                this.text = if (listening) "●" else "🎙"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(if (listening) "#EF4444" else "#2F2F2F"))
                }
            }
        }
    }

    fun moveCursorTo(targetX: Float, targetY: Float, triggerPulse: Boolean = true) {
        if (!cursorShown) showCursor()
        mainHandler.post {
            val cv = cursorView ?: return@post
            val cp = cursorParams ?: return@post
            val startX = curX
            val startY = curY
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    curX = startX + (targetX - startX) * f
                    curY = startY + (targetY - startY) * f
                    cp.x = (curX - 24f).toInt()
                    cp.y = (curY - 24f).toInt()
                    try { wm.updateViewLayout(cv, cp) } catch (_: Exception) {}
                }
                start()
            }
            if (triggerPulse) mainHandler.postDelayed({ cv.playClickPulse() }, 300L)
        }
    }

    fun hideCursorOnly() {
        mainHandler.post {
            try { cursorView?.let { wm.removeView(it) } } catch (_: Exception) {}
            cursorView = null
            cursorParams = null
            cursorShown = false
        }
    }

    fun hide() {
        hideCursorOnly()
        mainHandler.post {
            try { hudView?.let { wm.removeView(it) } } catch (_: Exception) {}
            hudView = null
            statusText = null
            micPillBtn = null
            hudShown = false
        }
    }

    private class CursorPointerView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111111"); style = Paint.Style.STROKE; strokeWidth = 4.5f; strokeJoin = Paint.Join.ROUND
        }
        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#10A37F"); style = Paint.Style.STROKE; strokeWidth = 6f
        }
        private var rippleProgress = 1f

        fun playClickPulse() {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 350L
                addUpdateListener { rippleProgress = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val tipX = 24f; val tipY = 24f
            if (rippleProgress < 1f) {
                ripplePaint.alpha = ((1f - rippleProgress) * 240).toInt().coerceIn(0, 255)
                canvas.drawCircle(tipX, tipY, 12f + rippleProgress * 44f, ripplePaint)
            }
            val arrow = Path().apply {
                moveTo(tipX, tipY)
                lineTo(tipX, tipY + 52f)
                lineTo(tipX + 15f, tipY + 39f)
                lineTo(tipX + 26f, tipY + 58f)
                lineTo(tipX + 34f, tipY + 53f)
                lineTo(tipX + 23f, tipY + 35f)
                lineTo(tipX + 41f, tipY + 35f)
                close()
            }
            canvas.drawPath(arrow, fillPaint)
            canvas.drawPath(arrow, strokePaint)
        }
    }
}
