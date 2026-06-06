package ai.kodi.app.data

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit interface for the non-streaming endpoints. The turn endpoints
 * (/audio, /text, /tool-result) are Server-Sent Events and are driven directly
 * via OkHttp in [KodiRepository] / [KodiSseClient].
 */
interface KodiApi {
    @GET("/v1/health")
    suspend fun health(): Map<String, Any>

    @POST("/v1/devices/register")
    suspend fun register(
        @Header("X-Kodi-Setup-Token") token: String?,
        @Body body: RequestBody,
    ): RegisterDto

    @POST("/v1/sessions")
    suspend fun createSession(@Body body: RequestBody): SessionDto

    @POST("/v1/briefing")
    suspend fun briefing(
        @Body body: BriefingRequestBody,
    ): BriefingDto
}
