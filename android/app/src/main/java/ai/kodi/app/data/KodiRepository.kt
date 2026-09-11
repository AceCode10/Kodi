package ai.kodi.app.data

import android.content.Context
import ai.kodi.app.BuildConfig
import ai.kodi.app.accessibility.ToolOutcome
import ai.kodi.app.voice.OnDeviceSpeech
import com.google.gson.Gson
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** SSE stream failure carrying the originating HTTP status when known. */
class SseException(message: String, val httpCode: Int? = null) : IOException(message)

class KodiRepository(
    private val context: Context,
    private val transcripts: TranscriptStore? = null,
) {
    private val prefs = KodiPrefs(context)
    private val gson = Gson()

    private fun buildPinner(): CertificatePinner? {
        val raw = prefs.certificatePins.trim()
        if (raw.isBlank()) return null
        val b = CertificatePinner.Builder()
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2) {
                b.add(parts[0].trim(), parts[1].trim())
            }
        }
        return b.build()
    }

    private fun baseClientBuilder(withHmac: Boolean): OkHttpClient.Builder {
        val b = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
        if (withHmac) {
            b.addInterceptor(
                HmacInterceptor(
                    deviceId = { prefs.deviceId },
                    secret = { prefs.apiSecret },
                ),
            )
        }
        buildPinner()?.let { b.certificatePinner(it) }
        if (BuildConfig.DEBUG) {
            b.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        return b
    }

    private fun retrofit(client: OkHttpClient): Retrofit {
        val base = prefs.backendBaseUrl.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    fun apiPublic(): KodiApi {
        val c = baseClientBuilder(withHmac = false).build()
        return retrofit(c).create(KodiApi::class.java)
    }

    fun apiAuthed(): KodiApi {
        val c = baseClientBuilder(withHmac = true).build()
        return retrofit(c).create(KodiApi::class.java)
    }

    suspend fun healthCheck(): Result<String> = runCatching {
        val h = apiPublic().health()
        h["status"]?.toString() ?: "ok"
    }

    suspend fun registerDevice(setupToken: String?): Result<RegisterDto> = runCatching {
        apiPublic().register(setupToken, emptyJsonRequestBody())
    }

    suspend fun ensureSession(): String {
        val existing = prefs.sessionId
        if (existing.isNotBlank()) return existing
        val sid = apiAuthed().createSession(emptyJsonRequestBody()).sessionId
        prefs.sessionId = sid
        return sid
    }

    suspend fun resetSession() {
        prefs.clearSession()
    }

    suspend fun fetchBriefing(): Result<String> = runCatching {
        val ctx = ClientContext.snapshotHeader(context) ?: ""
        apiAuthed().briefing(BriefingRequestBody(context = ctx)).text
    }

    // ---- SSE streaming pipeline ----

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** OkHttp client for SSE: HMAC-signed, no read timeout (the agent turn streams). */
    private fun sseClient(): OkHttpClient =
        baseClientBuilder(withHmac = true).readTimeout(0, TimeUnit.SECONDS).build()

    // ---- Gemini Live (WebSocket) ----

    /** WebSocket client: pinned, no HMAC interceptor (the upgrade GET is signed by hand),
     *  no read timeout, with pings to survive the ngrok/Caddy idle window. */
    fun liveWsClient(): OkHttpClient =
        baseClientBuilder(withHmac = false)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

    val liveBaseUrl: String get() = prefs.backendBaseUrl
    val liveDeviceId: String get() = prefs.deviceId
    val liveSecret: String get() = prefs.apiSecret

    fun liveConfigured(): Boolean =
        prefs.backendBaseUrl.isNotBlank() && prefs.deviceId.isNotBlank() && prefs.apiSecret.isNotBlank()

    private fun urlFor(path: String): String = prefs.backendBaseUrl.trimEnd('/') + path

    private fun audioRequest(sessionId: String, wav: ByteArray, ctx: String?): Request =
        Request.Builder()
            .url(urlFor("/v1/sessions/$sessionId/audio"))
            .header("Accept", "text/event-stream")
            .apply { ctx?.let { header("X-Kodi-Client-Context", it) } }
            .post(wav.toRequestBody("audio/wav".toMediaType()))
            .build()

    private fun textRequest(sessionId: String, text: String, ctx: String?): Request =
        Request.Builder()
            .url(urlFor("/v1/sessions/$sessionId/text"))
            .header("Accept", "text/event-stream")
            .apply { ctx?.let { header("X-Kodi-Client-Context", it) } }
            .post(gson.toJson(TranscriptBody(text = text)).toRequestBody(jsonMedia))
            .build()

    private fun toolResultRequest(sessionId: String, dto: ToolResultDto): Request =
        Request.Builder()
            .url(urlFor("/v1/sessions/$sessionId/tool-result"))
            .header("Accept", "text/event-stream")
            .post(gson.toJson(dto).toRequestBody(jsonMedia))
            .build()

    /** Drives one command to completion: stream → device tool → stream → ... → done. */
    private suspend fun streamTurnLoop(
        sessionId: String,
        firstRequest: Request,
        onTranscript: (String) -> Unit,
        onDelta: (String) -> Unit,
        toolHandler: suspend (CommandDto) -> ToolOutcome,
    ): String {
        val client = sseClient()
        var request = firstRequest
        while (true) {
            when (val r = KodiSseClient.stream(client, request, onTranscript, onDelta)) {
                is SseResult.Done -> return r.assistantText
                is SseResult.DeviceAction -> {
                    val cmd = CommandDto(
                        status = "device_action",
                        toolUseId = r.toolUseId,
                        toolName = r.toolName,
                        toolInput = r.toolInput,
                    )
                    val outcome = toolHandler(cmd)
                    request = toolResultRequest(
                        sessionId,
                        ToolResultDto(
                            toolUseId = r.toolUseId,
                            content = outcome.content,
                            isError = outcome.isError,
                        ),
                    )
                }
                is SseResult.Error -> throw SseException(r.message, r.httpCode)
            }
        }
    }

    private suspend fun runAudioStreaming(
        wav: ByteArray,
        onTranscript: (String) -> Unit,
        onDelta: (String) -> Unit,
        toolHandler: suspend (CommandDto) -> ToolOutcome,
    ): String {
        val ctx = ClientContext.snapshotHeader(context)
        var session = ensureSession()
        return try {
            streamTurnLoop(session, audioRequest(session, wav, ctx), onTranscript, onDelta, toolHandler)
        } catch (e: SseException) {
            if (e.httpCode == 404) {
                prefs.clearSession()
                session = ensureSession()
                streamTurnLoop(session, audioRequest(session, wav, ctx), onTranscript, onDelta, toolHandler)
            } else throw e
        }
    }

    private suspend fun runTextStreaming(
        text: String,
        onDelta: (String) -> Unit,
        toolHandler: suspend (CommandDto) -> ToolOutcome,
    ): String {
        val ctx = ClientContext.snapshotHeader(context)
        var session = ensureSession()
        return try {
            streamTurnLoop(session, textRequest(session, text, ctx), {}, onDelta, toolHandler)
        } catch (e: SseException) {
            if (e.httpCode == 404) {
                prefs.clearSession()
                session = ensureSession()
                streamTurnLoop(session, textRequest(session, text, ctx), {}, onDelta, toolHandler)
            } else throw e
        }
    }

    /** Public entry point for scheduled tasks: drives the agent loop from a text command. */
    suspend fun runText(
        text: String,
        onDelta: (String) -> Unit = {},
        toolHandler: suspend (CommandDto) -> ToolOutcome,
    ): String {
        transcripts?.add(TranscriptRole.USER, text)
        val reply = runTextStreaming(text, onDelta, toolHandler)
        if (reply.isNotBlank()) transcripts?.add(TranscriptRole.ASSISTANT, reply)
        return reply
    }

    /**
     * Primary path: cloud STT + streaming agent. On network/HTTP failure, one-shot on-device
     * [android.speech.SpeechRecognizer] then the streaming /text path.
     */
    suspend fun runVoiceCommandWithSttFallback(
        wavBytes: ByteArray,
        appContext: Context,
        onTranscript: (String) -> Unit = {},
        onDelta: (String) -> Unit = {},
        toolHandler: suspend (CommandDto) -> ToolOutcome,
    ): String {
        val logTranscript: (String) -> Unit = { t ->
            transcripts?.add(TranscriptRole.USER, t)
            onTranscript(t)
        }
        return try {
            val reply = runAudioStreaming(wavBytes, logTranscript, onDelta, toolHandler)
            if (reply.isNotBlank()) transcripts?.add(TranscriptRole.ASSISTANT, reply)
            reply
        } catch (e: SseException) {
            when (e.httpCode) {
                401, 403 -> "I can't reach your backend — please re-pair this device in onboarding."
                400, 503, in 500..599 -> fallbackLocalStt(appContext, onDelta, toolHandler)
                else -> fallbackLocalStt(appContext, onDelta, toolHandler)
            }
        } catch (_: IOException) {
            fallbackLocalStt(appContext, onDelta, toolHandler)
        }
    }

    private suspend fun fallbackLocalStt(
        appContext: Context,
        onDelta: (String) -> Unit,
        toolHandler: suspend (CommandDto) -> ToolOutcome,
    ): String {
        val text = OnDeviceSpeech.transcribeOrNull(appContext)
        if (text.isNullOrBlank()) {
            return "I'm having trouble connecting. Try again in a moment."
        }
        return try {
            runText(text, onDelta, toolHandler)
        } catch (_: IOException) {
            "I'm having trouble connecting. Try again in a moment."
        }
    }
}
