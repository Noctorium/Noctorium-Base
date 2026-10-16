package app.noctorium.spotify

import app.noctorium.domain.Artist
import app.noctorium.domain.HomeSection
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.SearchResults
import app.noctorium.domain.Track
import app.noctorium.providers.MusicProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Spotify: the library, search, albums and artists, and two rows of Home.
 *
 * What plays is decided elsewhere. A Spotify song is matched to the same recording on YouTube Music at the
 * moment it is played -- see [SpotifyMatch] -- unless the account signed in with Premium and the listener
 * chose to play on Spotify, in which case the account's own Spotify app plays it; see [SpotifyConnectEngine].
 * Everything here is the same either way: the names, the order, the covers are Spotify's.
 *
 * Albums and artists open as playlists, the way Bandcamp's do, so every screen that can open a playlist can
 * open them. An album is its songs; an artist is the songs of their newest albums and singles.
 *
 * Without a sign-in -- or with one Spotify refuses, which since 2026 is most of them -- a public playlist or an
 * album is still read, from its public page ([SpotifyPublicLibrary]), and the public playlists the listener chose
 * to follow are in the library either way. That page shows a playlist's first hundred songs, so wherever a working
 * sign-in can read a playlist whole, the sign-in is asked first.
 */
class SpotifyMusicProvider(
    private val client: SpotifyClient,
    private val access: SpotifyAccess,
    /** The public playlists followed without a sign-in, and the public pages they are read from. None given, none. */
    private val public: SpotifyPublicLibrary? = null,
) : MusicProvider {
    override val type: ProviderType = ProviderType.SPOTIFY

    /**
     * What the account has played most lately, and what it played last.
     *
     * Each row is left out quietly when Spotify will not give it -- most often a sign-in from before
     * Noctorium asked for these, which a new sign-in fixes -- because a missing row is not worth an error on
     * Home.
     */
    override suspend fun getHome(): List<HomeSection> {
        val token = quietToken() ?: return emptyList()
        return coroutineScope {
            val top = async { client.topTracks(token).valueOrNull().orEmpty() }
            val recent = async { client.recentlyPlayed(token).valueOrNull().orEmpty() }
            listOfNotNull(
                top.await().takeIf { it.isNotEmpty() }?.let {
                    HomeSection("spotify:top", "Your top songs on Spotify", type, "The last few weeks", it)
                },
                recent.await().takeIf { it.isNotEmpty() }?.let {
                    HomeSection("spotify:recent", "Played lately on Spotify", type, "From any device", it)
                },
            )
        }
    }

    override suspend fun search(query: String): SearchResults {
        val token = accessToken() ?: return SearchResults()
        val found = client.search(query, token).orThrow()
        return SearchResults(
            tracks = found.tracks,
            artists = found.artists.map { Artist(it.id.removePrefix(SpotifyClient.ARTIST_PREFIX), it.title, type) },
            albums = emptyList(),
            playlists = found.albums + found.artists,
        )
    }

    override suspend fun getTrack(id: String): Track? = null

    /** Spotify gives apps no recommendations any more; the queue's own way of carrying on is used instead. */
    override suspend fun getRecommendations(context: PlaybackContext): List<Track> = emptyList()

    /**
     * The account's playlists, with Liked Songs in front, and after them the public playlists the listener follows
     * -- each but those the account lists itself, which would otherwise be there twice.
     *
     * A sign-in Spotify refuses still throws, so the library goes on saying why; but it no longer takes the
     * followed playlists with it, which come along in [SpotifyUnavailable.stillRead].
     */
    override suspend fun getLibraryPlaylists(): List<Playlist> {
        val own: SpotifyRead<List<Playlist>>? = when (val ready = access.access()) {
            is SpotifyAccess.Access.Ready -> client.playlists(ready.accessToken)
            SpotifyAccess.Access.NotConfigured, SpotifyAccess.Access.NotConnected -> null
            is SpotifyAccess.Access.Failed -> SpotifyRead.Failed(ready.detail)
        }
        val listed = own?.valueOrNull().orEmpty()
        val followed = public?.listing(excluding = listed.mapTo(HashSet()) { it.id }).orEmpty()
        return when (own) {
            null, is SpotifyRead.Ok -> listed + followed
            is SpotifyRead.Unauthorized -> throw SpotifyUnavailable(own.detail, followed)
            is SpotifyRead.Failed -> throw SpotifyUnavailable(own.detail, followed)
        }
    }

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> = open(playlist).tracks

    /**
     * A playlist's, an album's or an artist's songs, and the public page they were read from where that is where.
     *
     * A playlist or an album is asked of the account first, when there is a sign-in, because that reads it whole.
     * When there is none, or Spotify refuses it -- an app whose owner has no Premium, or a playlist somebody else
     * made -- its public page is read instead, which needs nobody signed in and shows the first hundred songs. An
     * artist, and Liked Songs, need the sign-in.
     */
    suspend fun open(playlist: Playlist): SpotifyOpened {
        val id = playlist.id
        if (id == SpotifyClient.LIKED_SONGS_ID || id.startsWith(SpotifyClient.ARTIST_PREFIX)) {
            val token = accessToken() ?: return SpotifyOpened(emptyList())
            return SpotifyOpened(
                if (id == SpotifyClient.LIKED_SONGS_ID) {
                    client.playlistTracks(id, token).orThrow()
                } else {
                    artistTracks(id.removePrefix(SpotifyClient.ARTIST_PREFIX), token)
                },
            )
        }
        val signedIn: SpotifyRead<List<Track>>? = when (val ready = access.access()) {
            is SpotifyAccess.Access.Ready -> if (id.startsWith(SpotifyClient.ALBUM_PREFIX)) {
                client.albumTracks(id.removePrefix(SpotifyClient.ALBUM_PREFIX), ready.accessToken)
            } else {
                client.playlistTracks(id, ready.accessToken)
            }
            SpotifyAccess.Access.NotConfigured, SpotifyAccess.Access.NotConnected -> null
            is SpotifyAccess.Access.Failed -> SpotifyRead.Failed(ready.detail)
        }
        if (signedIn is SpotifyRead.Ok) return SpotifyOpened(signedIn.value)
        val pages = public ?: return SpotifyOpened(signedIn?.orThrow().orEmpty())
        return when (val read = pages.open(id)) {
            is SpotifyPublicRead.Ok -> SpotifyOpened(read.list.tracks, read.list)
            // Private is the answer that matters, whatever the sign-in said: it is why the page has nothing.
            is SpotifyPublicRead.Missing -> throw SpotifyUnavailable(read.message)
            else -> throw SpotifyUnavailable(signedIn?.detailOrNull() ?: read.message.orEmpty())
        }
    }

    /**
     * Nothing to resolve slice by slice.
     *
     * The listing already carries artwork and exact lengths, which for the other providers is what this
     * second pass exists to fetch. Spotify's own metadata is the best available, so it is left as it is --
     * and it is what the play-time match is judged against.
     */
    override suspend fun resolvePlaylistTracks(playlist: Playlist, from: Int, to: Int): List<Track> = emptyList()

    /**
     * An artist's newest albums and singles, as one list of songs.
     *
     * Spotify gives an app an artist's top songs no longer, so this is what an artist is here: their own
     * releases, newest first, as far as one page of ten of them goes.
     */
    private suspend fun artistTracks(artistId: String, token: String): List<Track> = coroutineScope {
        client.artistAlbums(artistId, token).orThrow()
            .map { album ->
                async {
                    (client.albumTracks(album.id.removePrefix(SpotifyClient.ALBUM_PREFIX), token) as? SpotifyRead.Ok)?.value.orEmpty()
                }
            }
            .awaitAll()
            .flatten()
    }

    /** Null when Spotify is simply not set up, which is silence rather than a failure. */
    private suspend fun accessToken(): String? = when (val ready = access.access()) {
        is SpotifyAccess.Access.Ready -> ready.accessToken
        SpotifyAccess.Access.NotConfigured, SpotifyAccess.Access.NotConnected -> null
        is SpotifyAccess.Access.Failed -> throw SpotifyUnavailable(ready.detail)
    }

    /** The same, for Home, where a failure is a missing row rather than a message. */
    private suspend fun quietToken(): String? = try {
        accessToken()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: SpotifyUnavailable) {
        null
    }

    /**
     * Turns a refusal into an exception, because that is how the library reports one.
     *
     * The library gathers each provider's playlists with `runCatching` and shows the message from whatever
     * failed. Returning an empty list instead would present an account that is signed out as one with no
     * playlists in it, which is the kind of quiet wrong answer that took three rounds to find in the likes.
     */
    private fun <T> SpotifyRead<T>.orThrow(): T = when (this) {
        is SpotifyRead.Ok -> value
        is SpotifyRead.Unauthorized -> throw SpotifyUnavailable(detail)
        is SpotifyRead.Failed -> throw SpotifyUnavailable(detail)
    }
}

/** Whether a Spotify playlist is really an artist, opened as their newest releases' songs. */
fun Playlist.isSpotifyArtist(): Boolean =
    provider == ProviderType.SPOTIFY && id.startsWith(SpotifyClient.ARTIST_PREFIX)

/** Whether a Spotify playlist is really an album. */
fun Playlist.isSpotifyAlbum(): Boolean =
    provider == ProviderType.SPOTIFY && id.startsWith(SpotifyClient.ALBUM_PREFIX)

/**
 * A Spotify read that did not happen, with the reason already written for a reader.
 *
 * [stillRead] is what could be read all the same: the public playlists the listener follows, which need no sign-in,
 * when it is the sign-in Spotify refused. The library shows them beside the reason.
 */
class SpotifyUnavailable(message: String, val stillRead: List<Playlist> = emptyList()) : Exception(message)

/**
 * A Spotify playlist's songs as they were opened, and [publicPage] -- the public page's copy, with its name, cover
 * and whether it was cut short -- when that is where they were read rather than through a sign-in.
 */
data class SpotifyOpened(val tracks: List<Track>, val publicPage: SpotifyPublicList? = null)
