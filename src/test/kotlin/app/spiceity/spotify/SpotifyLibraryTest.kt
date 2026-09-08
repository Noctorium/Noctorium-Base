package app.spiceity.spotify

import app.spiceity.domain.ProviderType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/** Answers a scripted reply per URL, so the reading can be exercised without an account. */
private class ScriptedSpotify(private val replies: Map<String, SpotifyResponse>) : SpotifyHttp {
    val asked = mutableListOf<String>()
    val tokens = mutableListOf<String>()

    override suspend fun get(url: String, accessToken: String): SpotifyResponse {
        asked += url
        tokens += accessToken
        return replies[url] ?: SpotifyResponse(404, """{"error":{"status":404,"message":"Not found"}}""")
    }
}

class SpotifyClientTest {
    private val playlistsUrl = "${SpotifyClient.API}/me/playlists?limit=50"
    private val likedUrl = "${SpotifyClient.API}/me/tracks?limit=50"

    private fun ok(body: String) = SpotifyResponse(200, body)

    @Test
    fun `playlists come back with liked songs in front of them`() = runBlocking {
        val http = ScriptedSpotify(
            mapOf(
                playlistsUrl to ok(
                    """
                    {"items":[
                      {"id":"37i9dQ","name":"Discover Weekly","public":false,
                       "owner":{"display_name":"Cem"},
                       "tracks":{"total":30},
                       "images":[{"url":"https://i/small.jpg","width":64},{"url":"https://i/big.jpg","width":640}],
                       "external_urls":{"spotify":"https://open.spotify.com/playlist/37i9dQ"}}
                    ],"next":null}
                    """.trimIndent(),
                ),
            ),
        )

        val playlists = SpotifyClient(http).playlists("token").valueOrNull()

        assertNotNull(playlists)
        assertEquals(2, playlists.size)
        // Liked songs are the thing most worth opening, and are not a playlist Spotify would ever list.
        assertEquals(SpotifyClient.LIKED_SONGS_ID, playlists.first().id)
        assertEquals("Liked Songs", playlists.first().title)

        val weekly = playlists[1]
        assertEquals("Discover Weekly", weekly.title)
        assertEquals(ProviderType.SPOTIFY, weekly.provider)
        assertEquals("Cem", weekly.ownerName)
        assertEquals(30, weekly.trackCount)
        assertEquals(false, weekly.isPublic)
        // Cover art rather than a thumbnail, so the largest image is the one taken.
        assertEquals("https://i/big.jpg", weekly.artworkUrl)
    }

    @Test
    fun `a token is sent as a bearer on every request`() = runBlocking {
        val http = ScriptedSpotify(mapOf(playlistsUrl to ok("""{"items":[],"next":null}""")))

        SpotifyClient(http).playlists("a-real-token")

        assertEquals(listOf("a-real-token"), http.tokens)
    }

    @Test
    fun `a track carries what is needed to find it again elsewhere`() = runBlocking {
        val http = ScriptedSpotify(
            mapOf(
                likedUrl to ok(
                    """
                    {"items":[{"added_at":"2026-01-01T00:00:00Z","track":{
                      "id":"4cOdK2wGLETKBW3PvgPWqT","name":"Come As You Are","duration_ms":219_000,
                      "artists":[{"id":"6olE6T","name":"Nirvana"}],
                      "album":{"id":"2gu","name":"Nevermind",
                               "images":[{"url":"https://i/cover.jpg","width":640}]},
                      "external_urls":{"spotify":"https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"}
                    }}],"next":null}
                    """.trimIndent().replace("219_000", "219000"),
                ),
            ),
        )

        val liked = SpotifyClient(http).likedSongs("token").valueOrNull()

        assertNotNull(liked)
        assertEquals(1, liked.size)
        val track = liked.single()
        assertEquals(ProviderType.SPOTIFY, track.provider)
        assertEquals("Come As You Are", track.title)
        assertEquals("Nirvana", track.artistLine)
        assertEquals(219_000, track.durationMs)
        assertEquals("Nevermind", track.album?.title)
        assertEquals("https://i/cover.jpg", track.artworkUrl)
    }

    /**
     * A playlist holds more than tracks. A removed track leaves a null, a podcast episode has no artist,
     * and a file from somebody's own disk is known to Spotify by name alone. None of those can be matched
     * to audio anywhere else, so carrying them in would only put unplayable rows in a playlist.
     */
    @Test
    fun `what is in a playlist but is not a track is left out`() = runBlocking {
        val http = ScriptedSpotify(
            mapOf(
                "${SpotifyClient.API}/playlists/mixed/tracks?limit=100" to ok(
                    """
                    {"items":[
                      {"track":null},
                      {"track":{"id":"ep1","name":"Some Episode","type":"episode","artists":[],
                                "duration_ms":3600000}},
                      {"track":{"id":"loc","name":"my-file.mp3","is_local":true,
                                "artists":[{"id":"x","name":"Unknown"}]}},
                      {"track":{"id":"real","name":"Everlong","duration_ms":250000,
                                "artists":[{"id":"foo","name":"Foo Fighters"}]}}
                    ],"next":null}
                    """.trimIndent(),
                ),
            ),
        )

        val tracks = SpotifyClient(http).playlistTracks("mixed", "token").valueOrNull()

        assertEquals(listOf("real"), tracks?.map { it.id })
    }

    /** Every page is followed, or a long playlist would quietly appear as its first fifty tracks. */
    @Test
    fun `paging follows Spotify's next links`() = runBlocking {
        val second = "${SpotifyClient.API}/me/tracks?limit=50&offset=50"
        val http = ScriptedSpotify(
            mapOf(
                likedUrl to ok(
                    """{"items":[{"track":{"id":"a","name":"A","artists":[{"id":"1","name":"X"}]}}],
                       "next":"$second"}""".trimIndent(),
                ),
                second to ok(
                    """{"items":[{"track":{"id":"b","name":"B","artists":[{"id":"1","name":"X"}]}}],
                       "next":null}""".trimIndent(),
                ),
            ),
        )

        val liked = SpotifyClient(http).likedSongs("token").valueOrNull()

        assertEquals(listOf("a", "b"), liked?.map { it.id })
        assertEquals(listOf(likedUrl, second), http.asked)
    }

    /**
     * Half a playlist is worth showing. A page failing part way through used to be the difference between
     * a long playlist appearing and it never appearing at all.
     */
    @Test
    fun `a page that fails keeps what was already gathered`() = runBlocking {
        val second = "${SpotifyClient.API}/me/tracks?limit=50&offset=50"
        val http = ScriptedSpotify(
            mapOf(
                likedUrl to ok(
                    """{"items":[{"track":{"id":"a","name":"A","artists":[{"id":"1","name":"X"}]}}],
                       "next":"$second"}""".trimIndent(),
                ),
                second to SpotifyResponse(500, "upstream exploded"),
            ),
        )

        val liked = SpotifyClient(http).likedSongs("token")

        assertEquals(listOf("a"), liked.valueOrNull()?.map { it.id })
    }

    /**
     * A refused token has to be told apart from an empty library, which is the shape of bug that made the
     * likes read as an account with nothing in it for three rounds of fixing.
     */
    @Test
    fun `a refused token is a refusal and not an empty library`() = runBlocking {
        val http = ScriptedSpotify(
            mapOf(
                playlistsUrl to SpotifyResponse(
                    401,
                    """{"error":{"status":401,"message":"The access token expired"}}""",
                ),
            ),
        )

        val result = SpotifyClient(http).playlists("stale")

        assertIs<SpotifyRead.Unauthorized>(result)
        assertNull(result.valueOrNull())
        assertTrue(result.detail.contains("Settings"), "the message does not say what to do: ${result.detail}")
    }

    /**
     * 403 is not 401. A development-mode Spotify app answers 403 to an account that is not on its user
     * list, and telling that person to sign in again would send them round a loop that cannot end.
     */
    @Test
    fun `being refused access says so instead of asking for another sign-in`() = runBlocking {
        val http = ScriptedSpotify(
            mapOf(playlistsUrl to SpotifyResponse(403, """{"error":{"status":403,"message":"Forbidden"}}""")),
        )

        val result = SpotifyClient(http).playlists("fine-token")

        assertIs<SpotifyRead.Failed>(result)
        assertTrue(result.detail.contains("development mode"), result.detail)
    }

    @Test
    fun `Spotify's own wording is passed on when it explains itself`() = runBlocking {
        val http = ScriptedSpotify(
            mapOf(
                playlistsUrl to SpotifyResponse(
                    400,
                    """{"error":{"status":400,"message":"Invalid limit"}}""",
                ),
            ),
        )

        val result = SpotifyClient(http).playlists("token")

        assertIs<SpotifyRead.Failed>(result)
        assertTrue(result.detail.contains("Invalid limit"), result.detail)
    }

    @Test
    fun `liked songs are read from the saved tracks endpoint when opened as a playlist`() = runBlocking {
        val http = ScriptedSpotify(
            mapOf(
                likedUrl to ok(
                    """{"items":[{"track":{"id":"a","name":"A","artists":[{"id":"1","name":"X"}]}}],
                       "next":null}""".trimIndent(),
                ),
            ),
        )

        val tracks = SpotifyClient(http).playlistTracks(SpotifyClient.LIKED_SONGS_ID, "token").valueOrNull()

        assertEquals(listOf("a"), tracks?.map { it.id })
        assertEquals(listOf(likedUrl), http.asked)
    }

    /** A playlist id goes into a path, so anything unexpected in it must not change the address. */
    @Test
    fun `a playlist id is escaped into the address`() {
        assertEquals("a%2Fb", SpotifyClient.encodePathSegment("a/b"))
        assertEquals("a%20b", SpotifyClient.encodePathSegment("a b"))
    }

    @Test
    fun `a reply that is not JSON is a failure rather than a crash`() = runBlocking {
        val http = ScriptedSpotify(mapOf(playlistsUrl to ok("<html>a proxy sign-in page</html>")))

        assertIs<SpotifyRead.Failed>(SpotifyClient(http).playlists("token"))
    }

    @Test
    fun `a playlist with no name is skipped rather than shown blank`() {
        val json = Json.parseToJsonElement("""{"id":"x","tracks":{"total":3}}""").jsonObject
        assertNull(SpotifyClient.playlistOf(json))
    }
}

class SpotifyAuthTest {
    private val auth = SpotifyAuth(openBrowser = {})

    /**
     * The address Spotify sends its reply to.
     *
     * Fixed and on the loopback interface, because Spotify compares redirect addresses exactly and this one
     * has to be registered against the client id before it is ever accepted -- a port chosen at random
     * could not have been. It is also the one address in Spiceity that is deliberately not HTTPS: it never
     * leaves the machine.
     */
    @Test
    fun `the redirect address is the loopback one that gets registered`() {
        assertEquals("http://127.0.0.1:8888/spiceity/spotify", SpotifyAuth.redirectUri())
    }

    @Test
    fun `the consent address proves the request without carrying a secret`() {
        val url = auth.authorizeUrl("my-client-id", "a-challenge", "a-state")

        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"), url)
        assertTrue(url.contains("client_id=my-client-id"))
        assertTrue(url.contains("response_type=code"))
        // PKCE, and specifically the hashed form: the plain one would send the verifier over the wire.
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge=a-challenge"))
        assertTrue(url.contains("state=a-state"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8888%2Fspiceity%2Fspotify"))
        // Nothing that could be extracted from a shipped binary and used against somebody's account.
        assertTrue(!url.contains("client_secret"), "a secret was put in the authorize URL")
    }

    /**
     * The permissions asked for are the promise made to the listener: Spotify is read and never written.
     * A scope that could change anything on their account has no business being requested.
     */
    @Test
    fun `only reading is ever asked for`() {
        val url = auth.authorizeUrl("id", "challenge", "state")
        val scopes = url.substringAfter("scope=").substringBefore('&')

        assertEquals("playlist-read-private+playlist-read-collaborative+user-library-read", scopes)
        listOf("modify", "write", "upload", "streaming", "playback").forEach { forbidden ->
            assertTrue(!scopes.contains(forbidden), "the scopes ask for more than reading: $scopes")
        }
    }

    @Test
    fun `each attempt gets its own verifier and state`() {
        val values = List(8) { SpotifyAuth.randomUrlSafe(32) }

        assertEquals(8, values.toSet().size, "the random values repeated")
        // URL-safe and unpadded, because they travel in a query string.
        values.forEach { value ->
            assertTrue(value.all { it.isLetterOrDigit() || it == '-' || it == '_' }, value)
        }
    }

    /**
     * A wrong redirect address is the way this setup fails, so the message names the fix.
     *
     * The pairs below are what Spotify actually answers, taken from its token endpoint rather than guessed:
     * a dead refresh token is `invalid_grant` / "Invalid refresh token", and an unknown client id is
     * `invalid_client` / "Failed to get client" — neither description names the real problem, so the code
     * has to be read as well.
     */
    @Test
    fun `Spotify's errors are turned into something to do about them`() {
        assertTrue(
            SpotifyAuth.explain("invalid_request", "Invalid redirect URI").contains(SpotifyAuth.redirectUri()),
            "the message does not say which address to register",
        )
        assertTrue(SpotifyAuth.explain("invalid_client", "Failed to get client").contains("client id"))
        assertTrue(
            SpotifyAuth.explain("invalid_grant", "Invalid refresh token").contains("Connecting Spotify again"),
        )
        // Anything unrecognised is passed through rather than replaced with a guess.
        assertEquals(
            "something new from Spotify",
            SpotifyAuth.explain("some_new_code", "something new from Spotify"),
        )
    }

    /**
     * Which failures mean the sign-in is gone. Getting this wrong costs either a listener signed out for
     * being briefly offline, or a dead token kept forever and an error at every refresh.
     */
    @Test
    fun `only Spotify refusing the sign-in counts as a refusal`() {
        assertTrue(SpotifyAuth.isRefusal("invalid_grant"))
        assertTrue(SpotifyAuth.isRefusal("invalid_client"))
        assertTrue(SpotifyAuth.isRefusal("unauthorized_client"))

        assertTrue(!SpotifyAuth.isRefusal("server_error"))
        assertTrue(!SpotifyAuth.isRefusal("temporarily_unavailable"))
        // A request Spiceity got wrong is a bug here, not a revoked sign-in.
        assertTrue(!SpotifyAuth.isRefusal("invalid_request"))
        assertTrue(!SpotifyAuth.isRefusal(""))
    }

    @Test
    fun `a token with time left on it is fresh and an expired one is not`() {
        val now = 1_800_000_000L
        assertTrue(SpotifyTokens("access", "refresh", now + 600).isFresh(now))
        assertTrue(!SpotifyTokens("access", "refresh", now - 1).isFresh(now))
        assertTrue(!SpotifyTokens("", "refresh", now + 600).isFresh(now))
    }
}
