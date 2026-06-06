package ai.kodi.app.tile

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import ai.kodi.app.data.KodiPrefs
import ai.kodi.app.voice.KodiVoiceService

class KodiTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val prefs = KodiPrefs(applicationContext)
        val enable = !prefs.wakeWordEnabled
        prefs.wakeWordEnabled = enable
        if (enable) {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, KodiVoiceService::class.java),
            )
        } else {
            applicationContext.stopService(Intent(applicationContext, KodiVoiceService::class.java))
        }
        refresh()
    }

    private fun refresh() {
        val prefs = KodiPrefs(applicationContext)
        val tile = qsTile ?: return
        tile.state = if (prefs.wakeWordEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Kodi"
        tile.contentDescription = if (prefs.wakeWordEnabled) "Wake-word listening" else "Wake-word off"
        tile.updateTile()
    }
}
