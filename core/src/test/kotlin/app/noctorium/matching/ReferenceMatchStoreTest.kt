package app.noctorium.matching

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Remembering which recording each reference was resolved to.
 *
 * Kept because resolving costs a search, but more because it should not *change*: the same search run next
 * week can rank a different upload first, and a playlist that plays a slightly different version each time
 * is unsettling in a way a slow one is not.
 */
class ReferenceMatchStoreTest {
    private val folder = Files.createTempDirectory("noctorium-matches")
    private val file = folder.resolve("matches.json")
    private val spotifyFile = folder.resolve("spotify").resolve("matches.json")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun store() = ReferenceMatchStore(file, spotifyFile)

    private fun resolved(id: String) = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = id,
        title = "Everlong",
        artists = listOf(Artist("foo", "Foo Fighters", ProviderType.YOUTUBE_MUSIC)),
        durationMs = 250_000,
        sourceUrl = "https://music.youtube.com/watch?v=$id",
    )

    private fun reference(provider: ProviderType, id: String) = Track(
        provider = provider,
        id = id,
        title = "Everlong",
        artists = listOf(Artist("foo", "Foo Fighters", provider)),
        durationMs = 250_000,
        sourceUrl = "https://example.invalid/$id",
    )

    private val spotifySong = reference(ProviderType.SPOTIFY, "spotify-track-id")

    @Test
    fun `a match survives a restart`() {
        store().put(spotifySong, resolved("yt-1"))

        val reopened = store()

        assertEquals("yt-1", reopened[spotifySong]?.id)
        assertEquals(ProviderType.YOUTUBE_MUSIC, reopened[spotifySong]?.provider)
    }

    @Test
    fun `an unknown track has no match rather than a wrong one`() {
        assertNull(store()[reference(ProviderType.SPOTIFY, "never-seen")])
    }

    /** For when the wrong song was chosen: forgetting it is what lets the next play search again. */
    @Test
    fun `a match can be forgotten`() {
        val store = store()
        store.put(spotifySong, resolved("yt-1"))

        store.forget(spotifySong)

        assertNull(store[spotifySong])
        assertNull(store()[spotifySong])
    }

    /**
     * The file holds nothing that cannot be worked out again, so a damaged one is started over rather than
     * being allowed to stop these tracks from playing at all.
     */
    @Test
    fun `a damaged file is treated as an empty one`() {
        Files.writeString(file, "{ this is not json")

        val store = store()

        assertEquals(0, store.size())
        store.put(spotifySong, resolved("yt-1"))
        assertEquals("yt-1", store()[spotifySong]?.id)
    }

    @Test
    fun `nowhere to write is not a failure`() {
        val store = ReferenceMatchStore(path = null, spotifyPath = null)

        store.put(spotifySong, resolved("yt-1"))

        assertEquals("yt-1", store[spotifySong]?.id, "the match should still be held in memory")
    }

    /** A Deezer number and a Spotify id that happen to read the same are two songs, matched apart. */
    @Test
    fun `each service's songs are kept under their own key`() {
        val store = store()
        store.put(reference(ProviderType.DEEZER, "3135553"), resolved("yt-deezer"))
        store.put(reference(ProviderType.APPLE_MUSIC, "3135553"), resolved("yt-apple"))

        val reopened = store()

        assertEquals("yt-deezer", reopened[ProviderType.DEEZER, "3135553"]?.id)
        assertEquals("yt-apple", reopened[ProviderType.APPLE_MUSIC, "3135553"]?.id)
        assertNull(reopened[ProviderType.TIDAL, "3135553"])
        assertNull(reopened[ProviderType.SPOTIFY, "3135553"])
        val written = Files.readString(file)
        assertEquals(true, "\"DEEZER:3135553\"" in written && "\"APPLE_MUSIC:3135553\"" in written, written)
    }

    /** Matches made before the other services came along were kept by bare Spotify id, in a file of their own. */
    @Test
    fun `the Spotify matches kept before are still found`() {
        Files.createDirectories(spotifyFile.parent)
        Files.writeString(
            spotifyFile,
            """{"old-spotify-id":{"provider":"YOUTUBE_MUSIC","id":"yt-old","title":"Everlong","artists":[],"sourceUrl":"https://music.youtube.com/watch?v=yt-old"}}""",
        )

        val store = store()

        assertEquals("yt-old", store[reference(ProviderType.SPOTIFY, "old-spotify-id")]?.id)
        assertNull(store[reference(ProviderType.DEEZER, "old-spotify-id")], "a bare id is Spotify's and nobody else's")

        // Carried into the new file by the next match, and found there once the old one is gone.
        store.put(reference(ProviderType.DEEZER, "1"), resolved("yt-new"))
        Files.delete(spotifyFile)
        assertEquals("yt-old", store()[reference(ProviderType.SPOTIFY, "old-spotify-id")]?.id)
    }

    @Test
    fun `an old Spotify match forgotten stays forgotten`() {
        Files.createDirectories(spotifyFile.parent)
        Files.writeString(
            spotifyFile,
            """{"old-spotify-id":{"provider":"YOUTUBE_MUSIC","id":"yt-old","title":"Everlong","artists":[],"sourceUrl":"https://music.youtube.com/watch?v=yt-old"}}""",
        )
        val old = reference(ProviderType.SPOTIFY, "old-spotify-id")

        store().forget(old)

        assertNull(store()[old], "the old file was read again and brought the wrong song back")
    }

    /** A match to a recording from a service this build has never heard of costs that match alone. */
    @Test
    fun `a match this build cannot read is left out, and the rest kept`() {
        Files.writeString(
            file,
            """
            {
              "DEEZER:1": {"provider":"YOUTUBE_MUSIC","id":"yt-1","title":"One","artists":[],"sourceUrl":"https://music.youtube.com/watch?v=yt-1"},
              "DEEZER:2": {"provider":"SOME_LATER_SERVICE","id":"x-2","title":"Two","artists":[],"sourceUrl":"https://example.invalid/2"}
            }
            """.trimIndent(),
        )

        val store = store()

        assertEquals("yt-1", store[ProviderType.DEEZER, "1"]?.id)
        assertNull(store[ProviderType.DEEZER, "2"])
        assertEquals(1, store.size())
    }
}
