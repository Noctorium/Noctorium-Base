package app.noctorium.deezer

import app.noctorium.domain.ProviderType
import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers per address, and keeps what was asked. */
internal class ScriptedDeezer(
    private val redirects: Map<String, String> = emptyMap(),
    private val answer: (String) -> HttpReply,
) : DeezerHttp {
    val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
    override suspend fun get(url: String): HttpReply {
        asked += url
        return answer(url)
    }
    override suspend fun redirect(url: String): String? = redirects[url]
}

/** Deezer's replies, in the shapes api.deezer.com gives them, shortened to what the client reads. */
internal object DeezerFixtures {
    const val API = DeezerClient.API

    fun ok(body: String) = HttpReply(200, body)

    val noData = ok("""{"error":{"type":"DataException","message":"no data","code":800}}""")
    val quota = ok("""{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""")

    fun track(id: Long, title: String, artist: String = "Daft Punk", duration: Int = 320, type: String = "track") = """
        {"id":$id,"readable":true,"title":"$title","title_short":"$title","link":"https://www.deezer.com/track/$id",
         "duration":$duration,"artist":{"id":27,"name":"$artist","type":"artist"},
         "album":{"id":302127,"title":"Discovery","cover_big":"https://cdn.example.invalid/500.jpg","cover_xl":"https://cdn.example.invalid/1000.jpg","type":"album"},
         "type":"$type"}
    """.trimIndent()

    fun album(id: Long, title: String) = """
        {"id":$id,"title":"$title","link":"https://www.deezer.com/album/$id","cover_xl":"https://cdn.example.invalid/$id.jpg",
         "nb_tracks":14,"record_type":"album","artist":{"id":27,"name":"Daft Punk","type":"artist"},"type":"album"}
    """.trimIndent()

    fun playlist(id: Long, title: String, loved: Boolean = false, owner: String = "Juniper Ashdown") = """
        {"id":$id,"title":"$title","public":true,"nb_tracks":70,"link":"https://www.deezer.com/playlist/$id",
         "picture_xl":"https://cdn.example.invalid/p$id.jpg","is_loved_track":$loved,
         "user":{"id":5550001,"name":"$owner","type":"user"},"type":"playlist"}
    """.trimIndent()

    val artist = """{"id":27,"name":"Daft Punk","link":"https://www.deezer.com/artist/27","picture_xl":"https://cdn.example.invalid/a27.jpg","type":"artist"}"""

    fun page(vararg items: String, next: String? = null, total: Int? = null) =
        """{"data":[${items.joinToString(",")}]${total?.let { ",\"total\":$it" }.orEmpty()}${next?.let { ",\"next\":\"$it\"" }.orEmpty()}}"""
}

class DeezerClientTest {
    private val api = DeezerFixtures.API

    private fun client(http: DeezerHttp) = DeezerClient(http, pause = {})

    @Test
    fun `a search asks for songs, albums, artists and playlists, and reads each`() = runBlocking {
        val http = ScriptedDeezer { url ->
            when {
                url.startsWith("$api/search?") -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.track(3135553, "One More Time"), total = 1))
                url.startsWith("$api/search/album?") -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.album(302127, "Discovery")))
                url.startsWith("$api/search/artist?") -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.artist))
                url.startsWith("$api/search/playlist?") -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.playlist(1595684095, "French Touch")))
                else -> error("asked $url")
            }
        }

        val found = client(http).search("daft punk one more time")

        val song = found.tracks.single()
        assertEquals("3135553", song.id)
        assertEquals(ProviderType.DEEZER, song.provider)
        assertEquals("One More Time", song.title)
        assertEquals(listOf("Daft Punk"), song.artists.map { it.name })
        assertEquals(320_000, song.durationMs)
        assertEquals("Discovery", song.album?.title)
        assertEquals("https://cdn.example.invalid/1000.jpg", song.artworkUrl)
        assertEquals("https://www.deezer.com/track/3135553", song.sourceUrl)
        assertEquals(listOf("album:302127"), found.albums.map { it.id })
        assertEquals("Daft Punk", found.albums.single().ownerName)
        assertEquals(listOf("artist:27"), found.artists.map { it.id })
        assertEquals(listOf("1595684095"), found.playlists.map { it.id })
        assertEquals(4, http.asked.size)
        assertTrue(http.asked.all { "q=daft+punk+one+more+time" in it }, http.asked.toString())
    }

    @Test
    fun `a song's own page names every artist on it`() = runBlocking {
        val http = ScriptedDeezer {
            DeezerFixtures.ok(
                """{"id":1,"title":"Song (feat. Someone)","duration":200,"link":"https://www.deezer.com/track/1",
                    "contributors":[{"id":10,"name":"Main Artist","role":"Main"},{"id":11,"name":"Someone","role":"Featured"}],
                    "artist":{"id":10,"name":"Main Artist"},"album":{"id":5,"title":"Album"},"type":"track"}""",
            )
        }

        val song = client(http).track("1")!!

        assertEquals(listOf("Main Artist", "Someone"), song.artists.map { it.name })
        assertEquals("$api/track/1", http.asked.single())
    }

    @Test
    fun `what Deezer does not have is no song, not a failure`() = runBlocking {
        assertNull(client(ScriptedDeezer { DeezerFixtures.noData }).track("0"))
        assertNull(client(ScriptedDeezer { DeezerFixtures.noData }).user(0))
    }

    @Test
    fun `an album's songs carry the album, and a long album is paged`() = runBlocking {
        val short = ScriptedDeezer {
            DeezerFixtures.ok(
                """{"id":302127,"title":"Discovery","cover_xl":"https://cdn.example.invalid/d.jpg","nb_tracks":2,
                    "artist":{"id":27,"name":"Daft Punk"},
                    "tracks":{"data":[{"id":1,"title":"One More Time","duration":320,"artist":{"id":27,"name":"Daft Punk"},"type":"track"},
                                      {"id":2,"title":"Aerodynamic","duration":212,"artist":{"id":27,"name":"Daft Punk"},"type":"track"}]}}""",
            )
        }
        val songs = client(short).albumTracks("302127")
        assertEquals(listOf("One More Time", "Aerodynamic"), songs.map { it.title })
        assertTrue(songs.all { it.album?.title == "Discovery" && it.artworkUrl == "https://cdn.example.invalid/d.jpg" })
        assertEquals(1, short.asked.size, "a whole album on its own page needs no second request")

        val long = ScriptedDeezer { url ->
            when (url) {
                "$api/album/9" -> DeezerFixtures.ok("""{"id":9,"title":"Long","nb_tracks":3,"artist":{"id":27,"name":"Daft Punk"},"tracks":{"data":[]}}""")
                "$api/album/9/tracks?limit=100" -> DeezerFixtures.ok(
                    DeezerFixtures.page(DeezerFixtures.track(1, "One"), DeezerFixtures.track(2, "Two"), next = "$api/album/9/tracks?limit=100&index=2"),
                )
                "$api/album/9/tracks?limit=100&index=2" -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.track(3, "Three")))
                else -> error("asked $url")
            }
        }
        assertEquals(listOf("One", "Two", "Three"), client(long).albumTracks("9").map { it.title })
    }

    @Test
    fun `a playlist is read page by page, stopping at the limit`() = runBlocking {
        val http = ScriptedDeezer { url ->
            when (url) {
                "$api/playlist/7/tracks?limit=100" -> DeezerFixtures.ok(
                    DeezerFixtures.page(DeezerFixtures.track(1, "One"), DeezerFixtures.track(2, "Two"), next = "$api/playlist/7/tracks?limit=100&index=2"),
                )
                "$api/playlist/7/tracks?limit=100&index=2" -> DeezerFixtures.ok(
                    DeezerFixtures.page(DeezerFixtures.track(3, "Three"), next = "$api/playlist/7/tracks?limit=100&index=3"),
                )
                "$api/playlist/7/tracks?limit=100&index=3" -> DeezerFixtures.ok(DeezerFixtures.page())
                else -> error("asked $url")
            }
        }

        assertEquals(listOf("One", "Two", "Three"), client(http).playlistTracks("7").map { it.title })
        assertEquals(listOf("One", "Two"), client(http).playlistTracks("7", limit = 2).map { it.title })
    }

    @Test
    fun `a page that fails part way keeps what came before it`() = runBlocking {
        val http = ScriptedDeezer { url ->
            if (url.endsWith("index=2")) HttpReply(500, "") else DeezerFixtures.ok(
                DeezerFixtures.page(DeezerFixtures.track(1, "One"), DeezerFixtures.track(2, "Two"), next = "$api/playlist/7/tracks?limit=100&index=2"),
            )
        }

        assertEquals(listOf("One", "Two"), client(http).playlistTracks("7").map { it.title })
        assertFailsWith<DeezerUnavailable> { client(ScriptedDeezer { HttpReply(500, "") }).playlistTracks("7") }
    }

    @Test
    fun `an episode or a row without an artist is left out of a playlist`() = runBlocking {
        val http = ScriptedDeezer {
            DeezerFixtures.ok(
                DeezerFixtures.page(
                    DeezerFixtures.track(1, "A song"),
                    DeezerFixtures.track(2, "An episode", type = "episode"),
                    """{"id":3,"title":"Nobody's","duration":100,"type":"track"}""",
                ),
            )
        }

        assertEquals(listOf("1"), client(http).playlistTracks("7").map { it.id })
    }

    @Test
    fun `a profile's playlists put its loved tracks apart from the rest`() = runBlocking {
        val http = ScriptedDeezer {
            DeezerFixtures.ok(
                DeezerFixtures.page(
                    DeezerFixtures.playlist(11, "Running"),
                    DeezerFixtures.playlist(12, "Loved Tracks", loved = true),
                    DeezerFixtures.playlist(13, "Late"),
                ),
            )
        }

        val listed = client(http).userPlaylists(5550001)

        assertEquals("12", listed.loved?.id)
        assertEquals(listOf("11", "13"), listed.others.map { it.id })
        assertEquals("Juniper Ashdown", listed.others.first().ownerName)
        assertEquals("$api/user/5550001/playlists?limit=100", http.asked.single())
    }

    @Test
    fun `a profile is read by its number`() = runBlocking {
        val http = ScriptedDeezer {
            DeezerFixtures.ok("""{"id":5550001,"name":"Juniper Ashdown","link":"https://www.deezer.com/profile/5550001","picture_xl":"https://cdn.example.invalid/u.jpg","type":"user"}""")
        }

        val user = client(http).user(5550001)!!

        assertEquals("Juniper Ashdown", user.name)
        assertEquals("https://www.deezer.com/profile/5550001", user.link)
    }

    @Test
    fun `the charts give songs, albums and playlists`() = runBlocking {
        val http = ScriptedDeezer {
            DeezerFixtures.ok(
                """{"tracks":${DeezerFixtures.page(DeezerFixtures.track(1, "One"))},
                    "albums":${DeezerFixtures.page(DeezerFixtures.album(2, "Two"))},
                    "artists":${DeezerFixtures.page(DeezerFixtures.artist)},
                    "playlists":${DeezerFixtures.page(DeezerFixtures.playlist(3, "Three"))},
                    "podcasts":{"data":[]}}""",
            )
        }

        val chart = client(http).chart()

        assertEquals(listOf("1"), chart.tracks.map { it.id })
        assertEquals(listOf("album:2"), chart.albums.map { it.id })
        assertEquals(listOf("3"), chart.playlists.map { it.id })
    }

    @Test
    fun `asked too fast, the client waits and asks once more`() = runBlocking {
        var answers = 0
        val waited = mutableListOf<Long>()
        val http = ScriptedDeezer { if (answers++ == 0) DeezerFixtures.quota else DeezerFixtures.ok(DeezerFixtures.track(1, "One")) }

        val song = DeezerClient(http, pause = { waited += it }).track("1")

        assertEquals("One", song?.title)
        assertEquals(listOf(DeezerClient.WINDOW_MS), waited)
        assertEquals(2, http.asked.size)

        val always = ScriptedDeezer { DeezerFixtures.quota }
        val refusal = assertFailsWith<DeezerUnavailable> { DeezerClient(always, pause = {}).track("1") }
        assertTrue(refusal.message!!.contains("too much"), refusal.message)
        assertEquals(2, always.asked.size, "asked only once more")
    }

    /** Deezer refuses the fifty-first request in five seconds; the client never gets that far. */
    @Test
    fun `no more than its share of requests go out in any five seconds`() = runBlocking {
        var now = 0L
        val waits = mutableListOf<Long>()
        val http = ScriptedDeezer { DeezerFixtures.noData }
        val client = DeezerClient(http, clock = { now }, pause = { waits += it; now += it })

        repeat(DeezerClient.PER_WINDOW + 1) { client.track("1") }

        assertEquals(DeezerClient.PER_WINDOW + 1, http.asked.size)
        assertEquals(listOf(DeezerClient.WINDOW_MS), waits, "the request past the share waited for the window to pass")
    }

    @Test
    fun `a refusal is said in Deezer's words, and no answer at all as that`() = runBlocking {
        val refused = assertFailsWith<DeezerUnavailable> {
            client(ScriptedDeezer { DeezerFixtures.ok("""{"error":{"type":"ParameterException","message":"Wrong parameter","code":500}}""") }).track("x")
        }
        assertEquals("Deezer: Wrong parameter", refused.message)

        val offline = assertFailsWith<DeezerUnavailable> { client(ScriptedDeezer { HttpReply(Http.UNREACHABLE, "timeout") }).track("1") }
        assertTrue(offline.message!!.startsWith("Could not reach Deezer"), offline.message)
    }

    @Test
    fun `a profile is found in its number or in any address Deezer writes for it`() {
        assertEquals(5550001L, DeezerClient.profileIdIn("5550001"))
        assertEquals(5550001L, DeezerClient.profileIdIn(" https://www.deezer.com/en/profile/5550001 "))
        assertEquals(5550001L, DeezerClient.profileIdIn("deezer.com/profile/5550001/loved"))
        assertEquals(5550001L, DeezerClient.profileIdIn("https://www.deezer.com/pt-br/profile/5550001?utm_source=share"))
        assertNull(DeezerClient.profileIdIn("https://www.deezer.com/en/track/3135553"))
        assertNull(DeezerClient.profileIdIn("somebody"))
        assertNull(DeezerClient.profileIdIn("0"))
    }

    @Test
    fun `a share link is followed to the profile it leads to`() = runBlocking {
        val http = ScriptedDeezer(
            redirects = mapOf(
                "https://link.deezer.com/s/31AbCdEf" to "https://www.deezer.com/en/profile/5550001?host=0&utm_source=share",
            ),
        ) { error("nothing should be read") }

        assertEquals(5550001L, client(http).profileIn("Here is mine: link.deezer.com/s/31AbCdEf"))
        assertNull(client(http).profileIn("https://link.deezer.com/s/nowhere"))
    }
}
