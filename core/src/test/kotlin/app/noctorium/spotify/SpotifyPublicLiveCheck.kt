package app.noctorium.spotify

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The public page reader against Spotify itself: one of Spotify's own editorial playlists, which is public and
 * always full, read the way the library reads a followed playlist.
 *
 * Off unless asked for, because it needs the network. It reads that one public page and nothing else -- no token is
 * asked for, nothing is signed in to, and no song's preview is touched:
 *
 * ```
 * NOCTORIUM_LIVE_CHECK=1 ./gradlew :core:test --tests 'app.noctorium.spotify.SpotifyPublicLiveCheck'
 * ```
 */
class SpotifyPublicLiveCheck {
    private val asked = System.getenv("NOCTORIUM_LIVE_CHECK")?.trim() == "1"

    @Test
    fun `a public editorial playlist comes back with its songs`() = runBlocking {
        if (!asked) {
            println("LIVE: skipped; set NOCTORIUM_LIVE_CHECK=1 to run it")
            return@runBlocking
        }
        val read = SpotifyPublicPages().playlist(TODAYS_TOP_HITS)
        assertTrue(read is SpotifyPublicRead.Ok, "the page did not read: ${read.message}")
        val list = read.list
        println("LIVE: '${list.title}' by ${list.owner}: ${list.tracks.size} songs, ${list.leftOut} left out, may be longer: ${list.mayBeLonger}")
        list.tracks.take(3).forEach { println("LIVE:   ${it.artistLine} - ${it.title} (${(it.durationMs ?: 0) / 1000} s)") }
        assertTrue(list.title.isNotBlank())
        assertTrue(list.tracks.size >= 10, "only ${list.tracks.size} songs")
        assertTrue(list.tracks.all { it.id.length == 22 && it.artists.single().name.isNotBlank() })
        assertTrue(list.tracks.count { (it.durationMs ?: 0) > 30_000 } >= list.tracks.size / 2, "most songs should say how long they are")
    }

    private companion object {
        /** Spotify's "Today's Top Hits", public and kept full by Spotify. */
        const val TODAYS_TOP_HITS = "37i9dQZF1DXcBWIGoYBM5M"
    }
}
