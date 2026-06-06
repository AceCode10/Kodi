package ai.kodi.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class TranscriptRole { USER, ASSISTANT }

data class TranscriptEntry(
    val ts: Long,
    val role: TranscriptRole,
    val text: String,
)

/** Append-only persisted history of user transcripts and assistant replies. */
class TranscriptStore(context: Context) {
    private val file: File = File(context.filesDir, "transcripts.json")
    private val gson = Gson()
    private val lock = Any()

    private val _entries = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val entries: StateFlow<List<TranscriptEntry>> = _entries

    init {
        load()
    }

    private fun load() {
        synchronized(lock) {
            if (!file.exists()) {
                _entries.value = emptyList()
                return
            }
            try {
                val type = object : TypeToken<List<TranscriptEntry>>() {}.type
                val list: List<TranscriptEntry> = gson.fromJson(file.readText(), type) ?: emptyList()
                _entries.value = list
            } catch (_: Exception) {
                _entries.value = emptyList()
            }
        }
    }

    fun add(role: TranscriptRole, text: String) {
        if (text.isBlank()) return
        val entry = TranscriptEntry(System.currentTimeMillis(), role, text.trim())
        synchronized(lock) {
            val capped = (_entries.value + entry).takeLast(MAX_ENTRIES)
            _entries.value = capped
            writeAtomic(gson.toJson(capped))
        }
    }

    /** Write to a temp file then atomically rename — survives a crash mid-write. */
    private fun writeAtomic(json: String) {
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                // renameTo can fail across some filesystems — fall back to direct write.
                file.writeText(json)
                tmp.delete()
            }
        } catch (_: Exception) { /* best-effort */ }
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            try { file.delete() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
