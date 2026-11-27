package com.friday.actionagent.engines.memory

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.friday.actionagent.core.SecureVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

data class ChatTurn(
    val role: String,
    val text: String,
    val engine: String = "",
    val steps: String = ""
)

data class ChatSession(
    val id: String,
    var title: String,
    var updatedAt: Long,
    val messages: MutableList<ChatTurn> = mutableListOf()
)

object PersonalMemoryEngine {
    private const val KEY_CONTACTS = "saved_contacts_json"
    private const val KEY_FACTS = "saved_facts_json"
    private const val KEY_LAST_NUM = "last_mentioned_phone"
    private const val SESSIONS_FILE = "friday_chat_sessions_v7.json"

    private var sessionsCache: MutableList<ChatSession>? = null
    var activeSession: ChatSession = ChatSession(UUID.randomUUID().toString(), "New chat", System.currentTimeMillis())
        private set

    val activeConversation: List<ChatTurn>
        get() = activeSession.messages

    private fun sessionsFile(context: Context): File = File(context.applicationContext.filesDir, SESSIONS_FILE)

    fun loadAllSessions(context: Context): MutableList<ChatSession> {
        sessionsCache?.let { return it }
        val list = mutableListOf<ChatSession>()
        try {
            val f = sessionsFile(context)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val msgsArr = o.optJSONArray("messages") ?: JSONArray()
                    val msgs = mutableListOf<ChatTurn>()
                    for (m in 0 until msgsArr.length()) {
                        val mo = msgsArr.getJSONObject(m)
                        msgs.add(
                            ChatTurn(
                                role = mo.optString("role", "user"),
                                text = mo.optString("text", ""),
                                engine = mo.optString("engine", ""),
                                steps = mo.optString("steps", "")
                            )
                        )
                    }
                    list.add(
                        ChatSession(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            title = o.optString("title", "Chat"),
                            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                            messages = msgs
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        list.sortByDescending { it.updatedAt }
        sessionsCache = list
        return list
    }

    private fun persistSessions(context: Context) {
        try {
            val list = loadAllSessions(context)
            val arr = JSONArray()
            for (s in list.take(35)) {
                val mArr = JSONArray()
                for (m in s.messages.takeLast(80)) {
                    mArr.put(
                        JSONObject()
                            .put("role", m.role)
                            .put("text", m.text)
                            .put("engine", m.engine)
                            .put("steps", m.steps)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("updatedAt", s.updatedAt)
                        .put("messages", mArr)
                )
            }
            sessionsFile(context).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun startFreshSession(): ChatSession {
        activeSession = ChatSession(UUID.randomUUID().toString(), "New chat", System.currentTimeMillis())
        return activeSession
    }

    fun openSession(context: Context, sessionId: String): ChatSession? {
        val found = loadAllSessions(context).firstOrNull { it.id == sessionId } ?: return null
        activeSession = found
        return found
    }

    fun deleteSession(context: Context, sessionId: String) {
        val list = loadAllSessions(context)
        list.removeAll { it.id == sessionId }
        if (activeSession.id == sessionId) {
            startFreshSession()
        }
        persistSessions(context)
    }

    fun recordTurn(context: Context, role: String, text: String, engine: String = "", steps: String = "") {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (activeSession.messages.isEmpty() && role == "user") {
            activeSession.title = clean.take(44)
        }
        activeSession.messages.add(ChatTurn(role, clean, engine, steps))
        activeSession.updatedAt = System.currentTimeMillis()

        val list = loadAllSessions(context)
        list.removeAll { it.id == activeSession.id }
        list.add(0, activeSession)
        persistSessions(context)
    }

    fun rememberLastNumber(context: Context, rawNumber: String) {
        val clean = rawNumber.replace(Regex("""[^\d+]"""), "")
        if (clean.length >= 6) {
            SecureVault.prefs(context).edit().putString(KEY_LAST_NUM, clean).apply()
        }
    }

    fun getLastNumber(context: Context): String {
        return SecureVault.prefs(context).getString(KEY_LAST_NUM, "") ?: ""
    }

    fun saveContactAlias(context: Context, name: String, number: String): String {
        val cleanName = name.trim()
        val cleanNum = number.replace(Regex("""[^\d+]"""), "")
        if (cleanName.isEmpty() || cleanNum.length < 5) return "பெயர் அல்லது நம்பர் சரியாக இல்லை."

        val prefs = SecureVault.prefs(context)
        val obj = try { JSONObject(prefs.getString(KEY_CONTACTS, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val key = normalizeAlias(cleanName)
        obj.put(key, cleanNum)
        obj.put(cleanName.lowercase(Locale.ROOT), cleanNum)
        prefs.edit().putString(KEY_CONTACTS, obj.toString()).apply()
        return "✅ '$cleanName' நம்பர் ($cleanNum) சேமிக்கப்பட்டது! இனி '$cleanName-க்கு கால் பண்ணு' எனச் சொன்னால் உடனே கால் செய்வேன்."
    }

    fun saveFact(context: Context, fact: String): String {
        val prefs = SecureVault.prefs(context)
        val arr = try { JSONArray(prefs.getString(KEY_FACTS, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        arr.put(fact.trim())
        while (arr.length() > 40) arr.remove(0)
        prefs.edit().putString(KEY_FACTS, arr.toString()).apply()
        return "✅ ஞாபகம் வச்சுக்கிட்டேன்: \"$fact\""
    }

    fun lookupPhoneNumber(context: Context, queryName: String): Pair<String, String>? {
        val norm = normalizeAlias(queryName)
        val rawLow = queryName.trim().lowercase(Locale.ROOT)

        val prefs = SecureVault.prefs(context)
        val obj = try { JSONObject(prefs.getString(KEY_CONTACTS, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == norm || k == rawLow || k.contains(norm) || norm.contains(k)) {
                return k to obj.getString(k)
            }
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val candidates = buildSearchAliases(queryName)
            try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null, null
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext()) {
                        val dName = cursor.getString(nameIdx) ?: continue
                        val dNum = cursor.getString(numIdx) ?: continue
                        val dLow = dName.lowercase(Locale.ROOT)
                        if (candidates.any { dLow.contains(it) }) {
                            return dName to dNum.replace(Regex("""[^\d+]"""), "")
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun buildMemoryContextPrompt(context: Context): String {
        val prefs = SecureVault.prefs(context)
        val contacts = prefs.getString(KEY_CONTACTS, "{}") ?: "{}"
        val facts = prefs.getString(KEY_FACTS, "[]") ?: "[]"
        val lastNum = getLastNumber(context)
        val sb = StringBuilder()
        if (contacts != "{}") sb.append("Saved Contacts: $contacts\n")
        if (facts != "[]") sb.append("User Memory: $facts\n")
        if (lastNum.isNotEmpty()) sb.append("Last Phone Number: $lastNum\n")
        return sb.toString()
    }

    private fun normalizeAlias(name: String): String {
        var s = name.trim().lowercase(Locale.ROOT)
        s = s.replace(Regex("""(க்கு|உக்கு|கிட்ட|நம்பர்|number|phone)$"""), "").trim()
        return when {
            s.contains("மம்மி") || s.contains("அம்மா") || s.contains("mummy") || s.contains("mom") || s.contains("amma") -> "mummy"
            s.contains("அப்பா") || s.contains("டேடி") || s.contains("dad") || s.contains("appa") -> "appa"
            s.contains("அண்ணன்") || s.contains("அண்ணா") || s.contains("bro") -> "anna"
            s.contains("ஆயா") || s.contains("பாட்டி") -> "aaya"
            else -> s
        }
    }

    private fun buildSearchAliases(name: String): List<String> {
        val norm = normalizeAlias(name)
        return when (norm) {
            "mummy" -> listOf("mummy", "mom", "amma", "uma", "மம்மி", "அம்மா")
            "appa" -> listOf("appa", "dad", "daddy", "father", "அப்பா")
            "aaya" -> listOf("aaya", "paati", "grandma", "ஆயா")
            else -> listOf(norm, name.trim().lowercase(Locale.ROOT)).distinct()
        }
    }
}
