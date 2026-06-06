package ai.kodi.app.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Terminal outcome of a single SSE turn stream. */
sealed class SseResult {
    data class Done(val assistantText: String) : SseResult()
    data class DeviceAction(
        val toolUseId: String,
        val toolName: String,
        val toolInput: JsonObject?,
    ) : SseResult()
    data class Error(val message: String, val httpCode: Int? = null) : SseResult()
}

object KodiSseClient {

    private val gson = Gson()

    /**
     * Opens one SSE stream and consumes events until a terminal one (done / device_action / error).
     * `transcript` and `delta` events are delivered through the callbacks as they arrive.
     */
    suspend fun stream(
        client: OkHttpClient,
        request: Request,
        onTranscript: (String) -> Unit,
        onDelta: (String) -> Unit,
    ): SseResult = suspendCancellableCoroutine { cont ->
        var settled = false
        fun settle(result: SseResult, source: EventSource?) {
            if (settled) return
            settled = true
            source?.cancel()
            if (cont.isActive) cont.resume(result)
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val obj = try {
                    gson.fromJson(data, JsonObject::class.java)
                } catch (_: Exception) {
                    null
                }
                when (type) {
                    "transcript" -> obj?.get("text")?.asString?.let(onTranscript)
                    "delta" -> obj?.get("text")?.asString?.let { if (it.isNotBlank()) onDelta(it) }
                    "device_action" -> settle(
                        SseResult.DeviceAction(
                            toolUseId = obj?.get("toolUseId")?.asString.orEmpty(),
                            toolName = obj?.get("toolName")?.asString.orEmpty(),
                            toolInput = obj?.getAsJsonObject("toolInput"),
                        ),
                        eventSource,
                    )
                    "done" -> settle(
                        SseResult.Done(obj?.get("assistantText")?.asString.orEmpty()),
                        eventSource,
                    )
                    "error" -> settle(
                        SseResult.Error(obj?.get("message")?.asString ?: "stream error"),
                        eventSource,
                    )
                }
            }

            override fun onClosed(eventSource: EventSource) {
                settle(SseResult.Error("stream closed before completion"), null)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                settle(
                    SseResult.Error(
                        message = t?.message ?: "sse connection failed",
                        httpCode = response?.code,
                    ),
                    null,
                )
            }
        }

        val source = EventSources.createFactory(client).newEventSource(request, listener)
        cont.invokeOnCancellation { source.cancel() }
    }
}
