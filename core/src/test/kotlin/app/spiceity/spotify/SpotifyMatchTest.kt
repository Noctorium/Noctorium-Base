package app.spiceity.spotify

import app.spiceity.domain.Album
import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deciding which recording a Spotify track refers to.
 *
 * This is the one place in the Spotify support where being wrong is worse than doing nothing. Spotify hands
 * over a name and a length and no audio, so every track has to be found somewhere else — and a search for a
 * well-known song returns its remixes, its live takes, its sped-up edits and hour-long loops of it, any of
 * which a search engine will happily rank first. Playing one of those instead of the song somebody chose is
 * the failure to guard against: it is silent, it looks like it worked, and it happens on the tracks people
 * care most about.
 *
 * So the rule these tests hold to is that a wrong match is worse than no match, and every distractor below
 * is a real thing that turns up in a YouTube Music search.
 */
class SpotifyMatchTest {
    private fun spotify(
        title: String,
        artist: String,
        durationMs: Long? = 213_000,
        id: String = "4cOdK2wGLETKBW3PvgPWqT",
    ) = Track(
        provider = ProviderType.SPOTIFY,
        id = id,
        title = title,
        artists = listOf(Artist("spotify-artist", artist, ProviderType.SPOTIFY)),
        album = Album("spotify-album", "Nevermind", emptyList(), ProviderType.SPOTIFY, "https://cover/big.jpg"),
        durationMs = durationMs,
        artworkUrl = "https://cover/big.jpg",
        sourceUrl = "https://open.spotify.com/track/$id",
    )

    private fun youTube(
        title: String,
        artist: String,
        durationMs: Long? = 213_000,
        id: String = "yt-1",
    ) = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = id,
        title = title,
        artists = listOf(Artist("yt-artist", artist, ProviderType.YOUTUBE_MUSIC)),
        durationMs = durationMs,
        artworkUrl = "https://i.ytimg.com/thumb.jpg",
        sourceUrl = "https://music.youtube.com/watch?v=$id",
    )

    // --- What to search for ---

    @Test
    fun `the query is the artist and the title`() {
        assertEquals(
            "Nirvana Come As You Are",
            SpotifyMatch.searchQuery(spotify("Come As You Are", "Nirvana")),
        )
    }

    /**
     * Spotify writes the release, not the song: "- Remastered 2011" describes a master tape. Searching for
     * it finds either nothing or a reissue upload, when the song itself is sitting right there.
     */
    @Test
    fun `edition wording is left out of the query`() {
        assertEquals(
            "Nirvana Come As You Are",
            SpotifyMatch.searchQuery(spotify("Come As You Are - Remastered 2011", "Nirvana")),
        )
        assertEquals(
            "Radiohead Creep",
            SpotifyMatch.searchQuery(spotify("Creep (Remastered)", "Radiohead")),
        )
        assertEquals(
            "The Beatles Help!",
            SpotifyMatch.searchQuery(spotify("Help! - Mono / Remastered", "The Beatles")),
        )
    }

    /** Part of the song and not of the release, so taking it off would match the wrong version. */
    @Test
    fun `a featured artist and a version stay in the query`() {
        assertEquals(
            "Kanye West Devil In A New Dress (feat. Rick Ross)",
            SpotifyMatch.searchQuery(spotify("Devil In A New Dress (feat. Rick Ross)", "Kanye West")),
        )
        assertTrue(SpotifyMatch.searchQuery(spotify("Layla - Acoustic", "Eric Clapton")).contains("Acoustic"))
    }

    // --- Choosing between what came back ---

    @Test
    fun `the song itself is chosen over everything a search puts beside it`() {
        val wanted = spotify("Come As You Are", "Nirvana")
        val candidates = listOf(
            youTube("Come As You Are (Sped Up)", "Nirvana", 168_000, "sped"),
            youTube("Come As You Are - Nirvana [1 HOUR LOOP]", "Music Hub", 3_600_000, "loop"),
            youTube("Come As You Are (Live at Reading 1992)", "Nirvana", 241_000, "live"),
            youTube("Come As You Are", "Nirvana", 212_000, "real"),
            youTube("Come As You Are (Techno Remix)", "DJ Someone", 305_000, "remix"),
        )

        assertEquals("real", SpotifyMatch.choose(wanted, candidates)?.id)
    }

    /**
     * The upload title carrying the artist is the ordinary case on YouTube: a label's channel is named after
     * the label, and the artist appears only in the title.
     */
    @Test
    fun `an artist named only in the upload title still counts`() {
        val wanted = spotify("Bohemian Rhapsody", "Queen", 354_000)
        val candidate = youTube(
            "Queen - Bohemian Rhapsody (Official Video Remastered)",
            "Queen Official",
            355_000,
            "official",
        )

        assertNotNull(SpotifyMatch.choose(wanted, listOf(candidate)))
    }

    @Test
    fun `nothing is chosen when only a remix came back`() {
        val wanted = spotify("Levels", "Avicii", 203_000)
        val candidates = listOf(
            youTube("Levels (Skrillex Remix)", "Avicii", 210_000, "remix"),
            youTube("Levels (Hardstyle Bootleg)", "Avicii", 199_000, "bootleg"),
        )

        assertNull(SpotifyMatch.choose(wanted, candidates), "a remix was accepted as the original")
    }

    /** A cover is a different performance by different people, however exactly the title agrees. */
    @Test
    fun `nothing is chosen when only a cover came back`() {
        val wanted = spotify("Creep", "Radiohead", 238_000)
        val candidates = listOf(
            youTube("Creep", "Some Cover Channel", 237_000, "cover"),
            youTube("Creep (Radiohead Cover)", "Another Singer", 240_000, "cover2"),
        )

        assertNull(SpotifyMatch.choose(wanted, candidates), "a cover was accepted as the original")
    }

    /**
     * The one signal that is not a matter of wording. An extended mix or a compilation under one title can
     * carry a title and artist that read perfectly, and be twenty minutes long.
     */
    @Test
    fun `a length far from Spotify's is refused however well the names read`() {
        val wanted = spotify("Strobe", "deadmau5", 634_000)
        val hourLong = youTube("Strobe", "deadmau5", 3_600_000, "hour")

        assertNull(SpotifyMatch.choose(wanted, listOf(hourLong)))
    }

    /**
     * A missing length must not reject the candidate. YouTube Music listings frequently carry none, and
     * treating that as disagreement would leave most of a Spotify library unplayable.
     */
    @Test
    fun `an unknown length is not held against a candidate`() {
        val wanted = spotify("Everlong", "Foo Fighters", 250_000)
        val noDuration = youTube("Everlong", "Foo Fighters", null, "unknown")

        assertEquals("unknown", SpotifyMatch.choose(wanted, listOf(noDuration))?.id)
    }

    @Test
    fun `accents and punctuation do not stop a match`() {
        val wanted = spotify("Sweet Child O' Mine", "Guns N' Roses", 356_000)
        val candidate = youTube("Guns N Roses - Sweet Child O Mine", "GunsNRosesVEVO", 355_000, "gnr")

        assertEquals("gnr", SpotifyMatch.choose(wanted, listOf(candidate))?.id)

        val beyonce = spotify("Halo", "Beyoncé", 261_000)
        assertNotNull(SpotifyMatch.choose(beyonce, listOf(youTube("Halo", "Beyonce", 262_000, "halo"))))
    }

    @Test
    fun `an empty search result is no match rather than a failure`() {
        assertNull(SpotifyMatch.choose(spotify("Anything", "Anyone"), emptyList()))
    }

    /** Matching a Spotify track to another Spotify track would resolve nothing at all. */
    @Test
    fun `a Spotify candidate is never the match`() {
        val wanted = spotify("Come As You Are", "Nirvana")
        assertNull(SpotifyMatch.choose(wanted, listOf(wanted)))
    }

    /**
     * A remix asked for is a remix chosen. The refusal above is about substitution, not about remixes --
     * somebody whose playlist holds the Skrillex remix should hear the Skrillex remix.
     */
    @Test
    fun `a remix in the playlist matches the remix`() {
        val wanted = spotify("Levels - Skrillex Remix", "Avicii", 210_000)
        val candidate = youTube("Levels (Skrillex Remix)", "Avicii", 211_000, "remix")

        assertEquals("remix", SpotifyMatch.choose(wanted, listOf(candidate))?.id)
    }

    // --- What actually gets played ---

    @Test
    fun `the played track is the recording, presented as Spotify writes it`() {
        val wanted = spotify("Sweet Child O' Mine", "Guns N' Roses", 356_000)
        val match = youTube(
            "Guns N' Roses - Sweet Child O' Mine (Official Music Video)",
            "Guns N' Roses",
            355_000,
            "gnr",
        )

        val played = SpotifyMatch.resolved(wanted, match)

        // Where the audio comes from, so downloading, liking and scrobbling all act on the real recording.
        assertEquals(ProviderType.YOUTUBE_MUSIC, played.provider)
        assertEquals("gnr", played.id)
        assertEquals(match.sourceUrl, played.sourceUrl)

        // How the song is written, so the player bar agrees with the playlist row it was started from.
        assertEquals("Sweet Child O' Mine", played.title)
        assertEquals("https://cover/big.jpg", played.artworkUrl)

        // The length of what is playing, because the seek bar is drawn from it.
        assertEquals(355_000, played.durationMs)
    }

    @Test
    fun `Spotify's artists stand in when the match named none`() {
        val wanted = spotify("Halo", "Beyoncé")
        val match = youTube("Halo", "Beyonce", id = "halo").copy(artists = emptyList())

        val played = SpotifyMatch.resolved(wanted, match)

        assertEquals("Beyoncé", played.artistLine)
        // Remapped to the provider whose pages can actually be opened.
        assertTrue(played.artists.all { it.provider == ProviderType.YOUTUBE_MUSIC })
    }

    @Test
    fun `a resolved track is queued and downloaded under the recording's own key`() {
        val wanted = spotify("Everlong", "Foo Fighters")
        val played = SpotifyMatch.resolved(wanted, youTube("Everlong", "Foo Fighters", id = "ever"))

        assertEquals("YOUTUBE_MUSIC:ever", played.queueKey)
    }

    // --- The pieces underneath ---

    @Test
    fun `normalizing leaves only what can be compared`() {
        assertEquals("guns n roses", SpotifyMatch.normalize("Guns N' Roses"))
        assertEquals("beyonce", SpotifyMatch.normalize("Beyoncé"))
        assertEquals("sigur ros", SpotifyMatch.normalize("Sigur Rós"))
        assertEquals("ac dc", SpotifyMatch.normalize("AC/DC"))
    }

    @Test
    fun `a title with nothing but edition wording keeps its original text`() {
        // Stripping everything would leave a blank query, which matches anything at all.
        assertTrue(SpotifyMatch.strippedTitle("- Remastered").isNotBlank())
        assertTrue(SpotifyMatch.strippedTitle("(Remastered)").isNotBlank())
    }

    @Test
    fun `lengths within a few seconds score the same as identical ones`() {
        assertEquals(1.0, SpotifyMatch.durationScore(213_000, 214_500))
        assertEquals(0.0, SpotifyMatch.durationScore(213_000, 3_600_000))
        // Neutral, not disagreement.
        assertEquals(0.5, SpotifyMatch.durationScore(213_000, null))
        assertEquals(0.5, SpotifyMatch.durationScore(null, 213_000))
    }
}
