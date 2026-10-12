package app.noctorium.core

import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.Playlist
import app.noctorium.domain.PlaylistEdit
import app.noctorium.domain.PlaylistVisibility
import app.noctorium.domain.ProviderType
import app.noctorium.domain.editableOnService
import app.noctorium.domain.shownVisibility
import app.noctorium.settings.AppDirectories
import app.noctorium.settings.CookieSource
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.social.InnertubeKeyProvider
import app.noctorium.social.LikeHttpClient
import app.noctorium.social.LikeHttpResponse
import app.noctorium.social.YouTubeMusicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One of the listener's YouTube playlists, opened and edited from the application, against a YouTube made up
 * here: the page that lists its songs, the library listing, and the edit endpoint, each answering from fixtures.
 */
class YouTubePlaylistEditStateTest {
    private val id = "PLnightBusHome000000000000000000aa"
    private val listed = Playlist(id, "Night Bus Home", ProviderType.YOUTUBE_MUSIC, sourceUrl = "https://music.youtube.com/playlist?list=$id")

    /**
     * YouTube Music as far as these tests go, answered by endpoint and by what was browsed. The library
     * listing waits for [listing] to open, so a test can look at the screen between an edit and the reload
     * that follows it.
     */
    private class MadeUpYouTube(private val page: String, private val editReply: LikeHttpResponse) : LikeHttpClient {
        val edits = CopyOnWriteArrayList<String>()
        val listing = CountDownLatch(1)
        @Volatile private var listedTitle = "Night Bus Home"

        override suspend fun send(
            method: String,
            url: String,
            token: String,
            cookies: String?,
            body: String?,
            headers: Map<String, String>,
        ): LikeHttpResponse = when {
            "/browse/edit_playlist?" in url -> editReply.also {
                edits += body.orEmpty()
                if (it.status == 200) NAME.find(body.orEmpty())?.let { name -> listedTitle = name.groupValues[1] }
            }
            "/browse?" in url && "FEmusic_liked_playlists" in body.orEmpty() -> {
                withContext(Dispatchers.IO) { listing.await(5, TimeUnit.SECONDS) }
                // The library as YouTube lists it: a name and an id, and nothing about who can see it.
                LikeHttpResponse(
                    200,
                    """{"contents":{"gridRenderer":{"items":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$listedTitle"}]},""" +
                        """"navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLnightBusHome000000000000000000aa"}}}}]}}}""",
                )
            }
            "/browse?" in url && "\"browseId\":\"VL" in body.orEmpty() -> LikeHttpResponse(200, page)
            else -> LikeHttpResponse(404, "")
        }

        private companion object {
            val NAME = Regex(""""playlistName":"([^"]*)"""")
        }
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/youtube-playlist/$name.json")).bufferedReader().use { it.readText() }

    /** A state signed in to the made-up YouTube with a made-up cookie, and the application's own providers. */
    private fun state(youTube: MadeUpYouTube): AppState {
        val folder = AppDirectories.resolve("harness-settings") ?: error("No folder for the test's cookies")
        Files.createDirectories(folder)
        val cookies = Files.createTempFile(folder, "youtube", ".cookies")
        Files.writeString(cookies, "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\tmadeUpSignature\n")
        return AppStateHarness.state(
            preferences = NoctoriumPreferences(autoplay = false, youtubeCookies = CookieSource(cookieFile = cookies.toString())),
            providers = null,
            youTubeMusic = YouTubeMusicClient(youTube),
            innertubeKeys = InnertubeKeyProvider { """{"INNERTUBE_API_KEY":"AIzaSyTest","INNERTUBE_CLIENT_VERSION":"1.20260825.00.00"}""" },
        )
    }

    @Test
    fun `opening an own playlist shows who can see it, and offers it for editing`() {
        state(MadeUpYouTube(fixture("own-private"), LikeHttpResponse(200, ""))).use { state ->
            state.openPlaylist(listed)

            waitFor("the playlist to load") { !state.library.value.openPlaylistLoading }
            val open = state.library.value.openPlaylist
            assertEquals(PlaylistVisibility.PRIVATE, open?.shownVisibility)
            assertEquals(false, open?.isPublic)
            assertEquals("Songs for the last bus home,\nwindow seat, rain on the glass.", open?.description)
            assertEquals(true, open?.ownedByListener)
            assertEquals(2, open?.tracks?.size)
            assertTrue(open!!.editableOnService())
        }
    }

    @Test
    fun `a playlist the listener only saved stops being offered for editing once it is opened`() {
        val saved = listed.copy(
            id = "PLtramStopClassics00000000000000aa",
            title = "Tram Stop Classics",
            sourceUrl = "https://music.youtube.com/playlist?list=PLtramStopClassics00000000000000aa",
        )
        state(MadeUpYouTube(fixture("saved"), LikeHttpResponse(200, ""))).use { state ->
            state.openPlaylist(saved)

            waitFor("the playlist to load") { !state.library.value.openPlaylistLoading }
            val open = state.library.value.openPlaylist
            assertEquals(false, open?.ownedByListener)
            assertFalse(open!!.editableOnService())
            assertNull(open.shownVisibility)
        }
    }

    @Test
    fun `an edit YouTube takes shows at once, and stays through the reload that follows`() {
        val youTube = MadeUpYouTube(fixture("own-private"), LikeHttpResponse(200, """{"status":"STATUS_SUCCEEDED"}"""))
        state(youTube).use { state ->
            state.openPlaylist(listed)
            waitFor("the playlist to load") { state.library.value.openPlaylist?.ownedByListener == true }

            state.editYouTubePlaylist(id, PlaylistEdit(title = "Night Bus Home (rain)", visibility = PlaylistVisibility.UNLISTED))

            // Before the reload: the screen already says what YouTube took.
            waitFor("the change to show") { state.library.value.openPlaylist?.title == "Night Bus Home (rain)" }
            assertEquals(1, youTube.edits.size)
            val open = state.library.value.openPlaylist
            assertEquals(PlaylistVisibility.UNLISTED, open?.shownVisibility)
            assertNull(open?.isPublic)
            assertEquals("Saved on YouTube Music: renamed to \"Night Bus Home (rain)\", now unlisted.", state.library.value.notice)
            assertFalse(state.library.value.noticeIsProblem)

            // The listing that follows every write cannot say who can see a playlist. It must not take the
            // answer away again, nor the controls with it.
            youTube.listing.countDown()
            waitFor("the library to reload") { state.library.value.playlists.isNotEmpty() && !state.library.value.loading }
            val reloaded = state.library.value.openPlaylist
            assertEquals("Night Bus Home (rain)", reloaded?.title)
            assertEquals(PlaylistVisibility.UNLISTED, reloaded?.shownVisibility)
            assertEquals(true, reloaded?.ownedByListener)
            assertEquals(2, reloaded?.tracks?.size)
            assertEquals(PlaylistVisibility.UNLISTED, state.library.value.playlists.single().shownVisibility)
        }
    }

    @Test
    fun `an edit YouTube turns down leaves the playlist as it was, and says why`() {
        val refused = LikeHttpResponse(403, """{"error":{"code":403,"message":"The caller does not have permission"}}""")
        state(MadeUpYouTube(fixture("own-private"), refused)).use { state ->
            state.openPlaylist(listed)
            waitFor("the playlist to load") { state.library.value.openPlaylist?.ownedByListener == true }

            state.editYouTubePlaylist(id, PlaylistEdit(title = "Somebody else's now", visibility = PlaylistVisibility.PUBLIC))

            waitFor("YouTube's answer") { state.library.value.notice != null }
            val library = state.library.value
            assertTrue(library.noticeIsProblem)
            assertContains(library.notice.orEmpty(), "HTTP 403")
            assertEquals("Night Bus Home", library.openPlaylist?.title)
            assertEquals(PlaylistVisibility.PRIVATE, library.openPlaylist?.shownVisibility)
        }
    }

    @Test
    fun `an edit that changes nothing sends nothing`() {
        val youTube = MadeUpYouTube(fixture("own-private"), LikeHttpResponse(200, """{"status":"STATUS_SUCCEEDED"}"""))
        state(youTube).use { state ->
            state.editYouTubePlaylist(id, PlaylistEdit())

            waitFor("the notice") { state.library.value.notice != null }
            assertTrue(youTube.edits.isEmpty())
        }
    }
}
