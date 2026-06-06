package ai.kodi.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class TtsManager(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private val queue = ArrayDeque<String>()
    private val lock = Any()
    private val utterSeq = AtomicInteger(0)

    @Volatile private var speaking = false

    fun init(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(appContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            speakNext()
                        }

                        override fun onError(utteranceId: String?) {
                            speakNext()
                        }
                    },
                )
            }
            onReady(ok)
        }
    }

    fun stop() {
        synchronized(lock) {
            queue.clear()
            speaking = false
        }
        tts?.stop()
    }

    /** Speak a whole reply at once (used for non-streamed callers, e.g. scheduled tasks). */
    fun speak(text: String) {
        stop()
        val parts = splitSentences(text)
        if (parts.isEmpty()) return
        synchronized(lock) { queue.addAll(parts) }
        speakNext(flush = true)
    }

    /** Begin an incremental streamed reply — clears anything currently queued/speaking. */
    fun beginStream() = stop()

    /** Feed one streamed chunk (typically a sentence); starts speaking if idle. */
    fun pushChunk(text: String) {
        val parts = splitSentences(text)
        if (parts.isEmpty()) return
        val kick: Boolean
        synchronized(lock) {
            queue.addAll(parts)
            kick = !speaking
        }
        if (kick) speakNext(flush = false)
    }

    /** Streamed reply finished — remaining queue drains on its own. */
    fun endStream() { /* no-op: onDone callbacks drain the queue */ }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun speakNext(flush: Boolean = false) {
        val t = tts ?: return
        val next: String?
        synchronized(lock) {
            next = queue.removeFirstOrNull()
            speaking = next != null
        }
        if (next == null) return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = "u${utterSeq.incrementAndGet()}"
        t.speak(next, mode, null, id)
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
