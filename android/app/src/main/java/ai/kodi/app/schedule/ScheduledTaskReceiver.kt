package ai.kodi.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import ai.kodi.app.voice.KodiVoiceService
import io.sentry.Sentry

class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(TaskScheduler.EXTRA_ID).orEmpty()
        val text = intent.getStringExtra(TaskScheduler.EXTRA_TEXT).orEmpty()
        Log.i(TAG, "Firing scheduled task $id: $text")
        try {
            if (id.isNotBlank()) ScheduledTaskStore(context).remove(id)
            if (text.isBlank()) return
            val service = Intent(context, KodiVoiceService::class.java)
                .setAction(KodiVoiceService.ACTION_RUN_SCHEDULED)
                .putExtra(KodiVoiceService.EXTRA_TASK_TEXT, text)
            ContextCompat.startForegroundService(context, service)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to fire scheduled task $id", e)
            try { Sentry.captureException(e) } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "KodiTaskReceiver"
    }
}
