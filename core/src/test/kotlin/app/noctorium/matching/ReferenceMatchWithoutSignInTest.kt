package app.noctorium.matching

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matching the Spotify songs that arrive without a sign-in: from a public page, whose songs carry the whole credit
 * line as one artist -- "Daft Punk, Pharrell Williams" -- and from Spotify's data download, whose songs carry no
 * length at all. Neither is allowed to make matching worse than it is for a song read through a sign-in.
 */
class ReferenceMatchWithoutSignInTest {
    private fun spotify(title: String, credit: String, durationMs: Long? = null) = Track(
        provider = ProviderType.SPOTIFY,
        id = "0MadeUpSong00000000001",
        title = title,
        artists = listOf(Artist(credit, credit, ProviderType.SPOTIFY)),
        durationMs = durationMs,
        sourceUrl = "https://open.spotify.com/track/0MadeUpSong00000000001",
    )

    private fun youTube(title: String, vararg artists: String, durationMs: Long? = 248_000, id: String = title) = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = id,
        title = title,
        artists = artists.map { Artist("yt-$it", it, ProviderType.YOUTUBE_MUSIC) },
        durationMs = durationMs,
        sourceUrl = "https://music.youtube.com/watch?v=$id",
    )

    @Test
    fun `a whole credit line matches the recording however YouTube Music credits it`() {
        val wanted = spotify("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", "Daft Punk, Pharrell Williams, Nile Rodgers", 248_000)
        listOf(
            youTube("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", "Daft Punk"),
            youTube("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", "Daft Punk", "Pharrell Williams", "Nile Rodgers"),
            youTube("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", "Pharrell Williams"),
            youTube("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", "Daft Punk, Pharrell Williams & Nile Rodgers"),
        ).forEach { credited ->
            assertEquals(credited.id, ReferenceMatch.choose(wanted, listOf(credited))?.id, credited.artistLine)
            assertTrue(ReferenceMatch.score(wanted, credited) >= .8, "${credited.artistLine}: ${ReferenceMatch.score(wanted, credited)}")
        }
        // Somebody else's song of the same name is still nobody's match.
        assertEquals(null, ReferenceMatch.choose(wanted, listOf(youTube("Get Lucky", "A Tribute Band"))))
        // And the search asks for the whole line, which a search engine reads as well as it reads one name.
        assertEquals("Daft Punk, Pharrell Williams, Nile Rodgers Get Lucky (feat. Pharrell Williams and Nile Rodgers)", ReferenceMatch.searchQuery(wanted))
    }

    @Test
    fun `a name with a comma in it is one artist and matches as one`() {
        val wanted = spotify("EARFQUAKE", "Tyler, The Creator", 190_000)
        val match = ReferenceMatch.choose(wanted, listOf(youTube("EARFQUAKE", "Tyler, The Creator", durationMs = 190_500)))
        assertEquals("EARFQUAKE", match?.id)
        assertEquals(1.0, ReferenceMatch.artistScore(wanted, match!!))
    }

    /**
     * The data download names a song and gives no length, so the length cannot tell a sped-up edit or a loop from
     * the song. The words still do, and the song itself still scores above every version that reads close to it.
     */
    @Test
    fun `with no length the song itself is still chosen over what a search puts beside it`() {
        val wanted = spotify("Get Lucky", "Daft Punk")
        val candidates = listOf(
            youTube("Get Lucky (Sped Up)", "Daft Punk", durationMs = 190_000, id = "sped"),
            youTube("Get Lucky [1 Hour Loop]", "Daft Punk", durationMs = 3_600_000, id = "loop"),
            youTube("Get Lucky (Radio Edit)", "Daft Punk", durationMs = 248_000, id = "edit"),
            youTube("Get Lucky", "Daft Punk", durationMs = 369_000, id = "album"),
            youTube("Get Lucky", "A Tribute Band", durationMs = 248_000, id = "cover"),
        )
        assertEquals("album", ReferenceMatch.choose(wanted, candidates)?.id)
        assertEquals("edit", ReferenceMatch.choose(wanted, candidates.filter { it.id != "album" })?.id)
        assertEquals(null, ReferenceMatch.choose(wanted, candidates.filter { it.id in setOf("sped", "loop", "cover") }))
        // Neutral, not refused: a song from the download plays at all.
        assertTrue(ReferenceMatch.score(wanted, youTube("Get Lucky", "Daft Punk", durationMs = null)) >= ReferenceMatch.ACCEPT_SCORE)
    }
}
