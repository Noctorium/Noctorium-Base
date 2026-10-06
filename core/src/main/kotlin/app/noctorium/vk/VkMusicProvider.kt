package app.noctorium.vk

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.HomeSection
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.SearchResults
import app.noctorium.domain.Track
import app.noctorium.playback.ServiceStream
import app.noctorium.providers.MusicProvider
import kotlinx.coroutines.CancellationException

/**
 * VK Music: the account's own music and playlists, search, and VK's suggestions for Home.
 *
 * Everything goes through [VkClient], one request at a time; see there for why. A song's address is asked
 * for only when it is about to play, because VK's addresses are bound to the moment and to the network
 * they were asked from, and asking for a playlist's worth of them up front is the bulk fetching VK watches
 * for.
 */
class VkMusicProvider(private val client: VkClient) : MusicProvider {
    override val type: ProviderType = ProviderType.VK

    /**
     * VK's suggestions and its popular songs, each left out quietly when VK will not give it.
     *
     * Home is no place for VK's refusals; Settings and the library say what is wrong.
     */
    override suspend fun getHome(): List<HomeSection> {
        if (!client.isSignedIn()) return emptyList()
        val suggested = quietly { client.recommendations(count = 24) }
        val popular = quietly { client.popular(count = 24) }
        return listOfNotNull(
            suggested.takeIf { it.isNotEmpty() }?.let {
                HomeSection("vk:recommended", "Suggested on VK", type, "Picked for your account", it.map(::trackOf))
            },
            popular.takeIf { it.isNotEmpty() }?.let {
                HomeSection("vk:popular", "Popular on VK", type, "What VK is playing", it.map(::trackOf))
            },
        )
    }

    override suspend fun search(query: String): SearchResults {
        if (!client.isSignedIn()) throw VkUnavailable("Sign in to VK in Settings to search VK.", signedOut = true)
        return SearchResults(tracks = client.search(query).map(::trackOf))
    }

    override suspend fun getTrack(id: String): Track? = client.byId(listOf(id)).firstOrNull()?.let(::trackOf)

    /** What VK suggests after the song that was playing. */
    override suspend fun getRecommendations(context: PlaybackContext): List<Track> {
        if (context.provider != type || !client.isSignedIn()) return emptyList()
        val seed = context.seedTrackId?.let { id -> id.split('_').take(2).joinToString("_") }
        return quietly { client.recommendations(seed = seed, count = 20) }.map(::trackOf)
    }

    /** "My music" first -- everything the account added -- then the account's playlists and albums. */
    override suspend fun getLibraryPlaylists(): List<Playlist> {
        if (!client.isSignedIn()) return emptyList()
        val userId = client.userId()
        val mine = Playlist(
            id = MY_MUSIC,
            title = "My music",
            provider = type,
            ownerName = "VK",
            sourceUrl = "https://vk.ru/audios$userId",
        )
        return listOf(mine) + client.playlists().map { playlist ->
            Playlist(
                id = "$PLAYLIST:${playlist.ownerId}_${playlist.id}" + (playlist.accessKey?.let { "_$it" } ?: ""),
                title = playlist.title,
                provider = type,
                ownerName = playlist.ownerName,
                artworkUrl = playlist.artworkUrl,
                sourceUrl = "https://vk.ru/music/playlist/${playlist.ownerId}_${playlist.id}" +
                    (playlist.accessKey?.let { "_$it" } ?: ""),
                trackCount = playlist.count,
            )
        }
    }

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> = when {
        playlist.id == MY_MUSIC -> client.myAudio(MY_MUSIC_LIMIT).map(::trackOf)
        playlist.id.startsWith("$PLAYLIST:") -> {
            val parts = playlist.id.removePrefix("$PLAYLIST:").split('_')
            val owner = parts.getOrNull(0)?.toLongOrNull()
            val id = parts.getOrNull(1)?.toLongOrNull()
            if (owner == null || id == null) emptyList() else client.playlistAudio(owner, id, parts.getOrNull(2)).map(::trackOf)
        }
        else -> playlist.tracks
    }

    override suspend fun resolvePlaylistTracks(playlist: Playlist, from: Int, to: Int): List<Track> = emptyList()

    /**
     * The address to play a VK song from, asked for now; null when [sourceUrl] is not a VK song at all.
     *
     * HLS, with some segments encrypted -- which [ServiceStream.decrypt] tells a platform whose player
     * cannot cope, so the stream goes through [app.noctorium.playback.HlsRelay] first.
     */
    suspend fun streamFor(sourceUrl: String): ServiceStream? {
        val source = VkSource.parse(sourceUrl) ?: return null
        val audio = client.byId(listOf(source.requestId)).firstOrNull()
            ?: throw VkUnavailable("VK no longer has this song.")
        if (audio.url.isBlank() || audio.url.contains("audio_api_unavailable")) {
            throw VkUnavailable(
                if (audio.contentRestricted != 0) "VK does not let this song be played from where you are."
                else "VK would not give an address for this song.",
            )
        }
        return ServiceStream(address = audio.url, userAgent = VkClient.BROWSER_AGENT, isHls = true, decrypt = true)
    }

    /** Adds the song to the account's music, which is what a heart is on VK. Answers the account's copy. */
    suspend fun add(track: Track): Track? {
        val source = VkSource.parse(track.sourceUrl) ?: return null
        val copy = client.add(source.ownerId, source.audioId, source.accessKey) ?: return null
        return track.copy(id = "${client.userId()}_$copy", sourceUrl = VkSource(client.userId(), copy, null).toString())
    }

    /** Takes the song out of the account's music. Only a song the account holds can be taken out. */
    suspend fun remove(track: Track): Boolean {
        val source = VkSource.parse(track.sourceUrl) ?: return false
        if (source.ownerId != client.userId()) return false
        return client.delete(source.ownerId, source.audioId)
    }

    private suspend fun quietly(read: suspend () -> List<VkAudio>): List<VkAudio> = try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        emptyList()
    }

    private fun trackOf(audio: VkAudio): Track {
        val artists = audio.artist.split(", ", " & ", " feat. ", " ft. ").map(String::trim).filter(String::isNotEmpty)
            .ifEmpty { listOf(audio.artist) }
            .map { name -> Artist(id = name, name = name, provider = type) }
        return Track(
            provider = type,
            id = audio.fullId,
            title = audio.title,
            artists = artists,
            album = audio.albumTitle?.let { title ->
                Album(id = audio.albumId ?: title, title = title, artists = artists, provider = type, artworkUrl = audio.artworkUrl)
            },
            durationMs = audio.durationSeconds.takeIf { it > 0 }?.times(1000L),
            artworkUrl = audio.artworkUrl,
            sourceUrl = VkSource.of(audio),
        )
    }

    companion object {
        const val MY_MUSIC = "my-music"
        const val PLAYLIST = "playlist"

        /** As much of the account's music as one listing gives; VK pages it in hundreds. */
        private const val MY_MUSIC_LIMIT = 1_000
    }
}
