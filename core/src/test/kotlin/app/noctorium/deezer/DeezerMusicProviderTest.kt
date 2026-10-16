package app.noctorium.deezer

import app.noctorium.core.AppStateHarness
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.core.SearchMode
import app.noctorium.domain.LinkKind
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.findMusicLink
import app.noctorium.core.SearchBangs
import app.noctorium.net.HttpReply
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deezer as one of the services: Home, the library from a public profile, playlists and what plays after. */
class DeezerMusicProviderTest {
    private val api = DeezerFixtures.API
    private val profile = 5550001L

    private fun provider(profileId: Long? = profile, answer: (String) -> HttpReply) =
        DeezerMusicProvider(DeezerClient(ScriptedDeezer(answer = answer), pause = {}), profileId = { profileId })

    @Test
    fun `the library is the loved tracks first, then the playlists, then the albums`() = runBlocking {
        val provider = provider { url ->
            when (url) {
                "$api/user/$profile/playlists?limit=100" -> DeezerFixtures.ok(
                    DeezerFixtures.page(DeezerFixtures.playlist(11, "Running"), DeezerFixtures.playlist(12, "Loved Tracks", loved = true)),
                )
                "$api/user/$profile/albums?limit=100" -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.album(302127, "Discovery")))
                else -> error("asked $url")
            }
        }

        assertEquals(listOf("12", "11", "album:302127"), provider.getLibraryPlaylists().map { it.id })
    }

    /** Where the profile does not list its loved tracks among its playlists, they are asked for on their own. */
    @Test
    fun `loved tracks not listed with the playlists are offered as one`() = runBlocking {
        val provider = provider { url ->
            when (url) {
                "$api/user/$profile/playlists?limit=100" -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.playlist(11, "Running")))
                "$api/user/$profile/albums?limit=100" -> DeezerFixtures.ok(DeezerFixtures.page())
                "$api/user/$profile/tracks?limit=100" -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.track(1, "One")))
                else -> error("asked $url")
            }
        }

        val library = provider.getLibraryPlaylists()

        assertEquals(listOf("loved:$profile", "11"), library.map { it.id })
        assertEquals(listOf("1"), provider.getPlaylistTracks(library.first()).map { it.id })
    }

    @Test
    fun `without a profile there is no library to read`() = runBlocking {
        assertTrue(provider(profileId = null) { error("asked $it") }.getLibraryPlaylists().isEmpty())
    }

    @Test
    fun `albums, artists and playlists open from their own addresses`() = runBlocking {
        val asked = mutableListOf<String>()
        val provider = provider { url ->
            asked += url
            when {
                url == "$api/album/302127" -> DeezerFixtures.ok("""{"id":302127,"title":"Discovery","nb_tracks":0,"artist":{"id":27,"name":"Daft Punk"},"tracks":{"data":[]}}""")
                url.startsWith("$api/artist/27/top") -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.track(1, "One")))
                url.startsWith("$api/playlist/7/tracks") -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.track(2, "Two")))
                else -> error("asked $url")
            }
        }

        provider.getPlaylistTracks(Playlist("album:302127", "Discovery", ProviderType.DEEZER))
        assertEquals(listOf("1"), provider.getPlaylistTracks(Playlist("artist:27", "Daft Punk", ProviderType.DEEZER)).map { it.id })
        assertEquals(listOf("2"), provider.getPlaylistTracks(Playlist("7", "A playlist", ProviderType.DEEZER)).map { it.id })
        assertEquals(listOf("$api/album/302127", "$api/artist/27/top?limit=50", "$api/playlist/7/tracks?limit=100"), asked)
    }

    @Test
    fun `Home has the loved tracks, the charts and the editors' picks, and leaves out a row that fails`() = runBlocking {
        val provider = provider { url ->
            when {
                url.startsWith("$api/chart/0") -> DeezerFixtures.ok(
                    """{"tracks":${DeezerFixtures.page(DeezerFixtures.track(1, "One"))},"albums":${DeezerFixtures.page(DeezerFixtures.album(2, "Two"))},"playlists":{"data":[]}}""",
                )
                url.startsWith("$api/editorial/0/selection") -> HttpReply(500, "")
                url.startsWith("$api/user/$profile/tracks") -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.track(3, "Three")))
                else -> error("asked $url")
            }
        }

        val home = provider.getHome()

        assertEquals(listOf("deezer:loved", "deezer:chart", "deezer:chart-albums"), home.map { it.id })
        assertTrue(home.all { it.provider == ProviderType.DEEZER })
        assertEquals(listOf("album:2"), home.last().playlists.map { it.id })
    }

    /** Somebody who never gave a profile never chose Deezer, and their Home asks it nothing. */
    @Test
    fun `without a profile Home has no Deezer rows and Deezer is not asked`() = runBlocking {
        assertTrue(provider(profileId = null) { error("asked $it") }.getHome().isEmpty())
    }

    @Test
    fun `after a Deezer song comes Deezer's radio of its artist`() = runBlocking {
        val provider = provider { url ->
            when {
                url == "$api/track/1" -> DeezerFixtures.ok(DeezerFixtures.track(1, "One"))
                url.startsWith("$api/artist/27/radio") -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.track(1, "One"), DeezerFixtures.track(4, "Four")))
                else -> error("asked $url")
            }
        }

        val after = provider.getRecommendations(PlaybackContext(ProviderType.DEEZER, PlaybackOrigin.QUEUE, seedTrackId = "1"))

        assertEquals(listOf("4"), after.map { it.id })
        assertTrue(provider.getRecommendations(PlaybackContext(ProviderType.SPOTIFY, PlaybackOrigin.QUEUE, seedTrackId = "1")).isEmpty())
    }

    @Test
    fun `a search gives the artists as artists and everything to open as playlists`() = runBlocking {
        val provider = provider { url ->
            when {
                url.startsWith("$api/search?") -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.track(1, "One")))
                url.startsWith("$api/search/album?") -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.album(2, "Two")))
                url.startsWith("$api/search/artist?") -> DeezerFixtures.ok(DeezerFixtures.page(DeezerFixtures.artist))
                url.startsWith("$api/search/playlist?") -> DeezerFixtures.ok(DeezerFixtures.page())
                else -> error("asked $url")
            }
        }

        val found = provider.search("daft punk")

        assertEquals(listOf("27"), found.artists.map { it.id })
        assertEquals(listOf("album:2", "artist:27"), found.playlists.map { it.id })
        assertTrue(found.playlists.first().isDeezerAlbum() && found.playlists.last().isDeezerArtist())
    }
}

/** Deezer in Settings, in a search box and in a pasted link. */
class DeezerSettingsTest {
    private val api = DeezerFixtures.API

    private fun deezer(answer: (String) -> HttpReply) = DeezerClient(ScriptedDeezer(answer = answer), pause = {})

    @Test
    fun `a profile's link is checked with Deezer and kept with its name`() {
        val client = deezer { url ->
            if (url == "$api/user/5550001") DeezerFixtures.ok("""{"id":5550001,"name":"Juniper Ashdown","type":"user"}""") else DeezerFixtures.noData
        }
        AppStateHarness.state(deezer = client).use { state ->
            state.setDeezerProfile("https://www.deezer.com/en/profile/5550001")
            waitFor("the profile") { state.settings.value.deezer.found }

            val deezer = state.settings.value.deezer
            assertEquals("5550001", deezer.profileId)
            assertEquals("Juniper Ashdown", deezer.name)
            assertEquals(false, deezer.checking)
            assertEquals("5550001", state.settings.value.preferences.deezerProfileId)
            assertEquals("Juniper Ashdown", state.settings.value.preferences.deezerProfileName)
        }
    }

    @Test
    fun `a profile Deezer has never heard of is said so, and not kept`() {
        AppStateHarness.state(deezer = deezer { DeezerFixtures.noData }).use { state ->
            state.setDeezerProfile("123")
            waitFor("the answer") { state.settings.value.deezer.message != null && !state.settings.value.deezer.checking }

            assertEquals(false, state.settings.value.deezer.found)
            assertTrue(state.settings.value.deezer.message!!.contains("no profile numbered 123"), state.settings.value.deezer.message)
            assertEquals("", state.settings.value.preferences.deezerProfileId)
        }
    }

    @Test
    fun `something that is not a profile is said so without asking Deezer`() {
        AppStateHarness.state(deezer = deezer { error("asked $it") }).use { state ->
            state.setDeezerProfile("my favourite songs")
            waitFor("the answer") { state.settings.value.deezer.message != null }
            assertTrue(state.settings.value.deezer.message!!.startsWith("That is not a Deezer profile"))
        }
    }

    @Test
    fun `a blank profile takes Deezer out of the library`() {
        AppStateHarness.state(preferences = app.noctorium.settings.NoctoriumPreferences(autoplay = false, deezerProfileId = "5550001", deezerProfileName = "Juniper Ashdown")).use { state ->
            assertEquals(true, state.settings.value.deezer.found, "a profile kept from before is found at launch")
            assertEquals("Juniper Ashdown", state.settings.value.deezer.name)

            state.setDeezerProfile("  ")

            assertEquals(false, state.settings.value.deezer.found)
            assertEquals("", state.settings.value.preferences.deezerProfileId)
        }
    }

    @Test
    fun `the bangs and the links know Deezer`() {
        assertEquals(SearchMode.DEEZER, SearchBangs.parse("!dz one more time").mode)
        assertEquals(SearchMode.DEEZER, SearchBangs.parse("one more time !deezer").mode)
        assertEquals("!dz", SearchBangs.bangFor(SearchMode.DEEZER))

        val song = findMusicLink("listen https://www.deezer.com/en/track/3135553?utm_source=share")!!
        assertEquals(ProviderType.DEEZER, song.provider)
        assertEquals(LinkKind.TRACK, song.kind)
        assertEquals("https://www.deezer.com/track/3135553", song.url)
        assertEquals("album:302127", findMusicLink("deezer.com/album/302127")!!.id)
        assertEquals("artist:27", findMusicLink("https://www.deezer.com/fr/artist/27")!!.id)
        assertEquals("1595684095", findMusicLink("https://www.deezer.com/playlist/1595684095")!!.id)
        assertEquals(LinkKind.SHORT, findMusicLink("https://link.deezer.com/s/31AbCdEf")!!.kind)
        assertNull(findMusicLink("https://www.deezer.com/en/profile/5550001"), "a profile is not something to play")
    }
}
