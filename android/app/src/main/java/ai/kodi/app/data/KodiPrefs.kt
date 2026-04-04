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

    fun clearSession() {
        sp.edit().remove("session_id").apply()
    }
}
