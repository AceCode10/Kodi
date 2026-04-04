package ai.kodi.app.data

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HmacInterceptor(
    private val deviceId: () -> String,
    private val secret: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val id = deviceId()
        val sec = secret()
        if (id.isBlank() || sec.isBlank()) {
            return chain.proceed(original)
        }

        val body = original.body
        val buffer = Buffer()
        body?.writeTo(buffer)
        val bytes = buffer.readByteArray()
        val bodyHash = sha256Hex(bytes)
        val ts = (System.currentTimeMillis() / 1000).toString()
        val path = original.url.encodedPath
        val canonical = "$ts\n${original.method}\n$path\n$bodyHash"
        val sig = hmacSha256Hex(sec, canonical)

        val newBody = bytes.toRequestBody(body?.contentType() ?: "application/octet-stream".toMediaType())
        val newReq = original.newBuilder()
            .header("X-Kodi-Device-Id", id)
            .header("X-Kodi-Timestamp", ts)
            .header("X-Kodi-Signature", sig)
            .method(original.method, newBody)
            .build()
        return chain.proceed(newReq)
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
