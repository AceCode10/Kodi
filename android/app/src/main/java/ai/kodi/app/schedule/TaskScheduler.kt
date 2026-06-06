package ai.kodi.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object TaskScheduler {
    private const val TAG = "TaskScheduler"
    const val EXTRA_ID = "task_id"
    const val EXTRA_TEXT = "task_text"

    fun schedule(context: Context, task: ScheduledTask): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntentFor(context, task.id, task.text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Log.w(TAG, "App lacks SCHEDULE_EXACT_ALARM — falling back to setAndAllowWhileIdle (inexact).")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.whenMillis, pi)
            return false
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.whenMillis, pi)
        return true
    }

    fun cancel(context: Context, id: String, text: String = "") {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntentFor(context, id, text))
    }

    fun rearmAll(context: Context) {
        val store = ScheduledTaskStore(context)
        store.pruneExpired(System.currentTimeMillis())
        store.list().forEach { schedule(context, it) }
    }

    private fun pendingIntentFor(context: Context, id: String, text: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java)
            .setAction("ai.kodi.app.FIRE_SCHEDULED_TASK")
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TEXT, text)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
