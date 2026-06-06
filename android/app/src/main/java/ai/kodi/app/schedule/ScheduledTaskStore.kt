package ai.kodi.app.schedule

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

data class ScheduledTask(
    val id: String,
    val whenMillis: Long,
    val text: String,
)

class ScheduledTaskStore(context: Context) {
    private val file: File = File(context.filesDir, "scheduled_tasks.json")
    private val gson = Gson()
    private val lock = Any()

    fun list(): List<ScheduledTask> = synchronized(lock) { load() }

    fun upsert(task: ScheduledTask): ScheduledTask = synchronized(lock) {
        val entry = if (task.id.isBlank()) task.copy(id = UUID.randomUUID().toString()) else task
        val cur = load().filter { it.id != entry.id } + entry
        save(cur)
        entry
    }

    fun remove(id: String): ScheduledTask? = synchronized(lock) {
        val cur = load()
        val gone = cur.firstOrNull { it.id == id } ?: return@synchronized null
        save(cur.filter { it.id != id })
        gone
    }

    fun pruneExpired(nowMillis: Long): List<ScheduledTask> = synchronized(lock) {
        val cur = load()
        val expired = cur.filter { it.whenMillis <= nowMillis }
        if (expired.isNotEmpty()) save(cur.filter { it.whenMillis > nowMillis })
        expired
    }

    private fun load(): List<ScheduledTask> {
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<ScheduledTask>>() {}.type
            gson.fromJson(file.readText(), type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun save(list: List<ScheduledTask>) {
        try { file.writeText(gson.toJson(list)) } catch (_: Exception) {}
    }
}
