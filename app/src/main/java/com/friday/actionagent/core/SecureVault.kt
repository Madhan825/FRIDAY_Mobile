package com.friday.actionagent.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecureVault {
    @Volatile
    private var cached: SharedPreferences? = null

    fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val created = try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    "friday_v7_secure_vault",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                context.applicationContext.getSharedPreferences("friday_v7_fallback", Context.MODE_PRIVATE)
            }

            listOf("friday_v6_secure_vault", "friday_v6_fallback", "friday_unified_v5_vault", "friday_prefs").forEach { oldName ->
                try {
                    val old = context.applicationContext.getSharedPreferences(oldName, Context.MODE_PRIVATE)
                    if (old.all.isNotEmpty() && created.getString("migrated_$oldName", null) == null) {
                        val ed = created.edit()
                        for ((k, v) in old.all) {
                            if (v is String && created.getString(k, null).isNullOrEmpty()) {
                                ed.putString(k, v)
                            }
                        }
                        ed.putString("migrated_$oldName", "1")
                        ed.apply()
                    }
                } catch (_: Exception) {
                }
            }

            cached = created
            return created
        }
    }
}
