package app.noctorium.core

import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.platform.SystemBridge
import app.noctorium.scrobble.AudioscrobblerSite
import app.noctorium.scrobble.LastFmClient
import app.noctorium.scrobble.PendingScrobbleRepository
import app.noctorium.scrobble.ScrobbleHttpClient
import app.noctorium.scrobble.ScrobbleHttpResponse
import app.noctorium.scrobble.ScrobbleManager
import app.noctorium.settings.InMemorySecretStore
import app.noctorium.settings.ScrobbleConnectionStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signing in to Last.fm or Libre.fm when no browser opens, as happened on Linux: the sign-in waits all the same, the
 * page can be copied into a browser by hand, and allowing Noctorium there connects it here.
 */
class ScrobblerSignInTest {

    /** A computer on which no browser opens, and whose clipboard is a list. */
    private class NoBrowser : SystemBridge {
        val copied = mutableListOf<String>()
        override fun openUrl(url: String) = error("No browser answered")
        override fun copyToClipboard(text: String) { copied += text }
        override fun defaultExportFolder(): Path? = null
    }

    /** Libre.fm, scripted: a token, then the sign-in once Noctorium has been allowed. */
    private class LibreFm : ScrobbleHttpClient {
        private val answers = ArrayDeque(
            listOf(
                ScrobbleHttpResponse(200, """{"token":"libre-token"}"""),
                ScrobbleHttpResponse(200, """{"session":{"name":"sample-listener","key":"libre-session"}}"""),
            ),
        )
        override suspend fun get(url: String, headers: Map<String, String>) = answers.removeFirstOrNull() ?: ScrobbleHttpResponse(200, "{}")
        override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>) = ScrobbleHttpResponse(200, "{}")
    }

    @Test
    fun `with no browser to open the page, the sign-in waits, the link can be copied, and allowing it connects`() {
        val queue = Files.createTempDirectory("noctorium-signin").resolve("pending.json")
        val bridge = NoBrowser()
        val credentials = InMemorySecretStore()
        val scrobblers = ScrobbleManager(
            credentials = credentials,
            lastFm = LastFmClient(LibreFm(), apiKey = "key", sharedSecret = "secret"),
            pendingRepository = PendingScrobbleRepository(queue),
            libreFm = LastFmClient(LibreFm(), apiKey = "key", sharedSecret = "secret", site = AudioscrobblerSite.LIBRE_FM),
        )
        AppStateHarness.state(system = bridge, credentials = credentials, scrobbleManager = scrobblers).use { state ->
            state.beginLibreFmLogin()

            waitFor("the page to be kept") { state.settings.value.scrobbling.libreFm.approvalUrl != null }
            waitFor("the listener to be told no browser opened") { "did not open" in state.settings.value.message.orEmpty() }
            val page = state.settings.value.scrobbling.libreFm.approvalUrl!!
            assertTrue(page.startsWith("https://libre.fm/api/auth/?api_key=key&token=libre-token"), page)

            state.copyScrobblerSignInLink(AudioscrobblerSite.LIBRE_FM)
            assertEquals(listOf(page), bridge.copied)
            assertTrue("Link copied" in state.settings.value.message.orEmpty())

            // Allowed in a browser of the listener's own, the waiting sign-in finishes here.
            waitFor("Libre.fm to connect") { state.settings.value.scrobbling.libreFm.status == ScrobbleConnectionStatus.CONNECTED }
            waitFor("its name to be remembered") { state.settings.value.preferences.libreFmUsername == "sample-listener" }
            assertEquals("libre-session", credentials.get(ScrobbleManager.LIBREFM_SESSION))
        }
        queue.parent.toFile().deleteRecursively()
    }
}
