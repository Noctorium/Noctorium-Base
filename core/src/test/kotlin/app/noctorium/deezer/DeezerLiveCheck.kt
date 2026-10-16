package app.noctorium.deezer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client against Deezer itself: search, an album, a playlist, the charts and the editors' picks.
 *
 * Off unless asked for, because it needs the network. It reads only Deezer's public catalogue, asks about no
 * profile but one that does not exist, and signs in to nothing:
 *
 * ```
 * NOCTORIUM_LIVE_CHECK=1 ./gradlew :core:test --tests 'app.noctorium.deezer.DeezerLiveCheck'
 * ```
 */
class DeezerLiveCheck {
    private val asked = System.getenv("NOCTORIUM_LIVE_CHECK")?.trim() == "1"

    @Test
    fun `Deezer answers every question the client asks`() = runBlocking {
        if (!asked) {
            println("LIVE: skipped; set NOCTORIUM_LIVE_CHECK=1 to run it")
            return@runBlocking
        }
        val client = DeezerClient()

        val found = client.search("daft punk one more time")
        println("LIVE: search found ${found.tracks.size} songs, ${found.albums.size} albums, ${found.artists.size} artists, ${found.playlists.size} playlists")
        val song = found.tracks.first { it.title.equals("One More Time", ignoreCase = true) }
        assertTrue(song.artists.any { it.name == "Daft Punk" })
        assertTrue((song.durationMs ?: 0) > 300_000)

        val album = song.album!!
        val songs = client.albumTracks(album.id)
        println("LIVE: '${album.title}' has ${songs.size} songs")
        assertTrue(songs.any { it.id == song.id })

        val chart = client.chart()
        println("LIVE: the charts have ${chart.tracks.size} songs, ${chart.albums.size} albums, ${chart.playlists.size} playlists")
        assertTrue(chart.tracks.isNotEmpty())
        chart.playlists.firstOrNull()?.let { playlist ->
            val listed = client.playlistTracks(playlist.id, limit = 150)
            println("LIVE: playlist '${playlist.title}' gave ${listed.size} of ${playlist.trackCount} songs")
            assertTrue(listed.isNotEmpty())
        }

        val picks = client.editorsPicks()
        println("LIVE: the editors pick ${picks.size} albums")

        val top = client.artistTop(song.artists.first().id, limit = 5)
        println("LIVE: ${song.artists.first().name}'s top songs: ${top.map { it.title }}")
        assertEquals(5, top.size)

        assertNull(client.user(0), "a profile that does not exist is no profile")
    }
}
