package ai.kodi.app.accessibility

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

class KodiNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val pkg = sbn.packageName
        NotificationBridge.add(NotificationEntry(pkg, title, text, sbn.postTime))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationBridge.connected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationBridge.connected = false
    }
}

data class NotificationEntry(
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
)

object NotificationBridge {
    @Volatile var connected: Boolean = false
    private val entries = CopyOnWriteArrayList<NotificationEntry>()
    private const val MAX = 50

    fun add(entry: NotificationEntry) {
        entries.add(0, entry)
        if (entries.size > MAX) entries.subList(MAX, entries.size).clear()
    }

    fun recent(count: Int): List<NotificationEntry> =
        entries.take(count.coerceIn(1, MAX))

    fun clear() = entries.clear()
}
