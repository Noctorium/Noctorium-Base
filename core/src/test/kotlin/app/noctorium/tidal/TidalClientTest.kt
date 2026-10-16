package app.noctorium.tidal

import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers per address, and keeps what was asked and with which token. */
internal class ScriptedTidal(private val answer: (String) -> HttpReply) : TidalHttp {
    val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
    val tokens = java.util.concurrent.CopyOnWriteArrayList<String>()
    override suspend fun get(url: String, accessToken: String): HttpReply {
        asked += url
        tokens += accessToken
        return answer(url)
    }
}

/** Tidal's replies, in the JSON:API shapes openapi.tidal.com/v2 documents, cut down to what the client reads. */
internal object TidalFixtures {
    const val API = TidalClient.API

    fun ok(body: String) = HttpReply(200, body)

    fun track(id: String, title: String, artist: String? = "a27", album: String? = "al1", version: String? = null, duration: String = "PT5M20S") = """
        {"id":"$id","type":"tracks","attributes":{"title":"$title","version":${version?.let { "\"$it\"" } ?: "null"},"duration":"$duration","isrc":"GBDUW0000053",
         "externalLinks":[{"href":"https://tidal.com/browse/track/$id","meta":{"type":"TIDAL_SHARING"}}]},
         "relationships":{"artists":{"data":[${artist?.let { """{"id":"$it","type":"artists"}""" }.orEmpty()}],"links":{"self":"/tracks/$id/relationships/artists"}},
                          "albums":{"data":[${album?.let { """{"id":"$it","type":"albums"}""" }.orEmpty()}],"links":{"self":"/tracks/$id/relationships/albums"}}}}
    """.trimIndent()

    fun artist(id: String = "a27", name: String = "Daft Punk") =
        """{"id":"$id","type":"artists","attributes":{"name":"$name","popularity":0.9}}"""

    fun album(id: String = "al1", title: String = "Discovery", art: String? = "art1", artist: String = "a27") = """
        {"id":"$id","type":"albums","attributes":{"title":"$title","numberOfItems":14,"duration":"PT1H1M","releaseDate":"2001-03-12"},
         "relationships":{"artists":{"data":[{"id":"$artist","type":"artists"}]},"coverArt":{"data":[${art?.let { """{"id":"$it","type":"artworks"}""" }.orEmpty()}]}}}
    """.trimIndent()

    fun artwork(id: String = "art1") = """
        {"id":"$id","type":"artworks","attributes":{"mediaType":"IMAGE","files":[
          {"href":"https://resources.example.invalid/$id/320.jpg","meta":{"width":320,"height":320}},
          {"href":"https://resources.example.invalid/$id/1280.jpg","meta":{"width":1280,"height":1280}}]}}
    """.trimIndent()

    fun playlist(id: String, name: String, type: String = "USER", access: String = "PUBLIC") = """
        {"id":"$id","type":"playlists","attributes":{"name":"$name","description":"Made up for a test","numberOfItems":3,
         "playlistType":"$type","accessType":"$access","externalLinks":[{"href":"https://tidal.com/browse/playlist/$id","meta":{"type":"TIDAL_SHARING"}}]}}
    """.trimIndent()

    fun document(data: String, included: List<String> = emptyList(), next: String? = null) =
        """{"data":$data,"included":[${included.joinToString(",")}],"links":{"self":"/x"${next?.let { ",\"next\":\"$it\"" }.orEmpty()}}}"""

    fun identifiers(type: String, vararg ids: String) = ids.joinToString(",", "[", "]") { """{"id":"$it","type":"$type"}""" }
}

class TidalClientTest {
    private val api = TidalFixtures.API

    private fun client(http: TidalHttp) = TidalClient(http, pause = {})

    /** The tracks of a search name artists they do not carry, so those are asked for, all at once. */
    @Test
    fun `a search reads its songs' artists and its albums' covers in one more request each`() = runBlocking {
        val http = ScriptedTidal { url ->
            when {
                url.startsWith("$api/searchResults/daft%20punk?") -> TidalFixtures.ok(
                    TidalFixtures.document(
                        """{"id":"daft punk","type":"searchResults","attributes":{"trackingId":"x"},"relationships":{
                            "tracks":{"data":${TidalFixtures.identifiers("tracks", "t1", "t2")}},
                            "albums":{"data":${TidalFixtures.identifiers("albums", "al1")}},
                            "artists":{"data":${TidalFixtures.identifiers("artists", "a27")}},
                            "playlists":{"data":${TidalFixtures.identifiers("playlists", "0b5df3e4-0000-4000-8000-000000000001")}}}}""",
                        listOf(
                            TidalFixtures.track("t1", "One More Time"), TidalFixtures.track("t2", "Aerodynamic"),
                            TidalFixtures.album(), TidalFixtures.artist(),
                            TidalFixtures.playlist("0b5df3e4-0000-4000-8000-000000000001", "French Touch", type = "EDITORIAL"),
                        ),
                    ),
                )
                url.startsWith("$api/tracks?") -> TidalFixtures.ok(
                    TidalFixtures.document(
                        "[${TidalFixtures.track("t2", "Aerodynamic")},${TidalFixtures.track("t1", "One More Time")}]",
                        listOf(TidalFixtures.artist(), TidalFixtures.album()),
                    ),
                )
                url.startsWith("$api/albums?") -> TidalFixtures.ok(
                    TidalFixtures.document("[${TidalFixtures.album()}]", listOf(TidalFixtures.artist(), TidalFixtures.artwork())),
                )
                else -> error("asked $url")
            }
        }

        val found = client(http).search("daft punk", "a-token", "GB")

        assertEquals(listOf("t1", "t2"), found.tracks.map { it.id }, "the search's own order is kept")
        val song = found.tracks.first()
        assertEquals(ProviderType.TIDAL, song.provider)
        assertEquals(listOf("Daft Punk"), song.artists.map { it.name })
        assertEquals("Discovery", song.album?.title)
        assertEquals(320_000, song.durationMs)
        assertEquals("https://tidal.com/browse/track/t1", song.sourceUrl)
        assertEquals(listOf("album:al1"), found.albums.map { it.id })
        assertEquals("Daft Punk", found.albums.single().ownerName)
        assertEquals("https://resources.example.invalid/art1/1280.jpg", found.albums.single().artworkUrl)
        assertEquals(listOf("artist:a27"), found.artists.map { it.id })
        assertEquals("Tidal", found.playlists.single().ownerName, "an editorial playlist is Tidal's")
        assertEquals(true, found.playlists.single().isPublic)
        assertTrue(http.asked.first().contains("countryCode=GB"), http.asked.first())
        assertTrue(http.asked[1].contains("filter%5Bid%5D=t1&filter%5Bid%5D=t2"), http.asked[1])
        assertTrue(http.tokens.all { it == "a-token" })
    }

    @Test
    fun `a song keeps its version in its title, as other services write it`() = runBlocking {
        val http = ScriptedTidal {
            TidalFixtures.ok(TidalFixtures.document(TidalFixtures.track("t9", "Come As You Are", artist = "n1", version = "Remastered"), listOf(TidalFixtures.artist("n1", "Nirvana"))))
        }

        val song = client(http).track("t9", "a-token", "US")!!

        assertEquals("Come As You Are (Remastered)", song.title)
        assertEquals(listOf("Nirvana"), song.artists.map { it.name })
        assertNull(song.album, "an album the document does not carry is not made up")
    }

    @Test
    fun `what Tidal does not have is no song, not a failure`() = runBlocking {
        assertNull(client(ScriptedTidal { HttpReply(404, """{"errors":[{"status":"404","code":"NOT_FOUND","detail":"Track not found"}]}""") }).track("0", "a-token", "US"))
    }

    @Test
    fun `an album's songs carry the album and its artists, and its later pages are read`() = runBlocking {
        val http = ScriptedTidal { url ->
            when {
                url.startsWith("$api/albums/al1?") -> TidalFixtures.ok(
                    """{"data":{"id":"al1","type":"albums","attributes":{"title":"Discovery"},"relationships":{
                        "artists":{"data":${TidalFixtures.identifiers("artists", "a27")}},
                        "coverArt":{"data":${TidalFixtures.identifiers("artworks", "art1")}},
                        "items":{"data":[{"id":"t1","type":"tracks","meta":{"trackNumber":1,"volumeNumber":1}}],
                                 "links":{"next":"/albums/al1/relationships/items?countryCode=US&page%5Bcursor%5D=two"}}}},
                      "included":[${TidalFixtures.artist()},${TidalFixtures.artwork()},${TidalFixtures.track("t1", "One More Time", artist = null, album = null)}]}""",
                )
                url == "$api/albums/al1/relationships/items?countryCode=US&page%5Bcursor%5D=two" -> TidalFixtures.ok(
                    TidalFixtures.document("[{\"id\":\"t2\",\"type\":\"tracks\"}]", listOf(TidalFixtures.track("t2", "Aerodynamic", artist = null, album = null))),
                )
                else -> error("asked $url")
            }
        }

        val songs = client(http).albumTracks("al1", "a-token", "US")

        assertEquals(listOf("One More Time", "Aerodynamic"), songs.map { it.title })
        assertTrue(songs.all { it.artists.single().name == "Daft Punk" && it.album?.title == "Discovery" })
        assertEquals("https://resources.example.invalid/art1/1280.jpg", songs.first().artworkUrl)
    }

    @Test
    fun `a playlist is read cursor by cursor, its videos left out, its songs' artists asked for`() = runBlocking {
        val first = "$api/playlists/p1/relationships/items?countryCode=US&include=items"
        val second = "$api/playlists/p1/relationships/items?countryCode=US&include=items&page%5Bcursor%5D=next"
        val http = ScriptedTidal { url ->
            when {
                url == first -> TidalFixtures.ok(
                    TidalFixtures.document(
                        """[{"id":"t1","type":"tracks"},{"id":"v1","type":"videos"}]""",
                        listOf(TidalFixtures.track("t1", "One"), """{"id":"v1","type":"videos","attributes":{"title":"A video"}}"""),
                        next = "/playlists/p1/relationships/items?countryCode=US&include=items&page%5Bcursor%5D=next",
                    ),
                )
                url == second -> TidalFixtures.ok(TidalFixtures.document("""[{"id":"t2","type":"tracks"}]""", listOf(TidalFixtures.track("t2", "Two"))))
                url.startsWith("$api/tracks?") -> TidalFixtures.ok(
                    TidalFixtures.document("[${TidalFixtures.track("t1", "One")},${TidalFixtures.track("t2", "Two")}]", listOf(TidalFixtures.artist())),
                )
                else -> error("asked $url")
            }
        }

        assertEquals(listOf("t1", "t2"), client(http).playlistTracks("p1", "a-token", "US").map { it.id })
    }

    @Test
    fun `a song Tidal names no artist for is left out, since there is nothing to match it by`() = runBlocking {
        val http = ScriptedTidal { TidalFixtures.ok(TidalFixtures.document(TidalFixtures.track("t1", "Nobody's", artist = null))) }
        assertNull(client(http).track("t1", "a-token", "US"))
    }

    @Test
    fun `the account is read from users me, with its country`() = runBlocking {
        val http = ScriptedTidal {
            TidalFixtures.ok("""{"data":{"id":"5550002","type":"users","attributes":{"username":"juniper.ashdown","country":"gb","email":"someone@example.invalid"}}}""")
        }

        val me = client(http).me("a-token")

        assertEquals("5550002", me.id)
        assertEquals("juniper.ashdown", me.name)
        assertEquals("GB", me.country)
        assertEquals("$api/users/me", http.asked.single())
    }

    @Test
    fun `the collection's playlists and the mixes are read for the account`() = runBlocking {
        val http = ScriptedTidal { url ->
            when {
                url.startsWith("$api/userCollections/5550002/relationships/playlists?") -> TidalFixtures.ok(
                    TidalFixtures.document(
                        TidalFixtures.identifiers("playlists", "p1", "p2"),
                        listOf(TidalFixtures.playlist("p1", "Running", access = "UNLISTED"), TidalFixtures.playlist("p2", "Late")),
                    ),
                )
                url.startsWith("$api/userRecommendations/5550002?") -> TidalFixtures.ok(
                    """{"data":{"id":"5550002","type":"userRecommendations","relationships":{
                        "myMixes":{"data":${TidalFixtures.identifiers("playlists", "m1")}},
                        "discoveryMixes":{"data":[]},
                        "newArrivalMixes":{"data":${TidalFixtures.identifiers("playlists", "m2")}}}},
                      "included":[${TidalFixtures.playlist("m1", "My Mix 1", type = "MIX")},${TidalFixtures.playlist("m2", "New Arrivals", type = "MIX")}]}""",
                )
                else -> error("asked $url")
            }
        }

        val playlists = client(http).userPlaylists("5550002", "a-token", "GB")
        assertEquals(listOf("Running", "Late"), playlists.map { it.title })
        assertEquals(app.noctorium.domain.PlaylistVisibility.UNLISTED, playlists.first().visibility)
        assertNull(playlists.first().isPublic, "an unlisted playlist is neither public nor private")
        assertEquals(true, playlists.last().isPublic)

        val mixes = client(http).mixes("5550002", "a-token", "GB")
        assertEquals(listOf("Your mixes on Tidal", "New arrivals on Tidal"), mixes.map { it.title })
        assertEquals(listOf("m1"), mixes.first().playlists.map { it.id })
    }

    @Test
    fun `a refused token says to sign in again, and a rate limit is waited out once`() = runBlocking {
        val refused = assertFailsWith<TidalUnavailable> {
            client(ScriptedTidal { HttpReply(401, """{"errors":[{"status":"401","code":"UNAUTHORIZED"}]}""") }).me("old")
        }
        assertTrue(refused.signedOut)

        var answers = 0
        val waited = mutableListOf<Long>()
        val busy = ScriptedTidal { if (answers++ == 0) HttpReply(429, "", mapOf("Retry-After" to listOf("3"))) else TidalFixtures.ok("""{"data":{"id":"1","type":"users","attributes":{"username":"x"}}}""") }
        assertEquals("x", TidalClient(busy, pause = { waited += it }).me("a-token").name)
        assertEquals(listOf(3_000L), waited)

        val offline = assertFailsWith<TidalUnavailable> { client(ScriptedTidal { HttpReply(Http.UNREACHABLE, "timeout") }).me("a-token") }
        assertTrue(!offline.signedOut && offline.message!!.startsWith("Could not reach Tidal"), offline.message)
    }

    @Test
    fun `lengths are read from Tidal's ISO 8601 durations`() {
        assertEquals(205_000, TidalClient.durationOf("PT3M25S"))
        assertEquals(3_660_000, TidalClient.durationOf("PT1H1M"))
        assertNull(TidalClient.durationOf("not a length"))
        assertNull(TidalClient.durationOf(null))
    }
}

/** Tidal as one of the services: what it reads with which token, and what it says when it cannot read at all. */
class TidalMusicProviderTest {
    private val api = TidalFixtures.API

    private fun access(signedIn: Boolean, app: TidalApp? = TidalApp("my-app-id", "my-app-secret")): TidalAccess {
        var stored: String? = if (signedIn) "a-refresh-token" else null
        return TidalAccess(
            refresh = { _, _ -> TidalAuth.Result.Success(TidalTokens("user-token-${System.nanoTime()}", "a-refresh-token", Long.MAX_VALUE)) },
            clientCredentials = { TidalAuth.Result.Success(TidalTokens("app-token", null, Long.MAX_VALUE)) },
            readApp = { app },
            readRefreshToken = { stored },
            writeRefreshToken = { stored = it },
            clearRefreshToken = { stored = null },
        )
    }

    private fun provider(http: TidalHttp, access: TidalAccess, userId: String = "5550002") =
        TidalMusicProvider(TidalClient(http, pause = {}), access, userId = { userId }, country = { "GB" })

    @Test
    fun `signed out, the catalogue is read as the app, and signed in, as the listener`() = runBlocking {
        val http = ScriptedTidal { TidalFixtures.ok(TidalFixtures.document("""{"id":"q","type":"searchResults","relationships":{}}""")) }

        provider(http, access(signedIn = false), userId = "").search("anything")
        assertEquals("app-token", http.tokens.single())

        provider(http, access(signedIn = true)).search("anything")
        assertTrue(http.tokens.last().startsWith("user-token"), http.tokens.last())
    }

    @Test
    fun `the library is the listener's songs, then their playlists, then their albums`() = runBlocking {
        val http = ScriptedTidal { url ->
            when {
                url.contains("/relationships/playlists?") -> TidalFixtures.ok(TidalFixtures.document(TidalFixtures.identifiers("playlists", "p1"), listOf(TidalFixtures.playlist("p1", "Running"))))
                url.contains("/relationships/albums?") -> TidalFixtures.ok(TidalFixtures.document(TidalFixtures.identifiers("albums", "al1"), listOf(TidalFixtures.album())))
                url.startsWith("$api/albums?") -> TidalFixtures.ok(TidalFixtures.document("[${TidalFixtures.album()}]", listOf(TidalFixtures.artist())))
                else -> error("asked $url")
            }
        }

        val library = provider(http, access(signedIn = true)).getLibraryPlaylists()

        assertEquals(listOf(TidalClient.COLLECTION_TRACKS, "p1", "album:al1"), library.map { it.id })
        assertTrue(provider(http, access(signedIn = false), userId = "").getLibraryPlaylists().isEmpty(), "nobody signed in has no collection")
    }

    /** A token can run out between being handed over and being used; one refusal is answered with a fresh one. */
    @Test
    fun `a token Tidal refuses is replaced once, and the read tried again`() = runBlocking {
        var calls = 0
        val http = ScriptedTidal {
            if (calls++ == 0) HttpReply(401, "") else TidalFixtures.ok(TidalFixtures.document(TidalFixtures.track("t1", "One"), listOf(TidalFixtures.artist())))
        }

        val song = provider(http, access(signedIn = true)).getTrack("t1")

        assertEquals("One", song?.title)
        assertEquals(2, http.tokens.toSet().size, "the second try went out with a new token")
    }

    @Test
    fun `with no Tidal app at all, a search is quiet and a song asked for says what to do`() = runBlocking {
        val http = ScriptedTidal { error("asked $it") }
        val unset = provider(http, access(signedIn = false, app = null), userId = "")

        assertTrue(unset.search("anything").tracks.isEmpty())
        val said = assertFailsWith<TidalUnavailable> { unset.getTrack("t1") }
        assertTrue(said.message!!.contains("developer.tidal.com"), said.message)
        assertFailsWith<TidalUnavailable> { unset.getPlaylistTracks(Playlist("album:al1", "Discovery", ProviderType.TIDAL)) }

        val noSecret = provider(http, access(signedIn = false, app = TidalApp("my-app-id")), userId = "")
        assertTrue(assertFailsWith<TidalUnavailable> { noSecret.getTrack("t1") }.message!!.contains("Connect Tidal"))
    }

    @Test
    fun `Home is the listener's mixes, and nothing for somebody not signed in`() = runBlocking {
        val http = ScriptedTidal {
            TidalFixtures.ok(
                """{"data":{"id":"5550002","type":"userRecommendations","relationships":{"myMixes":{"data":${TidalFixtures.identifiers("playlists", "m1")}}}},
                  "included":[${TidalFixtures.playlist("m1", "My Mix 1", type = "MIX")}]}""",
            )
        }

        val home = provider(http, access(signedIn = true)).getHome()
        assertEquals(listOf("tidal:mixes:0"), home.map { it.id })
        assertEquals(listOf("m1"), home.single().playlists.map { it.id })
        assertTrue(provider(ScriptedTidal { error("asked $it") }, access(signedIn = false), userId = "").getHome().isEmpty())
    }
}
