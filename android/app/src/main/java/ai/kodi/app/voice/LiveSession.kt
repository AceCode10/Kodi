package ai.kodi.app.voice

import android.content.Context
import android.util.Log
import ai.kodi.app.accessibility.DeviceToolExecutor
import ai.kodi.app.accessibility.ToolOutcome
import ai.kodi.app.data.CommandDto
import ai.kodi.app.data.HmacSigner
import ai.kodi.app.data.TranscriptRole
import ai.kodi.app.data.TranscriptStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Duplex Gemini Live voice session, proxied through the Kodi backend at /v1/live.
 *
 * Streams mic PCM up, plays Gemini audio down, dispatches device tool calls to
 * [DeviceToolExecutor], and surfaces transcripts to the UI. On any connection
 * failure it reports back via [onClosed] so the caller can fall back to the SSE
 * pipeline for that turn.
 */
class LiveSession(
    private val context: Context,
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val deviceId: String,
    private val secret: String,
    private val clientContextHeader: String?,
    private val transcripts: TranscriptStore,
    private val scope: CoroutineScope,
    private val onState: (VoiceState) -> Unit,
    private val onClosed: (failed: Boolean) -> Unit,
) {
    private val gson = Gson()
    private val audio = AudioStreamer()
    private var ws: WebSocket? = null
    @Volatile private var closed = false

    private val userBuf = StringBuilder()
    private val assistantBuf = StringBuilder()

    fun start() {
        val wsUrl = baseUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + LIVE_PATH
        val headers = HmacSigner.signedHeaders(deviceId, secret, "GET", LIVE_PATH, ByteArray(0))
        val builder = Request.Builder().url(wsUrl)
        headers.forEach { (k, v) -> builder.header(k, v) }
        clientContextHeader?.let { builder.header("X-Kodi-Client-Context", it) }
        ws = client.newWebSocket(builder.build(), listener)
    }

    fun stop() {
        if (closed) return
        closed = true
        try { ws?.close(1000, "client done") } catch (_: Exception) {}
        ws = null
        audio.release()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val started = audio.startCapture { pcm -> webSocket.send(pcm.toByteString()) }
            if (!started) {
                Log.e(TAG, "mic capture failed to start")
                failAndClose()
                return
            }
            onState(VoiceState.LISTENING)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Gemini audio out (24 kHz PCM16).
            audio.playbackWrite(bytes.toByteArray())
            onState(VoiceState.SPEAKING)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = try {
                gson.fromJson(text, JsonObject::class.java)
            } catch (_: Exception) {
                return
            }
            when (obj.get("type")?.asString) {
                "transcript" -> obj.get("text")?.asString?.let { userBuf.append(it) }
                "delta" -> obj.get("text")?.asString?.let { assistantBuf.append(it) }
                "device_action" -> handleDeviceAction(webSocket, obj)
                "interrupted" -> {
                    audio.clearPlayback()
                    onState(VoiceState.LISTENING)
                }
                "turn_complete" -> {
                    flushTranscripts()
                    onState(VoiceState.LISTENING)
                }
                "error" -> {
                    Log.e(TAG, "backend error: ${obj.get("message")?.asString}")
                    failAndClose()
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) { closed = true; audio.release(); onClosed(false) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "live ws failure (code=${response?.code})", t)
            failAndClose()
        }
    }

    private fun handleDeviceAction(webSocket: WebSocket, obj: JsonObject) {
        val id = obj.get("id")?.asString ?: return
        val name = obj.get("name")?.asString ?: return
        val input = obj.getAsJsonObject("input")
        scope.launch {
            val outcome = try {
                DeviceToolExecutor.execute(
                    context,
                    CommandDto(status = "device_action", toolUseId = id, toolName = name, toolInput = input),
                )
            } catch (e: Exception) {
                Log.e(TAG, "device tool $name", e)
                ToolOutcome("Device tool failed: ${e.message ?: "error"}", isError = true)
            }
            val reply = JsonObject().apply {
                addProperty("type", "tool_result")
                addProperty("id", id)
                addProperty("content", outcome.content)
                addProperty("isError", outcome.isError)
            }
            webSocket.send(gson.toJson(reply))
        }
    }

    private fun flushTranscripts() {
        val u = userBuf.toString().trim()
        val a = assistantBuf.toString().trim()
        if (u.isNotEmpty()) transcripts.add(TranscriptRole.USER, u)
        if (a.isNotEmpty()) transcripts.add(TranscriptRole.ASSISTANT, a)
        userBuf.setLength(0)
        assistantBuf.setLength(0)
    }

    private fun failAndClose() {
        if (closed) return
        closed = true
        try { ws?.cancel() } catch (_: Exception) {}
        ws = null
        audio.release()
        onClosed(true)
    }

    companion object {
        private const val TAG = "KodiLive"
        private const val LIVE_PATH = "/v1/live"
    }
}
