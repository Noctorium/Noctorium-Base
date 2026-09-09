package app.spiceity.social

import app.spiceity.domain.ProviderType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Searching YouTube Music through its own interface rather than through yt-dlp.
 *
 * yt-dlp lists that page as `url_transparent` stubs carrying an id and nothing else — no title, no artist,
 * no length — and mixes artists and albums in among the songs. That is why a YouTube Music track showed
 * "YouTube Music" where its artist belongs: the artist fell back to the provider's own name because none
 * ever arrived. The fixture here is a real reply, trimmed to five rows with the click-tracking blobs
 * removed; every field the parser reads is exactly as YouTube sent it.
 */
class YouTubeMusicSearchTest {
    private val client = YouTubeMusicClient(RecordingInnertube())

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/youtube-music-search-antarctica.json")) {
            "fixture missing from test resources"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `a real reply parses into songs with artist, album and length`() {
        val songs = client.parseSongs(fixture())

        assertEquals(6, songs.size)
        val first = songs.first()
        assertEquals("7tLGGiNjp_U", first.id)
        assertEquals("Antarctica", first.title)
        assertEquals("\$uicideboy\$", first.artistLine)
        assertEquals("Antarctica", first.album?.title)
        assertEquals(127_000, first.durationMs)
        assertEquals(ProviderType.YOUTUBE_MUSIC, first.provider)
        assertEquals("https://music.youtube.com/watch?v=7tLGGiNjp_U", first.sourceUrl)
        assertNotNull(first.artworkUrl)
    }

    /** The whole point of the change: no row may fall back to the provider's name for its artist. */
    @Test
    fun `no song is left carrying the provider name as its artist`() {
        val songs = client.parseSongs(fixture())

        assertTrue(songs.isNotEmpty())
        songs.forEach { song ->
            assertFalse(
                song.artistLine.equals("YouTube Music", ignoreCase = true),
                "${song.title} still has a placeholder artist",
            )
            assertTrue(song.artistLine.isNotBlank(), "${song.title} has no artist")
            assertNotNull(song.durationMs, "${song.title} has no length")
        }
    }

    @Test
    fun `every row keeps its own artist and length`() {
        val songs = client.parseSongs(fixture()).associateBy { it.id }

        assertEquals("Asia 2001", songs.getValue("j6Pr6-Iw8CE").artistLine)
        assertEquals(493_000, songs.getValue("j6Pr6-Iw8CE").durationMs)
        assertEquals("grcz", songs.getValue("WnIlnqugqVk").artistLine)
        assertEquals(184_000, songs.getValue("WnIlnqugqVk").durationMs)
        assertEquals("Vangelis", songs.getValue("Ts5HzcoAJ8A").artistLine)
        assertEquals("Vangelis: Delectus", songs.getValue("Ts5HzcoAJ8A").album?.title)
    }

    /**
     * A credit YouTube sends as one run is one artist, however many commas are inside it. Splitting the
     * text on punctuation would turn this into four artists, the last of them called "and Rajeev".
     */
    @Test
    fun `a credit sent as a single run is kept whole`() {
        val song = client.parseSongs(fixture()).single { it.id == "hiGI-VAgOII" }

        assertEquals(1, song.artists.size)
        assertEquals("Vijay Prakash, Krish, Devan, and Rajeev", song.artistLine)
        assertEquals("Thuppakki (Original Motion Picture Soundtrack)", song.album?.title)
    }

    /**
     * Separately credited artists arrive as separate runs with "," and "&" between them. Those separators
     * are not the field separator: reading them as one once left this row's album showing as "," alone.
     */
    @Test
    fun `separately credited artists are split, and the album survives them`() {
        val song = client.parseSongs(fixture()).single { it.id == "Y8IZiW8PbZc" }

        assertEquals(listOf("Ada Milea", "Dorina Chiriac", "Radu Banzaru"), song.artists.map { it.name })
        assertEquals("Apolodor (feat. Chiriac, Dorina / Banzaru, Radu)", song.album?.title)
        assertEquals(175_000, song.durationMs)
    }

    /** The play count sits in a third column; counting it as a detail would corrupt artist and album. */
    @Test
    fun `the play count column is not mistaken for a detail`() {
        client.parseSongs(fixture()).forEach { song ->
            assertFalse(song.artistLine.contains("plays"), "${song.title} took the play count as its artist")
            assertFalse(song.album?.title.orEmpty().contains("plays"))
        }
    }

    @Test
    fun `lengths are read in both minute and hour forms`() {
        assertEquals(127_000, client.durationTextToMs("2:07"))
        assertEquals(184_000, client.durationTextToMs("3:04"))
        assertEquals(3_723_000, client.durationTextToMs("1:02:03"))
    }

    @Test
    fun `text that is not a length is refused rather than guessed at`() {
        assertEquals(null, client.durationTextToMs("167M plays"))
        assertEquals(null, client.durationTextToMs("Antarctica"))
        assertEquals(null, client.durationTextToMs(""))
        assertEquals(null, client.durationTextToMs("12"))
        // Sixty seconds would be a minute; a value like this is not a length YouTube would print.
        assertEquals(null, client.durationTextToMs("3:99"))
    }

    @Test
    fun `a reply in an unfamiliar shape yields nothing rather than nonsense`() {
        assertTrue(client.parseSongs("""{"contents":{}}""").isEmpty())
        assertTrue(client.parseSongs("not json").isEmpty())
    }

    @Test
    fun `the songs filter is sent so albums and artists stay out of the results`() = runBlocking {
        val http = RecordingInnertube(LikeHttpResponse(200, fixture()))
        val session = YouTubeSession(InnertubeKeys("AIzaKey", "1.20260825.00.00"), "SAPISID=abc123")

        val songs = YouTubeMusicClient(http).searchSongs("antarctica", limit = 3, session = session)

        assertEquals(3, songs.size, "the limit is applied")
        assertContains(http.calls.single(), "/search?")
        assertContains(http.bodies.single().orEmpty(), """"query":"antarctica"""")
        assertContains(http.bodies.single().orEmpty(), """"params":"EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"""")
    }

    /** Signing in only personalises the results; a signed-out listener still gets a usable search. */
    @Test
    fun `search works without a session`() = runBlocking {
        val http = RecordingInnertube(LikeHttpResponse(200, fixture()))
        val anonymous = YouTubeSession(InnertubeKeys("AIzaKey", "1.20260825.00.00"), null)

        assertEquals(6, YouTubeMusicClient(http).searchSongs("antarctica", 10, anonymous).size)
    }

    @Test
    fun `a refused search returns nothing so the caller can fall back`() = runBlocking {
        val http = RecordingInnertube(LikeHttpResponse(403, "nope"))
        val session = YouTubeSession(InnertubeKeys("AIzaKey", "1.20260825.00.00"), "SAPISID=abc123")

        assertTrue(YouTubeMusicClient(http).searchSongs("antarctica", 10, session).isEmpty())
    }

    @Test
    fun `a blank query never reaches the network`() = runBlocking {
        val http = RecordingInnertube()
        val session = YouTubeSession(InnertubeKeys("AIzaKey", "1.20260825.00.00"), "SAPISID=abc123")

        assertTrue(YouTubeMusicClient(http).searchSongs("   ", 10, session).isEmpty())
        assertTrue(http.calls.isEmpty())
    }
}
