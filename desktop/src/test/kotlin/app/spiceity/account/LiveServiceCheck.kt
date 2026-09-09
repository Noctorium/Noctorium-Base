package app.spiceity.account

import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the real client against the deployed service, proving the seam between them rather than assuming
 * it. Everything either side of that seam is covered by tests that need nothing but this machine; this is
 * the one thing only the real thing can answer.
 *
 * Off unless asked for, because it needs the network and leaves an account behind each run:
 *
 * ```
 * SPICEITY_LIVE_CHECK=1 ./gradlew test --tests 'app.spiceity.account.LiveServiceCheck'
 * ```
 *
 * The accounts it makes end `@spiceity.invalid`, a domain that can never receive mail, so they are safe
 * to delete at any time.
 */
class LiveServiceCheck {
    private val asked = System.getenv("SPICEITY_LIVE_CHECK")?.trim() == "1"

    private fun randomText(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `the app's own client can sign up, report listens and read them back`() = runBlocking {
        if (!asked) {
            println("LIVE: skipped; set SPICEITY_LIVE_CHECK=1 to run it")
            return@runBlocking
        }
        val client = SpiceityAccountClient()
        val email = "applink-${randomText(4)}@spiceity.invalid"
        val password = randomText(18)

        println("LIVE: signing up $email")
        val signUp = client.signUp(email, password, "App Link Check")
        assertTrue(signUp is AccountResult.Success, "sign up failed: $signUp")
        val token = (signUp as AccountResult.Success).token
        assertEquals(email, signUp.user.email)
        assertEquals("App Link Check", signUp.user.displayName)
        println("LIVE: signed up as ${signUp.user.displayName} (id ${signUp.user.id})")

        println("LIVE: confirming the token")
        val me = client.whoAmI(token)
        assertNotNull(me, "whoAmI returned nothing for a token just issued")
        assertEquals(email, me.email)

        // Two listens of one track and one of another, so the distinct count has something to be wrong about.
        val now = Instant.now()
        val repeated = PlayReport(UUID.randomUUID().toString(), "YOUTUBE_MUSIC", "7tLGGiNjp_U", "Antarctica", "\$uicideboy\$", 127_000, now.minusSeconds(300))
        val plays = listOf(
            repeated,
            PlayReport(UUID.randomUUID().toString(), "YOUTUBE_MUSIC", "7tLGGiNjp_U", "Antarctica", "\$uicideboy\$", 127_000, now.minusSeconds(200)),
            PlayReport(UUID.randomUUID().toString(), "SOUNDCLOUD", "1612018959", "Something Else", "Another Artist", 210_000, now.minusSeconds(100)),
        )
        println("LIVE: reporting ${plays.size} listens")
        assertTrue(client.submit(plays, token), "the service refused the listens")

        println("LIVE: reporting the same batch again")
        assertTrue(client.submit(plays, token), "a resend should still be accepted, not error")

        val stats = client.stats(token)
        assertNotNull(stats, "stats returned nothing")
        println("LIVE: stats -> streams=${stats.streams} unique=${stats.uniqueTracks} artists=${stats.artists} hours=${stats.hours}")

        // Three listens, two distinct tracks, two artists. A resend must not have inflated any of it.
        assertEquals(3, stats.streams, "the resent batch was counted twice")
        assertEquals(2, stats.uniqueTracks)
        assertEquals(2, stats.artists)
        assertTrue(stats.hours > 0.0, "hours did not accumulate")

        println("LIVE: an empty batch is a no-op")
        assertTrue(client.submit(emptyList(), token))

        println("LIVE: a rubbish token is refused")
        assertEquals(null, client.whoAmI("not-a-real-token"))
        assertEquals(null, client.stats("not-a-real-token"))

        println("LIVE: the wrong password is refused")
        val refused = client.logIn(email, "definitely-not-the-password")
        assertTrue(refused is AccountResult.Refused, "expected a refusal, got $refused")

        println("LIVE: the right password is accepted")
        val again = client.logIn(email, password)
        assertTrue(again is AccountResult.Success, "could not sign in again: $again")
    }

    @Test
    fun `an unreachable service is reported as unreachable, not as a refusal`() = runBlocking {
        // Distinguishing the two is what stops a moment offline from looking like a wrong password.
        val client = SpiceityAccountClient(baseUrl = "http://127.0.0.1:1")

        val result = client.logIn("someone@example.com", "whatever")

        assertTrue(result is AccountResult.Unreachable, "expected Unreachable, got $result")
        assertEquals(null, client.stats("token"))
        assertEquals(null, client.whoAmI("token"))
        assertEquals(false, client.submit(listOf(PlayReport("c", "P", "t", "Title", "Artist", 1000, Instant.now())), "token"))
    }
}
