package app.spiceity.social

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The calls here mirror what SoundCloud's own bundle declares: `POST playlists`, `PUT playlists/:id` and
 * `DELETE playlists/:id`, with the body shaped as `{"playlist":{…}}`.
 */
class SoundCloudPlaylistTest {
    private val token = "2-294451-1184108872-secret"
    private val clientId = "Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo"

    @Test
    fun `creating posts a titled playlist and returns its new id`() = runBlocking {
        val http = RecordingPlaylistClient(LikeHttpResponse(201, """{"id":123456,"title":"Gym mix"}"""))

        val result = SoundCloudPlaylistClient(http)
            .create("Gym mix", listOf("111", "222"), isPublic = false, token = token, clientId = clientId)

        assertTrue(result.ok)
        assertEquals("123456", result.playlistId)
        assertEquals("POST https://api-v2.soundcloud.com/playlists?client_id=$clientId", http.calls.single())
        val body = http.bodies.single().orEmpty()
        assertContains(body, """"title":"Gym mix"""")
        assertContains(body, """"sharing":"private"""")
        // Track ids go as bare numbers, in order, the way the website sends them.
        assertContains(body, """"tracks":[111,222]""")
    }

    @Test
    fun `a public playlist says so`() = runBlocking {
        val http = RecordingPlaylistClient(LikeHttpResponse(201, """{"id":1}"""))

        SoundCloudPlaylistClient(http).create("Open", emptyList(), isPublic = true, token = token, clientId = clientId)

        assertContains(http.bodies.single().orEmpty(), """"sharing":"public"""")
    }

    @Test
    fun `an untitled playlist is refused before any request`() = runBlocking {
        val http = RecordingPlaylistClient()

        val result = SoundCloudPlaylistClient(http).create("   ", emptyList(), false, token, clientId)

        assertFalse(result.ok)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `setting tracks replaces the contents with a PUT`() = runBlocking {
        val http = RecordingPlaylistClient(LikeHttpResponse(200, "{}"))

        val result = SoundCloudPlaylistClient(http).setTracks("123456", listOf("7", "8", "9"), token, clientId)

        assertTrue(result.ok)
        assertEquals("PUT https://api-v2.soundcloud.com/playlists/123456?client_id=$clientId", http.calls.single())
        assertContains(http.bodies.single().orEmpty(), """"tracks":[7,8,9]""")
    }

    @Test
    fun `duplicate ids collapse so a track cannot appear twice`() = runBlocking {
        val http = RecordingPlaylistClient(LikeHttpResponse(200, "{}"))

        SoundCloudPlaylistClient(http).setTracks("1", listOf("5", "5", "6"), token, clientId)

        assertContains(http.bodies.single().orEmpty(), """"tracks":[5,6]""")
    }

    @Test
    fun `a playlist beyond SoundCloud's ceiling is refused rather than truncated`() = runBlocking {
        val http = RecordingPlaylistClient()

        val result = SoundCloudPlaylistClient(http)
            .setTracks("1", (1..501).map(Int::toString), token, clientId)

        assertFalse(result.ok)
        assertContains(result.detail, "500")
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `renaming sends only the new title`() = runBlocking {
        val http = RecordingPlaylistClient(LikeHttpResponse(200, "{}"))

        val result = SoundCloudPlaylistClient(http).rename("123456", "Better name", token, clientId)

        assertTrue(result.ok)
        assertTrue(http.calls.single().startsWith("PUT "))
        val body = http.bodies.single().orEmpty()
        assertContains(body, """"title":"Better name"""")
        assertFalse(body.contains("tracks"))
    }

    @Test
    fun `deleting uses DELETE and carries no body`() = runBlocking {
        val http = RecordingPlaylistClient(LikeHttpResponse(200, ""))

        val result = SoundCloudPlaylistClient(http).delete("123456", token, clientId)

        assertTrue(result.ok)
        assertEquals("DELETE https://api-v2.soundcloud.com/playlists/123456?client_id=$clientId", http.calls.single())
        assertEquals(null, http.bodies.single())
    }

    @Test
    fun `current contents are read in order, from either entry shape`() = runBlocking {
        val objects = SoundCloudPlaylistClient(
            RecordingPlaylistClient(LikeHttpResponse(200, """{"tracks":[{"id":3},{"id":1},{"id":2}]}""")),
        )
        assertEquals(listOf("3", "1", "2"), objects.trackIds("1", token, clientId))

        val bare = SoundCloudPlaylistClient(RecordingPlaylistClient(LikeHttpResponse(200, """{"tracks":[9,8]}""")))
        assertEquals(listOf("9", "8"), bare.trackIds("1", token, clientId))
    }

    @Test
    fun `refusals explain themselves by status`() = runBlocking {
        suspend fun attempt(status: Int) = SoundCloudPlaylistClient(RecordingPlaylistClient(LikeHttpResponse(status, "")))
            .delete("1", token, clientId)

        assertContains(attempt(401).detail, "401")
        assertContains(attempt(403).detail, "bot protection")
        assertContains(attempt(404).detail, "404")
        assertContains(attempt(422).detail, "422")
    }

    @Test
    fun `without a client id nothing is sent`() = runBlocking {
        val http = RecordingPlaylistClient()

        val result = SoundCloudPlaylistClient(http).create("Mix", emptyList(), false, token, null)

        assertFalse(result.ok)
        assertContains(result.detail, "client id")
        assertTrue(http.calls.isEmpty())
    }
}

private class RecordingPlaylistClient(private vararg val replies: LikeHttpResponse) : LikeHttpClient {
    val calls = mutableListOf<String>()
    val bodies = mutableListOf<String?>()

    override suspend fun send(
        method: String,
        url: String,
        token: String,
        cookies: String?,
        body: String?,
        headers: Map<String, String>,
    ): LikeHttpResponse {
        val index = calls.size
        calls += "$method $url"
        bodies += body
        return replies.getOrNull(index) ?: error("no scripted reply")
    }
}

class SoundCloudPlaylistVisibilityTest {
    private val token = "2-294451-1184108872-secret"
    private val clientId = "Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo"

    @Test
    fun `making a playlist public sends only the sharing field`() = runBlocking {
        val http = RecordingPlaylistClient(LikeHttpResponse(200, "{}"))

        val result = SoundCloudPlaylistClient(http).setVisibility("123", isPublic = true, token, clientId)

        assertTrue(result.ok)
        assertContains(result.detail, "public")
        assertEquals("PUT https://api-v2.soundcloud.com/playlists/123?client_id=$clientId", http.calls.single())
        val body = http.bodies.single().orEmpty()
        assertContains(body, """"sharing":"public"""")
        assertFalse(body.contains("tracks"))
        assertFalse(body.contains("title"))
    }

    @Test
    fun `making it private says so`() = runBlocking {
        val http = RecordingPlaylistClient(LikeHttpResponse(200, "{}"))

        val result = SoundCloudPlaylistClient(http).setVisibility("123", isPublic = false, token, clientId)

        assertContains(http.bodies.single().orEmpty(), """"sharing":"private"""")
        assertContains(result.detail, "private")
    }

    // Real fields from the account listing: SoundCloud reports both `public` and `sharing`.
    @Test
    fun `the listing reports each playlist's privacy`() = runBlocking {
        val body = """
            {"collection":[
              {"id":1926729563,"title":"Gym Playlist","public":true,"sharing":"public","track_count":35,
               "permalink_url":"https://soundcloud.com/yabosen/sets/gym","user":{"username":"Y A B O S E N"},
               "artwork_url":"https://i1.sndcdn.com/artworks-x-large.jpg"},
              {"id":42,"title":"Secret","public":false,"sharing":"private","track_count":3}
            ]}
        """.trimIndent()

        val playlists = SoundCloudPlaylistClient(RecordingPlaylistClient(LikeHttpResponse(200, body)))
            .list("1184108872", token, clientId)

        assertEquals(2, playlists.size)
        assertEquals(true, playlists.first().isPublic)
        assertEquals(35, playlists.first().trackCount)
        assertEquals("Y A B O S E N", playlists.first().ownerName)
        // The oversized original is swapped for the 500px variant, as everywhere else.
        assertEquals("https://i1.sndcdn.com/artworks-x-t500x500.jpg", playlists.first().artworkUrl)
        assertEquals(false, playlists.last().isPublic)
    }

    @Test
    fun `a listing entry with no title or id is skipped rather than shown broken`() = runBlocking {
        val body = """{"collection":[{"id":1},{"title":"No id"},{"id":2,"title":"Keeper","sharing":"private"}]}"""

        val playlists = SoundCloudPlaylistClient(RecordingPlaylistClient(LikeHttpResponse(200, body)))
            .list("1184108872", token, clientId)

        assertEquals(listOf("Keeper"), playlists.map { it.title })
        assertEquals(false, playlists.single().isPublic)
    }
}
