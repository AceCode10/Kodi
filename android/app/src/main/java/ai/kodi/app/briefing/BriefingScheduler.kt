package ai.kodi.app.briefing

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ai.kodi.app.data.KodiPrefs
import java.util.Calendar
import java.util.concurrent.TimeUnit

object BriefingScheduler {

    fun apply(context: Context) {
        val prefs = KodiPrefs(context)
        val wm = WorkManager.getInstance(context)
        if (!prefs.briefingEnabled) {
            wm.cancelUniqueWork(BriefingWorker.UNIQUE_NAME)
            return
        }
        val initialDelayMs = msUntilNext(prefs.briefingHour, prefs.briefingMinute)
        val req = PeriodicWorkRequestBuilder<BriefingWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(
            BriefingWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    private fun msUntilNext(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
