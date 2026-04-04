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
    private val utterSeq = AtomicInteger(0)

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
        queue.clear()
        tts?.stop()
    }

    fun speak(text: String) {
        stop()
        val parts = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        queue.addAll(parts)
        speakNext(flush = true)
    }

    private fun speakNext(flush: Boolean = false) {
        val t = tts ?: return
        val next = queue.removeFirstOrNull() ?: return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = "u${utterSeq.incrementAndGet()}"
        t.speak(next, mode, null, id)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
