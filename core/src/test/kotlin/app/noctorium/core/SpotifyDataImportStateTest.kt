package app.noctorium.core

import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playlists.LocalPlaylistRepository
import app.noctorium.playlists.PlaylistImport
import app.noctorium.spotify.ExportFixtures
import app.noctorium.spotify.SPOTIFY_LIKED_SONGS_TITLE
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spotify's data download imported into the library from the application, as a player hands it over -- bytes, a
 * path, a stream -- and its songs played as their YouTube Music matches with nobody signed in to Spotify at all.
 */
class SpotifyDataImportStateTest {
    private val folder = Files.createTempDirectory("noctorium-spotify-import")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

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

    private fun state(engine: AppStateHarness.Engine = AppStateHarness.Engine()): AppState = AppStateHarness.state(
        engine = engine,
        providers = null,
        backend = backend,
        playlists = LocalPlaylistRepository(folder.resolve("playlists.json")),
    )

    @Test
    fun `an imported song plays as its YouTube Music match with nobody signed in to Spotify`() {
        val engine = AppStateHarness.Engine()
        state(engine = engine).use { state ->
            state.importSpotifyData(ExportFixtures.download(), "my_spotify_data.zip")
            waitFor("the import") { state.settings.value.spotify.dataImport.summary != null }

            val summary = state.settings.value.spotify.dataImport.summary!!
            assertEquals(2, summary.playlists)
            assertEquals(4, summary.likedSongs)
            assertEquals(
                "Imported 2 playlists and 4 liked songs from Spotify. 1 podcast episode, 1 audiobook chapter and 1 local " +
                    "file were left out. Your 2 saved albums were not imported: Spotify's file names them without their songs.",
                summary.message,
            )
            assertEquals(summary.message, state.library.value.notice)
            assertFalse(state.settings.value.spotify.dataImport.importing)

            val titles = state.library.value.localPlaylists.map { it.title }
            assertEquals(listOf(SPOTIFY_LIKED_SONGS_TITLE, "Night Bus Home", "Rainy Mondays"), titles)
            val nightBusHome = state.library.value.localPlaylists.first { it.title == "Night Bus Home" }
            assertEquals(PlaylistImport.SPOTIFY_DATA, nightBusHome.importedFrom?.source)

            // A Spotify song with no length, matched by name and artist, and played as the recording.
            state.playLocalPlaylist(nightBusHome)
            waitFor("the recording to play") { state.playback.value.track?.id == "yt-Song-1" }
            assertTrue(engine.calls.contains("play yt-Song-1"), "${engine.calls}")
            assertEquals(ProviderType.YOUTUBE_MUSIC, state.playback.value.track?.provider)
            // Nobody signed in to Spotify, and nothing asked of it.
            assertFalse(state.settings.value.spotify.connected)
        }
    }

    @Test
    fun `importing again replaces what the last import made and leaves the listener's own alone`() {
        state().use { state ->
            val mine = state.createPlaylist("Night Bus Home", AppStateHarness.song("mine"))!!
            val file = folder.resolve("Playlist1.json")
            Files.write(file, ExportFixtures.playlists("Night Bus Home" to listOf(ExportFixtures.song(1))).toByteArray())
            state.importSpotifyData(file)
            waitFor("the first import") { state.settings.value.spotify.dataImport.summary != null }

            Files.write(file, ExportFixtures.playlists("Night Bus Home" to listOf(ExportFixtures.song(1), ExportFixtures.song(2))).toByteArray())
            state.importSpotifyData("Playlist1.json") { Files.newInputStream(file) }
            waitFor("the second import") { state.settings.value.spotify.dataImport.summary?.replaced == 1 }

            val playlists = state.library.value.localPlaylists
            assertEquals(2, playlists.size, "${playlists.map { it.title }}")
            assertEquals(mine, playlists.first { it.id == mine.id })
            assertEquals(listOf(ExportFixtures.id(1), ExportFixtures.id(2)), playlists.first { it.importedFrom != null }.tracks.map { it.id })
            assertTrue("from an earlier import" in state.settings.value.spotify.dataImport.message.orEmpty())
        }
    }

    @Test
    fun `a file that is not Spotify's download says so, and imports nothing`() {
        state().use { state ->
            state.importSpotifyData("""{"username":"made-up"}""".toByteArray(), "Userdata.json")
            waitFor("the refusal") { state.settings.value.spotify.dataImport.failed }
            assertTrue(state.settings.value.spotify.dataImport.message.orEmpty().contains("no playlists or Liked Songs"))
            assertTrue(state.library.value.localPlaylists.none { it.importedFrom != null })
        }
    }
}
