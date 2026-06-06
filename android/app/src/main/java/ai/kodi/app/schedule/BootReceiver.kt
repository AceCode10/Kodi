package ai.kodi.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.sentry.Sentry

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        Log.i(TAG, "Boot detected — re-arming scheduled tasks")
        try {
            TaskScheduler.rearmAll(context)
        } catch (e: Throwable) {
            Log.e(TAG, "Re-arm on boot failed", e)
            try { Sentry.captureException(e) } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "KodiBootReceiver"
    }
}
