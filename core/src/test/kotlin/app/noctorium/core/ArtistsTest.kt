package app.noctorium.core

import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.ARTIST_NAMED
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.domain.isArtistPage
import app.noctorium.domain.namesMatch
import app.noctorium.domain.soundCloudProfileOf
import app.noctorium.settings.NoctoriumPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pressing an artist's name opens their page on the service the song came from, as their songs, the way a playlist
 * opens; Search has Artists beside Music and Radio; and a link pasted into the search box plays at once when the
 * listener has said so.
 */
class ArtistsTest {
    @Test
    fun `names are the same artist when only their spelling differs`() {
        assertTrue(namesMatch("Daft Punk", "daft punk"))
        assertTrue(namesMatch("Daft Punk - Topic", "Daft Punk"))
        assertTrue(namesMatch("The Weeknd", "weeknd"))
        assertTrue(namesMatch("Beyoncé", "Beyonce"))
        assertFalse(namesMatch("Daft Punk", "Punk"))
        assertFalse(namesMatch("", "Daft Punk"))
    }

    @Test
    fun `a SoundCloud song's profile is the start of its address`() {
        assertEquals("https://soundcloud.com/odesza", soundCloudProfileOf("https://soundcloud.com/odesza/a-moment-apart"))
        assertEquals("https://soundcloud.com/flume", soundCloudProfileOf("https://soundcloud.com/flume/never-be-like-you?in=x"))
        assertNull(soundCloudProfileOf("https://soundcloud.com/odesza"))
        assertNull(soundCloudProfileOf("https://music.youtube.com/watch?v=abc"))
    }

    @Test
    fun `each service's artist opens as the page that service has`() {
        AppStateHarness.state().use { state ->
            val spotify = state.artistPageOf(Artist("4tZwfgrHOc3mvqYlEYSvVi", "Daft Punk", ProviderType.SPOTIFY))!!
            assertEquals("artist:4tZwfgrHOc3mvqYlEYSvVi", spotify.id, "Spotify's own artist page, by its id")

            val named = state.artistPageOf(Artist("Daft Punk", "Daft Punk", ProviderType.APPLE_MUSIC))!!
            assertEquals("${ARTIST_NAMED}Daft Punk", named.id, "found by name where the song carried no id")

            val song = AppStateHarness.song("odesza/a-moment-apart").copy(
                artists = listOf(Artist("SOUNDCLOUD:ODESZA", "ODESZA", ProviderType.SOUNDCLOUD)),
                sourceUrl = "https://soundcloud.com/odesza/a-moment-apart",
            )
            val soundCloud = state.artistPageOf(song.artists.first(), song)!!
            assertEquals("https://soundcloud.com/odesza/tracks", soundCloud.sourceUrl, "the uploader's own profile")
            assertTrue(soundCloud.isArtistPage())

            val youTube = state.artistPageOf(Artist("YOUTUBE_MUSIC:Daft Punk", "Daft Punk", ProviderType.YOUTUBE_MUSIC))!!
            assertEquals("Daft Punk", youTube.title)
            assertTrue(youTube.isArtistPage())

            assertNull(state.artistPageOf(Artist("radio-country:FR", "France", ProviderType.RADIO)), "a station has no artist")
        }
    }

    @Test
    fun `pressing the artist of a YouTube Music song opens their songs there, in the library`() {
        val theirs = Track(
            ProviderType.YOUTUBE_MUSIC, "omt", "One More Time",
            listOf(Artist("YOUTUBE_MUSIC:Daft Punk", "Daft Punk", ProviderType.YOUTUBE_MUSIC)),
            sourceUrl = "https://music.youtube.com/watch?v=omt",
        )
        val cover = theirs.copy(id = "cover", title = "One More Time (cover)", artists = listOf(Artist("YOUTUBE_MUSIC:Someone", "Someone", ProviderType.YOUTUBE_MUSIC)))
        val backend = AppStateHarness.Backend { provider, query ->
            if (provider == ProviderType.YOUTUBE_MUSIC && query == "Daft Punk") listOf(cover, theirs) else emptyList()
        }
        AppStateHarness.state(backend = backend, providers = null).use { state ->
            state.openArtistOf(theirs)
            assertEquals(Destination.LIBRARY, state.ui.value.destination)
            waitFor("their songs") { state.library.value.openPlaylist?.tracks?.isNotEmpty() == true }
            val page = state.library.value.openPlaylist!!
            assertEquals("Daft Punk", page.title)
            assertEquals(listOf("omt"), page.tracks.map { it.id }, "only their songs, not the cover")
        }
    }

    @Test
    fun `Artists in search lists the artists of what each service finds`() {
        AppStateHarness.state().use { state ->
            state.setSearchKind(SearchKind.ARTISTS)
            state.search("Some Artist")
            waitFor("artists") { !state.ui.value.searchLoading && state.ui.value.searchResults.playlists.isNotEmpty() }
            val pages = state.ui.value.searchResults.playlists
            assertTrue(pages.all { it.isArtistPage() && it.title == "Some Artist" }, "$pages")
            assertTrue(state.ui.value.searchResults.tracks.isEmpty())
        }
    }

    @Test
    fun `a pasted link plays at once only when the listener has said so`() {
        val link = "https://soundcloud.com/flume/never-be-like-you-feat-kai"
        AppStateHarness.state().use { state ->
            state.search(link)
            waitFor("the search") { !state.ui.value.searchLoading }
            assertEquals(LinkStatus.IDLE, state.linkState.value.status, "off by default: searched for, like anything typed")
        }
        AppStateHarness.state(preferences = NoctoriumPreferences(autoplay = false, playLinksFromSearch = true)).use { state ->
            state.search(link)
            assertTrue(state.linkState.value.status != LinkStatus.IDLE, "taken as a link")
            assertEquals(link, state.ui.value.searchQuery)
            assertFalse(state.ui.value.searchLoading)
            // Being typed out, a link is not played half way: only what arrives at once is.
            state.search("https://soundcloud.com/flume/never-be-like-you-feat-kai2")
            assertEquals("https://soundcloud.com/flume/never-be-like-you-feat-kai2", state.ui.value.searchQuery)
        }
    }
}
