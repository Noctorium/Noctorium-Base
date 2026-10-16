package app.noctorium.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The pieces of a sign-in with PKCE (RFC 7636), the flow made for programs that cannot keep a secret.
 *
 * Instead of a secret shipped inside the program, each attempt makes a value of its own -- the verifier -- sends
 * only its hash -- the challenge -- with the request, and proves it at the end by sending the verifier with the
 * code. A code caught on its way back is worth nothing without the verifier, which never left the device.
 */
object Pkce {
    private val random = SecureRandom()

    /** A fresh verifier: 64 random bytes, which is 86 characters of base64url, inside the 43 to 128 allowed. */
    fun verifier(): String = randomUrlSafe(64)

    /** The S256 challenge for [verifier]: its SHA-256, base64url-encoded without padding. */
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** A value to send out and expect back, which proves a reply belongs to the request that was sent. */
    fun state(): String = randomUrlSafe(24)

    fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }
}
