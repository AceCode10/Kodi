package ai.kodi.app.briefing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.kodi.app.KodiApplication
import ai.kodi.app.MainActivity
import ai.kodi.app.R
import ai.kodi.app.data.TranscriptRole
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class BriefingWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = appContext.applicationContext as KodiApplication
        val result = app.repository.fetchBriefing()
        val text = result.getOrElse {
            return Result.retry()
        }
        if (text.isBlank()) return Result.success()
        app.transcripts.add(TranscriptRole.ASSISTANT, text)
        postNotification(text)
        speak(text)
        return Result.success()
    }

    private fun postNotification(text: String) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Kodi briefings", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val tap = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(appContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_kodi)
            .setContentTitle("Kodi briefing")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private suspend fun speak(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        var tts: TextToSpeech? = null
        tts = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                if (cont.isActive) cont.resume(Unit)
                return@TextToSpeech
            }
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    tts?.shutdown()
                    if (cont.isActive) cont.resume(Unit)
                }
                @Deprecated("deprecated") override fun onError(utteranceId: String?) {
                    tts?.shutdown()
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "briefing")
        }
        cont.invokeOnCancellation { tts?.shutdown() }
    }

    companion object {
        const val UNIQUE_NAME = "kodi_briefing"
        private const val CHANNEL = "kodi_briefing"
        private const val NOTIF_ID = 43
    }
}
