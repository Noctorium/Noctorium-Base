package app.noctorium.social

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YouTube Music's radio panel, read into songs, and the same against YouTube itself when asked for. */
class YouTubeRadioTest {
    @Test
    fun `a radio panel becomes its songs, artists apart from the album and the year`() {
        val body = """
            {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{"contents":[
              {"playlistPanelVideoRenderer":{"videoId":"seed1","title":{"runs":[{"text":"The Seed"}]},
                "longBylineText":{"runs":[{"text":"Artist One"},{"text":" • "},{"text":"Album"},{"text":" • "},{"text":"2019"}]},
                "lengthText":{"runs":[{"text":"3:20"}]}}},
              {"playlistPanelVideoRenderer":{"videoId":"next1","title":{"runs":[{"text":"Next Song"}]},
                "longBylineText":{"runs":[{"text":"Artist Two"},{"text":" & "},{"text":"Artist Three"},{"text":" • "},{"text":"2021"}]},
                "thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/small=w60-h60"},{"url":"https://lh3.googleusercontent.com/big=w544-h544"}]},
                "lengthText":{"runs":[{"text":"4:05"}]}}}
            ]}}}}}}]}}}}}
        """.trimIndent()
        val songs = YouTubeMusicClient().parseRadio(body)
        assertEquals(listOf("seed1", "next1"), songs.map { it.id })
        val next = songs[1]
        assertEquals(listOf("Artist Two", "Artist Three"), next.artists.map { it.name })
        // A year alone is not an album.
        assertEquals(null, next.album)
        assertEquals(245_000, next.durationMs)
        assertEquals("https://lh3.googleusercontent.com/big=w544-h544", next.artworkUrl)
        assertEquals("Album", songs[0].album?.title)
    }

    /**
     * Against YouTube Music, signed out. Off unless asked for, because it needs the network:
     * `NOCTORIUM_LIVE_CHECK=1 ./gradlew :core:test --tests 'app.noctorium.social.YouTubeRadioTest'`
     */
    @Test
    fun `YouTube Music answers a radio for a song`() = runBlocking {
        if (System.getenv("NOCTORIUM_LIVE_CHECK")?.trim() != "1") return@runBlocking
        val keys = InnertubeKeyProvider().keys() ?: error("no innertube keys")
        val songs = YouTubeMusicClient().radio("4NRXx6U8ABQ", 10, YouTubeSession(keys, null))
        println("LIVE: radio gave ${songs.map { "${it.artistLine} - ${it.title}" }}")
        assertTrue(songs.size >= 5, "only ${songs.size} songs")
        assertTrue(songs.none { it.id == "4NRXx6U8ABQ" })
    }
}
