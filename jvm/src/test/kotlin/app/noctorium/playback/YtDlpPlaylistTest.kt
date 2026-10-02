package app.noctorium.playback

import app.noctorium.domain.ProviderType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YtDlpPlaylistTest {
    private val service = YtDlpService()

    // Captured from yt-dlp 2026.08.19 for a SoundCloud "/sets" page: entries carry id, title and url only.
    @Test
    fun `soundcloud sets become playlists that can be opened again`() {
        val payload = """
            {"_type": "playlist", "id": "2976616", "title": "Flume (Sets)", "entries": [
              {"id": "2059315872", "title": "Emma Louise & Flume - DUMB", "_type": "url",
               "url": "https://soundcloud.com/flume/sets/emma-louise-flume-dumb"},
              {"id": "2042574897", "title": "DUMB", "_type": "url",
               "url": "https://soundcloud.com/flume/sets/dumb-499556491"}
            ], "extractor": "soundcloud:user"}
        """.trimIndent()

        val playlists = service.mapPlaylists(ProviderType.SOUNDCLOUD, Json.parseToJsonElement(payload).jsonObject)

        assertEquals(2, playlists.size)
        assertEquals("Emma Louise & Flume - DUMB", playlists.first().title)
        assertEquals("https://soundcloud.com/flume/sets/emma-louise-flume-dumb", playlists.first().sourceUrl)
        assertEquals(ProviderType.SOUNDCLOUD, playlists.first().provider)
        assertEquals("SOUNDCLOUD:2059315872", playlists.first().playlistKey)
    }

    @Test
    fun `youtube playlist entries keep artwork, owner and track count`() {
        val payload = """
            {"_type": "playlist", "id": "playlists", "entries": [
              {"_type": "url", "id": "PL123", "title": "Late night",
               "url": "https://www.youtube.com/playlist?list=PL123",
               "uploader": "Yabosen", "playlist_count": 42,
               "thumbnails": [{"url": "https://i.ytimg.com/vi/a/small.jpg"}, {"url": "https://i.ytimg.com/vi/a/big.jpg"}]}
            ]}
        """.trimIndent()

        val playlist = service.mapPlaylists(ProviderType.YOUTUBE_MUSIC, Json.parseToJsonElement(payload).jsonObject).single()

        assertEquals("Late night", playlist.title)
        assertEquals("Yabosen", playlist.ownerName)
        assertEquals(42, playlist.trackCount)
        assertEquals("https://i.ytimg.com/vi/a/big.jpg", playlist.artworkUrl)
        assertEquals("https://www.youtube.com/playlist?list=PL123", playlist.sourceUrl)
    }

    @Test
    fun `entries without a title or url are skipped instead of becoming unopenable rows`() {
        val payload = """
            {"entries": [
              {"_type": "url", "id": "no-title", "url": "https://soundcloud.com/x/sets/y"},
              {"_type": "url", "id": "no-url", "title": "Orphan"},
              {"_type": "url", "id": "ok", "title": "Keeper", "url": "https://soundcloud.com/x/sets/keeper"}
            ]}
        """.trimIndent()

        val playlists = service.mapPlaylists(ProviderType.SOUNDCLOUD, Json.parseToJsonElement(payload).jsonObject)

        assertEquals(listOf("Keeper"), playlists.map { it.title })
    }

    @Test
    fun `a page with no entries yields no playlists`() {
        val playlists = service.mapPlaylists(
            ProviderType.YOUTUBE_MUSIC,
            Json.parseToJsonElement("""{"id": "playlists", "playlist_count": 0}""").jsonObject,
        )

        assertTrue(playlists.isEmpty())
    }

    // Captured from a real SoundCloud set: entries are url_transparent stubs with no title and no duration.
    // These used to be dropped outright, which is why an opened playlist looked empty.
    @Test
    fun `soundcloud set stubs stay playable instead of vanishing`() {
        val stub = """
            {"album": "Gym Playlist For Bipolar People", "album_artist": "Y A B O S E N",
             "album_type": "playlist", "ie_key": "Soundcloud", "id": "1612018959", "_type": "url_transparent",
             "url": "https://soundcloud.com/foolish_transgressions/femtanyl-katamari"}
        """.trimIndent()

        val track = service.mapTrack(ProviderType.SOUNDCLOUD, Json.parseToJsonElement(stub).jsonObject)

        assertEquals("Femtanyl katamari", track?.title)
        assertEquals("https://soundcloud.com/foolish_transgressions/femtanyl-katamari", track?.sourceUrl)
    }

    @Test
    fun `a fully resolved soundcloud entry keeps its real title and duration`() {
        val resolved = """
            {"id": "1612018959", "title": "Femtanyl - KATAMARI", "uploader": "FEMTANYL", "duration": 133.5,
             "webpage_url": "https://soundcloud.com/foolish_transgressions/femtanyl-katamari"}
        """.trimIndent()

        val track = service.mapTrack(ProviderType.SOUNDCLOUD, Json.parseToJsonElement(resolved).jsonObject)

        assertEquals("Femtanyl - KATAMARI", track?.title)
        assertEquals("FEMTANYL", track?.artists?.single()?.name)
        assertEquals(133_500, track?.durationMs)
    }

    @Test
    fun `an entry with no id is still skipped`() {
        val track = service.mapTrack(
            ProviderType.SOUNDCLOUD,
            Json.parseToJsonElement("""{"url": "https://soundcloud.com/a/b"}""").jsonObject,
        )

        assertEquals(null, track)
    }

    @Test
    fun `a pasted soundcloud profile link is reduced to the profile name`() {
        val service = YtDlpService()

        service.useSoundCloudProfile("  https://soundcloud.com/yabosen/  ")

        assertEquals("yabosen", service.soundCloudProfile)
    }
}

class YtDlpArtworkTest {
    private val service = YtDlpService()

    // Real SoundCloud variants: mini 16px through t500x500, plus an "original" that measured 271 KB against
    // 65 KB for the 500px file. A playlist row needs the small one.
    @Test
    fun `a sized variant is preferred over the full-size original`() {
        val payload = """
            {"id": "1", "title": "Track", "url": "https://soundcloud.com/a/b",
             "thumbnail": "https://i1.sndcdn.com/artworks-x-original.png",
             "thumbnails": [
               {"id": "mini", "url": "https://i1.sndcdn.com/artworks-x-mini.jpg", "width": 16},
               {"id": "t67x67", "url": "https://i1.sndcdn.com/artworks-x-t67x67.jpg", "width": 67},
               {"id": "t300x300", "url": "https://i1.sndcdn.com/artworks-x-t300x300.jpg", "width": 300},
               {"id": "t500x500", "url": "https://i1.sndcdn.com/artworks-x-t500x500.jpg", "width": 500}
             ]}
        """.trimIndent()

        val track = service.mapTrack(ProviderType.SOUNDCLOUD, Json.parseToJsonElement(payload).jsonObject)

        assertEquals("https://i1.sndcdn.com/artworks-x-t300x300.jpg", track?.artworkUrl)
    }

    @Test
    fun `when every variant is tiny the original is used so covers stay sharp`() {
        val payload = """
            {"id": "1", "title": "Track", "url": "https://soundcloud.com/a/b",
             "thumbnail": "https://i1.sndcdn.com/artworks-x-original.png",
             "thumbnails": [{"id": "mini", "url": "https://i1.sndcdn.com/artworks-x-mini.jpg", "width": 16}]}
        """.trimIndent()

        val track = service.mapTrack(ProviderType.SOUNDCLOUD, Json.parseToJsonElement(payload).jsonObject)

        assertEquals("https://i1.sndcdn.com/artworks-x-original.png", track?.artworkUrl)
    }

    @Test
    fun `a youtube entry with no artwork at all falls back to the video still`() {
        val track = service.mapTrack(
            ProviderType.YOUTUBE_MUSIC,
            Json.parseToJsonElement("""{"id": "abc123", "title": "Song", "url": "abc123"}""").jsonObject,
        )

        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", track?.artworkUrl)
    }
}
