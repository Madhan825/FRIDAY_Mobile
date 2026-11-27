package com.friday.actionagent.engines.offline

import android.content.Context
import android.os.Environment
import com.friday.actionagent.core.FridayBackgroundService
import com.friday.actionagent.core.SecureVault
import com.friday.actionagent.engines.hub.OpenAiCompatClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

data class OfflineModelCatalogItem(
    val id: String,
    val title: String,
    val badge: String,
    val description: String,
    val sizeLabel: String,
    val fileName: String,
    val downloadUrl: String
)

object EmbeddedOfflineBrain {
    const val EMBEDDED_PORT = 8085
    const val EMBEDDED_CHAT_URL = "http://127.0.0.1:$EMBEDDED_PORT/v1/chat/completions"
    private const val PREF_SAVED_MODEL_PATH = "saved_active_gguf_path"

    private val startMutex = Mutex()

    val CATALOG = listOf(
        OfflineModelCatalogItem(
            id = "qwen25_05b",
            title = "Qwen 2.5 0.5B Instruct",
            badge = "ULTRA FAST",
            description = "Instant replies on mobile • Tamil + English",
            sizeLabel = "468 MB",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        ),
        OfflineModelCatalogItem(
            id = "qwen25_15b",
            title = "Qwen 2.5 1.5B Instruct",
            badge = "RECOMMENDED",
            description = "Best speed + intelligence • Tamil + Coding + Actions",
            sizeLabel = "1.06 GB",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        ),
        OfflineModelCatalogItem(
            id = "llama32_1b",
            title = "Meta Llama 3.2 1B Instruct",
            badge = "FAST CHAT",
            description = "Natural friendly conversation engine",
            sizeLabel = "808 MB",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        ),
        OfflineModelCatalogItem(
            id = "deepseek_r1_15b",
            title = "DeepSeek-R1 1.5B Distill",
            badge = "REASONING",
            description = "Step-by-step logic, math & problem solving",
            sizeLabel = "1.12 GB",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
        ),
        OfflineModelCatalogItem(
            id = "smollm2_17b",
            title = "SmolLM2 1.7B Instruct",
            badge = "COMPACT",
            description = "HuggingFace lightweight general assistant",
            sizeLabel = "1.06 GB",
            fileName = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf"
        ),
        OfflineModelCatalogItem(
            id = "gemma2_2b",
            title = "Google Gemma 2 2B Instruct",
            badge = "QUALITY",
            description = "Strong writing & knowledge offline model",
            sizeLabel = "1.63 GB",
            fileName = "gemma-2-2b-it-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf"
        ),
        OfflineModelCatalogItem(
            id = "qwen25_3b",
            title = "Qwen 2.5 3B Instruct Pro",
            badge = "PRO 2GB",
            description = "High-accuracy Tamil, English, Coding & Tool Use",
            sizeLabel = "1.98 GB",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"
        ),
        OfflineModelCatalogItem(
            id = "llama32_3b",
            title = "Meta Llama 3.2 3B Instruct",
            badge = "PRO 2GB",
            description = "Deep natural conversation & instruction following",
            sizeLabel = "2.02 GB",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
        ),
        OfflineModelCatalogItem(
            id = "phi35_mini",
            title = "Microsoft Phi-3.5 Mini 3.8B",
            badge = "ULTRA 2.4GB",
            description = "Heavyweight offline reasoning & multi-task brain",
            sizeLabel = "2.39 GB",
            fileName = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf"
        )
    )

    @Volatile
    private var serverProcess: Process? = null

    @Volatile
    var loadedModelName: String = ""
        private set

    @Volatile
    var loadedModelPath: String = ""
        private set

    @Volatile
    var engineState: String = "Standby"
        private set

    fun internalModelsDir(context: Context): File {
        val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun hasNativeBinary(context: Context): Boolean {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libfriday_llm.so")
        return bin.exists() && bin.canExecute()
    }

    fun scanAllGgufFiles(context: Context): List<File> {
        val found = mutableListOf<File>()
        val roots = listOfNotNull(
            internalModelsDir(context),
            File(context.filesDir, "models"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0/Download/FRIDAY_Models"),
            File("/storage/emulated/0/Download/FRIDAY_Mobile"),
            File("/storage/emulated/0/Models"),
            File("/storage/emulated/0/Documents")
        ).distinctBy { it.absolutePath }

        fun walk(dir: File, depth: Int) {
            if (depth > 3 || !dir.exists() || !dir.isDirectory) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".gguf") && f.length() > 10_000_000L) {
                    if (found.none { it.absolutePath == f.absolutePath }) found.add(f)
                } else if (f.isDirectory && !f.name.startsWith(".") && f.name != "Android") {
                    walk(f, depth + 1)
                }
            }
        }

        roots.forEach { walk(it, 0) }
        return found.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun isCatalogItemInstalled(context: Context, item: OfflineModelCatalogItem): Boolean {
        val installed = scanAllGgufFiles(context)
        val targetLow = item.fileName.lowercase(Locale.ROOT)
        val stem = targetLow.removeSuffix(".gguf")
        return installed.any { f ->
            val n = f.name.lowercase(Locale.ROOT)
            n == targetLow || n.contains(stem)
        }
    }

    fun isRunning(): Boolean {
        return OpenAiCompatClient.quickPing("http://127.0.0.1:$EMBEDDED_PORT/health")
    }

    /**
     * Picks the best saved or fastest Tamil-capable model and keeps it persistently connected!
     */
    suspend fun autoStartPersistent(context: Context, onProgress: (String) -> Unit = {}): Boolean = startMutex.withLock {
        withContext(Dispatchers.IO) {
            if (isRunning()) {
                if (loadedModelName.isBlank()) {
                    val savedPath = SecureVault.prefs(context).getString(PREF_SAVED_MODEL_PATH, "") ?: ""
                    loadedModelPath = savedPath
                    loadedModelName = File(savedPath).nameWithoutExtension.ifBlank { "Offline AI" }
                }
                engineState = "Connected: $loadedModelName"
                return@withContext true
            }

            val models = scanAllGgufFiles(context)
            if (models.isEmpty()) {
                engineState = "No offline .gguf downloaded yet"
                return@withContext false
            }

            // 1. Prefer user's last explicitly connected model
            val savedPath = SecureVault.prefs(context).getString(PREF_SAVED_MODEL_PATH, "") ?: ""
            val savedFile = models.firstOrNull { it.absolutePath == savedPath }

            // 2. Otherwise prefer Qwen 2.5 1.5B -> Qwen 2.5 0.5B -> Qwen 3B -> Llama -> first available
            val best = savedFile
                ?: models.firstOrNull { it.name.lowercase(Locale.ROOT).contains("qwen2.5-1.5b") }
                ?: models.firstOrNull { it.name.lowercase(Locale.ROOT).contains("qwen2.5-0.5b") }
                ?: models.firstOrNull { it.name.lowercase(Locale.ROOT).contains("qwen") }
                ?: models.firstOrNull { it.name.lowercase(Locale.ROOT).contains("llama") }
                ?: models.first()

            startModelInternal(context, best, onProgress)
        }
    }

    suspend fun startModel(context: Context, modelFile: File, onProgress: (String) -> Unit = {}): Boolean = startMutex.withLock {
        withContext(Dispatchers.IO) {
            startModelInternal(context, modelFile, onProgress)
        }
    }

    private suspend fun startModelInternal(context: Context, modelFile: File, onProgress: (String) -> Unit): Boolean {
        if (isRunning() && loadedModelPath == modelFile.absolutePath) {
            engineState = "Connected: ${modelFile.nameWithoutExtension}"
            return true
        }
        stop()

        val bin = File(context.applicationInfo.nativeLibraryDir, "libfriday_llm.so")
        if (!bin.exists()) {
            engineState = "Native engine unavailable"
            return false
        }
        if (!modelFile.exists() || !modelFile.canRead()) {
            engineState = "Storage permission required for ${modelFile.name}"
            return false
        }

        try {
            engineState = "Connecting ${modelFile.nameWithoutExtension}..."
            onProgress("Connecting ${modelFile.nameWithoutExtension}...")

            // Keep alive in foreground service so Android never kills the offline LLM process
            try { FridayBackgroundService.start(context.applicationContext) } catch (_: Exception) {}

            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
            val pb = ProcessBuilder(
                bin.absolutePath,
                "-m", modelFile.absolutePath,
                "--host", "127.0.0.1",
                "--port", EMBEDDED_PORT.toString(),
                "-c", "2048",
                "-b", "256",
                "-t", threads.toString(),
                "--parallel", "1",
                "-ngl", "0"
            ).apply {
                redirectErrorStream(true)
            }
            serverProcess = pb.start()
            loadedModelName = modelFile.nameWithoutExtension
            loadedModelPath = modelFile.absolutePath

            repeat(60) {
                delay(400L)
                if (serverProcess?.isAlive != true) {
                    engineState = "Could not load ${modelFile.name}"
                    loadedModelName = ""
                    loadedModelPath = ""
                    return false
                }
                if (isRunning()) {
                    SecureVault.prefs(context).edit().putString(PREF_SAVED_MODEL_PATH, modelFile.absolutePath).apply()
                    engineState = "Connected: $loadedModelName"
                    onProgress("Connected: $loadedModelName")
                    return true
                }
            }
            engineState = "Model load timed out"
            return false
        } catch (e: Exception) {
            engineState = "Error: ${e.message}"
            return false
        }
    }

    fun stop() {
        try { serverProcess?.destroy() } catch (_: Exception) {}
        serverProcess = null
        loadedModelName = ""
        loadedModelPath = ""
    }

    fun deleteModel(context: Context, modelFile: File): Boolean {
        if (loadedModelPath == modelFile.absolutePath) {
            stop()
            SecureVault.prefs(context).edit().remove(PREF_SAVED_MODEL_PATH).apply()
        }
        return try { modelFile.delete() } catch (_: Exception) { false }
    }

    suspend fun downloadAndActivateModel(
        context: Context,
        item: OfflineModelCatalogItem,
        onPercent: (Int, String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val dir = internalModelsDir(context)
        val finalFile = File(dir, item.fileName)
        val tmpFile = File(dir, "${item.fileName}.part")

        try {
            if (finalFile.exists() && finalFile.length() > 50_000_000L) {
                onPercent(100, "Connecting ${item.title}...")
                val started = startModel(context, finalFile)
                return@withContext if (started) true to "✅ ${item.title} இணைக்கப்பட்டது!" else false to "⚠️ லோட் ஆகவில்லை: $engineState"
            }

            val dlClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            val req = Request.Builder().url(item.downloadUrl).build()
            dlClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false to "❌ Download failed (HTTP ${resp.code})"
                val body = resp.body ?: return@withContext false to "❌ Empty response"
                val total = body.contentLength()
                var downloaded = 0L
                var lastPct = -1

                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r == -1) break
                            output.write(buf, 0, r)
                            downloaded += r
                            val mb = downloaded / (1024 * 1024)
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onPercent(pct, "Downloading ${item.title} • $pct% ($mb MB)")
                                }
                            } else {
                                onPercent(0, "Downloading ${item.title} • $mb MB")
                            }
                        }
                    }
                }
            }

            if (finalFile.exists()) finalFile.delete()
            tmpFile.renameTo(finalFile)
            onPercent(100, "Activating ${item.title}...")
            val started = startModel(context, finalFile)
            if (started) true to "✅ ${item.title} டவுன்லோட் ஆகி உடனே இணைக்கப்பட்டது!"
            else false to "⚠️ டவுன்லோட் ஆனது (${finalFile.name}), ஆனால் லோட் ஆகவில்லை: $engineState"
        } catch (e: Exception) {
            tmpFile.delete()
            false to "⚠️ Download error: ${e.message}"
        }
    }
}
