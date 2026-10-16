package app.noctorium.spotify

import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import app.noctorium.spotify.SpotifyPageFixtures.albumPage
import app.noctorium.spotify.SpotifyPageFixtures.entity
import app.noctorium.spotify.SpotifyPageFixtures.listId
import app.noctorium.spotify.SpotifyPageFixtures.ok
import app.noctorium.spotify.SpotifyPageFixtures.page
import app.noctorium.spotify.SpotifyPageFixtures.playlistPage
import app.noctorium.spotify.SpotifyPageFixtures.song
import app.noctorium.spotify.SpotifyPageFixtures.songId
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading a playlist or an album from Spotify's public page, against pages made up in its shape. */
class SpotifyPublicPagesTest {
    private val id = listId(1)
    private val address = "${SpotifyPublicPages.EMBED}/playlist/$id"

    private fun pages(vararg replies: Pair<String, HttpReply>) = ScriptedPages(replies.toMap())
    private fun reader(http: SpotifyPageHttp) = SpotifyPublicPages(http, gapMs = 0)

    @Test
    fun `a public playlist gives its name, owner, cover and songs`() = runBlocking {
        val http = pages(address to ok(playlistPage(id, "Night Bus Home", owner = "Night Tram", songs = 3)))
        val read = reader(http).playlist(id)

        assertIs<SpotifyPublicRead.Ok>(read)
        val list = read.list
        assertEquals(id, list.id)
        assertEquals("Night Bus Home", list.title)
        assertEquals("Night Tram", list.owner)
        assertEquals("https://image.example.invalid/$id.jpg", list.artworkUrl)
        assertEquals(listOf(songId(1), songId(2), songId(3)), list.tracks.map { it.id })
        assertEquals(false, list.mayBeLonger)
        assertEquals(0, list.leftOut)
        assertEquals("https://open.spotify.com/playlist/$id", list.url)
        assertEquals(listOf(address), http.asked)

        val first = list.tracks.first()
        // The same ids, provider and address the Web API gives, so matches, hearts and the match store line up.
        assertEquals(app.noctorium.domain.ProviderType.SPOTIFY, first.provider)
        assertEquals("Song 1", first.title)
        assertEquals(200_001L, first.durationMs)
        assertEquals("https://open.spotify.com/track/${songId(1)}", first.sourceUrl)
        assertNull(first.album)
    }

    @Test
    fun `the whole credit line is one artist, commas and all`() = runBlocking {
        val entries = listOf(
            song(1, title = "Made-Up Single", credit = "Tyler, The Creator"),
            song(2, title = "Made-Up Duet (feat. Night Tram)", credit = "The Lamplighters, Night Tram"),
        )
        val read = reader(pages(address to ok(page(entity("playlist", id, "Duets", "Sample Owner", entries))))).playlist(id)

        assertIs<SpotifyPublicRead.Ok>(read)
        assertEquals(listOf("Tyler, The Creator"), read.list.tracks[0].artists.map { it.name })
        assertEquals(listOf("The Lamplighters, Night Tram"), read.list.tracks[1].artists.map { it.name })
        assertEquals("The Lamplighters, Night Tram", read.list.tracks[1].artistLine)
    }

    @Test
    fun `an episode and anything without a name or an artist are left out and counted`() = runBlocking {
        val extra = listOf(
            song(10, title = "A Made-Up Podcast, Episode 4", entityType = "episode"),
            song(11, credit = ""),
            song(12, title = " "),
            """{"uri":"spotify:local:Someone:Something:Track:200","title":"my-file","subtitle":"Someone"}""",
            song(1),
        )
        val read = reader(pages(address to ok(playlistPage(id, "Mixed Bag", songs = 2, extra = extra)))).playlist(id)

        assertIs<SpotifyPublicRead.Ok>(read)
        // The second "Song 1" is the same song twice, which is kept once rather than counted as left out.
        assertEquals(listOf(songId(1), songId(2)), read.list.tracks.map { it.id })
        assertEquals(4, read.list.leftOut)
    }

    @Test
    fun `a hundred songs back means the playlist may go on past them`() = runBlocking {
        val full = reader(pages(address to ok(playlistPage(id, "Long One", songs = 100)))).playlist(id)
        assertIs<SpotifyPublicRead.Ok>(full)
        assertEquals(100, full.list.tracks.size)
        assertTrue(full.list.mayBeLonger)

        val short = reader(pages(address to ok(playlistPage(id, "Short One", songs = 99)))).playlist(id)
        assertIs<SpotifyPublicRead.Ok>(short)
        assertEquals(false, short.list.mayBeLonger)
    }

    @Test
    fun `an album's songs carry the album, its cover and its artists`() = runBlocking {
        val albumId = listId(2)
        val http = pages("${SpotifyPublicPages.EMBED}/album/$albumId" to ok(albumPage(albumId, "Harbour Lights", "Paper Satellites")))
        val read = reader(http).list("album:$albumId")

        assertIs<SpotifyPublicRead.Ok>(read)
        assertEquals("album:$albumId", read.list.id)
        assertTrue(read.list.isAlbum)
        assertEquals("https://open.spotify.com/album/$albumId", read.list.url)
        assertEquals(false, read.list.mayBeLonger)
        val song = read.list.tracks.first()
        assertEquals("Harbour Lights", song.album?.title)
        assertEquals("https://image.example.invalid/$albumId.jpg", song.artworkUrl)
        assertEquals(listOf("Paper Satellites"), song.album?.artists?.map { it.name })
    }

    @Test
    fun `a private playlist, or one that is not there, says so in words`() = runBlocking {
        // Spotify's own answer: "Page not found", as data in the page, under HTTP 200.
        val notFound = reader(pages(address to ok(SpotifyPageFixtures.notFoundPage))).playlist(id)
        assertIs<SpotifyPublicRead.Missing>(notFound)
        assertEquals(
            "This playlist is private or does not exist. Only public playlists can be read without signing in.",
            notFound.message,
        )

        assertIs<SpotifyPublicRead.Missing>(reader(pages(address to HttpReply(404, "<html>gone</html>"))).playlist(id))
        val noEntity = page("null")
        assertIs<SpotifyPublicRead.Missing>(reader(pages(address to ok(noEntity))).playlist(id))
        // Nothing that is not an id is ever put in the address.
        val http = pages()
        assertIs<SpotifyPublicRead.Missing>(reader(http).playlist("../../api/token"))
        assertTrue(http.asked.isEmpty())
    }

    @Test
    fun `a page whose shape changed is said to have changed, not shown empty`() = runBlocking {
        val changed = listOf(
            "<html><body>No data here at all</body></html>",
            """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"somethingNew":{}}}}</script>""",
            page("""{"type":"playlist","name":"No Songs Key","subtitle":"x"}"""),
            page("""{"type":"playlist","name":"Songs Elsewhere","subtitle":"x","items":[]}"""),
            """<script id="__NEXT_DATA__" type="application/json">{not json</script>""",
        )
        changed.forEach { body ->
            val read = reader(pages(address to ok(body))).playlist(id)
            assertIs<SpotifyPublicRead.Changed>(read, body)
            assertEquals("Spotify's public page has changed and Noctorium cannot read it yet.", read.message)
        }
    }

    @Test
    fun `no answer, or a bad one, is unreachable rather than private`() = runBlocking {
        val offline = reader(pages(address to HttpReply(Http.UNREACHABLE, "timeout"))).playlist(id)
        assertIs<SpotifyPublicRead.Unreachable>(offline)
        assertTrue(offline.message.contains("Could not reach"), offline.message)

        val down = reader(pages(address to HttpReply(503, "<html>busy</html>"))).playlist(id)
        assertIs<SpotifyPublicRead.Unreachable>(down)
        assertTrue("503" in down.message && "<html>" !in down.message, down.message)

        assertIs<SpotifyPublicRead.Unreachable>(reader(pages(address to HttpReply(429, ""))).playlist(id))
    }

    @Test
    fun `pages are asked one at a time, a polite gap apart`() = runBlocking {
        var now = 10_000L
        val waits = mutableListOf<Long>()
        val http = ScriptedPages { ok(playlistPage(id, "Again")) }
        val reader = SpotifyPublicPages(http, clock = { now }, pause = { waits += it; now += it }, gapMs = 1_500)

        reader.playlist(id)
        now += 200
        reader.playlist(id)

        assertEquals(listOf(1_300L), waits)
        assertEquals(2, http.asked.size)
    }

    @Test
    fun `the copy kept on disk reads back as it was written`() {
        val folder = java.nio.file.Files.createTempDirectory("noctorium-public-copies")
        try {
            val file = folder.resolve("public-playlists.json")
            val list = (SpotifyPublicPages.parse(playlistPage(id, "Kept", songs = 2), "playlist", id, 1_800_000_000) as SpotifyPublicRead.Ok).list
            SpotifyPublicCopies(file).put(list)

            assertEquals(list, SpotifyPublicCopies(file)[id])
            // A copy this build cannot read is left out, and the rest kept.
            val text = java.nio.file.Files.readAllBytes(file).toString(Charsets.UTF_8)
            java.nio.file.Files.write(file, text.replaceFirst("{", """{"broken":{"title":5},""").toByteArray())
            assertEquals(list, SpotifyPublicCopies(file)[id])
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    /** It is a page for browsers, and is asked for as a browser asks for one. */
    @Test
    fun `the page is asked for with a browser's User-Agent`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("<html></html>"))
        server.start()
        try {
            DefaultSpotifyPageHttp().get(server.url("/embed/playlist/$id").toString())
            val request = server.takeRequest()
            assertEquals(Http.DESKTOP_USER_AGENT, request.getHeader("User-Agent"))
            assertTrue(request.getHeader("Accept").orEmpty().startsWith("text/html"))
            // Nothing that signs anybody in.
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
        } finally {
            server.shutdown()
        }
    }
}

/** Every way a listener can name a playlist, and what is not one. */
class SpotifyPlaylistLinkTest {
    private val id = "37i9dQZF1DXcBWIGoYBM5M"

    @Test
    fun `every form a playlist link comes in gives its id`() {
        listOf(
            "https://open.spotify.com/playlist/$id?si=0a1b2c3d4e5f",
            "open.spotify.com/playlist/$id",
            "https://open.spotify.com/intl-de/playlist/$id?si=x",
            "/intl-pt-BR/playlist/$id",
            "https://open.spotify.com/embed/playlist/$id?utm_source=generator",
            "/embed/playlist/$id",
            "spotify:playlist:$id",
            "spotify:user:someone:playlist:$id",
            "https://open.spotify.com/user/someone/playlist/$id",
            "  $id  ",
            "Listen to this: https://open.spotify.com/playlist/$id?si=abc.",
        ).forEach { form -> assertEquals(id, spotifyPlaylistIdIn(form), form) }
    }

    @Test
    fun `an album, a song, an artist or anything else is not a playlist`() {
        listOf(
            "https://open.spotify.com/album/$id",
            "https://open.spotify.com/track/$id",
            "spotify:artist:$id",
            "https://open.spotify.com/playlist/tooShort",
            "https://music.youtube.com/playlist?list=PL0123456789abcdef",
            "",
            "${id}x",
        ).forEach { form -> assertNull(spotifyPlaylistIdIn(form), form) }
    }

    @Test
    fun `a share link to be followed is told apart`() {
        assertTrue(isSpotifyShortLink("https://spotify.link/aBc12dEf"))
        assertTrue(isSpotifyShortLink("spoti.fi/3xYz12"))
        assertTrue(!isSpotifyShortLink("https://open.spotify.com/playlist/$id"))
        assertNull(spotifyPlaylistIdIn("https://spotify.link/aBc12dEf"))
    }
}
