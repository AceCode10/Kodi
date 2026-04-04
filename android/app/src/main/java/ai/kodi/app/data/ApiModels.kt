package ai.kodi.app.data

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

fun emptyJsonRequestBody() = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())

data class RegisterDto(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("api_secret") val apiSecret: String,
)

data class SessionDto(
    @SerializedName("session_id") val sessionId: String,
)

data class ToolResultDto(
    @SerializedName("toolUseId") val toolUseId: String,
    @SerializedName("content") val content: String,
    @SerializedName("isError") val isError: Boolean = false,
)

data class CommandDto(
    @SerializedName("status") val status: String,
    @SerializedName("assistantText") val assistantText: String? = null,
    @SerializedName("transcript") val transcript: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("toolUseId") val toolUseId: String? = null,
    @SerializedName("toolName") val toolName: String? = null,
    @SerializedName("toolInput") val toolInput: JsonObject? = null,
)
