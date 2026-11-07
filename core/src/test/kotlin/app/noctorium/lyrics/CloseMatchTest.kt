package app.noctorium.lyrics

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloseMatchTest {
    private fun upload(title: String, channel: String, seconds: Long = 290) = Track(
        provider = ProviderType.YOUTUBE_VIDEO,
        id = "x",
        title = title,
        artists = listOf(Artist("c", channel, ProviderType.YOUTUBE_VIDEO)),
        durationMs = seconds * 1_000,
        sourceUrl = "https://www.youtube.com/watch?v=x",
    )

    private val lines = "[00:01.00]Like the legend of the phoenix\\n[00:04.00]Our ends were beginnings\\n[00:07.00]What keeps the planet spinning\\n[00:10.00]The force from the beginning"

    private fun candidate(id: Int, title: String, artist: String, seconds: Int = 248) =
        """{"id":$id,"trackName":"$title","artistName":"$artist","duration":$seconds.0,"plainLyrics":"x","syncedLyrics":"$lines"}"""

    /** An LRCLIB whose search answers [answer] for every search, and nothing for an exact lookup. */
    private fun lrclib(answer: String, asked: MutableList<String> = mutableListOf()) = object : LyricsHttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): LyricsHttpResponse {
            asked += url
            return if ("/api/search" in url) LyricsHttpResponse(200, answer) else LyricsHttpResponse(404, "")
        }
    }

    @Test
    fun `an uploader's additions come off the title`() {
        assertEquals("Daft Punk - Get Lucky", stripUploaderNoise("Daft Punk - Get Lucky (slowed + reverb) [lyrics]"))
        assertEquals("Get Lucky", stripUploaderNoise("Get Lucky | 1 hour version"))
        assertEquals("Get Lucky", stripUploaderNoise("Get Lucky ft. Pharrell Williams"))
        assertEquals("Get Lucky", stripUploaderNoise("Get Lucky (Official Audio) ✨"))
        assertEquals("Get Lucky", stripUploaderNoise("Get Lucky sped up"))
        // A remix's lyrics may differ, so it stays in the name.
        assertEquals("Get Lucky (Daft Punk Remix)", stripUploaderNoise("Get Lucky (Daft Punk Remix)"))
    }

    @Test
    fun `a channel is told from an artist`() {
        assertTrue(looksLikeAChannel("Daft Punk - Topic"))
        assertTrue(looksLikeAChannel("DaftPunkVEVO"))
        assertTrue(looksLikeAChannel("Chill Vibes Music"))
        assertTrue(!looksLikeAChannel("Daft Punk"))
    }

    @Test
    fun `a title with a dash is read as artist and song`() {
        val guesses = guessesFor(upload("Daft Punk - Get Lucky (slowed + reverb)", "Chill Vibes Music"))
        assertEquals(Guess("Get Lucky", "Daft Punk"), guesses.first())
        // The channel's name is never searched for as the artist.
        assertTrue(guesses.none { it.artist == "Chill Vibes Music" })
    }

    @Test
    fun `a re-upload finds the song's lyrics, marked as a close match`() = runBlocking {
        val asked = mutableListOf<String>()
        val found = CloseMatchFinder(lrclib("[${candidate(7, "Get Lucky", "Daft Punk")}]", asked))
            .find(upload("Daft Punk - Get Lucky (slowed + reverb) [lyrics]", "Chill Vibes Music"))
        assertNotNull(found)
        assertEquals(LyricsProviderStatus.FOUND, found.status)
        assertEquals(CloseMatch("Get Lucky", "Daft Punk"), found.result?.closeMatch)
        assertEquals(4, found.result?.lines?.size)
        // Found at the first search, with no more asked of a volunteer-run service.
        assertEquals(1, asked.size)
    }

    /** Found on a real phone: an uploader whose name looks like an artist's, and no source that knows them. */
    @Test
    fun `an uploader who looks like an artist still gets the title searched on its own`() = runBlocking {
        val asked = mutableListOf<String>()
        val http = object : LyricsHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): LyricsHttpResponse {
                asked += url
                // As LRCLIB answers: nothing by "shxhmir", the song itself by its title.
                return if ("shxhmir" in url) LyricsHttpResponse(200, "[]") else LyricsHttpResponse(200, "[${candidate(7, "Get Lucky", "Daft Punk")}]")
            }
        }
        val found = CloseMatchFinder(http).find(upload("Get Lucky", "shxhmir", seconds = 110))
        assertEquals(CloseMatch("Get Lucky", "Daft Punk"), found?.result?.closeMatch)
        assertEquals(3, asked.size, "the title alone, after the two with the uploader's name")
    }

    @Test
    fun `the best of several candidates is taken`() = runBlocking {
        val answer = "[${candidate(1, "Lucky", "Somebody Else")},${candidate(2, "Get Lucky", "Daft Punk")},${candidate(3, "Get Lucky (Radio Edit)", "Daft Punk")}]"
        val found = CloseMatchFinder(lrclib(answer)).find(upload("Daft Punk - Get Lucky", "Some Uploads"))
        assertEquals(CloseMatch("Get Lucky", "Daft Punk"), found?.result?.closeMatch)
    }

    @Test
    fun `nothing near enough is no match at all, rather than somebody else's lyrics`() = runBlocking {
        val answer = "[${candidate(1, "Harder Better Faster Stronger", "Daft Punk")},${candidate(2, "Lucky Star", "Madonna")}]"
        assertNull(CloseMatchFinder(lrclib(answer)).find(upload("Daft Punk - Get Lucky", "Some Uploads")))
        assertNull(CloseMatchFinder(lrclib("[]")).find(upload("Daft Punk - Get Lucky", "Some Uploads")))
    }

    @Test
    fun `a search that fails leaves no match`() = runBlocking {
        val failing = object : LyricsHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>) = LyricsHttpResponse(503, "")
        }
        assertNull(CloseMatchFinder(failing).find(upload("Daft Punk - Get Lucky", "Some Uploads")))
    }

    @Test
    fun `the repository looks for a close match only when no source found the song`() = runBlocking<Unit> {
        // As LRCLIB answers: nothing for the song under the uploader's name, the song under its own.
        val http = object : LyricsHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): LyricsHttpResponse = when {
                "/api/search" !in url -> LyricsHttpResponse(404, "")
                "Chill" in url -> LyricsHttpResponse(200, "[]")
                else -> LyricsHttpResponse(200, "[${candidate(7, "Get Lucky", "Daft Punk")}]")
            }
        }
        val outcomes = LyricsRepository(http).findAll(upload("Daft Punk - Get Lucky (slowed + reverb)", "Chill Vibes Music"))
        val lrclib = outcomes.first { it.provider == LyricsProviderId.LRCLIB }
        assertEquals(LyricsProviderStatus.FOUND, lrclib.status)
        assertNotNull(lrclib.result?.closeMatch)
    }
}
