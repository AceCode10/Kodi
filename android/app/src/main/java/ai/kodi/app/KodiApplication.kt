package ai.kodi.app

import android.app.Application
import android.util.Log
import ai.kodi.app.briefing.BriefingScheduler
import ai.kodi.app.data.KodiRepository
import ai.kodi.app.data.TranscriptStore
import io.sentry.android.core.SentryAndroid

class KodiApplication : Application() {
    val transcripts by lazy { TranscriptStore(this) }
    val repository by lazy { KodiRepository(this, transcripts) }

    override fun onCreate() {
        super.onCreate()
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isNotBlank()) {
            try {
                SentryAndroid.init(this) { options ->
                    options.dsn = dsn
                    options.tracesSampleRate = 0.0
                    options.isSendDefaultPii = false
                    options.environment = if (BuildConfig.DEBUG) "debug" else "production"
                }
            } catch (e: Throwable) {
                Log.w("KodiApplication", "Sentry init failed", e)
            }
        }
        try {
            BriefingScheduler.apply(this)
        } catch (e: Throwable) {
            Log.w("KodiApplication", "Briefing scheduler failed", e)
        }
    }
}
