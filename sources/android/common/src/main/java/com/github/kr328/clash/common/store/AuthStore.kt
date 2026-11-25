package com.github.kr328.clash.common.store

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthStore(context: Context) {
    private val prefs: SharedPreferences = createPreferences(context.applicationContext)

    private fun createPreferences(context: Context): SharedPreferences {
        val isDebuggable =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        if (isDebuggable) {
            return context.getSharedPreferences("auth_store", Context.MODE_PRIVATE)
        }

        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "auth_store",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            context.getSharedPreferences("auth_store", Context.MODE_PRIVATE)
        }
    }

    var authData: String?
        get() = prefs.getString("auth_data", null)
        set(value) = prefs.edit().run {
            if (value != null) putString("auth_data", value) else remove("auth_data")
            apply()
        }

    var authEmail: String?
        get() = prefs.getString("auth_email", null)
        set(value) = prefs.edit().run {
            if (value != null) putString("auth_email", value) else remove("auth_email")
            apply()
        }

    var expiredAt: Long?
        get() = if (prefs.contains("expired_at")) prefs.getLong("expired_at", 0) else null
        set(value) = prefs.edit().run {
            if (value != null) putLong("expired_at", value) else remove("expired_at")
            apply()
        }

    var trafficUpload: Long
        get() = prefs.getLong("upload", 0)
        set(value) { prefs.edit().putLong("upload", value).apply() }

    var trafficDownload: Long
        get() = prefs.getLong("download", 0)
        set(value) { prefs.edit().putLong("download", value).apply() }

    var trafficTotal: Long
        get() = prefs.getLong("total", 0)
        set(value) { prefs.edit().putLong("total", value).apply() }

    var planName: String
        get() = prefs.getString("plan_name", "") ?: ""
        set(value) { prefs.edit().putString("plan_name", value).apply() }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun isLoggedIn(): Boolean = authData?.isNotEmpty() == true
}
