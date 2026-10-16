package app.noctorium.scrobble

import app.noctorium.domain.ProviderType
import app.noctorium.settings.InMemorySecretStore
import app.noctorium.settings.ScrobbleConnectionStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Libre.fm, which speaks Last.fm's protocol at its own addresses, beside Last.fm and on its own. */
class LibreFmTest {

    private val song = ScrobbleTrack("youtube:night-bus", "Night Bus", "Saltmarsh", null, 200, "https://example.invalid/night-bus", ProviderType.YOUTUBE_MUSIC)

    @Test
    fun `Libre_fm is asked at its own addresses, in Last_fm's protocol`() = runBlocking {
        val http = AddressedHttpClient { _, _ -> ScrobbleHttpResponse(200, """{"token":"libre-token"}""") }
        val client = LastFmClient(http, apiKey = "key", sharedSecret = "secret", site = AudioscrobblerSite.LIBRE_FM)

        assertEquals("libre-token", client.beginAuthorization())
        val asked = http.requests.single().first
        assertTrue(asked.startsWith("https://libre.fm/2.0/?"), asked)
        assertTrue("method=auth.getToken" in asked && "api_sig=" in asked, asked)
        assertEquals("https://libre.fm/api/auth/?api_key=key&token=libre-token", client.authorizationUrl("libre-token"))
    }

    @Test
    fun `a Libre_fm sign-in keeps its page to copy while it waits, and connects once Noctorium is allowed`() = withQueue { queue ->
        val http = ScriptedAnswers(
            ScrobbleHttpResponse(200, """{"token":"libre-token"}"""),
            ScrobbleHttpResponse(200, """{"error":14,"message":"Unauthorized Token"}"""),
            ScrobbleHttpResponse(200, """{"session":{"name":"sample-listener","key":"libre-session"}}"""),
        )
        val credentials = InMemorySecretStore()
        val manager = ScrobbleManager(
            credentials = credentials,
            lastFm = LastFmClient(ScriptedAnswers(), apiKey = "key", sharedSecret = "secret"),
            pendingRepository = PendingScrobbleRepository(queue),
            libreFm = LastFmClient(http, apiKey = "key", sharedSecret = "secret", site = AudioscrobblerSite.LIBRE_FM),
        )
        runBlocking {
            val authorization = manager.beginAuthorization(AudioscrobblerSite.LIBRE_FM)
            val waiting = manager.state.value.libreFm
            assertEquals(ScrobbleConnectionStatus.AWAITING_APPROVAL, waiting.status)
            assertEquals(authorization.url, waiting.approvalUrl, "the page is kept to copy or open again")
            assertEquals(ScrobbleConnectionStatus.DISCONNECTED, manager.state.value.lastFm.status, "Last.fm is not touched")

            assertEquals("sample-listener", manager.awaitApproval(AudioscrobblerSite.LIBRE_FM, authorization.token, attempts = 3, pollDelayMillis = 1))
        }
        assertEquals(ScrobbleConnectionStatus.CONNECTED, manager.state.value.libreFm.status)
        assertNull(manager.state.value.libreFm.approvalUrl, "nothing left to allow")
        assertEquals("libre-session", credentials.get(ScrobbleManager.LIBREFM_SESSION))
        assertNull(credentials.get(ScrobbleManager.LASTFM_SESSION))
    }

    @Test
    fun `a sign-in begun before a restart still has its page to copy`() = withQueue { queue ->
        val manager = ScrobbleManager(
            credentials = InMemorySecretStore().apply { put(ScrobbleManager.LIBREFM_PENDING, "waiting-token") },
            lastFm = LastFmClient(ScriptedAnswers(), apiKey = "key", sharedSecret = "secret"),
            pendingRepository = PendingScrobbleRepository(queue),
            libreFm = LastFmClient(ScriptedAnswers(), apiKey = "key", sharedSecret = "secret", site = AudioscrobblerSite.LIBRE_FM),
        )
        runBlocking { manager.initialize(lastFmUsername = "", listenBrainzUsername = "") }
        val libre = manager.state.value.libreFm
        assertEquals(ScrobbleConnectionStatus.AWAITING_APPROVAL, libre.status)
        assertEquals("https://libre.fm/api/auth/?api_key=key&token=waiting-token", libre.approvalUrl)
    }

    @Test
    fun `scrobbles queued for each go to each, and a Libre_fm sign-in it refuses is let go of alone`() = withQueue { queue ->
        PendingScrobbleRepository(queue).apply {
            enqueue(PendingScrobble(ScrobbleTarget.LASTFM, song, 1_700_000_000))
            enqueue(PendingScrobble(ScrobbleTarget.LIBREFM, song, 1_700_000_000))
        }
        val lastFmHttp = AddressedHttpClient { _, _ -> ScrobbleHttpResponse(200, """{"scrobbles":{}}""") }
        val libreHttp = AddressedHttpClient { _, _ -> ScrobbleHttpResponse(200, """{"error":9,"message":"Invalid session key"}""") }
        val credentials = InMemorySecretStore().apply {
            put(ScrobbleManager.LASTFM_SESSION, "lastfm-session")
            put(ScrobbleManager.LASTFM_SESSION_APPLICATION, "key")
            put(ScrobbleManager.LIBREFM_SESSION, "dead-libre-session")
        }
        val manager = ScrobbleManager(
            credentials = credentials,
            lastFm = LastFmClient(lastFmHttp, apiKey = "key", sharedSecret = "secret"),
            pendingRepository = PendingScrobbleRepository(queue),
            libreFm = LastFmClient(libreHttp, apiKey = "key", sharedSecret = "secret", site = AudioscrobblerSite.LIBRE_FM),
        )
        runBlocking { manager.initialize(lastFmUsername = "sample-listener", listenBrainzUsername = "", libreFmUsername = "sample-listener") }

        assertTrue(lastFmHttp.requests.single().first.startsWith("https://ws.audioscrobbler.com/2.0/"))
        assertTrue(libreHttp.requests.single().first.startsWith("https://libre.fm/2.0/"))
        assertTrue("method=track.scrobble" in libreHttp.requests.single().second.orEmpty())
        // Libre.fm said its sign-in is dead: that one goes, and asks to be made again; Last.fm's stays.
        assertNull(credentials.get(ScrobbleManager.LIBREFM_SESSION))
        assertEquals("lastfm-session", credentials.get(ScrobbleManager.LASTFM_SESSION))
        assertEquals(ScrobbleConnectionStatus.ERROR, manager.state.value.libreFm.status)
        assertTrue(manager.state.value.libreFm.message.orEmpty().startsWith("Libre.fm needs connecting again"))
        assertEquals(ScrobbleConnectionStatus.CONNECTED, manager.state.value.lastFm.status)
        // Libre.fm's scrobble stays in the queue for when it is connected again; Last.fm's went.
        assertEquals(listOf(ScrobbleTarget.LIBREFM), PendingScrobbleRepository(queue).list().map { it.target })
    }

    @Test
    fun `a queued scrobble for a service this build does not know costs none of the others`() = withQueue { queue ->
        PendingScrobbleRepository(queue).enqueue(PendingScrobble(ScrobbleTarget.LASTFM, song, 1_700_000_000))
        val written = Files.readString(queue)
        // One more, for a service added after this build, as a later Noctorium would have written it.
        val later = written.trimEnd().removeSuffix("]") + ", " + written.substringAfter('[').substringBeforeLast(']')
            .replace("\"LASTFM\"", "\"SOMEWHERE_NEW\"") + "]"
        Files.writeString(queue, later)

        assertEquals(listOf(ScrobbleTarget.LASTFM), PendingScrobbleRepository(queue).list().map { it.target })
    }

    private fun withQueue(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("noctorium-librefm")
        try {
            block(directory.resolve("pending.json"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

/** Answers every request as [answer] says, and keeps each address and body asked with. */
private class AddressedHttpClient(private val answer: (String, String?) -> ScrobbleHttpResponse) : ScrobbleHttpClient {
    val requests = mutableListOf<Pair<String, String?>>()

    override suspend fun get(url: String, headers: Map<String, String>): ScrobbleHttpResponse {
        requests += url to null
        return answer(url, null)
    }

    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>): ScrobbleHttpResponse {
        requests += url to body
        return answer(url, body)
    }
}

/** One scripted answer per request, in order; an empty object once they run out. */
private class ScriptedAnswers(vararg answers: ScrobbleHttpResponse) : ScrobbleHttpClient {
    private val queue = ArrayDeque(answers.toList())
    private fun next() = queue.removeFirstOrNull() ?: ScrobbleHttpResponse(200, "{}")
    override suspend fun get(url: String, headers: Map<String, String>) = next()
    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>) = next()
}
