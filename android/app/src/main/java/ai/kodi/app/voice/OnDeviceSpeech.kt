package ai.kodi.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object OnDeviceSpeech {

    suspend fun transcribeOrNull(context: Context, timeoutMs: Long = 18_000): String? {
        val app = context.applicationContext
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    if (!SpeechRecognizer.isRecognitionAvailable(app)) {
                        cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    val sr = SpeechRecognizer.createSpeechRecognizer(app)
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    }
                    sr.setRecognitionListener(
                        object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {}
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}
                            override fun onEndOfSpeech() {}
                            override fun onError(error: Int) {
                                sr.destroy()
                                if (cont.isActive) cont.resume(null)
                            }

                            override fun onResults(results: Bundle?) {
                                sr.destroy()
                                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                if (cont.isActive) cont.resume(list?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() })
                            }

                            override fun onPartialResults(partialResults: Bundle?) {}
                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        },
                    )
                    sr.startListening(intent)
                    cont.invokeOnCancellation { try { sr.destroy() } catch (_: Exception) {} }
                }
            }
        }
    }
}
