package app.noctorium.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading what people paste. The cases are the ones the share buttons of each app actually produce, plus
 * the ways a link arrives dressed up: in a sentence, without its scheme, with a full stop after it.
 */
class MusicLinksTest {

    private fun track(provider: ProviderType, id: String, url: String) = MusicLink(provider, LinkKind.TRACK, id, url)

    @Test
    fun `a YouTube Music song, from the share button, loses its tracking`() {
        assertEquals(
            track(ProviderType.YOUTUBE_MUSIC, "PzYrr7K1dvU", "https://music.youtube.com/watch?v=PzYrr7K1dvU"),
            findMusicLink("https://music.youtube.com/watch?v=PzYrr7K1dvU&si=Qm9vX2Fz-2kYp1Xa"),
        )
    }

    @Test
    fun `every shape of YouTube link is the same video`() {
        val expected = track(ProviderType.YOUTUBE_VIDEO, "dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
            "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abcdEFGH1234",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ?feature=shared",
            // A song inside a mix is the song. The mix is what YouTube would have played next.
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ&start_radio=1",
        ).forEach { pasted -> assertEquals(expected, findMusicLink(pasted), pasted) }
    }

    @Test
    fun `a link is found inside whatever was pasted around it`() {
        val expected = track(ProviderType.YOUTUBE_VIDEO, "dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(expected, findMusicLink("listen to this!! https://youtu.be/dQw4w9WgXcQ."))
        assertEquals(expected, findMusicLink("(https://youtu.be/dQw4w9WgXcQ)"))
        assertEquals(expected, findMusicLink("  youtube.com/watch?v=dQw4w9WgXcQ  "), "no scheme, as from an address bar")
        assertEquals(expected, findMusicLink("Never Gonna Give You Up\nhttps://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `playlists are playlists, on both YouTubes`() {
        assertEquals(
            MusicLink(
                ProviderType.YOUTUBE_MUSIC, LinkKind.PLAYLIST, "OLAK5uy_nMr9h2VlS-2PULNz3M3XVXQj_P3C2bqaY",
                "https://music.youtube.com/playlist?list=OLAK5uy_nMr9h2VlS-2PULNz3M3XVXQj_P3C2bqaY",
            ),
            findMusicLink("https://music.youtube.com/playlist?list=OLAK5uy_nMr9h2VlS-2PULNz3M3XVXQj_P3C2bqaY&si=x"),
        )
        assertEquals(
            MusicLink(
                ProviderType.YOUTUBE_VIDEO, LinkKind.PLAYLIST, "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
                "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
            ),
            findMusicLink("https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"),
        )
    }

    @Test
    fun `a SoundCloud track, and a private one, keep only what identifies them`() {
        assertEquals(
            track(ProviderType.SOUNDCLOUD, "burialuk/archangel", "https://soundcloud.com/burialuk/archangel"),
            findMusicLink("https://soundcloud.com/burialuk/archangel?utm_source=clipboard&utm_medium=text&si=d0e1"),
        )
        assertEquals(
            track(ProviderType.SOUNDCLOUD, "burialuk/archangel", "https://soundcloud.com/burialuk/archangel"),
            findMusicLink("https://m.soundcloud.com/burialuk/archangel"),
        )
        assertEquals(
            track(ProviderType.SOUNDCLOUD, "someone/demo/s-AbC123xyz", "https://soundcloud.com/someone/demo/s-AbC123xyz"),
            findMusicLink("https://soundcloud.com/someone/demo/s-AbC123xyz"),
            "a private track is nothing without its secret part",
        )
    }

    @Test
    fun `a SoundCloud set is a playlist, and a short link is followed later`() {
        assertEquals(
            MusicLink(ProviderType.SOUNDCLOUD, LinkKind.PLAYLIST, "burialuk/sets/untrue", "https://soundcloud.com/burialuk/sets/untrue"),
            findMusicLink("https://soundcloud.com/burialuk/sets/untrue?si=1"),
        )
        assertEquals(
            MusicLink(ProviderType.SOUNDCLOUD, LinkKind.SHORT, "Xy12AbCdE", "https://on.soundcloud.com/Xy12AbCdE"),
            findMusicLink("https://on.soundcloud.com/Xy12AbCdE"),
        )
    }

    @Test
    fun `what cannot be played is not a link to play`() {
        listOf(
            "",
            "just some words",
            "https://example.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/@SomeChannel",
            "https://www.youtube.com/results?search_query=burial",
            "https://www.youtube.com/watch?v=tooShort",
            "https://www.youtube.com/playlist?list=RDdQw4w9WgXcQ",
            "https://soundcloud.com/burialuk",
            "https://soundcloud.com/burialuk/likes",
            "https://soundcloud.com/discover/sets/charts-top",
            "https://youtube.evil.com/watch?v=dQw4w9WgXcQ",
            "https://evilyoutube.com/watch?v=dQw4w9WgXcQ",
        ).forEach { pasted -> assertNull(findMusicLink(pasted), "read \"$pasted\" as a link") }
    }

    @Test
    fun `the placeholder is the link's track, waiting to be filled in`() {
        val placeholder = findMusicLink("https://soundcloud.com/burialuk/near-dark")!!.placeholderTrack()
        assertEquals("SOUNDCLOUD:burialuk/near-dark", placeholder.queueKey)
        assertEquals("Near dark", placeholder.title)
        assertEquals(emptyList(), placeholder.artists, "no artist, so the backend knows to look one up")
        assertEquals("https://soundcloud.com/burialuk/near-dark", placeholder.sourceUrl)
    }
}

/** Telling a paste from typing, which decides whether the box plays what it holds. */
class ArrivedAtOnceTest {
    @Test
    fun `a paste into an empty box, or over what was there, arrived at once`() {
        kotlin.test.assertTrue(arrivedAtOnce("", "https://youtu.be/dQw4w9WgXcQ"))
        kotlin.test.assertTrue(arrivedAtOnce("https://youtu.be/aaaaaaaaaaa", "https://youtu.be/dQw4w9WgXcQ"), "pasted over a selection")
        kotlin.test.assertTrue(arrivedAtOnce("listen: ", "listen: https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `typing, a letter at a time, did not`() {
        kotlin.test.assertFalse(arrivedAtOnce("https://soundcloud.com/burialuk/", "https://soundcloud.com/burialuk/a"))
        kotlin.test.assertFalse(arrivedAtOnce("https://soundcloud.com/burialuk/ab", "https://soundcloud.com/burialuk/a"), "a backspace")
        kotlin.test.assertFalse(arrivedAtOnce("youtu.be/dQw4w9WgXc", "youtu.be/dQw4w9WgXcQ"))
    }
}
