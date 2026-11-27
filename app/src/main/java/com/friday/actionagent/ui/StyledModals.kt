package com.friday.actionagent.ui

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.friday.actionagent.core.FridayAccessibilityService
import com.friday.actionagent.core.SecureVault
import com.friday.actionagent.engines.offline.EmbeddedOfflineBrain
import com.friday.actionagent.engines.offline.OfflineModelCatalogItem
import java.io.File

object StyledModals {

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun cardBg(fill: String, stroke: String, r: Float) = GradientDrawable().apply {
        setColor(Color.parseColor(fill))
        cornerRadius = r
        setStroke(2, Color.parseColor(stroke))
    }

    private fun buildSheetDialog(ctx: Context, title: String, subtitle: String, contentBuilder: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141417"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Styled Header Bar
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1C1C21"))
            setPadding(dp(ctx, 18), dp(ctx, 20), dp(ctx, 16), dp(ctx, 16))
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = title
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 19f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        })
        titleCol.addView(TextView(ctx).apply {
            text = subtitle
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12.5f
            setPadding(0, dp(ctx, 2), 0, 0)
        })

        val closeBtn = TextView(ctx).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E5E7EB"))
            textSize = 16f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2A2A32"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 38))
            setOnClickListener { d.dismiss() }
        }
        header.addView(titleCol)
        header.addView(closeBtn)
        root.addView(header)

        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#2A2A32"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        })

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 30))
        }
        contentBuilder(body, d)

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(body)
        }
        root.addView(scroll)
        d.setContentView(root)
        d.show()
        return d
    }

    private fun sectionTitle(ctx: Context, label: String): TextView {
        return TextView(ctx).apply {
            text = label
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.06f
            setPadding(dp(ctx, 4), dp(ctx, 14), dp(ctx, 4), dp(ctx, 8))
        }
    }

    private fun statusBadge(ctx: Context, text: String, bgHex: String, textHex: String): TextView {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor(textHex))
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
            background = cardBg(bgHex, bgHex, dp(ctx, 14).toFloat())
        }
    }

    /**
     * 1. RICH CARD-BASED PERMISSIONS CENTER (Clear GRANTED ✓ vs ENABLE → Badges!)
     */
    fun showPermissionsSheet(ctx: Context, onRequestRuntimePerms: () -> Unit) {
        val acc = FridayAccessibilityService.instance != null
        val ovl = Settings.canDrawOverlays(ctx)
        val storage = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val call = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        val grantedCount = listOf(acc, ovl, storage, mic && call && contacts).count { it }

        buildSheetDialog(
            ctx,
            title = "Permissions & System Access",
            subtitle = "$grantedCount / 4 Core Permissions Active"
        ) { body, dialog ->

            fun addPermCard(icon: String, name: String, desc: String, granted: Boolean, onTap: () -> Unit) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
                    background = cardBg(
                        if (granted) "#1A2521" else "#231E22",
                        if (granted) "#10B981" else "#3F3F46",
                        dp(ctx, 16).toFloat()
                    )
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(ctx, 12)
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        onTap()
                    }
                }

                card.addView(TextView(ctx).apply {
                    text = icon
                    textSize = 22f
                    setPadding(0, 0, dp(ctx, 14), 0)
                })

                val info = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 10) }
                }
                info.addView(TextView(ctx).apply {
                    text = name
                    setTextColor(Color.parseColor("#F9FAFB"))
                    textSize = 15f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                })
                info.addView(TextView(ctx).apply {
                    text = desc
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 12f
                    setPadding(0, dp(ctx, 3), 0, 0)
                })

                val badge = if (granted) {
                    statusBadge(ctx, "GRANTED ✓", "#065F46", "#6EE7B7")
                } else {
                    statusBadge(ctx, "ENABLE →", "#991B1B", "#FCA5A5")
                }

                card.addView(info)
                card.addView(badge)
                body.addView(card)
            }

            addPermCard(
                "🖱️", "Accessibility Control",
                "YouTube, Chrome, ஆப்களைத் தானாகக் கிளிக் செய்து டைப் செய்ய",
                acc
            ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

            addPermCard(
                "✨", "Floating Cursor & Overlay",
                "திரையில் மவுஸ் கர்சர் மற்றும் Background Voice Pill காட்ட",
                ovl
            ) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }

            addPermCard(
                "📂", "All Files Storage Access",
                "Download ஃபோல்டரில் உள்ள .gguf ஆஃப்லைன் மூளைகளைத் தானாகக் கண்டறிய",
                storage
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    } catch (_: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }

            addPermCard(
                "📞", "Mic, Calls, Contacts & Torch",
                "நேரடி குரல் பேச்சு, மம்மிக்கு கால் மற்றும் டார்ச் லைட் இயக்க",
                mic && call && contacts
            ) { onRequestRuntimePerms() }
        }
    }

    /**
     * 2. RICH OFFLINE AI MODEL HUB (Separate Installed vs Available • Auto-Hides Downloaded Models!)
     */
    fun showOfflineModelsSheet(
        ctx: Context,
        onActivateModel: (File) -> Unit,
        onDeleteModel: (File) -> Unit,
        onDownloadModel: (OfflineModelCatalogItem) -> Unit
    ) {
        val installed = EmbeddedOfflineBrain.scanAllGgufFiles(ctx)
        val availableToDownload = EmbeddedOfflineBrain.CATALOG.filter { !EmbeddedOfflineBrain.isCatalogItemInstalled(ctx, it) }
        val activePath = EmbeddedOfflineBrain.loadedModelPath
        val isRunning = EmbeddedOfflineBrain.isRunning()

        buildSheetDialog(
            ctx,
            title = "Offline AI Model Hub",
            subtitle = "Built-in Native Engine • ${installed.size} Installed • Auto-Connects on Launch"
        ) { body, dialog ->

            // Top Engine Status Hero Card
            val hero = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
                background = cardBg("#1B2430", "#2563EB", dp(ctx, 16).toFloat())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(ctx, 12)
                }
            }
            val heroCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            heroCol.addView(TextView(ctx).apply {
                text = if (isRunning) "🟢 Connected: ${EmbeddedOfflineBrain.loadedModelName}" else "⚡ Offline Engine Ready"
                setTextColor(Color.WHITE)
                textSize = 14.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            heroCol.addView(TextView(ctx).apply {
                text = "No Termux needed — runs 100% inside FRIDAY APK"
                setTextColor(Color.parseColor("#93C5FD"))
                textSize = 11.5f
            })
            hero.addView(heroCol)
            body.addView(hero)

            // SECTION 1: INSTALLED MODELS (Already on Phone!)
            body.addView(sectionTitle(ctx, "INSTALLED ON YOUR PHONE (${installed.size})"))
            if (installed.isEmpty()) {
                body.addView(TextView(ctx).apply {
                    text = "இன்னும் எந்த ஆஃப்லைன் மாடலும் டவுன்லோட் ஆகவில்லை. கீழே உள்ள 'Qwen 2.5 1.5B' அல்லது 'Qwen 2.5 3B'-ஐ டவுன்லோட் செய்!"
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 13f
                    setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
                    background = cardBg("#1E1E24", "#2E2E38", dp(ctx, 14).toFloat())
                })
            } else {
                installed.forEach { file ->
                    val mb = file.length() / (1024 * 1024)
                    val sizeStr = if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else "$mb MB"
                    val isActive = isRunning && (activePath == file.absolutePath || EmbeddedOfflineBrain.loadedModelName == file.nameWithoutExtension)

                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(ctx, 15), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
                        background = cardBg(
                            if (isActive) "#152822" else "#1F1F25",
                            if (isActive) "#10B981" else "#32323D",
                            dp(ctx, 16).toFloat()
                        )
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(ctx, 10)
                        }
                    }

                    val col = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 8) }
                    }
                    col.addView(TextView(ctx).apply {
                        text = file.nameWithoutExtension
                        setTextColor(Color.parseColor("#F9FAFB"))
                        textSize = 14f
                        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    })
                    col.addView(TextView(ctx).apply {
                        text = "Downloaded • $sizeStr"
                        setTextColor(Color.parseColor("#9CA3AF"))
                        textSize = 11.5f
                    })

                    val actionPill = if (isActive) {
                        statusBadge(ctx, "ACTIVE ✓", "#065F46", "#6EE7B7")
                    } else {
                        statusBadge(ctx, "CONNECT", "#1E3A8A", "#93C5FD").apply {
                            setOnClickListener {
                                dialog.dismiss()
                                onActivateModel(file)
                            }
                        }
                    }

                    val delBtn = TextView(ctx).apply {
                        text = "🗑"
                        textSize = 16f
                        setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
                        setOnClickListener {
                            dialog.dismiss()
                            onDeleteModel(file)
                        }
                    }

                    card.addView(col)
                    card.addView(actionPill)
                    card.addView(delBtn)
                    body.addView(card)
                }
            }

            // SECTION 2: AVAILABLE TO DOWNLOAD (Already-Downloaded models are HIDDEN automatically!)
            if (availableToDownload.isNotEmpty()) {
                body.addView(sectionTitle(ctx, "AVAILABLE TO DOWNLOAD (${availableToDownload.size})"))
                availableToDownload.forEach { item ->
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(ctx, 15), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
                        background = cardBg("#1E1E24", "#2F2F3A", dp(ctx, 16).toFloat())
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(ctx, 10)
                        }
                    }

                    val col = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 10) }
                    }
                    col.addView(TextView(ctx).apply {
                        text = "${item.title}  [${item.badge}]"
                        setTextColor(Color.parseColor("#F3F4F6"))
                        textSize = 14f
                        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    })
                    col.addView(TextView(ctx).apply {
                        text = "${item.description} • ${item.sizeLabel}"
                        setTextColor(Color.parseColor("#9CA3AF"))
                        textSize = 11.5f
                        setPadding(0, dp(ctx, 3), 0, 0)
                    })

                    val dlPill = statusBadge(ctx, "⬇ GET", "#2563EB", "#FFFFFF").apply {
                        setOnClickListener {
                            dialog.dismiss()
                            onDownloadModel(item)
                        }
                    }

                    card.addView(col)
                    card.addView(dlPill)
                    body.addView(card)
                }
            }
        }
    }

    /**
     * 3. RICH SETTINGS, BRAINS & API KEYS SHEET
     */
    fun showSettingsAndKeysSheet(ctx: Context, onSaved: () -> Unit) {
        val prefs = SecureVault.prefs(ctx)
        buildSheetDialog(
            ctx,
            title = "AI Brains & Settings",
            subtitle = "Auto-Connected Free Engines + Optional Personal API Keys"
        ) { body, dialog ->

            // Auto-Connect Free Cloud Toggle Card
            val toggleCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
                background = cardBg("#1A2521", "#10B981", dp(ctx, 16).toFloat())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(ctx, 14)
                }
            }
            val tCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tCol.addView(TextView(ctx).apply {
                text = "Auto-Connect Free Cloud Engine"
                setTextColor(Color.WHITE)
                textSize = 14.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            tCol.addView(TextView(ctx).apply {
                text = "Zero API Key needed — instant fallback when online"
                setTextColor(Color.parseColor("#6EE7B7"))
                textSize = 11.5f
            })
            val freeSwitch = Switch(ctx).apply {
                isChecked = prefs.getBoolean("free_cloud_enabled", true)
            }
            toggleCard.addView(tCol)
            toggleCard.addView(freeSwitch)
            body.addView(toggleCard)

            body.addView(sectionTitle(ctx, "OPTIONAL API KEYS (AES-256 ENCRYPTED)"))

            fun addKeyCard(label: String, hint: String, keyName: String): EditText {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                    background = cardBg("#1E1E24", "#2F2F3A", dp(ctx, 14).toFloat())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(ctx, 10)
                    }
                }
                card.addView(TextView(ctx).apply {
                    text = label
                    setTextColor(Color.parseColor("#D1D5DB"))
                    textSize = 12.5f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                })
                val et = EditText(ctx).apply {
                    setText(prefs.getString(keyName, ""))
                    this.hint = hint
                    setHintTextColor(Color.parseColor("#6B7280"))
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    background = null
                    setPadding(0, dp(ctx, 6), 0, dp(ctx, 2))
                }
                card.addView(et)
                body.addView(card)
                return et
            }

            val fGem = addKeyCard("Google Gemini API Key", "Starts with AIza... (aistudio.google.com)", "gemini_key")
            val fGroq = addKeyCard("Groq Free API Key (Ultra-Fast Llama 70B)", "Starts with gsk_... (console.groq.com)", "groq_key")
            val fOr = addKeyCard("OpenRouter Free Key (DeepSeek-R1)", "Starts with sk-or-...", "openrouter_key")
            val fOai = addKeyCard("OpenAI API Key (Optional)", "Starts with sk-...", "openai_key")

            val saveBtn = TextView(ctx).apply {
                text = "Save & Connect"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#0D0D0D"))
                textSize = 15.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
                background = cardBg("#FFFFFF", "#FFFFFF", dp(ctx, 26).toFloat())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx, 14)
                }
                setOnClickListener {
                    prefs.edit()
                        .putBoolean("free_cloud_enabled", freeSwitch.isChecked)
                        .putString("gemini_key", fGem.text.toString().trim())
                        .putString("groq_key", fGroq.text.toString().trim())
                        .putString("openrouter_key", fOr.text.toString().trim())
                        .putString("openai_key", fOai.text.toString().trim())
                        .apply()
                    dialog.dismiss()
                    onSaved()
                }
            }
            body.addView(saveBtn)
        }
    }

    /**
     * 4. STYLED BRAIN SWITCHER SHEET
     */
    fun showBrainSelectorSheet(
        ctx: Context,
        onSelected: (String, String) -> Unit,
        onOpenModels: () -> Unit
    ) {
        val current = SecureVault.prefs(ctx).getString("preferred_brain", "auto") ?: "auto"
        val options = listOf(
            Triple("auto", "⚡ Auto-Connect (Best Available)", "Built-In Offline First → Free Cloud / API Keys"),
            Triple("offline", "📱 Built-In Offline GGUF Only", "100% Zero Internet • Uses your installed .gguf"),
            Triple("free_cloud", "☁️ Free Cloud AI (No API Key)", "Always-on fast online intelligence"),
            Triple("gemini", "✨ Google Gemini", "Uses your Gemini 2.5 / 3.8 Flash API Key"),
            Triple("groq", "🦙 Groq Llama 70B", "Ultra-fast cloud Llama (Free Key)"),
            Triple("deepseek", "🧠 DeepSeek-R1", "Deep reasoning via OpenRouter")
        )

        buildSheetDialog(
            ctx,
            title = "Select FRIDAY Brain",
            subtitle = "Stays connected permanently across app launches"
        ) { body, dialog ->
            options.forEach { (key, title, desc) ->
                val active = current == key
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ctx, 15), dp(ctx, 14), dp(ctx, 15), dp(ctx, 14))
                    background = cardBg(
                        if (active) "#152822" else "#1E1E24",
                        if (active) "#10B981" else "#2F2F3A",
                        dp(ctx, 16).toFloat()
                    )
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(ctx, 10)
                    }
                    setOnClickListener {
                        SecureVault.prefs(ctx).edit().putString("preferred_brain", key).apply()
                        dialog.dismiss()
                        onSelected(key, title)
                    }
                }
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(TextView(ctx).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 14.5f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                })
                col.addView(TextView(ctx).apply {
                    text = desc
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 11.5f
                })
                card.addView(col)
                if (active) card.addView(statusBadge(ctx, "ACTIVE ✓", "#065F46", "#6EE7B7"))
                body.addView(card)
            }

            val manageBtn = TextView(ctx).apply {
                text = "⬇  Manage / Download Offline AI Models"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 14.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
                background = cardBg("#2563EB", "#3B82F6", dp(ctx, 22).toFloat())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx, 8)
                }
                setOnClickListener {
                    dialog.dismiss()
                    onOpenModels()
                }
            }
            body.addView(manageBtn)
        }
    }
}
