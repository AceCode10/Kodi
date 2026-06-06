package ai.kodi.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import ai.kodi.app.voice.KodiVoiceService

/**
 * Receives ACTION_ASSIST and ACTION_VOICE_COMMAND from the system (long-press home,
 * power-button assistant gesture, "Hey Google" replacement when set as default assistant).
 * Triggers a one-shot voice command, no UI.
 */
class AssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, Intent(this, KodiVoiceService::class.java))
        startService(
            Intent(this, KodiVoiceService::class.java).setAction(KodiVoiceService.ACTION_MANUAL_COMMAND),
        )
        finish()
    }
}
