package app.noctorium.core

import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.Artist
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import app.noctorium.playlists.LocalPlaylistRepository
import app.noctorium.settings.InMemorySecretStore
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.SettingsRepository
import app.noctorium.spotify.SPOTIFY_WITHOUT_SIGN_IN
import app.noctorium.spotify.ScriptedPages
import app.noctorium.spotify.SpotifyAuth
import app.noctorium.spotify.SpotifyClient
import app.noctorium.spotify.SpotifyHttp
import app.noctorium.spotify.SpotifyPageFixtures
import app.noctorium.spotify.SpotifyPageFixtures.albumPage
import app.noctorium.spotify.SpotifyPageFixtures.listId
import app.noctorium.spotify.SpotifyPageFixtures.ok
import app.noctorium.spotify.SpotifyPageFixtures.playlistPage
import app.noctorium.spotify.SpotifyPageFixtures.songId
import app.noctorium.spotify.SpotifyPublicCopies
import app.noctorium.spotify.SpotifyPublicPages
import app.noctorium.spotify.SpotifyPublicRead
import app.noctorium.spotify.SpotifyResponse
import app.noctorium.spotify.SpotifyTokens
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spotify for a listener whose sign-in cannot work: public playlists followed by their links and kept in step with
 * Spotify, and pasted links that open with nobody signed in -- and the sign-in, where it works, still reading what it
 * can read whole. Against made-up pages and a made-up Spotify; nothing here reaches the real one.
 */
class SpotifyWithoutSignInTest {
    private val folder = Files.createTempDirectory("noctorium-spotify-without-sign-in")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private val nightBus = listId(1)
    private val longOne = listId(2)
    private val hidden = listId(3)
    private val album = listId(4)
    private val own = listId(7)

    private fun embed(kind: String, id: String) = "${SpotifyPublicPages.EMBED}/$kind/$id"

    /** The made-up public pages: two public playlists, an album, and a private playlist that answers "not found". */
    private fun pages() = ScriptedPages(
        mapOf(
            embed("playlist", nightBus) to ok(playlistPage(nightBus, "Night Bus Home", owner = "Night Tram", songs = 3)),
            embed("playlist", longOne) to ok(playlistPage(longOne, "Long One", owner = "Signal Box", songs = 100)),
            embed("album", album) to ok(albumPage(album, "Harbour Lights", "Paper Satellites", songs = 2)),
            embed("playlist", hidden) to ok(SpotifyPageFixtures.notFoundPage),
        ),
    )

    /** A YouTube Music with the same recording of every made-up song, which is what a Spotify song is played as. */
    private val backend = AppStateHarness.Backend { provider, query ->
        if (provider != ProviderType.YOUTUBE_MUSIC) return@Backend emptyList()
        val title = query.substringAfter("The Lamplighters ", "").ifBlank { return@Backend emptyList() }
        listOf(
            Track(
                ProviderType.YOUTUBE_MUSIC,
                "yt-" + title.replace(' ', '-'),
                title,
                listOf(Artist("yt-lamplighters", "The Lamplighters", ProviderType.YOUTUBE_MUSIC)),
                durationMs = 200_000,
                sourceUrl = "https://music.youtube.com/watch?v=made-up",
            ),
        )
    }

    /** A Spotify that answers as [answer] says, and a sign-in for it when [signedIn]. */
    private class MadeUpSpotify(private val answer: (String) -> SpotifyResponse) : SpotifyHttp {
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        override suspend fun get(url: String, accessToken: String): SpotifyResponse {
            asked += url.removePrefix(SpotifyClient.API)
            return answer(url.removePrefix(SpotifyClient.API))
        }
    }

    private fun state(
        pages: ScriptedPages = pages(),
        preferences: NoctoriumPreferences = NoctoriumPreferences(autoplay = false),
        api: MadeUpSpotify? = null,
        copies: SpotifyPublicCopies = SpotifyPublicCopies(null),
        engine: AppStateHarness.Engine = AppStateHarness.Engine(),
        playlists: LocalPlaylistRepository = LocalPlaylistRepository(folder.resolve("playlists.json")),
    ): AppState = AppStateHarness.state(
        engine = engine,
        preferences = preferences,
        providers = null,
        backend = backend,
        spotifyPages = SpotifyPublicPages(pages, gapMs = 0),
        spotifyCopies = copies,
        spotify = SpotifyClient(api ?: MadeUpSpotify { SpotifyResponse(Http.UNREACHABLE, "no Spotify here") }),
        credentials = InMemorySecretStore().also { if (api != null) it.put("spotify.refresh_token", "made-up-refresh") },
        spotifyRefresh = { _, _ ->
            SpotifyAuth.Result.Success(SpotifyTokens("made-up-access", "made-up-refresh", Instant.now().epochSecond + 3_600))
        },
        playlists = playlists,
    )

    private fun AppState.listed(id: String): Playlist? = library.value.playlists.firstOrNull { it.provider == ProviderType.SPOTIFY && it.id == id }

    private fun AppState.publicMessage(): String? = settings.value.spotify.publicMessage

    private fun AppState.added(link: String): String {
        addSpotifyPublicPlaylist(link)
        waitFor("the playlist to be read") { !settings.value.spotify.addingPublicPlaylist && publicMessage() != null }
        return publicMessage()!!
    }

    private fun AppState.reloaded() {
        refreshLibrary(force = true)
        waitFor("the library") { library.value.loaded && !library.value.loading }
    }

    // --- Following public playlists ---

    @Test
    fun `a public playlist is added from its link, read before it is kept, and is in the library`() {
        state().use { state ->
            assertEquals("Added “Night Bus Home”: 3 songs.", state.added("https://open.spotify.com/playlist/$nightBus?si=0a1b2c"))
            assertEquals(listOf(nightBus), state.settings.value.preferences.spotifyPublicPlaylists)

            val listed = state.settings.value.spotify.publicPlaylists.single()
            assertEquals("Night Bus Home", listed.title)
            assertEquals("Night Tram", listed.owner)
            assertEquals(3, listed.songCount)
            assertFalse(listed.mayBeLonger)
            assertNull(listed.lastError)
            assertEquals("https://open.spotify.com/playlist/$nightBus", listed.url)

            waitFor("the library to list it") { state.listed(nightBus) != null }
            val entry = state.listed(nightBus)!!
            assertEquals("Night Tram · Public playlist", entry.ownerName)
            assertEquals(true, entry.isPublic)
            assertEquals(3, entry.trackCount)
        }
    }

    @Test
    fun `a playlist the page shows a hundred songs of says it may be longer`() {
        state().use { state ->
            val said = state.added("spotify:playlist:$longOne")
            assertTrue(said.startsWith("Added “Long One”: 100 songs."), said)
            assertTrue("first 100 songs" in said, said)
            assertTrue(state.settings.value.spotify.publicPlaylists.single().mayBeLonger)
            waitFor("the library to list it") { state.listed(longOne) != null }
            assertEquals("Signal Box · Public playlist · first 100 songs", state.listed(longOne)!!.ownerName)
        }
    }

    @Test
    fun `a private playlist is refused in words and not kept`() {
        state().use { state ->
            assertEquals(SpotifyPublicRead.MISSING_PLAYLIST, state.added("https://open.spotify.com/embed/playlist/$hidden"))
            assertTrue(state.settings.value.preferences.spotifyPublicPlaylists.isEmpty())
            assertTrue(state.settings.value.spotify.publicPlaylists.isEmpty())
        }
    }

    @Test
    fun `the same playlist twice, an album, or something that is not a link are each said so`() {
        val pages = pages()
        state(pages).use { state ->
            state.added(nightBus)
            assertEquals("“Night Bus Home” is already in your library.", state.added("/intl-de/playlist/$nightBus"))
            assertTrue(state.added("https://open.spotify.com/album/$album").startsWith("That Spotify link is not a playlist."))
            assertTrue(state.added("my favourite playlist").startsWith("That is not a link to a Spotify playlist."))
            assertEquals(listOf(nightBus), state.settings.value.preferences.spotifyPublicPlaylists)
            assertEquals(1, pages.asked.count { it == embed("playlist", nightBus) }, "${pages.asked}")
        }
    }

    @Test
    fun `a removed playlist leaves the library and Settings`() {
        state().use { state ->
            state.added(nightBus)
            waitFor("the library to list it") { state.listed(nightBus) != null }
            state.removeSpotifyPublicPlaylist("https://open.spotify.com/playlist/$nightBus")
            waitFor("it to go") { state.publicMessage()?.startsWith("Removed") == true }
            assertEquals("Removed “Night Bus Home” from your library.", state.publicMessage())
            assertTrue(state.settings.value.preferences.spotifyPublicPlaylists.isEmpty())
            assertTrue(state.settings.value.spotify.publicPlaylists.isEmpty())
            assertNull(state.listed(nightBus))
            state.reloaded()
            assertNull(state.listed(nightBus))
        }
    }

    // --- The library: no sign-in, a working one, and one Spotify refuses ---

    @Test
    fun `with nobody signed in the followed playlists are listed and open from their pages`() {
        val pages = pages()
        state(pages, NoctoriumPreferences(autoplay = false, spotifyPublicPlaylists = listOf(nightBus, longOne))).use { state ->
            state.reloaded()
            assertEquals(listOf(nightBus, longOne), state.library.value.playlists.map { it.id })
            assertNull(state.library.value.errorMessage)

            state.openPlaylist(state.listed(nightBus)!!)
            waitFor("the songs") { state.library.value.openPlaylist?.tracks?.isNotEmpty() == true }
            assertEquals(listOf(songId(1), songId(2), songId(3)), state.library.value.openPlaylist!!.tracks.map { it.id })
            assertNull(state.library.value.openPlaylistError)
            // Listed and opened on one read of the page.
            assertEquals(1, pages.asked.count { it == embed("playlist", nightBus) }, "${pages.asked}")
        }
    }

    @Test
    fun `with a working sign-in the account's playlists come first, each listed once, and read whole`() {
        val spotify = MadeUpSpotify { url ->
            when (url) {
                "/me/playlists?limit=50" -> SpotifyResponse(
                    200,
                    """{"items":[
                        {"id":"$own","name":"Made Here","public":false,"owner":{"display_name":"Sample Owner"},"tracks":{"total":1},"images":[]},
                        {"id":"$nightBus","name":"Night Bus Home","public":true,"owner":{"display_name":"Night Tram"},"tracks":{"total":3},"images":[]}
                    ],"next":null}""",
                )
                "/playlists/$own/items?limit=50" -> SpotifyResponse(
                    200,
                    """{"items":[{"item":{"id":"${songId(50)}","name":"Own Song","artists":[{"id":"a","name":"Signal Box"}],"duration_ms":180000}}],"next":null}""",
                )
                else -> SpotifyResponse(403, """{"error":{"status":403,"message":"Forbidden"}}""")
            }
        }
        val pages = pages()
        state(pages, NoctoriumPreferences(autoplay = false, spotifyPublicPlaylists = listOf(nightBus, longOne)), api = spotify).use { state ->
            state.reloaded()
            assertEquals(listOf(SpotifyClient.LIKED_SONGS_ID, own, nightBus, longOne), state.library.value.playlists.map { it.id })
            // The account's own entry for the one it has, with no public page read for it.
            assertEquals("Night Tram", state.listed(nightBus)!!.ownerName)
            assertTrue(pages.asked.none { it == embed("playlist", nightBus) }, "${pages.asked}")
            assertNull(state.library.value.errorMessage)

            state.openPlaylist(state.listed(own)!!)
            waitFor("the account's songs") { state.library.value.openPlaylist?.tracks?.isNotEmpty() == true }
            assertEquals(listOf(songId(50)), state.library.value.openPlaylist!!.tracks.map { it.id })

            // Somebody else's playlist, which Spotify will not open for an app: its public page instead.
            state.openPlaylist(state.listed(longOne)!!)
            waitFor("the page's songs") { state.library.value.openPlaylist?.id == longOne && state.library.value.openPlaylist?.tracks?.isNotEmpty() == true }
            assertEquals(100, state.library.value.openPlaylist!!.tracks.size)
            assertTrue(spotify.asked.any { it.startsWith("/playlists/$longOne/") }, "${spotify.asked}")
        }
    }

    @Test
    fun `a sign-in Spotify refuses no longer hides the public playlists, and says what else there is`() {
        val premium = "Active premium subscription required for the owner of the app."
        val spotify = MadeUpSpotify { SpotifyResponse(403, premium) }
        state(preferences = NoctoriumPreferences(autoplay = false, spotifyPublicPlaylists = listOf(nightBus)), api = spotify).use { state ->
            state.reloaded()
            assertEquals(listOf(nightBus), state.library.value.playlists.map { it.id })
            val said = state.library.value.errorMessage.orEmpty()
            assertTrue("Premium" in said, said)
            assertTrue(SPOTIFY_WITHOUT_SIGN_IN in said, said)
            waitFor("the Spotify card to say it") { state.settings.value.spotify.message?.contains("Premium") == true }
            assertTrue(SPOTIFY_WITHOUT_SIGN_IN in state.settings.value.spotify.message!!)

            state.openPlaylist(state.listed(nightBus)!!)
            waitFor("the page's songs") { state.library.value.openPlaylist?.tracks?.size == 3 }
            assertNull(state.library.value.openPlaylistError)
        }
    }

    @Test
    fun `a playlist that cannot be read just now keeps its last good copy, and says so`() {
        val file = folder.resolve("public-playlists.json")
        val copy = (SpotifyPublicPages.parse(playlistPage(nightBus, "Night Bus Home", songs = 3), "playlist", nightBus, 1_800_000_000) as SpotifyPublicRead.Ok).list
        SpotifyPublicCopies(file).put(copy)
        val offline = ScriptedPages { HttpReply(Http.UNREACHABLE, "no connection") }
        state(offline, NoctoriumPreferences(autoplay = false, spotifyPublicPlaylists = listOf(nightBus)), copies = SpotifyPublicCopies(file)).use { state ->
            // Settings lists it as it was, before anything is read.
            waitFor("Settings to list it") { state.settings.value.spotify.publicPlaylists.singleOrNull()?.title == "Night Bus Home" }

            state.reloaded()
            assertEquals(listOf(nightBus), state.library.value.playlists.map { it.id })
            val said = state.library.value.errorMessage.orEmpty()
            assertTrue("“Night Bus Home” could not be refreshed" in said, said)
            waitFor("Settings to say why") { state.settings.value.spotify.publicPlaylists.single().lastError != null }
            assertTrue(state.settings.value.spotify.publicPlaylists.single().lastError!!.contains("Could not reach"))

            state.openPlaylist(state.listed(nightBus)!!)
            waitFor("the kept songs") { state.library.value.openPlaylist?.tracks?.size == 3 }
        }
    }

    // --- Pasted links ---

    @Test
    fun `a pasted playlist link plays with nobody signed in, and can be kept`() {
        val engine = AppStateHarness.Engine()
        state(engine = engine).use { state ->
            state.openLink("listen to this https://open.spotify.com/playlist/$nightBus?si=abc")
            waitFor("the link to open") { state.linkState.value.status == LinkStatus.DONE }
            assertEquals(3, state.linkState.value.tracks.size)
            assertTrue(state.linkState.value.canKeepInLibrary)
            waitFor("the first song to play as its recording") { state.playback.value.track?.id == "yt-Song-1" }

            state.added(state.linkState.value.link!!.url)
            assertFalse(state.linkState.value.canKeepInLibrary)
            assertEquals(listOf(nightBus), state.settings.value.preferences.spotifyPublicPlaylists)
        }
    }

    @Test
    fun `a pasted album opens too, a long playlist says it is cut short, and a song asks for the sign-in`() {
        state().use { state ->
            state.openLink("https://open.spotify.com/album/$album", LinkAction.QUEUE)
            waitFor("the album to open") { state.linkState.value.status == LinkStatus.DONE }
            assertEquals(listOf("Harbour Lights"), state.linkState.value.tracks.map { it.album?.title }.distinct())
            assertFalse(state.linkState.value.canKeepInLibrary, "an album is not a playlist to follow")

            state.openLink("https://open.spotify.com/playlist/$longOne", LinkAction.QUEUE)
            waitFor("the playlist to open") { state.linkState.value.status == LinkStatus.DONE && state.linkState.value.link?.id == longOne }
            assertTrue(state.linkState.value.message.orEmpty().contains("first 100 songs"), state.linkState.value.message)

            state.openLink("https://open.spotify.com/playlist/$hidden", LinkAction.QUEUE)
            waitFor("the refusal") { state.linkState.value.status == LinkStatus.FAILED }
            assertEquals(SpotifyPublicRead.MISSING_PLAYLIST, state.linkState.value.message)

            state.openLink("https://open.spotify.com/track/${songId(1)}", LinkAction.QUEUE)
            waitFor("the refusal") { state.linkState.value.status == LinkStatus.FAILED && state.linkState.value.link?.id == songId(1) }
            assertTrue(state.linkState.value.message.orEmpty().startsWith("Connect Spotify in Settings to open Spotify songs"), state.linkState.value.message)
        }
    }

    // --- Files from before ---

    @Test
    fun `a settings file from before follows no public playlists, and one that does reads back`() {
        val file = folder.resolve("settings.json")
        Files.write(file, """{"profileName":"Made-Up Listener","spotifyClientId":"","spotifyDevice":""}""".toByteArray())
        assertEquals(emptyList(), SettingsRepository(file).load().spotifyPublicPlaylists)

        SettingsRepository(file).save(NoctoriumPreferences(spotifyPublicPlaylists = listOf(nightBus, longOne)))
        assertEquals(listOf(nightBus, longOne), SettingsRepository(file).load().spotifyPublicPlaylists)
        // And a file from a build with something of its own beside them still reads.
        Files.write(file, """{"spotifyPublicPlaylists":["$nightBus"],"somethingLater":{"x":1}}""".toByteArray())
        assertEquals(listOf(nightBus), SettingsRepository(file).load().spotifyPublicPlaylists)
    }
}
