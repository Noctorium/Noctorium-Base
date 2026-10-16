package app.noctorium.applemusic

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The client against Apple Music itself: the web player's page and script for its developer token, then the
 * catalogue -- a search, an album, the charts -- read with that token.
 *
 * Off unless asked for, because it needs the network. It reads only Apple's public pages and catalogue, signs in to
 * nothing, and keeps the token it finds in memory only:
 *
 * ```
 * NOCTORIUM_LIVE_CHECK=1 ./gradlew :core:test --tests 'app.noctorium.applemusic.AppleMusicLiveCheck'
 * ```
 */
class AppleMusicLiveCheck {
    private val asked = System.getenv("NOCTORIUM_LIVE_CHECK")?.trim() == "1"

    @Test
    fun `Apple Music answers every question the catalogue asks`(): Unit = runBlocking {
        if (!asked) {
            println("LIVE: skipped; set NOCTORIUM_LIVE_CHECK=1 to run it")
            return@runBlocking
        }
        val http = DefaultAppleMusicHttp()
        val tokens = AppleMusicDeveloperToken(http, cache = null)
        val token = tokens.token()
        println("LIVE: the web player's token runs out at ${AppleMusicDeveloperToken.expiryOf(token)?.let { java.time.Instant.ofEpochSecond(it) }}")
        val client = AppleMusicClient(http, tokens)

        val found = client.search("daft punk one more time", "us")
        println("LIVE: search found ${found.tracks.size} songs, ${found.albums.size} albums, ${found.artists.size} artists, ${found.playlists.size} playlists")
        val song = found.tracks.first { it.title.equals("One More Time", ignoreCase = true) }
        assertTrue(song.artists.any { it.name == "Daft Punk" })
        assertTrue((song.durationMs ?: 0) > 300_000)

        val album = found.albums.first()
        val songs = client.albumTracks(album.id.removePrefix(AppleMusicClient.ALBUM_PREFIX), "us")
        println("LIVE: '${album.title}' has ${songs.size} songs")
        assertTrue(songs.isNotEmpty())

        val charts = client.charts("us")
        println("LIVE: the charts have ${charts.songs.size} songs, ${charts.albums.size} albums, ${charts.playlists.size} playlists")
        assertTrue(charts.songs.isNotEmpty())

        found.artists.firstOrNull()?.let { artist ->
            val top = client.artistTopSongs(artist.id.removePrefix(AppleMusicClient.ARTIST_PREFIX), "us")
            println("LIVE: ${artist.title}'s top songs: ${top.take(5).map { it.title }}")
        }
        found.playlists.firstOrNull()?.let { playlist ->
            val listed = client.playlistTracks(playlist.id, "us", limit = 150)
            println("LIVE: playlist '${playlist.title}' gave ${listed.size} songs")
        }
    }
}
