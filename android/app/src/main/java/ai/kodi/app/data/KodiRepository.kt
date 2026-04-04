package ai.kodi.app.data

import android.content.Context
import ai.kodi.app.BuildConfig
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
        h["status"] ?: "ok"
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

    /**
     * Sends WAV (16-bit mono PCM) and runs device-tool loop until [CommandDto.status] is done or error.
     */
    suspend fun runVoiceCommand(wavBytes: ByteArray, toolHandler: suspend (CommandDto) -> String): String {
        return try {
            val session = ensureSession()
            val api = apiAuthed()
            var cmd = api.postAudio(session, wavBytes.toRequestBody("audio/wav".toMediaType()))
            while (cmd.status == "device_action") {
                val toolId = cmd.toolUseId ?: break
                val resultText = toolHandler(cmd)
                cmd = api.postToolResult(
                    session,
                    ToolResultDto(toolUseId = toolId, content = resultText, isError = false),
                )
            }
            when (cmd.status) {
                "done" -> cmd.assistantText.orEmpty()
                else -> cmd.message ?: "I'm having trouble connecting. Try again in a moment."
            }
        } catch (_: HttpException) {
            "I'm having trouble connecting. Try again in a moment."
        } catch (_: IOException) {
            "I'm having trouble connecting. Try again in a moment."
        }
    }
}
