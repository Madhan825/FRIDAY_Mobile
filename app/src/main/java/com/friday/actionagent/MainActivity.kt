package com.friday.actionagent

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.friday.actionagent.core.BackgroundVoiceController
import com.friday.actionagent.core.FridayAccessibilityService
import com.friday.actionagent.core.SecureVault
import com.friday.actionagent.engines.hub.AutoBrainRouter
import com.friday.actionagent.engines.memory.ChatSession
import com.friday.actionagent.engines.memory.PersonalMemoryEngine
import com.friday.actionagent.engines.offline.EmbeddedOfflineBrain
import com.friday.actionagent.engines.offline.OfflineModelCatalogItem
import com.friday.actionagent.ui.LiveVoiceModal
import com.friday.actionagent.ui.StyledModals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerHistoryList: LinearLayout
    private lateinit var drawerStatusText: TextView
    private lateinit var modelPillText: TextView
    private lateinit var downloadBanner: LinearLayout
    private lateinit var downloadBannerText: TextView
    private lateinit var downloadProgress: ProgressBar
    private lateinit var welcomeBox: LinearLayout
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var attachChip: TextView
    private lateinit var cmdInput: EditText
    private lateinit var micBtn: TextView
    private lateinit var actionBtn: TextView

    private var liveVoiceModal: LiveVoiceModal? = null
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var attachedFileName: String? = null
    private var attachedFileText: String? = null
    private var activeStepsView: TextView? = null
    private var pendingActionAfterPerm: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        pendingActionAfterPerm?.invoke()
        pendingActionAfterPerm = null
        refreshDrawerAndHeader()
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val name = uri.lastPathSegment?.substringAfterLast("/") ?: "document.txt"
                    attachedFileName = name
                    attachedFileText = reader.readText().take(16000)
                    attachChip.text = "📎 $name   ✕"
                    attachChip.visibility = View.VISIBLE
                }
            } catch (_: Exception) {
                toast("இந்த ஃபைலைப் படிக்க முடியவில்லை (TXT/Code ஃபைல் தேர்ந்தெடு).")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BackgroundVoiceController.init(this)
        PersonalMemoryEngine.loadAllSessions(this)
        buildChatGptStyleUi()
        bindVoiceCallbacks()
        autoConnectPersistentBrain()
    }

    override fun onResume() {
        super.onResume()
        refreshDrawerAndHeader()
    }

    override fun onDestroy() {
        liveVoiceModal?.dismissOnlyUi()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * Auto-connects immediately on launch & stays connected permanently!
     */
    private fun autoConnectPersistentBrain() {
        uiScope.launch {
            if (EmbeddedOfflineBrain.isRunning()) {
                refreshDrawerAndHeader()
                return@launch
            }
            val models = withContext(Dispatchers.IO) { EmbeddedOfflineBrain.scanAllGgufFiles(this@MainActivity) }
            if (models.isNotEmpty()) {
                modelPillText.text = "Connecting... ▾"
                val ok = EmbeddedOfflineBrain.autoStartPersistent(this@MainActivity)
                if (ok) toast("🟢 Auto-Connected: ${EmbeddedOfflineBrain.loadedModelName}")
            }
            refreshDrawerAndHeader()
        }
    }

    private fun bindVoiceCallbacks() {
        BackgroundVoiceController.onUserSpoken = { spoken ->
            runOnUiThread {
                addUserBubble(spoken)
                activeStepsView = addSubtleStepIndicator()
                updateActionButtonIcon()
                refreshDrawerHistory()
            }
        }
        BackgroundVoiceController.onLogEvent = { line ->
            runOnUiThread {
                activeStepsView?.let { tv ->
                    tv.visibility = View.VISIBLE
                    tv.append("• $line\n")
                    scrollChatBottom()
                }
            }
        }
        BackgroundVoiceController.onAssistantReply = { reply, engine ->
            runOnUiThread {
                addAssistantMessage(reply, engine)
                activeStepsView = null
                updateActionButtonIcon()
                refreshDrawerAndHeader()
            }
        }
        BackgroundVoiceController.onStateChanged = { _, listening ->
            runOnUiThread {
                micBtn.setTextColor(if (listening) Color.parseColor("#EF4444") else Color.parseColor("#B4B4B4"))
                micBtn.text = if (listening) "●" else "🎙"
                if (listening) modelPillText.text = "● Listening..."
                else refreshHeaderLabelOnly()
            }
        }
        BackgroundVoiceController.onRmsLevel = { amp ->
            runOnUiThread { liveVoiceModal?.updateRms(amp) }
        }
        BackgroundVoiceController.onLiveSubtitle = { phase, txt ->
            runOnUiThread { liveVoiceModal?.updatePhase(phase, txt) }
        }
    }

    private fun buildChatGptStyleUi() {
        drawerLayout = DrawerLayout(this).apply {
            setBackgroundColor(Color.parseColor("#212121"))
        }

        val mainRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#212121"))
            layoutParams = DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT)
        }

        // 1. Minimal 100% ChatGPT Top Bar (☰  |  FRIDAY ▾  |  ✎)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#212121"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
        }

        val hamburgerBtn = TextView(this).apply {
            text = "☰"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        }

        val centerWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        modelPillText = TextView(this).apply {
            text = "FRIDAY ▾"
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 15.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = makeRoundBg("#2A2A2A", "#2A2A2A", dp(18).toFloat())
            setOnClickListener { openBrainSelector() }
        }
        centerWrap.addView(modelPillText)

        val newChatBtn = TextView(this).apply {
            text = "✎"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setOnClickListener { startNewChat() }
        }

        topBar.addView(hamburgerBtn)
        topBar.addView(centerWrap)
        topBar.addView(newChatBtn)
        mainRoot.addView(topBar)

        // Download / Loading Banner
        downloadBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#171717"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        downloadBannerText = TextView(this).apply {
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 12f
        }
        downloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) }
        }
        downloadBanner.addView(downloadBannerText)
        downloadBanner.addView(downloadProgress)
        mainRoot.addView(downloadBanner)

        // 2. Scrollable Chat Area
        chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(18))
        }

        welcomeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(105), dp(16), dp(40))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        welcomeBox.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_friday_logo)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { bottomMargin = dp(18) }
        })
        welcomeBox.addView(TextView(this).apply {
            text = "What can I help with?"
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 23f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(26))
        })

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        row1.addView(makeSuggestionPill("◉  Live Voice Chat") { launchFullScreenLiveVoice() })
        row1.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        row1.addView(makeSuggestionPill("▶  YouTube Search") { submitCommand("YouTube-ல Godzilla Minus One சர்ச் பண்ணு") })

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row2.addView(makeSuggestionPill("🧠  Offline AI Hub") { openOfflineModels() })
        row2.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        row2.addView(makeSuggestionPill("📞  Call Mummy") { submitCommand("மம்மிக்கு கால் பண்ணு") })

        welcomeBox.addView(row1)
        welcomeBox.addView(row2)
        chatContainer.addView(welcomeBox)
        chatScroll.addView(chatContainer)
        mainRoot.addView(chatScroll)

        // 3. Attached File Chip
        attachChip = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 12f
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = makeRoundBg("#2F2F2F", "#444444", dp(18).toFloat())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16)
                bottomMargin = dp(6)
            }
            setOnClickListener {
                attachedFileName = null
                attachedFileText = null
                visibility = View.GONE
            }
        }
        mainRoot.addView(attachChip)

        // 4. 100% ChatGPT Bottom Composer (+ | [ Message FRIDAY   🎙  (◉ / ↑) ])
        val composerWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#212121"))
            setPadding(dp(12), dp(8), dp(12), dp(14))
        }

        val plusBtn = TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 24f
            background = makeCircleBg("#2F2F2F", "#2F2F2F")
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) }
            setOnClickListener { openPlusQuickMenu() }
        }

        val inputPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(6), dp(4))
            background = makeRoundBg("#2F2F2F", "#2F2F2F", dp(26).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        cmdInput = EditText(this).apply {
            hint = "Message FRIDAY"
            setHintTextColor(Color.parseColor("#9B9B9B"))
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 15.5f
            typeface = Typeface.SANS_SERIF
            background = null
            maxLines = 5
            setPadding(0, dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateActionButtonIcon() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        micBtn = TextView(this).apply {
            text = "🎙"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B4B4B4"))
            textSize = 17f
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(4) }
            setOnClickListener { ensureRuntimePerms { BackgroundVoiceController.startListeningNow() } }
        }

        actionBtn = TextView(this).apply {
            text = "◉"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0D0D0D"))
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            background = makeCircleBg("#FFFFFF", "#FFFFFF")
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener {
                if (AutoBrainRouter.isRunning) {
                    AutoBrainRouter.stopAll()
                    updateActionButtonIcon()
                } else {
                    val typed = cmdInput.text.toString().trim()
                    if (typed.isNotEmpty()) {
                        cmdInput.setText("")
                        submitCommand(typed)
                    } else {
                        launchFullScreenLiveVoice()
                    }
                }
            }
        }

        inputPill.addView(cmdInput)
        inputPill.addView(micBtn)
        inputPill.addView(actionBtn)
        composerWrap.addView(plusBtn)
        composerWrap.addView(inputPill)
        mainRoot.addView(composerWrap)

        // ============================================================
        // LEFT CHATGPT SIDEBAR DRAWER (#171717)
        // ============================================================
        val drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#171717"))
            setPadding(dp(16), dp(22), dp(16), dp(18))
            layoutParams = DrawerLayout.LayoutParams(dp(295), DrawerLayout.LayoutParams.MATCH_PARENT, GravityCompat.START)
        }

        val sideTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(16))
        }
        sideTop.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_friday_logo)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) }
        })
        sideTop.addView(TextView(this).apply {
            text = "FRIDAY"
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 17f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        drawerPanel.addView(sideTop)

        drawerPanel.addView(makeSidebarRow("✎   New chat") {
            drawerLayout.closeDrawer(GravityCompat.START)
            startNewChat()
        })
        drawerPanel.addView(makeSidebarRow("◉   Live Voice Chat") {
            drawerLayout.closeDrawer(GravityCompat.START)
            launchFullScreenLiveVoice()
        })
        drawerPanel.addView(makeSidebarRow("🧠   Offline AI Model Hub") {
            drawerLayout.closeDrawer(GravityCompat.START)
            openOfflineModels()
        })
        drawerPanel.addView(makeSidebarRow("⚙️   Permissions Center") {
            drawerLayout.closeDrawer(GravityCompat.START)
            openPermissions()
        })
        drawerPanel.addView(makeSidebarRow("🔑   Brains & API Settings") {
            drawerLayout.closeDrawer(GravityCompat.START)
            openSettings()
        })

        drawerPanel.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                topMargin = dp(10)
                bottomMargin = dp(12)
            }
        })
        drawerPanel.addView(TextView(this).apply {
            text = "Chat History"
            setTextColor(Color.parseColor("#8E8E8E"))
            textSize = 12f
            setPadding(dp(10), 0, 0, dp(8))
        })

        drawerHistoryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val histScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(drawerHistoryList)
        }
        drawerPanel.addView(histScroll)

        drawerStatusText = TextView(this).apply {
            text = "Connecting..."
            setTextColor(Color.parseColor("#9B9B9B"))
            textSize = 11.5f
            setPadding(dp(10), dp(12), dp(10), dp(4))
        }
        drawerPanel.addView(drawerStatusText)

        drawerLayout.addView(mainRoot)
        drawerLayout.addView(drawerPanel)
        setContentView(drawerLayout)
    }

    private fun makeSuggestionPill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#D4D4D8"))
        textSize = 13f
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = makeRoundBg("#212121", "#3A3A3A", dp(22).toFloat())
        setOnClickListener { onClick() }
    }

    private fun makeSidebarRow(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#ECECEC"))
        textSize = 14.5f
        setPadding(dp(10), dp(11), dp(10), dp(11))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    /**
     * Opens Full-Screen ChatGPT Live Voice Mode with animated orb!
     */
    private fun launchFullScreenLiveVoice() {
        ensureRuntimePerms {
            if (liveVoiceModal == null) {
                liveVoiceModal = LiveVoiceModal(
                    context = this,
                    onMinimizeToBackground = {
                        if (BackgroundVoiceController.liveBackgroundMode) {
                            FridayAccessibilityService.instance?.overlay?.showHud("FRIDAY Live Active • Tap 🎙", false)
                            toast("🟢 Live Voice running in background")
                        }
                    },
                    onEndSession = {
                        BackgroundVoiceController.setLiveMode(this, false)
                        updateActionButtonIcon()
                    }
                )
            }
            liveVoiceModal?.show()
            BackgroundVoiceController.setLiveMode(this, true, showFloatingPill = false)
            updateActionButtonIcon()
        }
    }

    private fun startNewChat() {
        PersonalMemoryEngine.startFreshSession()
        chatContainer.removeAllViews()
        welcomeBox.visibility = View.VISIBLE
        chatContainer.addView(welcomeBox)
        attachedFileName = null
        attachedFileText = null
        attachChip.visibility = View.GONE
        refreshDrawerHistory()
    }

    /**
     * Real ChatGPT History Load — renders the ENTIRE past conversation in the chat feed!
     */
    private fun loadSavedSessionIntoChat(session: ChatSession) {
        PersonalMemoryEngine.openSession(this, session.id)
        chatContainer.removeAllViews()
        welcomeBox.visibility = View.GONE
        session.messages.forEach { turn ->
            if (turn.role == "user") {
                addUserBubble(turn.text)
            } else {
                if (turn.steps.isNotBlank()) {
                    val tv = addSubtleStepIndicator()
                    tv.visibility = View.VISIBLE
                    tv.text = turn.steps
                }
                addAssistantMessage(turn.text, turn.engine.ifBlank { "FRIDAY" })
            }
        }
        scrollChatBottom()
    }

    private fun updateActionButtonIcon() {
        when {
            AutoBrainRouter.isRunning -> {
                actionBtn.text = "■"
                actionBtn.background = makeCircleBg("#FFFFFF", "#FFFFFF")
                actionBtn.setTextColor(Color.parseColor("#0D0D0D"))
            }
            cmdInput.text.toString().trim().isNotEmpty() -> {
                actionBtn.text = "↑"
                actionBtn.background = makeCircleBg("#FFFFFF", "#FFFFFF")
                actionBtn.setTextColor(Color.parseColor("#0D0D0D"))
            }
            BackgroundVoiceController.liveBackgroundMode -> {
                actionBtn.text = "◉"
                actionBtn.background = makeCircleBg("#10A37F", "#10A37F")
                actionBtn.setTextColor(Color.WHITE)
            }
            else -> {
                actionBtn.text = "◉"
                actionBtn.background = makeCircleBg("#FFFFFF", "#FFFFFF")
                actionBtn.setTextColor(Color.parseColor("#0D0D0D"))
            }
        }
    }

    private fun ensureRuntimePerms(onReady: () -> Unit) {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingActionAfterPerm = onReady
            permLauncher.launch(missing.toTypedArray())
        } else {
            onReady()
        }
    }

    private fun submitCommand(text: String) {
        BackgroundVoiceController.handleSpokenCommand(this, text, attachedFileName, attachedFileText)
    }

    private fun addUserBubble(text: String) {
        welcomeBox.visibility = View.GONE
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
                bottomMargin = dp(12)
            }
        }
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 15.5f
            setLineSpacing(4f, 1.0f)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = makeRoundBg("#2F2F2F", "#2F2F2F", dp(22).toFloat())
            maxWidth = (resources.displayMetrics.widthPixels * 0.80f).toInt()
        }
        row.addView(bubble)
        chatContainer.addView(row)
        scrollChatBottom()
    }

    private fun addSubtleStepIndicator(): TextView {
        val tv = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#9B9B9B"))
            textSize = 12f
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        chatContainer.addView(tv)
        return tv
    }

    private fun addAssistantMessage(text: String, engineName: String) {
        welcomeBox.visibility = View.GONE
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(18)
            }
        }

        val body = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 15.5f
            setLineSpacing(7f, 1.0f)
            setTextIsSelectable(true)
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }
        toolsRow.addView(TextView(this).apply {
            this.text = "📋"
            textSize = 13f
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("friday", text))
                toast("Copied")
            }
        })
        toolsRow.addView(TextView(this).apply {
            this.text = "🔊"
            textSize = 13f
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener { BackgroundVoiceController.speak(text, false) }
        })
        toolsRow.addView(TextView(this).apply {
            this.text = engineName
            setTextColor(Color.parseColor("#737373"))
            textSize = 11f
        })

        block.addView(body)
        block.addView(toolsRow)
        chatContainer.addView(block)
        scrollChatBottom()
    }

    private fun scrollChatBottom() = chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }

    private fun refreshHeaderLabelOnly() {
        val loaded = EmbeddedOfflineBrain.loadedModelName
        modelPillText.text = when {
            loaded.isNotEmpty() && EmbeddedOfflineBrain.isRunning() -> "🟢 FRIDAY Offline ▾"
            SecureVault.prefs(this).getBoolean("free_cloud_enabled", true) -> "🟢 FRIDAY AI ▾"
            else -> "FRIDAY ▾"
        }
    }

    private fun refreshDrawerAndHeader() {
        refreshDrawerHistory()
        uiScope.launch {
            val info = withContext(Dispatchers.IO) {
                val offRun = EmbeddedOfflineBrain.isRunning()
                val ggufs = EmbeddedOfflineBrain.scanAllGgufFiles(this@MainActivity)
                val acc = FridayAccessibilityService.instance != null
                val ovl = Settings.canDrawOverlays(this@MainActivity)
                val topTitle = when {
                    offRun -> "🟢 FRIDAY Offline ▾"
                    SecureVault.prefs(this@MainActivity).getBoolean("free_cloud_enabled", true) -> "🟢 FRIDAY AI ▾"
                    else -> "FRIDAY ▾"
                }
                val footer = "Brain: ${if (offRun) "🟢 ${EmbeddedOfflineBrain.loadedModelName}" else "🟢 Free Cloud (${ggufs.size} GGUF)"}\n" +
                    "Cursor: ${if (acc && ovl) "🟢 Ready" else "⚠️ Enable in Permissions"}"
                topTitle to footer
            }
            modelPillText.text = info.first
            drawerStatusText.text = info.second
        }
    }

    private fun refreshDrawerHistory() {
        drawerHistoryList.removeAllViews()
        val sessions = PersonalMemoryEngine.loadAllSessions(this)
        if (sessions.isEmpty()) {
            drawerHistoryList.addView(TextView(this).apply {
                text = "No past chats yet"
                setTextColor(Color.parseColor("#6B6B6B"))
                textSize = 13f
                setPadding(dp(10), dp(6), dp(10), dp(6))
            })
        } else {
            sessions.forEach { session ->
                val isCurrent = session.id == PersonalMemoryEngine.activeSession.id
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(9), dp(6), dp(9))
                    background = if (isCurrent) makeRoundBg("#262626", "#262626", dp(10).toFloat()) else null
                }
                row.addView(TextView(this).apply {
                    text = session.title
                    setTextColor(if (isCurrent) Color.WHITE else Color.parseColor("#D4D4D8"))
                    textSize = 13.5f
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        drawerLayout.closeDrawer(GravityCompat.START)
                        loadSavedSessionIntoChat(session)
                        refreshDrawerHistory()
                    }
                })
                row.addView(TextView(this).apply {
                    text = "✕"
                    setTextColor(Color.parseColor("#737373"))
                    textSize = 12f
                    setPadding(dp(10), dp(4), dp(6), dp(4))
                    setOnClickListener {
                        PersonalMemoryEngine.deleteSession(this@MainActivity, session.id)
                        if (isCurrent) startNewChat()
                        refreshDrawerHistory()
                    }
                })
                drawerHistoryList.addView(row)
            }
        }
    }

    private fun openPlusQuickMenu() {
        val opts = arrayOf(
            "📎  Attach text / code file",
            "◉  Open Full-Screen Live Voice Chat",
            "🧠  Offline AI Model Hub",
            "🖱️  Test Mouse Cursor & Touch",
            "⚙️  Permissions Center"
        )
        AlertDialog.Builder(this)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> filePickerLauncher.launch(arrayOf("text/*", "application/json", "*/*"))
                    1 -> launchFullScreenLiveVoice()
                    2 -> openOfflineModels()
                    3 -> submitCommand("test cursor")
                    4 -> openPermissions()
                }
            }
            .show()
    }

    private fun openBrainSelector() {
        StyledModals.showBrainSelectorSheet(
            ctx = this,
            onSelected = { key, title ->
                toast("Connected: $title")
                if (key == "offline" || key == "auto") autoConnectPersistentBrain()
                refreshDrawerAndHeader()
            },
            onOpenModels = { openOfflineModels() }
        )
    }

    private fun openPermissions() {
        StyledModals.showPermissionsSheet(this) {
            ensureRuntimePerms { toast("✅ Permissions granted!") }
        }
    }

    private fun openSettings() {
        StyledModals.showSettingsAndKeysSheet(this) {
            refreshDrawerAndHeader()
            toast("✅ Settings saved & connected")
        }
    }

    private fun openOfflineModels() {
        StyledModals.showOfflineModelsSheet(
            ctx = this,
            onActivateModel = { file: File ->
                uiScope.launch {
                    downloadBanner.visibility = View.VISIBLE
                    downloadProgress.isIndeterminate = true
                    downloadBannerText.text = "Connecting ${file.nameWithoutExtension}..."
                    val ok = EmbeddedOfflineBrain.startModel(this@MainActivity, file)
                    downloadProgress.isIndeterminate = false
                    downloadBanner.visibility = View.GONE
                    toast(if (ok) "🟢 Connected: ${file.nameWithoutExtension}" else "⚠️ ${EmbeddedOfflineBrain.engineState}")
                    refreshDrawerAndHeader()
                }
            },
            onDeleteModel = { file: File ->
                EmbeddedOfflineBrain.deleteModel(this, file)
                toast("🗑 Deleted ${file.name}")
                refreshDrawerAndHeader()
            },
            onDownloadModel = { item: OfflineModelCatalogItem ->
                uiScope.launch {
                    downloadBanner.visibility = View.VISIBLE
                    downloadProgress.isIndeterminate = false
                    downloadProgress.progress = 0
                    val (_, msg) = EmbeddedOfflineBrain.downloadAndActivateModel(this@MainActivity, item) { pct, txt ->
                        runOnUiThread {
                            downloadProgress.progress = pct
                            downloadBannerText.text = txt
                        }
                    }
                    downloadBanner.visibility = View.GONE
                    addAssistantMessage(msg, "Offline Hub")
                    refreshDrawerAndHeader()
                }
            }
        )
    }

    private fun makeRoundBg(fillHex: String, strokeHex: String, radius: Float) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        cornerRadius = radius
        setStroke(2, Color.parseColor(strokeHex))
    }

    private fun makeCircleBg(fillHex: String, strokeHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(fillHex))
        setStroke(2, Color.parseColor(strokeHex))
    }
}
