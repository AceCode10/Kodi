package ai.kodi.app

import android.app.Application
import ai.kodi.app.data.KodiRepository

class KodiApplication : Application() {
    val repository by lazy { KodiRepository(this) }
}
