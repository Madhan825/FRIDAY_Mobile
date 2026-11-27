package com.friday.actionagent.engines.nlp

import android.content.Context
import com.friday.actionagent.engines.memory.PersonalMemoryEngine
import java.util.Locale

data class ParsedIntent(
    val intent: String, // call_phone, save_contact, save_fact, youtube_search, chrome_search, maps, whatsapp, flashlight, battery, time_date, alarm, open_app, click, scroll, volume, volume_step, back, home, test_cursor, chat
    val app: String = "",
    val query: String = "",
    val target: String = "",
    val phoneNumber: String = "",
    val contactName: String = "",
    val direction: String = "down",
    val volume: Int = -1,
    val flag: Boolean = false
)

object SmartIntentEngine {

    private val bilingualPhraseMap = linkedMapOf(
        "காட்ஸ் எல்லாம் மைனஸ் ஒன்னு" to "Godzilla Minus One",
        "காட்ஸில்லா மைனஸ் ஒன்னு" to "Godzilla Minus One",
        "காட்ஜில்லா மைனஸ் ஒன்னு" to "Godzilla Minus One",
        "காட்ஸ் இல்லாம" to "Godzilla",
        "காட்ஸ் எல்லாம்" to "Godzilla",
        "கேர்ள்ஸ் எல்லாம்" to "Godzilla",
        "காட்ஜில்லா" to "Godzilla",
        "காட்ஸில்லா" to "Godzilla",
        "காட்ஸிலா" to "Godzilla",
        "காட்ஜிலா" to "Godzilla",
        "மைனஸ் ஒன்னு" to "Minus One",
        "மைனஸ் ஒன்" to "Minus One",
        "ஸ்பைடர் மேன்" to "Spider Man",
        "அயர்ன் மேன்" to "Iron Man",
        "ஜனநாயகன்" to "Jana Nayagan",
        "விஜய் சாங்ஸ்" to "Vijay songs",
        "விஜய் சாங்" to "Vijay song",
        "விஜய் பாட்டு" to "Vijay song",
        "விஜய்" to "Vijay",
        "அஜித்" to "Ajith",
        "சூர்யா" to "Surya",
        "ரஜினி" to "Rajini",
        "தனுஷ்" to "Dhanush",
        "அனிருத்" to "Anirudh",
        "ஏ ஆர் ரஹ்மான்" to "AR Rahman",
        "ஜிடிஏ 6" to "GTA 6",
        "ஜிடிஏ 5" to "GTA 5",
        "ஜிடிஏ" to "GTA",
        "சாங்ஸ்" to "songs",
        "சாங்" to "song",
        "பாட்டு" to "song",
        "பாடல்கள்" to "songs",
        "ட்ரெய்லர்" to "trailer",
        "டிரெய்லர்" to "trailer",
        "டீசர்" to "teaser",
        "மூவி" to "movie",
        "படம்" to "movie",
        "வீடியோ" to "video",
        "கேம்ஸ்" to "games",
        "கேம்" to "game",
        "மாடல்" to "model",
        "சாப்ட்வேர்" to "software",
        "டவுன்லோட்" to "download",
        "காமெடி" to "comedy",
        "நியூஸ்" to "news",
        "தமிழ்" to "Tamil"
    )

    private val commandFillers = listOf(
        "ஆஃப்லைனில் ரன் ஆகட்டும்", "ஆப்லைனில் ரன் ஆகட்டும்", "ஆஃப்லைன்ல ரன் ஆகட்டும்",
        "உன்னோட குரல் யூஸ் பண்ணி", "உன் குரல் யூஸ் பண்ணி", "வாய்ஸ் யூஸ் பண்ணி",
        "யூடியூப் ஓப்பன் பண்ணிட்டு", "யூடியூப் ஓப்பன் பண்ணி", "யூடியூப் ஓபன் பண்ணிட்டு", "யூடியூப் ஓபன் பண்ணி", "யூட்யூப் ஓபன் பண்ணி",
        "யூடியூப் திறந்து", "யூடியூபை திறந்து", "யூடியூப்ல போய்", "யூடியூப்ல", "யூடியூபில்",
        "youtube ஓப்பன் பண்ணிட்டு", "youtube ஓப்பன் பண்ணி", "youtube ஓபன் பண்ணிட்டு", "youtube ஓபன் பண்ணி",
        "youtubeஐ திறந்து", "youtube-ஐ திறந்து", "youtube-ல போய்", "youtube ல போய்", "youtube-ல", "youtubeல", "youtube ல",
        "ஓப்பன் youtube அண்ட் சர்ச்", "ஓபன் youtube அண்ட் சர்ச்", "ஓப்பன் youtube", "ஓபன் youtube",
        "open youtube and search", "open youtube and play", "open youtube and type", "open youtube",
        "youtube search", "in youtube", "on youtube",
        "குரோம் ஓப்பன் பண்ணி", "குரோம் ஓபன் பண்ணிட்டு", "குரோம் ஓபன் பண்ணி", "குரோம் திறந்து", "குரோமில்", "குரோம்ல",
        "chrome ஓபன் பண்ணி", "chrome-ல", "chrome ல", "open chrome and search", "open chrome", "google search",
        "ஓப்பன் பண்ணிட்டு", "ஓப்பன் பண்ணி", "ஓப்பன் செய்", "ஓப்பன் பண்ணு", "ஓப்பன்",
        "ஓபன் பண்ணிட்டு", "ஓபன் பண்ணி", "ஓபன் செய்", "ஓபன் பண்ணு", "ஓபன்",
        "திறந்துட்டு", "திறந்து", "திற",
        "அண்ட் சர்ச்", "அன்ட் சர்ச்", "ஆண்ட் சர்ச்", "அண்ட்", "அன்ட்", "ஆண்ட்",
        "என்று சர்ச் பண்ணுங்க", "என்று சர்ச் பண்ணு", "ன்னு சர்ச் பண்ணுங்க", "ன்னு சர்ச் பண்ணு",
        "நு சர்ச் பண்ணு", "சர்ச் பண்ணுங்க", "சர்ச் பண்ணு", "சர்ச் செய்", "சர்ச்",
        "என்று டைப் பண்ணுங்க", "என்று டைப் பண்ணு", "ன்னு டைப் பண்ணு", "டைப் பண்ணுங்க", "டைப் பண்ணு", "டைப் செய்", "டைப்",
        "மெசேஜ் பண்ணு", "என்று தேடு", "ன்னு தேடு", "தேடுங்க", "தேடு", "போடுங்க", "போடு", "பிளே பண்ணு", "ப்ளே பண்ணு",
        "என்று", "என", "சொல்லி", "பண்ணுங்க", "பண்ணு", "பண்ணி", "காட்டு",
        "youtube", "யூடியூப்", "யூட்யூப்", "chrome", "குரோம்"
    )

    /**
     * Instant (<5ms) natural bilingual parser — ZERO slow LLM calls here!
     * If not a direct fast command, returns "chat" so the AI brain handles it naturally in ONE single pass!
     */
    fun understandFast(context: Context, rawInput: String): ParsedIntent {
        val text = rawInput.trim()
        val low = text.lowercase(Locale.ROOT)

        val digitsMatch = Regex("""\+?\d[\d\s-]{5,14}\d""").find(text)
        if (digitsMatch != null) {
            PersonalMemoryEngine.rememberLastNumber(context, digitsMatch.value)
        }

        // 1. Time / Date / Battery (instant offline answers!)
        if (low.contains("டைம்") || low.contains("நேரம்") || low.contains("மணி என்ன") ||
            low.contains("what time") || low.contains("current time") || low == "time" ||
            low.contains("தேதி") || low.contains("டேட்") || low.contains("what date") || low.contains("today date")
        ) {
            return ParsedIntent("time_date", query = text)
        }
        if (low.contains("பேட்டரி") || low.contains("battery") || low.contains("சார்ஜ் எவ்வளவு")) {
            return ParsedIntent("battery")
        }

        // 2. Flashlight / Torch
        if (low.contains("torch") || low.contains("flashlight") || low.contains("டார்ச்") || low.contains("ஃபிளாஷ்")) {
            val turnOff = low.contains("off") || low.contains("ஆஃப்") || low.contains("ஆப்") || low.contains("நிறுத்து")
            return ParsedIntent("flashlight", flag = !turnOff)
        }

        // 3. Cursor Test & Volume
        if (low.contains("test cursor") || low.contains("cursor test") || low.contains("கர்சர் டெஸ்ட்") || low == "cursor" || low == "mouse") {
            return ParsedIntent("test_cursor")
        }
        val volMatch = Regex("""(?:volume|sound|வால்யூம்|சவுண்ட்).*?(\d{1,3})\s*%?""").find(low)
        if (volMatch != null) {
            return ParsedIntent("volume", volume = volMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: 50)
        }
        if (low.contains("volume up") || low.contains("சவுண்ட் அதிகம்") || low.contains("சவுண்ட் ஏத்து")) {
            return ParsedIntent("volume_step", flag = true)
        }
        if (low.contains("volume down") || low.contains("சவுண்ட் குறை") || low.contains("சவுண்ட் கம்மி")) {
            return ParsedIntent("volume_step", flag = false)
        }

        // 4. Save Contact Number
        if ((low.contains("சேவ்") || low.contains("save")) &&
            (low.contains("நம்பர்") || low.contains("number") || digitsMatch != null)
        ) {
            val num = digitsMatch?.value?.replace(Regex("""[^\d+]"""), "") ?: PersonalMemoryEngine.getLastNumber(context)
            val name = extractContactName(text)
            if (num.isNotEmpty() && name.isNotEmpty()) {
                return ParsedIntent("save_contact", contactName = name, phoneNumber = num)
            }
        }

        // 5. Phone Call
        if (low.contains("கால் பண்ணு") || low.contains("கால் செய்") || low.contains("போன் பண்ணு") ||
            low.startsWith("call ") || low.contains(" call ") || low.contains("dial ")
        ) {
            val num = digitsMatch?.value?.replace(Regex("""[^\d+]"""), "") ?: ""
            val name = if (num.isEmpty()) extractContactName(text) else ""
            return ParsedIntent("call_phone", contactName = name, phoneNumber = num)
        }

        // 6. Save Memory Fact
        if (low.contains("ஞாபகம் வச்சுக்கோ") || low.startsWith("remember ")) {
            val fact = text.replace(Regex("""(?i)^(remember\s+|ஞாபகம் வச்சுக்கோ\s*)"""), "").trim()
            if (fact.isNotEmpty()) return ParsedIntent("save_fact", query = fact)
        }

        // 7. Navigation
        if (low == "home" || low == "go home" || low == "ஹோம்" || low.contains("ஹோம் போ")) return ParsedIntent("home")
        if (low == "back" || low == "go back" || low == "பேக்" || low.contains("பின்னாடி போ")) return ParsedIntent("back")
        if (low.contains("scroll") || low.contains("swipe") || low.contains("ஸ்க்ரோல்")) {
            val dir = when {
                low.contains("up") || low.contains("மேலே") -> "up"
                low.contains("left") || low.contains("இடது") -> "left"
                low.contains("right") || low.contains("வலது") -> "right"
                else -> "down"
            }
            return ParsedIntent("scroll", direction = dir)
        }

        // 8. Alarm
        val alarmMatch = Regex("""(?:alarm|அலாரம்).*?(\d{1,2})(?::(\d{2}))?""").find(low)
        if (alarmMatch != null) {
            val h = alarmMatch.groupValues[1].toIntOrNull() ?: 7
            val m = alarmMatch.groupValues[2].toIntOrNull() ?: 0
            return ParsedIntent("alarm", volume = h * 60 + m)
        }

        // 9. Maps / Directions
        if (low.contains("map") || low.contains("மேப்") || low.contains("வழி காட்டு") || low.contains("directions to")) {
            val place = text.replace(Regex("""(?i)(google maps|maps|மேப்|வழி காட்டு|directions to|open|ஓபன்|ஓப்பன்|பண்ணு|சர்ச்)"""), " ")
                .replace(Regex("""\s+"""), " ").trim()
            if (place.isNotEmpty()) return ParsedIntent("maps", query = place)
        }

        // 10. YouTube Search or Open
        val hasYoutube = low.contains("youtube") || low.contains("யூடியூப்") || low.contains("யூட்யூப்")
        if (hasYoutube) {
            val query = extractCleanQuery(text)
            if (query.isNotEmpty()) {
                return ParsedIntent("youtube_search", app = "YouTube", query = query)
            }
            return ParsedIntent("open_app", app = "YouTube")
        }

        // 11. Chrome / Google Search
        val hasChrome = low.contains("chrome") || low.contains("குரோம்") || low.contains("google") || low.contains("கூகுள்")
        val hasSearchVerb = listOf("search", "சர்ச்", "தேடு", "download", "டவுன்லோட்").any { low.contains(it) }
        if ((hasChrome && hasSearchVerb) || low.startsWith("search ") || low.startsWith("google ")) {
            val query = extractCleanQuery(text)
            if (query.isNotEmpty()) return ParsedIntent("chrome_search", app = "Chrome", query = query)
            if (hasChrome) return ParsedIntent("open_app", app = "Chrome")
        }

        // 12. Click visible UI text
        val clickEn = Regex("""^(?:click|tap|touch)\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
        val clickTa = Regex("""(.+?)\s*(?:கிளிக்|டச்)(?:\s+பண்ணு|\s+செய்)?$""").find(text)
        if ((clickEn != null || clickTa != null) && !low.contains("open ") && !low.contains("ஓபன்") && !low.contains("ஓப்பன்")) {
            val target = (clickEn ?: clickTa)!!.groupValues[1].trim()
            if (target.isNotEmpty()) return ParsedIntent("click", target = target)
        }

        // 13. Open ANY Installed App
        val openEn = Regex("""^(?:open|launch)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
        val openTa = Regex("""^(.+?)\s*(?:ஓபன்|ஓப்பன்|திற)(?:\s+பண்ணு|\s+செய்)?$""").find(text)
        if (openEn != null || openTa != null) {
            val app = (openEn ?: openTa)!!.groupValues[1].trim()
            if (app.isNotEmpty()) return ParsedIntent("open_app", app = app)
        }

        // Otherwise -> let our Single-Pass Universal AI Brain handle naturally (can answer OR invoke tools!)
        return ParsedIntent("chat", query = text)
    }

    private fun extractContactName(raw: String): String {
        var s = raw
        val remove = listOf(
            "இது வந்து", "இது", "வந்து", "இந்த நம்பருக்கு", "இந்த நம்பர்", "நம்பருக்கு", "நம்பர்",
            "அதை சேவ் பண்ணிக்கோ", "சேவ் பண்ணிக்கோ", "சேவ் பண்ணு", "சேவ் செய்", "சேவ்", "save",
            "கால் பண்ணுங்க", "கால் பண்ணு", "கால் செய்", "கால்", "போன் பண்ணு", "போன் செய்",
            "call", "dial", "phone", "number", "this is", "my", "to"
        )
        s = s.replace(Regex("""\+?\d[\d\s-]{5,14}\d"""), " ")
        for (r in remove) s = s.replace(r, " ", ignoreCase = true)
        s = s.replace(Regex("""(க்கு|உக்கு|கிட்ட)(?=\s|$)"""), "")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    fun extractCleanQuery(raw: String): String {
        var s = raw.replace(Regex("""[,\.!?\-]+"""), " ").trim()
        s = s.replace(Regex("""(?i)(youtube|chrome|google)(?:ஐ|ல|ல்|க்கு|இல்|உள்ள|குள்ள)"""), " ")

        for ((ta, en) in bilingualPhraseMap) {
            if (s.contains(ta)) s = s.replace(ta, " $en ")
        }
        for (phrase in commandFillers) {
            s = s.replace(phrase, " ", ignoreCase = true)
        }

        s = s.replace(Regex("""(?<=^|\s)(?:ல|ல்|ஐ|கு|க்கு|உள்ள|இல்)(?=\s|$)"""), " ")

        val enStopWords = listOf("open", "launch", "and", "then", "search", "for", "type", "play", "find", "in", "on", "to", "me", "please", "panni", "pannu", "nu", "nnu", "la")
        for (w in enStopWords) {
            s = s.replace(Regex("""(?i)\b${Regex.escape(w)}\b"""), " ")
        }

        s = s.split(Regex("""\s+""")).joinToString(" ") { word ->
            if (word == "ஒன்னு" || word.length <= 3) word
            else word.replace(Regex("""(ன்னு|னு|ன்றத|ன்றது|ஐ)$"""), "")
        }

        s = s.replace(Regex("""\s+"""), " ").trim()

        for ((ta, en) in bilingualPhraseMap) {
            if (s.contains(ta)) s = s.replace(ta, " $en ")
        }
        s = s.replace(Regex("""\s+"""), " ").trim()

        if (s.any { it in '\u0B80'..'\u0BFF' }) {
            s = transliterateTamilToEnglish(s)
        }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    fun transliterateTamilToEnglish(input: String): String {
        val vowels = mapOf(
            'அ' to "a", 'ஆ' to "aa", 'இ' to "i", 'ஈ' to "ee", 'உ' to "u", 'ஊ' to "oo",
            'எ' to "e", 'ஏ' to "ae", 'ஐ' to "ai", 'ஒ' to "o", 'ஓ' to "oo", 'ஔ' to "au", 'ஃ' to "h"
        )
        val consonants = mapOf(
            'க' to "k", 'ங' to "ng", 'ச' to "s", 'ஜ' to "j", 'ஞ' to "nj", 'ட' to "t",
            'ண' to "n", 'த' to "th", 'ந' to "n", 'ன' to "n", 'ப' to "p", 'ம' to "m",
            'ய' to "y", 'ர' to "r", 'ற' to "r", 'ல' to "l", 'ள' to "l", 'ழ' to "zh",
            'வ' to "v", 'ஷ' to "sh", 'ஸ' to "s", 'ஹ' to "h"
        )
        val vowelSigns = mapOf(
            'ா' to "a", 'ி' to "i", 'ீ' to "ee", 'ு' to "u", 'ூ' to "oo",
            'ெ' to "e", 'ே' to "e", 'ை' to "ai", 'ொ' to "o", 'ோ' to "o", 'ௌ' to "au", '்' to ""
        )
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                vowels.containsKey(ch) -> sb.append(vowels[ch])
                consonants.containsKey(ch) -> {
                    val base = consonants[ch]!!
                    val next = if (i + 1 < input.length) input[i + 1] else '\u0000'
                    if (vowelSigns.containsKey(next)) {
                        sb.append(base).append(vowelSigns[next])
                        i++
                    } else {
                        sb.append(base).append("a")
                    }
                }
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}
