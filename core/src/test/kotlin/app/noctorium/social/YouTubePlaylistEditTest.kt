package app.noctorium.social

import app.noctorium.domain.PlaylistEdit
import app.noctorium.domain.PlaylistVisibility
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who can see one of the listener's YouTube Music playlists, and changing it with the name and description.
 *
 * The desktop could never make a YouTube playlist public or private: the button was only offered once the
 * service had said which it was, and nothing ever read that from YouTube. The playlist's own page says it, in
 * the edit form only the owner's view carries, and says by the same token whose the playlist is -- the
 * library lists playlists the listener merely saved beside their own.
 *
 * The fixtures are shaped like the replies ytmusicapi reads, in the page's current layout and in the one
 * before it, and every name, id and title in them is made up.
 */
class YouTubePlaylistEditTest {
    private val keys = InnertubeKeys("AIzaSyTest", "1.20260825.00.00")
    private val session = YouTubeSession(keys, "SAPISID=abc123; HSID=x")
    private val reader = YouTubeMusicClient(RecordingInnertube())

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/youtube-playlist/$name.json")) {
            "fixture $name missing from test resources"
        }.bufferedReader().use { it.readText() }

    /** The request body every edit opens with, for comparing a whole body rather than pieces of one. */
    private val context = """{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20260825.00.00","hl":"en","gl":"US"}}"""

    // --- Reading ---

    @Test
    fun `an own playlist says who can see it, in the page as it is laid out now`() {
        val private = reader.parsePlaylistDetails(fixture("own-private"))
        val public = reader.parsePlaylistDetails(fixture("own-public"))
        val unlisted = reader.parsePlaylistDetails(fixture("own-unlisted"))

        assertEquals(YouTubePlaylistDetails(true, PlaylistVisibility.PRIVATE, "Night Bus Home", "Songs for the last bus home,\nwindow seat, rain on the glass."), private)
        assertEquals(YouTubePlaylistDetails(true, PlaylistVisibility.PUBLIC, "Harbour Walks", "Saturday mornings, the long way round the harbour."), public)
        // No description on the page is an own playlist with none, which is not the same as not knowing.
        assertEquals(YouTubePlaylistDetails(true, PlaylistVisibility.UNLISTED, "Rehearsal Takes", ""), unlisted)
    }

    @Test
    fun `and in the page as it was laid out before, with the header at the top of the reply`() {
        val public = reader.parsePlaylistDetails(fixture("older-own-public"))
        val unlisted = reader.parsePlaylistDetails(fixture("older-own-unlisted"))
        val private = reader.parsePlaylistDetails(fixture("older-own-private"))

        assertEquals(YouTubePlaylistDetails(true, PlaylistVisibility.PUBLIC, "Kitchen Radio", "Whatever is on while the kettle boils."), public)
        assertEquals(YouTubePlaylistDetails(true, PlaylistVisibility.UNLISTED, "Demo Tapes", "For the band, not for the world."), unlisted)
        assertEquals(YouTubePlaylistDetails(true, PlaylistVisibility.PRIVATE, "Sleep Set", ""), private)
    }

    /** Somebody else's playlist has only the ordinary header, and none of its privacy or description is taken. */
    @Test
    fun `a playlist the listener only saved is not theirs, in either layout`() {
        assertEquals(YouTubePlaylistDetails(owned = false), reader.parsePlaylistDetails(fixture("saved")))
        assertEquals(YouTubePlaylistDetails(owned = false), reader.parsePlaylistDetails(fixture("older-saved")))
    }

    @Test
    fun `a reply with no header at all says nothing, rather than that the playlist is nobody's`() {
        val songsOnly = """{"contents":{"musicPlaylistShelfRenderer":{"contents":[]}}}"""

        assertNull(reader.parsePlaylistDetails(songsOnly))
        assertNull(reader.parsePlaylistDetails("not json"))
    }

    @Test
    fun `the songs and the details come from the one request that opens the playlist`() = runBlocking {
        val http = RecordingInnertube(LikeHttpResponse(200, fixture("own-unlisted")))

        val page = YouTubeMusicClient(http).playlistPage("PLrehearsalTakes0000000000000000aa", 5_000, session)

        assertEquals(1, http.calls.size)
        assertContains(http.bodies.single().orEmpty(), """"browseId":"VLPLrehearsalTakes0000000000000000aa"""")
        assertEquals(listOf("Slow Rivers (take 3)"), page?.tracks?.map { it.title })
        assertEquals(PlaylistVisibility.UNLISTED, page?.details?.visibility)
        assertEquals(true, page?.details?.owned)
    }

    @Test
    fun `reading the songs alone still answers with the songs`() = runBlocking {
        val http = RecordingInnertube(LikeHttpResponse(200, fixture("saved")))

        val tracks = YouTubeMusicClient(http).playlistTracks("PLtramStopClassics00000000000000aa", 5_000, session)

        assertEquals(listOf("nTlamplight"), tracks?.map { it.id })
    }

    // --- Writing ---

    @Test
    fun `one change sends one action and nothing about the rest`() = runBlocking {
        val http = RecordingInnertube(LikeHttpResponse(200, """{"status":"STATUS_SUCCEEDED"}"""))

        val result = YouTubeMusicClient(http).editPlaylist(
            "PLnightBusHome000000000000000000aa",
            PlaylistEdit(visibility = PlaylistVisibility.UNLISTED),
            session,
        )

        assertTrue(result.ok, result.detail)
        assertEquals("Playlist is now unlisted on YouTube Music.", result.detail)
        assertEquals(1, http.calls.size)
        assertContains(http.calls.single(), "/browse/edit_playlist?")
        assertEquals(
            """{"context":$context,"playlistId":"PLnightBusHome000000000000000000aa",""" +
                """"actions":[{"action":"ACTION_SET_PLAYLIST_PRIVACY","playlistPrivacy":"UNLISTED"}]}""",
            http.bodies.single(),
        )
    }

    @Test
    fun `several changes go in one request, an action each`() = runBlocking {
        val http = RecordingInnertube(LikeHttpResponse(200, """{"status":"STATUS_SUCCEEDED"}"""))

        val result = YouTubeMusicClient(http).editPlaylist(
            "VLPLnightBusHome000000000000000000aa",
            PlaylistEdit(
                title = "  Night Bus Home (rain)  ",
                description = "Window seat.\nLast stop.",
                visibility = PlaylistVisibility.PUBLIC,
            ),
            session,
        )

        assertTrue(result.ok, result.detail)
        assertEquals(1, http.calls.size)
        // Edited by the bare id, and the name trimmed of the spaces it was typed with.
        assertEquals(
            """{"context":$context,"playlistId":"PLnightBusHome000000000000000000aa","actions":[""" +
                """{"action":"ACTION_SET_PLAYLIST_NAME","playlistName":"Night Bus Home (rain)"},""" +
                """{"action":"ACTION_SET_PLAYLIST_DESCRIPTION","playlistDescription":"Window seat.\nLast stop."},""" +
                """{"action":"ACTION_SET_PLAYLIST_PRIVACY","playlistPrivacy":"PUBLIC"}]}""",
            http.bodies.single(),
        )
        assertEquals(
            "Saved on YouTube Music: renamed to \"Night Bus Home (rain)\", description saved, now public.",
            result.detail,
        )
    }

    @Test
    fun `a single change is described as it always was`() = runBlocking {
        val ok = LikeHttpResponse(200, """{"status":"STATUS_SUCCEEDED"}""")
        val client = YouTubeMusicClient(RecordingInnertube(ok, ok, ok, ok))

        assertEquals("Renamed to \"Late\" on YouTube Music.", client.renamePlaylist("PL1", "Late", session).detail)
        assertEquals("Playlist is now private on YouTube Music.", client.setPlaylistVisibility("PL1", false, session).detail)
        assertEquals("Description saved on YouTube Music.", client.editPlaylist("PL1", PlaylistEdit(description = "x"), session).detail)
        assertEquals("Description removed on YouTube Music.", client.editPlaylist("PL1", PlaylistEdit(description = ""), session).detail)
    }

    @Test
    fun `a blank name is turned down here, before anything is sent`() = runBlocking {
        val http = RecordingInnertube()

        val result = YouTubeMusicClient(http).editPlaylist("PL1", PlaylistEdit(title = "   ", visibility = PlaylistVisibility.PRIVATE), session)

        assertFalse(result.ok)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `without a sign-in nothing is sent and the listener is told where to sign in`() = runBlocking {
        val http = RecordingInnertube()

        val result = YouTubeMusicClient(http).editPlaylist(
            "PL1",
            PlaylistEdit(visibility = PlaylistVisibility.PUBLIC),
            YouTubeSession(keys, null),
        )

        assertFalse(result.ok)
        assertContains(result.detail, "Sign in")
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `a playlist that turns out not to be theirs is said plainly`() = runBlocking {
        val forbidden = LikeHttpResponse(
            403,
            """{"error":{"code":403,"message":"The caller does not have permission","status":"PERMISSION_DENIED"}}""",
        )

        val result = YouTubeMusicClient(RecordingInnertube(forbidden))
            .editPlaylist("PLtramStopClassics00000000000000aa", PlaylistEdit(visibility = PlaylistVisibility.PRIVATE), session)

        assertFalse(result.ok)
        assertContains(result.detail, "may not be yours")
        assertContains(result.detail, "HTTP 403")
        assertContains(result.detail, "The caller does not have permission")
    }

    @Test
    fun `any other refusal names the status`() = runBlocking {
        val result = YouTubeMusicClient(RecordingInnertube(LikeHttpResponse(500, "")))
            .editPlaylist("PL1", PlaylistEdit(title = "New"), session)

        assertFalse(result.ok)
        assertEquals("YouTube answered HTTP 500.", result.detail)
    }

    /** A 200 is not enough: the reply says in its own status whether anything was done. */
    @Test
    fun `a reply that says it did nothing is not taken for success`() = runBlocking {
        val result = YouTubeMusicClient(RecordingInnertube(LikeHttpResponse(200, """{"status":"STATUS_FAILED"}""")))
            .editPlaylist("PL1", PlaylistEdit(visibility = PlaylistVisibility.PUBLIC), session)

        assertFalse(result.ok)
        assertContains(result.detail, "did not make the change")
    }

    @Test
    fun `a reply with no status at all is taken at its word`() = runBlocking {
        val result = YouTubeMusicClient(RecordingInnertube(LikeHttpResponse(200, "{}")))
            .editPlaylist("PL1", PlaylistEdit(visibility = PlaylistVisibility.PUBLIC), session)

        assertTrue(result.ok)
    }

    @Test
    fun `a new playlist can be made unlisted from the start`() = runBlocking {
        val http = RecordingInnertube(LikeHttpResponse(200, """{"playlistId":"PLnew"}"""))

        val result = YouTubeMusicClient(http).createPlaylist("Demo Tapes", emptyList(), PlaylistVisibility.UNLISTED, session)

        assertTrue(result.ok)
        assertContains(http.bodies.single().orEmpty(), """"privacyStatus":"UNLISTED"""")
    }
}
