package com.friday.actionagent.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.util.Locale

object LocalSystemLayer {

    fun setMediaVolume(context: Context, percent: Int): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clamped = percent.coerceIn(0, 100)
        val target = (maxVolume * clamped / 100f).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "🔊 வால்யூம் $clamped% ஆக அமைக்கப்பட்டது."
    }

    fun adjustVolume(context: Context, increase: Boolean): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return if (increase) "🔊 சவுண்ட் அதிகப்படுத்தப்பட்டது." else "🔉 சவுண்ட் குறைக்கப்பட்டது."
    }

    fun setFlashlight(context: Context, turnOn: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backCam = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "⚠️ இந்த போனில் ஃபிளாஷ்லைட் கிடைக்கவில்லை."
            cm.setTorchMode(backCam, turnOn)
            if (turnOn) "🔦 டார்ச் லைட் ON செய்யப்பட்டது." else "🔦 டார்ச் லைட் OFF செய்யப்பட்டது."
        } catch (e: Exception) {
            "⚠️ டார்ச் லைட் மாற்ற முடியவில்லை: ${e.message}"
        }
    }

    fun getBatteryStatus(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return if (pct >= 0) {
            "🔋 பேட்டரி இப்போது $pct% உள்ளது${if (charging) " (சார்ஜ் ஆகிறது)" else ""}."
        } else {
            "பேட்டரி நிலை தெரியவில்லை."
        }
    }

    fun setAlarm(context: Context, hour: Int, minute: Int, message: String = "FRIDAY Alarm"): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            String.format(Locale.US, "⏰ அலாரம் %02d:%02d-க்கு அமைக்கப்பட்டது.", hour, minute)
        } catch (e: Exception) {
            "⚠️ அலாரம் அமைக்க முடியவில்லை: ${e.message}"
        }
    }

    fun openMaps(context: Context, place: String): String {
        return try {
            val enc = URLEncoder.encode(place, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "🗺️ Google Maps-இல் '$place' திறக்கப்பட்டது."
        } catch (e: Exception) {
            openUrl(context, "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(place, "UTF-8")}")
        }
    }

    fun openWhatsAppMessage(context: Context, phone: String, msg: String): String {
        val clean = phone.replace(Regex("""[^\d]"""), "")
        val url = if (clean.length >= 10) {
            val full = if (clean.length == 10) "91$clean" else clean
            "https://wa.me/$full?text=${URLEncoder.encode(msg, "UTF-8")}"
        } else {
            "https://wa.me/?text=${URLEncoder.encode(msg, "UTF-8")}"
        }
        return openUrl(context, url)
    }

    fun placePhoneCall(context: Context, number: String, displayName: String = ""): String {
        val clean = number.replace(Regex("""[^\d+*#]"""), "")
        if (clean.length < 3) return "சரியான போன் நம்பர் கிடைக்கவில்லை."
        val label = if (displayName.isNotBlank()) "$displayName ($clean)" else clean
        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        return try {
            val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val intent = Intent(action, Uri.parse("tel:$clean")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            if (hasCallPerm) "📞 $label-க்கு கால் செய்கிறேன்..." else "📞 Dialer திறக்கப்பட்டது: $label"
        } catch (e: Exception) {
            "⚠️ கால் செய்ய முடியவில்லை: ${e.message}"
        }
    }

    fun openSystemSettings(context: Context, subAction: String = Settings.ACTION_SETTINGS): String {
        return try {
            context.startActivity(Intent(subAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "⚙️ Settings திறக்கப்பட்டது."
        } catch (_: Exception) {
            "Settings திறக்க முடியவில்லை."
        }
    }

    fun openUrl(context: Context, url: String): String {
        val fixed = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fixed)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "🌐 திறக்கப்பட்டது: $fixed"
        } catch (e: Exception) {
            "URL திறக்க முடியவில்லை: ${e.message}"
        }
    }

    fun openInstalledApp(context: Context, rawAppName: String): Pair<Boolean, String> {
        val q = rawAppName.trim().lowercase(Locale.ROOT)
        if (q.contains("wifi") || q.contains("வைபை")) return true to openSystemSettings(context, Settings.ACTION_WIFI_SETTINGS)
        if (q.contains("bluetooth") || q.contains("ப்ளூடூத்")) return true to openSystemSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
        if (q.contains("settings") || q.contains("செட்டிங்ஸ்")) return true to openSystemSettings(context)

        val knownPackages = mapOf(
            "youtube" to listOf("com.google.android.youtube"),
            "யூடியூப்" to listOf("com.google.android.youtube"),
            "யூட்யூப்" to listOf("com.google.android.youtube"),
            "chrome" to listOf("com.android.chrome"),
            "குரோம்" to listOf("com.android.chrome"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "வாட்ஸ்அப்" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "instagram" to listOf("com.instagram.android"),
            "இன்ஸ்டாகிராம்" to listOf("com.instagram.android"),
            "termux" to listOf("com.termux"),
            "play store" to listOf("com.android.vending"),
            "spotify" to listOf("com.spotify.music"),
            "telegram" to listOf("org.telegram.messenger"),
            "camera" to listOf("com.android.camera", "com.android.camera2", "com.sec.android.app.camera"),
            "zarchiver" to listOf("ru.zdevs.zarchiver"),
            "calculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
            "files" to listOf("com.google.android.apps.nbu.files", "com.android.documentsui")
        )

        val pm = context.packageManager
        for ((key, pkgs) in knownPackages) {
            if (q.contains(key)) {
                for (pkg in pkgs) {
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return true to "🚀 $key திறக்கப்பட்டது."
                    }
                }
            }
        }

        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
        var matchedPkg: String? = null
        var matchedLabel = rawAppName
        for (info in apps) {
            val label = info.loadLabel(pm).toString()
            val pkg = info.activityInfo.packageName
            if (label.lowercase(Locale.ROOT) == q || pkg.lowercase(Locale.ROOT) == q) {
                matchedPkg = pkg
                matchedLabel = label
                break
            }
            if (matchedPkg == null && (label.lowercase(Locale.ROOT).contains(q) || pkg.lowercase(Locale.ROOT).contains(q))) {
                matchedPkg = pkg
                matchedLabel = label
            }
        }

        if (matchedPkg != null) {
            val launch = pm.getLaunchIntentForPackage(matchedPkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return true to "🚀 $matchedLabel திறக்கப்பட்டது."
            }
        }
        return false to "'$rawAppName' ஆப் போனில் கிடைக்கவில்லை."
    }
}
