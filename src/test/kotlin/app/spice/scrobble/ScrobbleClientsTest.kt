package app.spice.scrobble

import app.spice.domain.ProviderType
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
