package app.noctorium.scrobble

import app.noctorium.domain.ProviderType
import app.noctorium.update.AppVersion
import app.noctorium.update.Version
import app.noctorium.settings.ScrobbleConnectionStatus
import app.noctorium.settings.InMemorySecretStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class ScrobbleClientsTest {
    @Test
    fun `ListenBrainz validates token and returns username`() = runBlocking {
        val http = RecordingHttpClient(
            getResponse = ScrobbleHttpResponse(200, """{"valid":true,"user_name":"listener"}"""),
        )

        assertEquals("listener", ListenBrainzClient(http).validateToken("token"))
        assertEquals("Token token", http.lastHeaders["Authorization"])
    }

    @Test
    fun `ListenBrainz playing now omits timestamp and completed listen includes it`() = runBlocking {
        val http = RecordingHttpClient()
        val client = ListenBrainzClient(http)
        val track = ScrobbleTrack("key", "Song", "Artist", "Album", 180, "https://youtube.com/watch?v=x", ProviderType.YOUTUBE_MUSIC)

        client.nowPlaying("token", track)
        val playing = Json.parseToJsonElement(http.posts.last()).jsonObject
        assertEquals("playing_now", playing["listen_type"]?.toString()?.trim('"'))
        assertNull(playing["payload"]?.jsonArray?.first()?.jsonObject?.get("listened_at"))

        client.scrobble("token", track, 1_700_000_000)
        val completed = Json.parseToJsonElement(http.posts.last()).jsonObject
        assertEquals(1_700_000_000, completed["payload"]?.jsonArray?.first()?.jsonObject?.get("listened_at")?.toString()?.toLong())
    }

    @Test
    fun `Lastfm signature follows sorted parameter protocol`() {
        val client = LastFmClient(RecordingHttpClient(), apiKey = "key", sharedSecret = "secret")

        val signature = client.signature(mapOf("token" to "token", "method" to "auth.getSession", "api_key" to "key"))

        assertEquals("9ac306496295a8866c4a8673395540eb", signature)
    }

    @Test
    fun `Lastfm ships with application credentials so sign-in needs no manual keys`() {
        assertTrue(LastFmClient(RecordingHttpClient()).configured)
    }

    @Test
    fun `Lastfm approval polling finishes sign-in once the listener allows Noctorium`() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val directory = Files.createTempDirectory("noctorium-lastfm-approval")
        try {
            val http = ScriptedHttpClient(
                ScrobbleHttpResponse(200, """{"token":"request-token"}"""),
                ScrobbleHttpResponse(200, """{"error":14,"message":"Unauthorized Token"}"""),
                ScrobbleHttpResponse(200, """{"session":{"name":"listener","key":"session-key"}}"""),
            )
            val manager = ScrobbleManager(
                // Nothing here is testing where a secret is kept, and the real store on this
                // machine runs PowerShell per read.
                credentials = InMemorySecretStore(),
                lastFm = LastFmClient(http, apiKey = "key", sharedSecret = "secret"),
                pendingRepository = PendingScrobbleRepository(directory.resolve("pending.json")),
            )

            runBlocking {
                val authorization = manager.beginLastFmAuthorization()
                assertTrue(authorization.url.startsWith("https://www.last.fm/api/auth/"))
                assertEquals("listener", manager.awaitLastFmApproval(authorization.token, attempts = 3, pollDelayMillis = 1))
            }

            assertEquals(ScrobbleConnectionStatus.CONNECTED, manager.state.value.lastFm.status)
            assertEquals("listener", manager.state.value.lastFm.username)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a Last_fm sign-in made through another application asks to be made again`() {
        val directory = Files.createTempDirectory("noctorium-lastfm-application")
        try {
            // Saved before sign-ins recorded their application: it came from the first one Noctorium shipped.
            val credentials = InMemorySecretStore().apply { put(ScrobbleManager.LASTFM_SESSION, "old-session") }
            val manager = ScrobbleManager(
                credentials = credentials,
                lastFm = LastFmClient(RecordingHttpClient(), apiKey = "renamed-application-key", sharedSecret = "secret"),
                pendingRepository = PendingScrobbleRepository(directory.resolve("pending.json")),
            )

            runBlocking { manager.initialize(lastFmUsername = "listener", listenBrainzUsername = "") }

            assertEquals(ScrobbleConnectionStatus.ERROR, manager.state.value.lastFm.status)
            assertContains(manager.state.value.lastFm.message.orEmpty(), "connecting again")
            assertNull(credentials.get(ScrobbleManager.LASTFM_SESSION))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sign-ins from before the rename are asked to be made again, through the new application`() {
        // Only meaningful for the application this build ships, which a machine can point elsewhere.
        if (System.getenv("NOCTORIUM_LASTFM_API_KEY") != null) return
        assertNotEquals(LastFmApplication.FIRST_API_KEY, LastFmApplication.apiKey, "this build signs in as the renamed application")
        val directory = Files.createTempDirectory("noctorium-lastfm-rename")
        try {
            val credentials = InMemorySecretStore().apply { put(ScrobbleManager.LASTFM_SESSION, "session-from-before") }
            val manager = ScrobbleManager(
                credentials = credentials,
                lastFm = LastFmClient(RecordingHttpClient()),
                pendingRepository = PendingScrobbleRepository(directory.resolve("pending.json")),
            )
            runBlocking { manager.initialize(lastFmUsername = "listener", listenBrainzUsername = "") }
            assertEquals(ScrobbleConnectionStatus.ERROR, manager.state.value.lastFm.status)
            assertNull(credentials.get(ScrobbleManager.LASTFM_SESSION))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a Last_fm sign-in from the application in use is kept`() {
        val directory = Files.createTempDirectory("noctorium-lastfm-application")
        try {
            listOf(
                // Recorded with the application it came from.
                InMemorySecretStore().apply {
                    put(ScrobbleManager.LASTFM_SESSION, "session")
                    put(ScrobbleManager.LASTFM_SESSION_APPLICATION, "current-key")
                } to "current-key",
                // From before that was recorded, through the application every such sign-in came from.
                InMemorySecretStore().apply { put(ScrobbleManager.LASTFM_SESSION, "session") } to LastFmApplication.FIRST_API_KEY,
            ).forEach { (credentials, key) ->
                val manager = ScrobbleManager(
                    credentials = credentials,
                    lastFm = LastFmClient(RecordingHttpClient(), apiKey = key, sharedSecret = "secret"),
                    pendingRepository = PendingScrobbleRepository(directory.resolve("pending-$key.json")),
                )
                runBlocking { manager.initialize(lastFmUsername = "listener", listenBrainzUsername = "") }
                assertEquals(ScrobbleConnectionStatus.CONNECTED, manager.state.value.lastFm.status, key)
                assertEquals("session", credentials.get(ScrobbleManager.LASTFM_SESSION))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Last_fm refusing the session drops it and keeps the scrobble for later`() {
        val directory = Files.createTempDirectory("noctorium-lastfm-refused")
        try {
            val credentials = InMemorySecretStore().apply {
                put(ScrobbleManager.LASTFM_SESSION, "dead-session")
                put(ScrobbleManager.LASTFM_SESSION_APPLICATION, "key")
            }
            val pending = PendingScrobbleRepository(directory.resolve("pending.json"))
            pending.enqueue(
                PendingScrobble(
                    ScrobbleTarget.LASTFM,
                    ScrobbleTrack("key", "Song", "Artist", null, 180, "https://soundcloud.com/x", ProviderType.SOUNDCLOUD),
                    1_700_000_000,
                ),
            )
            val manager = ScrobbleManager(
                credentials = credentials,
                lastFm = LastFmClient(
                    RecordingHttpClient(postResponse = ScrobbleHttpResponse(200, """{"error":9,"message":"Invalid session key - Please re-authenticate"}""")),
                    apiKey = "key",
                    sharedSecret = "secret",
                ),
                pendingRepository = pending,
            )

            // Initialising flushes the queue, which is where the dead session is found out.
            runBlocking { manager.initialize(lastFmUsername = "listener", listenBrainzUsername = "") }

            assertEquals(ScrobbleConnectionStatus.ERROR, manager.state.value.lastFm.status)
            assertNull(credentials.get(ScrobbleManager.LASTFM_SESSION))
            assertEquals(1, pending.list().size, "the scrobble waits for the next sign-in")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Last_fm errors keep their number`() {
        val error = LastFmException(9, "Last.fm error 9: Invalid session key")
        assertTrue(error.invalidSession)
        assertFalse(LastFmException(11, "Last.fm error 11: Service Offline").invalidSession)
    }

    @Test
    fun `the version reported to a service is the one the build actually is`() = runBlocking {
        // It used to be the literal "0.1.0", which was true for exactly one release and then quietly
        // became a lie -- the kind nothing fails on, because a wrong version is still a valid one.
        val before = AppVersion.name
        try {
            AppVersion.set(Version.parse("3.4.5"))
            val http = RecordingHttpClient()
            val track = ScrobbleTrack("key", "Song", "Artist", null, 180, "https://soundcloud.com/x", ProviderType.SOUNDCLOUD)

            ListenBrainzClient(http).scrobble("token", track, 1_700_000_000)

            val info = Json.parseToJsonElement(http.posts.last()).jsonObject["payload"]
                ?.jsonArray?.first()?.jsonObject?.get("track_metadata")
                ?.jsonObject?.get("additional_info")?.jsonObject
            assertEquals("3.4.5", info?.get("submission_client_version")?.toString()?.trim('"'))
        } finally {
            AppVersion.set(Version.parse(before))
        }
    }

    @Test
    fun `a build that does not know its version says so rather than guessing`() {
        val before = AppVersion.name
        try {
            AppVersion.set(null)
            assertEquals("0.0.0", AppVersion.name)
        } finally {
            AppVersion.set(Version.parse(before))
        }
    }

    @Test
    fun `official scrobble threshold excludes short tracks and uses earlier limit`() {
        assertNull(ScrobbleManager.scrobbleThresholdMs(30_000))
        assertEquals(90_000, ScrobbleManager.scrobbleThresholdMs(180_000))
        assertEquals(240_000, ScrobbleManager.scrobbleThresholdMs(600_000))
    }
}

private class RecordingHttpClient(
    private val getResponse: ScrobbleHttpResponse = ScrobbleHttpResponse(200, "{}"),
    private val postResponse: ScrobbleHttpResponse = ScrobbleHttpResponse(200, "{}"),
) : ScrobbleHttpClient {
    var lastHeaders: Map<String, String> = emptyMap()
    val posts = mutableListOf<String>()

    override suspend fun get(url: String, headers: Map<String, String>): ScrobbleHttpResponse {
        lastHeaders = headers
        return getResponse
    }

    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>): ScrobbleHttpResponse {
        lastHeaders = headers
        posts += body
        return postResponse
    }
}

/** Replays one scripted response per request so multi-step flows can be exercised in order. */
private class ScriptedHttpClient(vararg responses: ScrobbleHttpResponse) : ScrobbleHttpClient {
    private val queue = ArrayDeque(responses.toList())

    override suspend fun get(url: String, headers: Map<String, String>) = next()

    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>) = next()

    private fun next() = queue.removeFirstOrNull() ?: error("No scripted response left")
}
