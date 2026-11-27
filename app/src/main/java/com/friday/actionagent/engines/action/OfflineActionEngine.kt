package com.friday.actionagent.engines.action

import android.content.Context
import android.provider.Settings
import com.friday.actionagent.core.FridayAccessibilityService
import com.friday.actionagent.core.LocalSystemLayer
import com.friday.actionagent.engines.memory.PersonalMemoryEngine
import com.friday.actionagent.engines.nlp.ParsedIntent
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OfflineActionEngine {

    suspend fun executeParsedIntent(
        context: Context,
        parsed: ParsedIntent,
        onLog: (String) -> Unit
    ): String? {
        val service = FridayAccessibilityService.instance
        val hasOverlay = Settings.canDrawOverlays(context)

        return when (parsed.intent) {
            "time_date" -> {
                val now = SimpleDateFormat("hh:mm a, EEEE, dd MMM yyyy", Locale.getDefault()).format(Date())
                "🕒 இப்போது நேரம்: $now"
            }

            "battery" -> LocalSystemLayer.getBatteryStatus(context)

            "flashlight" -> LocalSystemLayer.setFlashlight(context, parsed.flag)

            "alarm" -> {
                val totalMin = if (parsed.volume >= 0) parsed.volume else 420
                LocalSystemLayer.setAlarm(context, totalMin / 60, totalMin % 60)
            }

            "maps" -> LocalSystemLayer.openMaps(context, parsed.query)

            "whatsapp" -> LocalSystemLayer.openWhatsAppMessage(context, parsed.phoneNumber, parsed.query)

            "save_contact" -> {
                val num = parsed.phoneNumber.ifBlank { PersonalMemoryEngine.getLastNumber(context) }
                val name = parsed.contactName.ifBlank { parsed.query }
                if (num.isBlank() || name.isBlank()) {
                    "யார் நம்பர் மற்றும் என்ன நம்பர் என்பதைச் சொல் (எ.கா: 'மம்மி நம்பர் 9876543210 சேவ் பண்ணு')."
                } else {
                    PersonalMemoryEngine.saveContactAlias(context, name, num)
                }
            }

            "save_fact" -> PersonalMemoryEngine.saveFact(context, parsed.query)

            "call_phone" -> {
                if (parsed.phoneNumber.isNotBlank()) {
                    PersonalMemoryEngine.rememberLastNumber(context, parsed.phoneNumber)
                    return LocalSystemLayer.placePhoneCall(context, parsed.phoneNumber, parsed.contactName)
                }
                val name = parsed.contactName.ifBlank { parsed.query }
                if (name.isBlank()) return "யாருக்கு அல்லது எந்த நம்பருக்கு கால் பண்ண வேண்டும் என்று சொல்."
                val hit = PersonalMemoryEngine.lookupPhoneNumber(context, name)
                if (hit != null) {
                    LocalSystemLayer.placePhoneCall(context, hit.second, hit.first)
                } else {
                    "'$name' நம்பர் கிடைக்கவில்லை. முதலில் நம்பரைச் சொல்லி 'இது $name நம்பர் சேவ் பண்ணு' எனச் சொல்."
                }
            }

            "volume" -> LocalSystemLayer.setMediaVolume(context, if (parsed.volume >= 0) parsed.volume else 50)
            "volume_step" -> LocalSystemLayer.adjustVolume(context, parsed.flag)

            "test_cursor" -> {
                if (!hasOverlay) return "⚠️ Cursor Overlay OFF-இல் உள்ளது! மெனு (☰) → Permissions திறந்து ON செய்."
                if (service == null) return "⚠️ Accessibility OFF-இல் உள்ளது! மெனு (☰) → Permissions திறந்து FRIDAY-ஐ ON செய்."
                service.overlay?.show("Testing cursor...")
                val dm = context.resources.displayMetrics
                val w = dm.widthPixels.toFloat()
                val h = dm.heightPixels.toFloat()
                onLog("Moving cursor across screen")
                service.overlay?.moveCursorTo(w * 0.5f, h * 0.35f, true)
                delay(600)
                service.overlay?.moveCursorTo(w * 0.78f, h * 0.55f, true)
                delay(600)
                service.performSwipe("down")
                delay(300)
                service.overlay?.hideCursorOnly()
                "✅ கர்சர் + டச் + ஸ்க்ரோல் 100% வேலை செய்கிறது!"
            }

            "home" -> {
                service ?: return "⚠️ Accessibility ON செய்."
                service.goHome()
                "Home திரைக்குச் சென்றேன்."
            }

            "back" -> {
                service ?: return "⚠️ Accessibility ON செய்."
                service.goBack()
                "Back அழுத்தினேன்."
            }

            "scroll" -> {
                service ?: return "⚠️ Accessibility ON செய்."
                service.overlay?.show("Scrolling ${parsed.direction}...")
                if (service.currentPackageName() == context.packageName) {
                    service.goHome()
                    delay(650)
                }
                val ok = service.performSwipe(parsed.direction)
                service.overlay?.hideCursorOnly()
                if (ok) "${parsed.direction} திசையில் ஸ்க்ரோல் செய்தேன்." else "ஸ்க்ரோல் செய்ய முடியவில்லை."
            }

            "click" -> {
                service ?: return "⚠️ Accessibility ON செய்."
                val target = parsed.target.ifBlank { parsed.query }
                if (target.isBlank()) return null
                service.overlay?.show("Clicking '$target'...")
                if (service.currentPackageName() == context.packageName) {
                    service.goHome()
                    delay(850)
                }
                val ok = service.clickVisibleText(target)
                service.overlay?.hideCursorOnly()
                if (ok) "'$target' மீது கிளிக் செய்தேன்." else "திரையில் '$target' கிடைக்கவில்லை."
            }

            "youtube_search" -> {
                val cleanQuery = parsed.query.trim()
                if (cleanQuery.isEmpty()) {
                    val (_, msg) = LocalSystemLayer.openInstalledApp(context, "youtube")
                    return msg
                }

                onLog("Query: \"$cleanQuery\"")
                val alreadyInYt = service?.currentPackageName() == "com.google.android.youtube"
                if (!alreadyInYt) {
                    val (opened, msg) = LocalSystemLayer.openInstalledApp(context, "youtube")
                    onLog(msg)
                    if (!opened) return msg
                }

                if (service == null) {
                    val enc = URLEncoder.encode(cleanQuery, "UTF-8")
                    LocalSystemLayer.openUrl(context, "https://www.youtube.com/results?search_query=$enc")
                    return "YouTube-இல் '$cleanQuery' திறந்தேன்."
                }

                service.overlay?.show("YouTube: $cleanQuery")
                delay(if (alreadyInYt) 800L else 2300L)

                onLog("Tapping Search")
                val clickedSearch = service.clickVisibleText("Search")
                    || service.clickVisibleText("Search YouTube")
                    || service.clickVisibleText("தேடு")
                    || service.clickVisibleText("menu_search")
                if (!clickedSearch) {
                    val dm = context.resources.displayMetrics
                    service.performTap(dm.widthPixels * 0.80f, dm.heightPixels * 0.08f)
                }
                delay(900)

                onLog("Typing '$cleanQuery'")
                val typed = service.performTypeText(0f, 0f, cleanQuery, true)
                delay(1300)

                if (!typed) {
                    val enc = URLEncoder.encode(cleanQuery, "UTF-8")
                    LocalSystemLayer.openUrl(context, "https://www.youtube.com/results?search_query=$enc")
                }
                service.overlay?.hideCursorOnly()
                "YouTube-இல் '$cleanQuery' தேடினேன்."
            }

            "chrome_search" -> {
                val cleanQuery = parsed.query.trim()
                if (cleanQuery.isEmpty()) {
                    val (_, msg) = LocalSystemLayer.openInstalledApp(context, "chrome")
                    return msg
                }
                onLog("Web Search: \"$cleanQuery\"")
                val enc = URLEncoder.encode(cleanQuery, "UTF-8")
                LocalSystemLayer.openUrl(context, "https://www.google.com/search?q=$enc")
                "Chrome-இல் '$cleanQuery' தேடினேன்."
            }

            "open_app" -> {
                val app = parsed.app.ifBlank { parsed.query }
                val (_, msg) = LocalSystemLayer.openInstalledApp(context, app)
                msg
            }

            else -> null
        }
    }
}
