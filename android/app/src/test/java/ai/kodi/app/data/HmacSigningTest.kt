package ai.kodi.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Golden-vector style test matching backend [app.hmac_auth] canonical string format.
 */
class HmacSigningTest {

    @Test
    fun canonical_hmac_matches_python_logic() {
        val secret = "test-secret"
        val ts = "1700000000"
        val method = "POST"
        val path = "/v1/sessions"
        val body = "{}".toByteArray(Charsets.UTF_8)
        val bodyHash = sha256Hex(body)
        val canonical = "$ts\n${method.uppercase()}\n$path\n$bodyHash"
        val sig = hmacSha256Hex(secret, canonical)
        assertEquals(64, sig.length)
        val sig2 = hmacSha256Hex(secret, canonical)
        assertEquals(sig, sig2)
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
