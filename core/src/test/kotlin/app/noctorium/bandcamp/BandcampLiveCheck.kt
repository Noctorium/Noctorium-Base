package app.noctorium.bandcamp

import app.noctorium.net.Http
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The client against Bandcamp itself: search, a release, its stream, an artist, discover and a page.
 *
 * Off unless asked for, because it needs the network. It reads only public pages and signs in to nothing:
 *
 * ```
 * NOCTORIUM_LIVE_CHECK=1 ./gradlew :core:test --tests 'app.noctorium.bandcamp.BandcampLiveCheck'
 * ```
 */
class BandcampLiveCheck {
    private val asked = System.getenv("NOCTORIUM_LIVE_CHECK")?.trim() == "1"

    @Test
    fun `Bandcamp answers every question the client asks`() = runBlocking {
        if (!asked) {
            println("LIVE: skipped; set NOCTORIUM_LIVE_CHECK=1 to run it")
            return@runBlocking
        }
        val client = BandcampClient()

        val found = client.search("tycho awake")
        val song = found.tracks.first { it.title.equals("Awake", ignoreCase = true) }
        println("LIVE: search found ${found.tracks.size} songs, ${found.releases.size} releases, ${found.bands.size} artists")
        val album = found.trackReleases.getValue(song.id)

        val release = client.release(album.kind, album.id, album.bandId)
        println("LIVE: '${release.title}' by ${release.artist}: ${release.tracks.size} tracks")
        assertTrue(release.tracks.isNotEmpty())
        val stream = assertNotNull(client.stream(song.id, album.bandId), "no stream for ${song.title}")

        val audio = Http().send(stream, headers = mapOf("Range" to "bytes=0-4095"))
        println("LIVE: stream answered ${audio.status} ${audio.header("Content-Type")}")
        assertTrue(audio.status == 200 || audio.status == 206)
        assertEquals("audio/mpeg", audio.header("Content-Type"))

        val band = client.band(album.bandId)
        println("LIVE: ${band.name} has ${band.releases.size} releases")
        assertTrue(band.releases.isNotEmpty())

        val discover = client.discover(DiscoverSlice.BEST_SELLING, "electronic", 6)
        println("LIVE: discover gave ${discover.map { "${it.bandName} - ${it.title}" }}")
        assertTrue(discover.isNotEmpty())

        val page = assertNotNull(client.page(release.url!!))
        assertEquals(release.id, page.itemId)
        assertEquals(release.bandId, page.bandId)
    }
}
