package ai.kodi.app.data

import android.content.Context
import ai.kodi.app.BuildConfig
import ai.kodi.app.voice.OnDeviceSpeech
import com.google.gson.Gson
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class KodiRepository(private val context: Context) {
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

    private suspend fun runToolLoop(
        session: String,
        first: CommandDto,
        toolHandler: suspend (CommandDto) -> String,
    ): String {
        val api = apiAuthed()
        var cmd = first
        while (cmd.status == "device_action") {
            val toolId = cmd.toolUseId ?: break
            val resultText = toolHandler(cmd)
            cmd = api.postToolResult(
                session,
                ToolResultDto(toolUseId = toolId, content = resultText, isError = false),
            )
        }
        return when (cmd.status) {
            "done" -> cmd.assistantText.orEmpty()
            else -> cmd.message ?: "I'm having trouble connecting. Try again in a moment."
        }
    }

    private suspend fun runFromAudioBytes(wavBytes: ByteArray, toolHandler: suspend (CommandDto) -> String): String {
        val session = ensureSession()
        val api = apiAuthed()
        return try {
            val first = api.postAudio(session, wavBytes.toRequestBody("audio/wav".toMediaType()))
            runToolLoop(session, first, toolHandler)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                prefs.clearSession()
                val newSession = ensureSession()
                val first = api.postAudio(newSession, wavBytes.toRequestBody("audio/wav".toMediaType()))
                runToolLoop(newSession, first, toolHandler)
            } else throw e
        }
    }

    private suspend fun runFromTranscript(text: String, toolHandler: suspend (CommandDto) -> String): String {
        val session = ensureSession()
        val api = apiAuthed()
        return try {
            val first = api.postTranscript(session, TranscriptBody(text = text))
            runToolLoop(session, first, toolHandler)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                prefs.clearSession()
                val newSession = ensureSession()
                val first = api.postTranscript(newSession, TranscriptBody(text = text))
                runToolLoop(newSession, first, toolHandler)
            } else throw e
        }
    }

    /**
     * Primary path: cloud STT + agent. On network/HTTP failure, one-shot on-device [SpeechRecognizer] then POST /text.
     */
    suspend fun runVoiceCommandWithSttFallback(
        wavBytes: ByteArray,
        appContext: Context,
        toolHandler: suspend (CommandDto) -> String,
    ): String {
        return try {
            runFromAudioBytes(wavBytes, toolHandler)
        } catch (e: HttpException) {
            if (e.code() in 400..599) fallbackLocalStt(appContext, toolHandler)
            else "I'm having trouble connecting. Try again in a moment."
        } catch (_: IOException) {
            fallbackLocalStt(appContext, toolHandler)
        }
    }

    private suspend fun fallbackLocalStt(appContext: Context, toolHandler: suspend (CommandDto) -> String): String {
        val text = OnDeviceSpeech.transcribeOrNull(appContext)
        if (text.isNullOrBlank()) {
            return "I'm having trouble connecting. Try again in a moment."
        }
        return try {
            runFromTranscript(text, toolHandler)
        } catch (_: HttpException) {
            "I'm having trouble connecting. Try again in a moment."
        } catch (_: IOException) {
            "I'm having trouble connecting. Try again in a moment."
        }
    }
}
