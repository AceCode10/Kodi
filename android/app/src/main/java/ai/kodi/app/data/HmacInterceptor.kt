package ai.kodi.app.data

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

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
        val headers = HmacSigner.signedHeaders(id, sec, original.method, original.url.encodedPath, bytes)

        val newBody = bytes.toRequestBody(body?.contentType() ?: "application/octet-stream".toMediaType())
        val builder = original.newBuilder().method(original.method, newBody)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return chain.proceed(builder.build())
    }
}
