package app.noctorium.providers

import app.noctorium.domain.Artist
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.MusicBackend
import app.noctorium.settings.CookieSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which reader answers for a YouTube Music playlist, and when the other one is asked.
 *
 * The distinction these guard is not academic. NewPipeExtractor refuses any playlist id shorter than ten
 * characters, and YouTube's own built-in lists are two -- `LM` for Liked Music, `SE` for Episodes for
 * Later -- so the backend cannot open the very playlists a listener finds in their library. It answers
 * "URL not accepted", which the phone showed in red above an empty list.
 *
 * So YouTube Music's own interface is asked first, and the rule is that anything it answers is the
 * answer. The first version of this got it wrong by falling back whenever the result was empty, which
 * meant an empty playlist -- a perfectly ordinary thing -- was handed to a backend that could only fail,
 * and "Nothing in here" became an error message.
 */
class BackendMusicProviderPlaylistTest {

    private val likedMusic = Playlist(
        id = "LM",
        title = "Liked Music",
        provider = ProviderType.YOUTUBE_MUSIC,
        sourceUrl = "https://music.youtube.com/playlist?list=LM",
    )

    private fun track(id: String) = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = id,
        title = "Giorgio by Moroder",
        artists = listOf(Artist("daft-punk", "Daft Punk", ProviderType.YOUTUBE_MUSIC)),
        sourceUrl = "https://music.youtube.com/watch?v=$id",
    )

    @Test
    fun `what YouTube Music answers is used, and the backend is never asked`() = runBlocking {
        val backend = RecordingBackend()
        val provider = BackendMusicProvider(
            ProviderType.YOUTUBE_MUSIC,
            backend,
            playlistTracks = { _, _ -> listOf(track("ZFZM6jDTWd4")) },
        )

        val tracks = provider.getPlaylistTracks(likedMusic)

        assertEquals(listOf("ZFZM6jDTWd4"), tracks.map { it.id })
        assertTrue(backend.listed.isEmpty(), "the backend cannot open this playlist and must not be tried")
    }

    /** The bug this file exists for. */
    @Test
    fun `an empty playlist is an answer, not a reason to try the backend`() = runBlocking {
        val backend = RecordingBackend()
        val provider = BackendMusicProvider(
            ProviderType.YOUTUBE_MUSIC,
            backend,
            playlistTracks = { _, _ -> emptyList() },
        )

        val tracks = provider.getPlaylistTracks(likedMusic)

        assertTrue(tracks.isEmpty())
        assertTrue(
            backend.listed.isEmpty(),
            "an empty playlist went to a backend that can only answer 'URL not accepted'",
        )
    }

    @Test
    fun `not being able to answer does fall back to the backend`() = runBlocking {
        val backend = RecordingBackend(tracks = listOf(track("fallback")))
        val provider = BackendMusicProvider(
            ProviderType.YOUTUBE_MUSIC,
            backend,
            // Null is how "no session, cannot say" arrives.
            playlistTracks = { _, _ -> null },
        )

        val tracks = provider.getPlaylistTracks(likedMusic)

        assertEquals(listOf("fallback"), tracks.map { it.id })
        assertEquals(listOf("https://music.youtube.com/playlist?list=LM"), backend.listed)
    }

    @Test
    fun `a reader that throws is also a fallback rather than an empty playlist`() = runBlocking {
        val backend = RecordingBackend(tracks = listOf(track("fallback")))
        val provider = BackendMusicProvider(
            ProviderType.YOUTUBE_MUSIC,
            backend,
            playlistTracks = { _, _ -> throw IllegalStateException("the session expired") },
        )

        assertEquals(listOf("fallback"), provider.getPlaylistTracks(likedMusic).map { it.id })
    }

    @Test
    fun `the playlist is browsed by the id in its address`() = runBlocking {
        var asked: String? = null
        val provider = BackendMusicProvider(
            ProviderType.YOUTUBE_MUSIC,
            RecordingBackend(),
            playlistTracks = { id, _ -> asked = id; emptyList() },
        )

        provider.getPlaylistTracks(
            likedMusic.copy(
                id = "ignored",
                // The trailing parameter must not end up part of the id.
                sourceUrl = "https://music.youtube.com/playlist?list=PLabcdefghij&si=xyz",
            ),
        )

        assertEquals("PLabcdefghij", asked)
    }

    @Test
    fun `SoundCloud is left to the backend entirely`() = runBlocking {
        val backend = RecordingBackend(tracks = listOf(track("soundcloud")))
        val provider = BackendMusicProvider(
            ProviderType.SOUNDCLOUD,
            backend,
            playlistTracks = { _, _ -> error("YouTube Music must not be asked about SoundCloud") },
        )

        val likes = Playlist(
            id = "likes",
            title = "Liked tracks",
            provider = ProviderType.SOUNDCLOUD,
            sourceUrl = "https://soundcloud.com/someone/likes",
        )
        assertEquals(listOf("soundcloud"), provider.getPlaylistTracks(likes).map { it.id })
    }

    /** Answers nothing, and remembers what it was asked to list. */
    private class RecordingBackend(private val tracks: List<Track> = emptyList()) : MusicBackend {
        val listed = mutableListOf<String>()

        override fun useSession(provider: ProviderType, source: CookieSource) = Unit
        override fun useSoundCloudProfile(username: String) = Unit
        override val soundCloudProfile: String = ""
        override suspend fun search(provider: ProviderType, query: String, limit: Int) = emptyList<Track>()
        override suspend fun listPlaylists(provider: ProviderType, url: String, limit: Int) =
            emptyList<Playlist>()

        override suspend fun listTracks(provider: ProviderType, url: String, limit: Int): List<Track> {
            listed += url
            return tracks
        }

        override suspend fun resolveTracks(
            provider: ProviderType,
            url: String,
            from: Int,
            to: Int,
        ) = emptyList<Track>()

        override suspend fun enrichMetadata(track: Track) = track
        override suspend fun resolveAudio(sourceUrl: String) = ""
        override suspend fun resolveSoundCloudPermalink(userId: String): String? = null
        override suspend fun downloadAudio(
            sourceUrl: String,
            outputTemplate: String,
            onProgress: (Float) -> Unit,
        ) = Unit

        override fun canConvertAudio() = false
        override suspend fun exportAudio(
            sourceUrl: String,
            outputTemplate: String,
            format: app.noctorium.downloads.ExportFormat,
            onProgress: (Float) -> Unit,
        ) = Unit

        override suspend fun describe() = "a backend that answers nothing"
    }
}
