package ai.kodi.app.data

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface KodiApi {
    @GET("/v1/health")
    suspend fun health(): Map<String, String>

    @POST("/v1/devices/register")
    suspend fun register(
        @Header("X-Kodi-Setup-Token") token: String?,
        @Body body: RequestBody,
    ): RegisterDto

    @POST("/v1/sessions")
    suspend fun createSession(@Body body: RequestBody): SessionDto

    @POST("/v1/sessions/{sessionId}/audio")
    suspend fun postAudio(
        @Path("sessionId") sessionId: String,
        @Body audio: RequestBody,
    ): CommandDto

    @POST("/v1/sessions/{sessionId}/tool-result")
    suspend fun postToolResult(
        @Path("sessionId") sessionId: String,
        @Body body: ToolResultDto,
    ): CommandDto
}
