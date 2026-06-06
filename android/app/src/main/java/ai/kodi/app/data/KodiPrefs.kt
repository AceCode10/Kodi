package ai.kodi.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class KodiPrefs(context: Context) {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val sp = EncryptedSharedPreferences.create(
        context,
        "kodi_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var onboardingComplete: Boolean
        get() = sp.getBoolean("onboarding_complete", false)
        set(v) = sp.edit().putBoolean("onboarding_complete", v).apply()

    var backendBaseUrl: String
        get() = sp.getString("backend_base_url", "") ?: ""
        set(v) = sp.edit().putString("backend_base_url", v.trimEnd('/')).apply()

    var deviceId: String
        get() = sp.getString("device_id", "") ?: ""
        set(v) = sp.edit().putString("device_id", v).apply()

    var apiSecret: String
        get() = sp.getString("api_secret", "") ?: ""
        set(v) = sp.edit().putString("api_secret", v).apply()

    /** Comma-separated OkHttp pins: host1|sha256/aaa...,host2|sha256/bbb */
    var certificatePins: String
        get() = sp.getString("cert_pins", "") ?: ""
        set(v) = sp.edit().putString("cert_pins", v).apply()

    var sessionId: String
        get() = sp.getString("session_id", "") ?: ""
        set(v) = sp.edit().putString("session_id", v).apply()

    var wakeWordEnabled: Boolean
        get() = sp.getBoolean("wake_word_enabled", true)
        set(v) = sp.edit().putBoolean("wake_word_enabled", v).apply()

    /** Use the Gemini Live duplex voice path; falls back to the SSE pipeline on failure. */
    var liveModeEnabled: Boolean
        get() = sp.getBoolean("live_mode_enabled", true)
        set(v) = sp.edit().putBoolean("live_mode_enabled", v).apply()

    var dynamicColor: Boolean
        get() = sp.getBoolean("dynamic_color", true)
        set(v) = sp.edit().putBoolean("dynamic_color", v).apply()

    var briefingEnabled: Boolean
        get() = sp.getBoolean("briefing_enabled", false)
        set(v) = sp.edit().putBoolean("briefing_enabled", v).apply()

    var briefingHour: Int
        get() = sp.getInt("briefing_hour", 8)
        set(v) = sp.edit().putInt("briefing_hour", v.coerceIn(0, 23)).apply()

    var briefingMinute: Int
        get() = sp.getInt("briefing_minute", 0)
        set(v) = sp.edit().putInt("briefing_minute", v.coerceIn(0, 59)).apply()

    fun clearSession() {
        sp.edit().remove("session_id").apply()
    }

    fun clearAll() {
        sp.edit().clear().apply()
    }
}
