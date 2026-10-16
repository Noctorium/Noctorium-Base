package app.noctorium.applemusic

import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.net.HttpReply
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers per address, and keeps what was asked with which headers. */
internal class ScriptedApple(private val answer: (String, Map<String, String>) -> HttpReply) : AppleMusicHttp {
    val asked = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    override suspend fun get(url: String, headers: Map<String, String>): HttpReply {
        asked += url to headers
        return answer(url, headers)
    }
    val urls: List<String> get() = asked.map { it.first }
}

/** Apple's replies, in the shapes api.music.apple.com and music.apple.com give them, cut down to what is read. */
internal object AppleFixtures {
    const val API = AppleMusicClient.API

    fun ok(body: String) = HttpReply(200, body)

    private fun part(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    /** A made-up token in the shape of a developer token: not signed by anybody, and never sent anywhere real. */
    fun jwt(issuer: String = "AMPWebPlay", expires: Long = 4_102_444_800) =
        part("""{"typ":"JWT","alg":"ES256","kid":"MadeUpKid"}""") + "." + part("""{"iss":"$issuer","iat":1791903194,"exp":$expires}""") + "." + part("not-a-signature")

    fun page(script: String = "/assets/index~0a1b2c3d4e.js") =
        """<!doctype html><html><head><script type="module" crossorigin src="$script"></script></head><body></body></html>"""

    /** A script that hands MusicKit its token through a variable, with another token beside it to be passed over. */
    fun script(token: String, decoy: String = jwt(issuer = "SomebodyElse")) =
        """const Do="2642.7.0-external",zz="$decoy",qa="$token";function start(){return{musicKit:n({developerToken:qa,app:BY})}}"""

    fun artwork(id: String) = """{"width":3000,"height":3000,"url":"https://is1-ssl.example.invalid/$id/{w}x{h}bb.jpg"}"""

    fun song(id: String, title: String, artist: String = "Daft Punk", album: String = "Discovery", type: String = "songs", catalogId: String? = null) = """
        {"id":"$id","type":"$type","href":"/v1/catalog/us/songs/$id","attributes":{"albumName":"$album","artistName":"$artist",
         "artwork":${artwork(id)},"durationInMillis":320357,"name":"$title","isrc":"GBDUW0000053",
         "playParams":{"id":"$id","kind":"song"${catalogId?.let { ",\"catalogId\":\"$it\"" }.orEmpty()}},
         ${if (type == "songs") "\"url\":\"https://music.apple.com/us/album/discovery/697194953?i=$id\"," else ""}"trackNumber":1}}
    """.trimIndent()

    fun album(id: String, title: String) = """
        {"id":"$id","type":"albums","attributes":{"artistName":"Daft Punk","artwork":${artwork(id)},"name":"$title","trackCount":14,
         "url":"https://music.apple.com/us/album/discovery/$id"}}
    """.trimIndent()

    fun playlist(id: String, title: String, type: String = "playlists") = """
        {"id":"$id","type":"$type","attributes":{"artwork":${artwork(id)},"curatorName":${if (type == "playlists") "\"Apple Music Electronic\"" else "null"},
         "description":{"standard":"Made up for a test."},"name":"$title","canEdit":true}}
    """.trimIndent()

    fun data(vararg items: String, next: String? = null) = """{"data":[${items.joinToString(",")}]${next?.let { ",\"next\":\"$it\"" }.orEmpty()}}"""
}

class AppleMusicDeveloperTokenTest {
    private val folder = Files.createTempDirectory("noctorium-apple-token")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    @Test
    fun `the token handed to MusicKit is the one found, whatever else the script holds`() {
        val token = AppleFixtures.jwt()
        assertEquals(token, AppleMusicDeveloperToken.tokenIn(AppleFixtures.script(token)))
        assertEquals(4_102_444_800, AppleMusicDeveloperToken.expiryOf(token))

        // Without the variable to follow, the web player's own is told apart by who issued it.
        val loose = "a=\"${AppleFixtures.jwt(issuer = "SomebodyElse")}\",b=\"$token\""
        assertEquals(token, AppleMusicDeveloperToken.tokenIn(loose))
        assertNull(AppleMusicDeveloperToken.tokenIn("no tokens in here"))
    }

    @Test
    fun `the token is found in the page's script once, kept, and found again when refused`() = runBlocking {
        val first = AppleFixtures.jwt(expires = 4_102_444_800)
        var served = first
        val http = ScriptedApple { url, _ ->
            when {
                url == AppleMusicDeveloperToken.WEB_PLAYER -> AppleFixtures.ok(AppleFixtures.page())
                url == "https://music.apple.com/assets/index~0a1b2c3d4e.js" -> AppleFixtures.ok(AppleFixtures.script(served))
                else -> error("asked $url")
            }
        }
        val cache = folder.resolve("token.json")
        val tokens = AppleMusicDeveloperToken(http, cache, now = { 1_800_000_000 })

        assertEquals(first, tokens.token())
        assertEquals(first, tokens.token())
        assertEquals(2, http.asked.size, "the page and its script, once")
        assertEquals(first, AppleMusicDeveloperToken(ScriptedApple { url, _ -> error("asked $url") }, cache, now = { 1_800_000_000 }).token(), "kept on disk")

        served = AppleFixtures.jwt(issuer = "AMPWebPlay", expires = 4_102_444_801)
        tokens.refused(first)
        assertEquals(served, tokens.token())
        assertEquals(4, http.asked.size)
    }

    @Test
    fun `a token about to run out is looked for again`() = runBlocking {
        val soon = AppleFixtures.jwt(expires = 1_800_000_100)
        val http = ScriptedApple { url, _ ->
            if (url == AppleMusicDeveloperToken.WEB_PLAYER) AppleFixtures.ok(AppleFixtures.page()) else AppleFixtures.ok(AppleFixtures.script(soon))
        }
        val tokens = AppleMusicDeveloperToken(http, cache = null, now = { 1_800_000_000 })

        tokens.token()
        tokens.token()

        assertEquals(4, http.asked.size, "a token with less than a day in it was trusted")
    }

    @Test
    fun `the listener's own token is used as it is, and nothing is fetched`() = runBlocking {
        val tokens = AppleMusicDeveloperToken(ScriptedApple { url, _ -> error("asked $url") }, cache = null, own = { " my-own-token " })
        assertEquals("my-own-token", tokens.token())
        assertTrue(tokens.isOwn())
    }

    @Test
    fun `a web player that has changed is said so`() = runBlocking {
        val tokens = AppleMusicDeveloperToken(ScriptedApple { _, _ -> AppleFixtures.ok("<html>nothing here</html>") }, cache = null)
        val said = assertFailsWith<AppleMusicUnavailable> { tokens.token() }
        assertTrue(said.message!!.contains("web player has changed"), said.message)
    }
}

class AppleMusicClientTest {
    private val api = AppleFixtures.API

    /** A client whose developer token is the listener's own, so nothing but the catalogue is asked. */
    private fun client(http: AppleMusicHttp, own: String? = "dev-token") =
        AppleMusicClient(http, AppleMusicDeveloperToken(http, cache = null, own = { own }), pause = {})

    @Test
    fun `a search reads songs, albums, artists and playlists, sent as the web player sends them`() = runBlocking {
        val http = ScriptedApple { url, _ ->
            check(url.startsWith("$api/catalog/gb/search?term=daft+punk&types=songs,albums,artists,playlists&limit=10")) { url }
            AppleFixtures.ok(
                """{"results":{
                    "songs":{"href":"/x","data":[${AppleFixtures.song("697195462", "One More Time")}]},
                    "albums":{"data":[${AppleFixtures.album("697194953", "Discovery")}]},
                    "artists":{"data":[{"id":"5468295","type":"artists","attributes":{"name":"Daft Punk","url":"https://music.apple.com/gb/artist/daft-punk/5468295"}}]},
                    "playlists":{"data":[${AppleFixtures.playlist("pl.0123456789abcdef", "Electronic Essentials")}]}},
                  "meta":{"results":{"order":["songs","albums","artists","playlists"]}}}""",
            )
        }

        val found = client(http).search("daft punk", "gb")

        val song = found.tracks.single()
        assertEquals("697195462", song.id)
        assertEquals(ProviderType.APPLE_MUSIC, song.provider)
        assertEquals("One More Time", song.title)
        assertEquals(listOf("Daft Punk"), song.artists.map { it.name })
        assertEquals(320_357, song.durationMs)
        assertEquals("Discovery", song.album?.title)
        assertEquals("https://is1-ssl.example.invalid/697195462/1000x1000bb.jpg", song.artworkUrl)
        assertEquals("https://music.apple.com/us/album/discovery/697194953?i=697195462", song.sourceUrl)
        assertEquals(listOf("album:697194953"), found.albums.map { it.id })
        assertEquals(listOf("artist:5468295"), found.artists.map { it.id })
        assertEquals("Apple Music Electronic", found.playlists.single().ownerName)

        val headers = http.asked.single().second
        assertEquals("Bearer dev-token", headers["Authorization"])
        assertEquals("https://music.apple.com", headers["Origin"])
        assertNull(headers["Media-User-Token"], "a catalogue search carries nobody's sign-in")
    }

    @Test
    fun `an album's songs carry the album, and its later pages are read`() = runBlocking {
        val http = ScriptedApple { url, _ ->
            when (url) {
                "$api/catalog/us/albums/697194953" -> AppleFixtures.ok(
                    """{"data":[{"id":"697194953","type":"albums","attributes":{"name":"Discovery","artistName":"Daft Punk","artwork":${AppleFixtures.artwork("al")}},
                        "relationships":{"tracks":{"data":[${AppleFixtures.song("1", "One More Time")}],"next":"/v1/catalog/us/albums/697194953/tracks?offset=1"}}}]}""",
                )
                "$api/catalog/us/albums/697194953/tracks?offset=1" -> AppleFixtures.ok(AppleFixtures.data(AppleFixtures.song("2", "Aerodynamic")))
                else -> error("asked $url")
            }
        }

        val songs = client(http).albumTracks("697194953", "us")

        assertEquals(listOf("One More Time", "Aerodynamic"), songs.map { it.title })
        assertTrue(songs.all { it.album?.id == "697194953" && it.album?.title == "Discovery" })
    }

    @Test
    fun `a catalogue playlist is read page by page`() = runBlocking {
        val http = ScriptedApple { url, _ ->
            when (url) {
                "$api/catalog/us/playlists/pl.abc/tracks?limit=100" -> AppleFixtures.ok(
                    AppleFixtures.data(AppleFixtures.song("1", "One"), """{"id":"mv","type":"music-videos","attributes":{"name":"A video","artistName":"Daft Punk"}}""", next = "/v1/catalog/us/playlists/pl.abc/tracks?offset=100"),
                )
                "$api/catalog/us/playlists/pl.abc/tracks?offset=100" -> AppleFixtures.ok(AppleFixtures.data(AppleFixtures.song("2", "Two")))
                else -> error("asked $url")
            }
        }

        assertEquals(listOf("1", "2"), client(http).playlistTracks("pl.abc", "us").map { it.id }, "the music video is left out")
    }

    @Test
    fun `the charts give songs, albums and playlists`() = runBlocking {
        val http = ScriptedApple { _, _ ->
            AppleFixtures.ok(
                """{"results":{"songs":[{"chart":"most-played","name":"Top Songs","data":[${AppleFixtures.song("1", "One")}]}],
                    "albums":[{"chart":"most-played","name":"Top Albums","data":[${AppleFixtures.album("2", "Two")}]}],
                    "playlists":[{"chart":"most-played","name":"Top Playlists","data":[${AppleFixtures.playlist("pl.3", "Three")}]}]}}""",
            )
        }

        val charts = client(http).charts("us")

        assertEquals(listOf("1"), charts.songs.map { it.id })
        assertEquals(listOf("album:2"), charts.albums.map { it.id })
        assertEquals(listOf("pl.3"), charts.playlists.map { it.id })
    }

    @Test
    fun `the library is read with the listener's token, a library song named by its catalogue id`() = runBlocking {
        val http = ScriptedApple { url, _ ->
            when {
                url == "$api/me/library/songs?limit=100" -> AppleFixtures.ok(
                    AppleFixtures.data(AppleFixtures.song("i.AbCdEf", "One More Time", type = "library-songs", catalogId = "697195462"), AppleFixtures.song("i.Uploaded", "A file of mine", type = "library-songs")),
                )
                url == "$api/me/library/playlists?limit=100" -> AppleFixtures.ok(AppleFixtures.data(AppleFixtures.playlist("p.MadeUp1", "Running", type = "library-playlists")))
                url == "$api/me/library/playlists/p.MadeUp1/tracks?limit=100" -> AppleFixtures.ok(AppleFixtures.data(AppleFixtures.song("i.AbCdEf", "One More Time", type = "library-songs", catalogId = "697195462")))
                url == "$api/me/storefront" -> AppleFixtures.ok("""{"data":[{"id":"gb","type":"storefronts","attributes":{"name":"United Kingdom"}}]}""")
                else -> error("asked $url")
            }
        }
        val apple = client(http)

        val songs = apple.librarySongs("user-token", "gb")
        assertEquals(listOf("697195462", "i.Uploaded"), songs.map { it.id })
        assertEquals("https://music.apple.com/gb/song/697195462", songs.first().sourceUrl)
        val playlist = apple.libraryPlaylists("user-token", "gb").single()
        assertEquals("p.MadeUp1", playlist.id)
        assertEquals(true, playlist.ownedByListener)
        assertEquals("Made up for a test.", playlist.description)
        assertEquals(listOf("697195462"), apple.libraryPlaylistTracks("p.MadeUp1", "user-token", "gb").map { it.id })
        assertEquals("gb", apple.storefront("user-token"))
        assertTrue(http.asked.all { (_, headers) -> headers["Media-User-Token"] == "user-token" })
    }

    @Test
    fun `what was played lately is each song once, and recommendations leave out stations`() = runBlocking {
        val http = ScriptedApple { url, _ ->
            when {
                url.startsWith("$api/me/recent/played/tracks") -> AppleFixtures.ok(AppleFixtures.data(AppleFixtures.song("1", "One"), AppleFixtures.song("1", "One"), AppleFixtures.song("2", "Two")))
                url.startsWith("$api/me/recommendations") -> AppleFixtures.ok(
                    """{"data":[{"id":"r1","type":"personal-recommendation","attributes":{"title":{"stringForDisplay":"Made for You"}},
                        "relationships":{"contents":{"data":[${AppleFixtures.playlist("pl.mix", "Favourites Mix")},{"id":"ra.1","type":"stations","attributes":{"name":"A station"}},${AppleFixtures.album("9", "Nine")}]}}},
                      {"id":"r2","type":"personal-recommendation","attributes":{"title":{"stringForDisplay":"Stations for You"}},
                        "relationships":{"contents":{"data":[{"id":"ra.2","type":"stations","attributes":{"name":"Another"}}]}}}]}""",
                )
                else -> error("asked $url")
            }
        }

        assertEquals(listOf("1", "2"), client(http).recentlyPlayed("user-token", "us").map { it.id })
        val rows = client(http).recommendations("user-token", "us")
        assertEquals(listOf("Made for You"), rows.map { it.title }, "a row of nothing but stations is no row")
        assertEquals(listOf("pl.mix", "album:9"), rows.single().playlists.map { it.id })
    }

    /** The web player's token is replaced every so often; the old one stops working without warning. */
    @Test
    fun `a developer token Apple refuses is found again, and the request asked once more`() = runBlocking {
        val first = AppleFixtures.jwt(expires = 4_102_444_800)
        val second = AppleFixtures.jwt(expires = 4_102_444_801)
        var serving = first
        val http = ScriptedApple { url, headers ->
            when {
                url == AppleMusicDeveloperToken.WEB_PLAYER -> AppleFixtures.ok(AppleFixtures.page())
                url.startsWith("https://music.apple.com/assets/") -> AppleFixtures.ok(AppleFixtures.script(serving).also { serving = second })
                headers["Authorization"] == "Bearer $first" -> HttpReply(401, "")
                else -> AppleFixtures.ok(AppleFixtures.data(AppleFixtures.song("1", "One")))
            }
        }
        val apple = AppleMusicClient(http, AppleMusicDeveloperToken(http, cache = null), pause = {})

        assertEquals("One", apple.song("1", "us")?.title)
        assertEquals(listOf("Bearer $first", "Bearer $second"), http.asked.filter { it.first.startsWith(api) }.map { it.second["Authorization"] })
    }

    @Test
    fun `a sign-in Apple refuses says to sign in again, and a missing song is no song`() = runBlocking {
        val refused = ScriptedApple { _, _ -> HttpReply(403, """{"errors":[{"id":"X","title":"Forbidden","detail":"Authentication required for request","status":"403","code":"40300"}]}""") }
        val said = assertFailsWith<AppleMusicUnavailable> { client(refused).librarySongs("spent", "us") }
        assertTrue(said.signedOut)
        assertTrue(said.message!!.contains("Sign in to Apple Music again"), said.message)

        val missing = ScriptedApple { _, _ -> HttpReply(404, """{"errors":[{"title":"Resource Not Found","status":"404"}]}""") }
        assertNull(client(missing).song("000", "us"))

        // An account with no subscription is not a sign-in to throw away.
        val unsubscribed = ScriptedApple { _, _ -> HttpReply(403, """{"errors":[{"title":"Forbidden","detail":"An active subscription is required","status":"403"}]}""") }
        val subscription = assertFailsWith<AppleMusicUnavailable> { client(unsubscribed).librarySongs("token", "us") }
        assertTrue(!subscription.signedOut && subscription.message!!.contains("subscription"), subscription.message)
    }

    @Test
    fun `the listener's token is read from a cookie header, a cookie line or alone`() {
        val token = "AmTkn0123456789+abcdefghijklmnopqrstuv/w=="
        assertEquals(token, AppleMusicClient.userTokenIn(token))
        assertEquals(token, AppleMusicClient.userTokenIn("Cookie: itua=GB; media-user-token=$token; dslang=GB-EN"))
        assertEquals(token, AppleMusicClient.userTokenIn("media-user-token=$token"))
        assertNull(AppleMusicClient.userTokenIn("itua=GB; dslang=GB-EN"))
        assertNull(AppleMusicClient.userTokenIn("not a token at all"))
    }

    @Test
    fun `the signed-in web player's cookie is the listener's token`() {
        val cookies = listOf(
            app.noctorium.auth.HarvestedCookie(".music.apple.com", "/", "itua", "GB", true, 0),
            app.noctorium.auth.HarvestedCookie(".music.apple.com", "/", "media-user-token", "AmTknLive0123456789", true, 4_102_444_800),
        )
        assertEquals("AmTknLive0123456789", app.noctorium.auth.appleMusicUserToken(cookies))
        assertNull(app.noctorium.auth.appleMusicUserToken(cookies.take(1)))
        assertNull(app.noctorium.auth.appleMusicUserToken(listOf(cookies[1].copy(expiresEpochSeconds = 1))), "an expired cookie is no sign-in")
    }
}

/** Apple Music as one of the services. */
class AppleMusicProviderTest {
    private val api = AppleFixtures.API

    private fun provider(http: AppleMusicHttp, userToken: String?) = AppleMusicProvider(
        AppleMusicClient(http, AppleMusicDeveloperToken(http, cache = null, own = { "dev-token" }), pause = {}),
        userToken = { userToken },
        storefront = { "us" },
    )

    @Test
    fun `nobody signed in has no library and no Home rows, and Apple is not asked`() = runBlocking {
        val apple = provider(ScriptedApple { url, _ -> error("asked $url") }, userToken = null)
        assertTrue(apple.getLibraryPlaylists().isEmpty())
        assertTrue(apple.getHome().isEmpty())
    }

    @Test
    fun `signed in, the library is every song as one playlist, then the playlists`() = runBlocking {
        val http = ScriptedApple { url, _ ->
            if (url.startsWith("$api/me/library/playlists")) AppleFixtures.ok(AppleFixtures.data(AppleFixtures.playlist("p.MadeUp1", "Running", type = "library-playlists")))
            else error("asked $url")
        }
        assertEquals(listOf(AppleMusicClient.LIBRARY_SONGS, "p.MadeUp1"), provider(http, "user-token").getLibraryPlaylists().map { it.id })
    }

    @Test
    fun `a library playlist is read with the sign-in, a catalogue one without`() = runBlocking {
        val http = ScriptedApple { url, _ -> AppleFixtures.ok(AppleFixtures.data(AppleFixtures.song("1", "One"))).also { check(url.startsWith(api)) } }
        val apple = provider(http, "user-token")

        apple.getPlaylistTracks(Playlist("p.MadeUp1", "Running", ProviderType.APPLE_MUSIC))
        apple.getPlaylistTracks(Playlist("pl.editorial", "Essentials", ProviderType.APPLE_MUSIC))

        assertEquals("$api/me/library/playlists/p.MadeUp1/tracks?limit=100", http.urls[0])
        assertEquals("user-token", http.asked[0].second["Media-User-Token"])
        assertEquals("$api/catalog/us/playlists/pl.editorial/tracks?limit=100", http.urls[1])
        assertNull(http.asked[1].second["Media-User-Token"])
    }

    @Test
    fun `signed in, Home has what was played lately, the recommendations and the charts`() = runBlocking {
        val http = ScriptedApple { url, _ ->
            when {
                url.startsWith("$api/me/recent/played/tracks") -> AppleFixtures.ok(AppleFixtures.data(AppleFixtures.song("1", "One")))
                url.startsWith("$api/me/recommendations") -> HttpReply(500, "")
                url.startsWith("$api/catalog/us/charts") -> AppleFixtures.ok(
                    """{"results":{"songs":[{"data":[${AppleFixtures.song("2", "Two")}]}],"albums":[{"data":[]}]}}""",
                )
                else -> error("asked $url")
            }
        }

        assertEquals(listOf("applemusic:recent", "applemusic:chart"), provider(http, "user-token").getHome().map { it.id })
    }
}
