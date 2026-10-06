package app.noctorium.spotify

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.RoutingPlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers per address and method, and keeps what was asked, so writes can be checked as well as reads. */
private class Scripted(private val answer: (method: String, url: String) -> SpotifyResponse) : SpotifyHttp {
    val asked = mutableListOf<String>()
    val bodies = mutableListOf<String?>()
    override suspend fun get(url: String, accessToken: String) = send("GET", url, accessToken)
    override suspend fun send(method: String, url: String, accessToken: String, body: String?): SpotifyResponse {
        asked += "$method $url"
        bodies += body
        return answer(method, url)
    }
}

/** The 2026 shape of Spotify's interface, and how the client keeps working with both shapes. */
class SpotifyEndpointsTest {
    private val api = SpotifyClient.API

    @Test
    fun `a playlist's songs come from items, each under item`() = runBlocking {
        val http = Scripted { _, url ->
            if (url == "$api/playlists/p1/items?limit=50") {
                SpotifyResponse(200, """{"items":[{"item":{"id":"t1","name":"Song","artists":[{"id":"a","name":"Artist"}],"duration_ms":1000}}],"next":null}""")
            } else {
                SpotifyResponse(404, "")
            }
        }
        assertEquals(listOf("t1"), SpotifyClient(http).playlistTracks("p1", "token").valueOrNull()?.map { it.id })
        assertEquals(1, http.asked.size)
    }

    @Test
    fun `an app from before 2026 is answered at the old address`() = runBlocking {
        val http = Scripted { _, url ->
            when (url) {
                "$api/playlists/p1/tracks?limit=100" ->
                    SpotifyResponse(200, """{"items":[{"track":{"id":"t1","name":"Song","artists":[{"id":"a","name":"Artist"}]}}],"next":null}""")
                else -> SpotifyResponse(404, """{"error":{"status":404,"message":"Not found"}}""")
            }
        }
        assertEquals(listOf("t1"), SpotifyClient(http).playlistTracks("p1", "token").valueOrNull()?.map { it.id })
    }

    @Test
    fun `a playlist somebody else made is explained, not just refused`() = runBlocking {
        val http = Scripted { _, _ -> SpotifyResponse(403, """{"error":{"status":403,"message":"Forbidden"}}""") }
        val failure = SpotifyClient(http).playlistTracks("theirs", "token") as SpotifyRead.Failed
        assertTrue(failure.detail.contains("you made or collaborate on"), failure.detail)
    }

    @Test
    fun `a playlist counts its songs in either shape`() {
        val modern = kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"x","name":"N","items":{"total":7}}""")
        val older = kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"x","name":"N","tracks":{"total":9}}""")
        assertEquals(7, SpotifyClient.playlistOf(modern as kotlinx.serialization.json.JsonObject)?.trackCount)
        assertEquals(9, SpotifyClient.playlistOf(older as kotlinx.serialization.json.JsonObject)?.trackCount)
    }

    @Test
    fun `search gives songs, and albums and artists to open`() = runBlocking {
        val http = Scripted { _, _ ->
            SpotifyResponse(
                200,
                """
                {"tracks":{"items":[{"id":"t1","name":"Song","artists":[{"id":"a1","name":"Artist"}],"duration_ms":1000}]},
                 "albums":{"items":[{"id":"al1","name":"Album","artists":[{"id":"a1","name":"Artist"}],"total_tracks":9,"images":[]}]},
                 "artists":{"items":[{"id":"a1","name":"Artist","images":[]}]}}
                """.trimIndent(),
            )
        }
        val found = SpotifyClient(http).search("song", "token").valueOrNull()!!
        assertEquals(listOf("t1"), found.tracks.map { it.id })
        assertEquals(listOf("album:al1"), found.albums.map { it.id })
        assertEquals(listOf("artist:a1"), found.artists.map { it.id })
        assertTrue(http.asked.single().contains("type=track,album,artist&limit=10"))
    }

    @Test
    fun `an album's songs carry the album they are on`() = runBlocking {
        val http = Scripted { _, _ ->
            SpotifyResponse(
                200,
                """{"name":"Album","images":[{"url":"https://i/a.jpg","width":640}],"artists":[{"id":"a1","name":"Artist"}],
                    "tracks":{"items":[{"id":"t1","name":"One","artists":[{"id":"a1","name":"Artist"}],"duration_ms":1000}],"next":null}}""",
            )
        }
        val songs = SpotifyClient(http).albumTracks("al1", "token").valueOrNull()!!
        assertEquals("Album", songs.single().album?.title)
        assertEquals("https://i/a.jpg", songs.single().artworkUrl)
    }

    @Test
    fun `liking goes through the library address, and the old one where that is not there`() = runBlocking {
        val modern = Scripted { _, _ -> SpotifyResponse(200, "") }
        assertTrue(SpotifyClient(modern).setSaved("t1", true, "token") is SpotifyRead.Ok)
        assertEquals("PUT $api/me/library?uris=spotify%3Atrack%3At1", modern.asked.single())

        val older = Scripted { _, url -> if ("/me/library" in url) SpotifyResponse(404, "") else SpotifyResponse(200, "") }
        assertTrue(SpotifyClient(older).setSaved("t1", false, "token") is SpotifyRead.Ok)
        assertEquals("DELETE $api/me/tracks?ids=t1", older.asked.last())
    }

    @Test
    fun `nothing playing anywhere is no state, not a failure`() = runBlocking {
        val http = Scripted { _, _ -> SpotifyResponse(204, "") }
        val state = SpotifyClient(http).playerState("token")
        assertTrue(state is SpotifyRead.Ok)
        assertNull(state.value)
    }

    @Test
    fun `playing names the song, the start and the device`() = runBlocking {
        val http = Scripted { _, _ -> SpotifyResponse(204, "") }
        SpotifyClient(http).play("t1", 1500, "dev 1", "token")
        assertEquals("PUT $api/me/player/play?device_id=dev+1", http.asked.single())
        assertEquals("""{"uris":["spotify:track:t1"],"position_ms":1500}""", http.bodies.single())
    }
}

/** How a song played on Spotify is followed: when it has ended, and when the listener took Spotify elsewhere. */
class SpotifyConnectEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val song = Track(ProviderType.SPOTIFY, "t1", "Song", listOf(Artist("a", "Artist", ProviderType.SPOTIFY)), durationMs = 200_000, sourceUrl = "https://open.spotify.com/track/t1")

    @AfterTest
    fun stop() = scope.cancel()

    private fun engine(answer: (String, String) -> SpotifyResponse = { _, _ -> SpotifyResponse(204, "") }) =
        SpotifyConnectEngine(SpotifyClient(Scripted(answer)), { "token" }, { "" }, scope)

    private fun devices() = SpotifyResponse(200, """{"devices":[{"id":"pc","name":"PC","type":"Computer","is_active":true,"volume_percent":50}]}""")

    private fun playing(engine: SpotifyConnectEngine) = runBlocking { engine.play(song) }

    @Test
    fun `a song is played on the active device, and shown playing`() {
        val engine = engine { method, url -> if (method == "GET" && url.endsWith("/devices")) devices() else SpotifyResponse(204, "") }
        playing(engine)
        assertEquals(PlaybackStatus.PLAYING, engine.state.value.status)
        assertEquals(.5f, engine.state.value.volume)
    }

    @Test
    fun `with Spotify open nowhere, the listener is told where to open it`() {
        val engine = engine { _, _ -> SpotifyResponse(200, """{"devices":[]}""") }
        playing(engine)
        assertEquals(PlaybackStatus.ERROR, engine.state.value.status)
        assertTrue(engine.state.value.errorMessage!!.contains("Open the Spotify app"))
    }

    @Test
    fun `Spotify moving on at the end of the song is the song ending`() {
        val engine = engine { method, url -> if (method == "GET" && url.endsWith("/devices")) devices() else SpotifyResponse(204, "") }
        playing(engine)
        // Near the end, then Spotify is found playing something of its own choosing.
        engine.follow(song, SpotifyPlayerState(true, 197_000, "t1", 200_000, null))
        engine.follow(song, SpotifyPlayerState(true, 2_000, "autoplay", 180_000, null))
        assertEquals(PlaybackStatus.IDLE, engine.state.value.status)
        assertEquals("t1", engine.state.value.track?.id)
    }

    @Test
    fun `a song stopped at its end is over, wherever the device leaves the position`() {
        val engine = engine { method, url -> if (method == "GET" && url.endsWith("/devices")) devices() else SpotifyResponse(204, "") }
        playing(engine)
        engine.follow(song, SpotifyPlayerState(false, 199_500, "t1", 200_000, null))
        assertEquals(PlaybackStatus.IDLE, engine.state.value.status)
    }

    @Test
    fun `another song chosen in Spotify mid-song pauses ours instead of skipping it`() {
        val engine = engine { method, url -> if (method == "GET" && url.endsWith("/devices")) devices() else SpotifyResponse(204, "") }
        playing(engine)
        engine.follow(song, SpotifyPlayerState(true, 30_000, "t1", 200_000, null))
        engine.follow(song, SpotifyPlayerState(true, 1_000, "other", 150_000, null))
        assertEquals(PlaybackStatus.PAUSED, engine.state.value.status)
        assertEquals("Spotify is playing something else now.", engine.state.value.errorMessage)
    }

    @Test
    fun `with nothing more queued here, Spotify's own autoplay is followed instead of stopped`() {
        val http = Scripted { method, url -> if (method == "GET" && url.endsWith("/devices")) devices() else SpotifyResponse(204, "") }
        val followed = mutableListOf<String>()
        val engine = SpotifyConnectEngine(SpotifyClient(http), { "token" }, { "" }, scope, followAfterEnd = { next -> followed += next.id; true })
        runBlocking { engine.play(song) }
        val chosen = Track(ProviderType.SPOTIFY, "auto1", "Chosen by Spotify", song.artists, durationMs = 180_000, sourceUrl = "https://open.spotify.com/track/auto1")
        engine.follow(song, SpotifyPlayerState(true, 197_000, "t1", 200_000, null))
        engine.follow(song, SpotifyPlayerState(true, 1_500, "auto1", 180_000, null, track = chosen))

        assertEquals(listOf("auto1"), followed)
        assertEquals(PlaybackStatus.PLAYING, engine.state.value.status)
        assertEquals("auto1", engine.state.value.track?.id)
        assertEquals(1_500, engine.state.value.positionMs)
        // Nothing was paused: Spotify keeps playing its choice.
        assertTrue(http.asked.none { it.startsWith("PUT") && it.contains("/pause") })
    }

    @Test
    fun `pausing in Spotify shows as paused here`() {
        val engine = engine { method, url -> if (method == "GET" && url.endsWith("/devices")) devices() else SpotifyResponse(204, "") }
        playing(engine)
        engine.follow(song, SpotifyPlayerState(false, 60_000, "t1", 200_000, null))
        assertEquals(PlaybackStatus.PAUSED, engine.state.value.status)
        assertEquals(60_000, engine.state.value.positionMs)
    }
}

/** One player made of two, and the one thing it must never do: skip a song because it switched. */
class RoutingPlaybackEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    private class Fake(initial: PlaybackState) : PlaybackEngine {
        val flow = MutableStateFlow(initial)
        val played = mutableListOf<String>()
        var stopped = 0
        override val state: StateFlow<PlaybackState> = flow
        override suspend fun play(track: Track) {
            played += track.id
            flow.value = PlaybackState(PlaybackStatus.PLAYING, track)
        }
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setVolume(value: Float) {}
        override suspend fun setVolumeBoost(enabled: Boolean) {}
        override suspend fun setMuted(muted: Boolean) {}
        override suspend fun seekTo(positionMs: Long) {}
        override suspend fun stop() { stopped++ }
        override fun close() {}
    }

    private fun track(provider: ProviderType, id: String) = Track(provider, id, id, emptyList(), sourceUrl = "https://x/$id")

    @Test
    fun `each track goes to its own player, and the other one is stopped`() = runBlocking {
        val local = Fake(PlaybackState())
        // Left behind by an earlier song on Spotify: idle, with a track. Shown even for a moment, that reads
        // as a song ending, and the queue would move on.
        val spotify = Fake(PlaybackState(PlaybackStatus.IDLE, track(ProviderType.SPOTIFY, "old")))
        val router = RoutingPlaybackEngine(local, spotify, { it.provider == ProviderType.SPOTIFY }, scope)
        val seen = mutableListOf<PlaybackState>()
        val watching = scope.launchCollect(router, seen)

        router.play(track(ProviderType.YOUTUBE_MUSIC, "yt"))
        withTimeout(2_000) { router.state.first { it.track?.id == "yt" && it.status == PlaybackStatus.PLAYING } }
        router.play(track(ProviderType.SPOTIFY, "sp"))
        withTimeout(2_000) { router.state.first { it.track?.id == "sp" && it.status == PlaybackStatus.PLAYING } }
        delay(100)
        watching.cancel()

        assertEquals(listOf("yt"), local.played)
        assertEquals(listOf("sp"), spotify.played)
        assertEquals(1, local.stopped)
        assertTrue(seen.none { it.track?.id == "old" }, "the stale Spotify state was passed on: $seen")
    }

    private fun CoroutineScope.launchCollect(router: RoutingPlaybackEngine, into: MutableList<PlaybackState>) =
        launch { router.state.collect { synchronized(into) { into += it } } }
}
