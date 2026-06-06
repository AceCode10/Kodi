package ai.kodi.app.data

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Canonical Kodi HMAC signing, shared by [HmacInterceptor] (HTTP) and the
 * /v1/live WebSocket handshake. Matches backend `app.hmac_auth`:
 * canonical = "$ts\n$METHOD\n$path\n$sha256(body)".
 */
object HmacSigner {

    const val HEADER_DEVICE_ID = "X-Kodi-Device-Id"
    const val HEADER_TIMESTAMP = "X-Kodi-Timestamp"
    const val HEADER_SIGNATURE = "X-Kodi-Signature"

    fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Build the three signed headers for a request/handshake. */
    fun signedHeaders(
        deviceId: String,
        secret: String,
        method: String,
        path: String,
        body: ByteArray,
    ): Map<String, String> {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val canonical = "$ts\n${method.uppercase()}\n$path\n${sha256Hex(body)}"
        return mapOf(
            HEADER_DEVICE_ID to deviceId,
            HEADER_TIMESTAMP to ts,
            HEADER_SIGNATURE to hmacSha256Hex(secret, canonical),
        )
    }
}
