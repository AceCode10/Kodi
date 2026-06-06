package ai.kodi.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Per-package consent for Kodi to open arbitrary apps via open_app.
 * Built-in dedicated-tool packages (WhatsApp, Telegram, Gmail, Maps, etc.) bypass this gate.
 */
class AppAllowlist(context: Context) {
    private val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val sp = EncryptedSharedPreferences.create(
        context,
        "kodi_allowlist",
        key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun isAllowed(pkg: String): Boolean = sp.getStringSet(KEY, emptySet())?.contains(pkg) ?: false

    fun grant(pkg: String) = mutate { it.add(pkg) }
    fun revoke(pkg: String) = mutate { it.remove(pkg) }
    fun list(): Set<String> = sp.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    private inline fun mutate(block: (MutableSet<String>) -> Unit) {
        val cur = (sp.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        block(cur)
        sp.edit().putStringSet(KEY, cur).apply()
    }

    companion object {
        private const val KEY = "allowed_packages"

        /** Packages that already have dedicated tools — always treated as allowed for open_app. */
        val BUILT_IN_PACKAGES: Set<String> = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.thunderdog.challegram",
            "com.google.android.gm",
            "com.google.android.apps.maps",
            "com.spotify.music",
            "com.google.android.youtube",
            "com.google.android.calendar",
            "com.android.calendar",
            "com.google.android.deskclock",
            "com.android.chrome",
            "com.android.phone",
            "com.google.android.dialer",
            "com.android.mms",
            "com.google.android.apps.messaging",
        )
    }
}
