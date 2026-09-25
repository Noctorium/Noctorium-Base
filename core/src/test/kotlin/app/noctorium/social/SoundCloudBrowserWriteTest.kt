package app.noctorium.social

import app.noctorium.net.BrowserReply
import app.noctorium.net.BrowserRequester
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which client makes a SoundCloud write.
 *
 * SoundCloud's bot protection refuses a like from anything that is not a browser, whatever the request
 * carries: Chrome's user agent, its client hints, its fetch metadata and the clearance cookie the sign-in
 * had already earned were all answered 403 with a captcha, from the phone and from a plain command line
 * alike. Reading likes over the same session, with none of that, is answered at once. So where a browser
 * exists the write goes through it, and these guard the handover rather than the browser itself.
 */
class SoundCloudBrowserWriteTest {
    private val token = "2-294451-1234567890-secret"
    private val clientId = "Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo"

    @Test
    fun `the write goes to the browser, and the ordinary client is never asked`() = runBlocking {
        val http = ScriptedHttp()
        val browser = ScriptedBrowser(BrowserReply(200, "\"OK\""))

        val result = SoundCloudLikeClient(http, browser)
            .setLiked("1612018959", "1234567890", token, clientId, liked = true)

        assertEquals(LikeOutcome.LIKED, result.outcome)
        assertEquals(
            "PUT https://api-v2.soundcloud.com/users/1234567890/track_likes/1612018959?client_id=$clientId",
            browser.calls.single(),
        )
        assertTrue(http.calls.isEmpty(), "the ordinary client can only be refused and must not be tried")
    }

    @Test
    fun `unliking goes the same way`() = runBlocking {
        val browser = ScriptedBrowser(BrowserReply(200, ""))

        val result = SoundCloudLikeClient(ScriptedHttp(), browser)
            .setLiked("1612018959", "1234567890", token, clientId, liked = false)

        assertEquals(LikeOutcome.UNLIKED, result.outcome)
        assertTrue(browser.calls.single().startsWith("DELETE "))
    }

    /**
     * The session and nothing else. Cookies, origin and referer are the browser's own, which is the whole
     * reason for going through it -- an assembled header is exactly what does not work here.
     */
    @Test
    fun `only the session is handed over, and the browser supplies the rest`() = runBlocking {
        val browser = ScriptedBrowser(BrowserReply(200, ""))

        SoundCloudLikeClient(ScriptedHttp(), browser)
            .setLiked("1", "2", token, clientId, liked = true)

        assertEquals(mapOf("Authorization" to "OAuth $token"), browser.headers.single())
    }

    /** A browser that answered has answered. Retrying without it could only do worse. */
    @Test
    fun `a refusal from the browser is the answer, not a reason to fall back`() = runBlocking {
        val http = ScriptedHttp(LikeHttpResponse(200, ""))
        val browser = ScriptedBrowser(BrowserReply(403, "captcha"))

        val result = SoundCloudLikeClient(http, browser)
            .setLiked("1", "2", token, clientId, liked = true)

        assertEquals(LikeOutcome.FAILED, result.outcome)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `a token the browser is told is stale is still reported as a stale token`() = runBlocking {
        val result = SoundCloudLikeClient(ScriptedHttp(), ScriptedBrowser(BrowserReply(401, "")))
            .setLiked("1", "2", token, clientId, liked = true)

        assertEquals(LikeOutcome.TOKEN_REJECTED, result.outcome)
    }

    /** Null is "there is no browser here", which is the desktop and every test that passes none. */
    @Test
    fun `no browser falls back to the ordinary client`() = runBlocking {
        val http = ScriptedHttp(LikeHttpResponse(200, ""))
        val browser = ScriptedBrowser(reply = null)

        val result = SoundCloudLikeClient(http, browser)
            .setLiked("1", "2", token, clientId, liked = true)

        assertEquals(LikeOutcome.LIKED, result.outcome)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun `a browser that throws falls back rather than losing the like`() = runBlocking {
        val http = ScriptedHttp(LikeHttpResponse(200, ""))
        val browser = BrowserRequester { _, _, _ -> error("the WebView was destroyed") }

        val result = SoundCloudLikeClient(http, browser)
            .setLiked("1", "2", token, clientId, liked = true)

        assertEquals(LikeOutcome.LIKED, result.outcome)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun `reading likes never goes near the browser`() = runBlocking {
        val browser = ScriptedBrowser(BrowserReply(200, ""))
        val http = ScriptedHttp(LikeHttpResponse(200, """{"collection":[1,2]}"""))

        val liked = SoundCloudLikeClient(http, browser).likedTrackIds("1234567890", token, clientId)

        assertEquals(setOf("1", "2"), liked.ids)
        assertTrue(browser.calls.isEmpty(), "reads are answered without it and cost a page load if sent there")
    }

    private class ScriptedBrowser(private val reply: BrowserReply?) : BrowserRequester {
        val calls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()

        override suspend fun send(
            method: String,
            url: String,
            headers: Map<String, String>,
        ): BrowserReply? {
            calls += "$method $url"
            this.headers += headers
            return reply
        }
    }

    private class ScriptedHttp(private vararg val replies: LikeHttpResponse) : LikeHttpClient {
        val calls = mutableListOf<String>()

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
            return replies.getOrNull(index) ?: error("no scripted reply")
        }
    }
}

/**
 * How a liked track is recognised again afterwards.
 *
 * The two backends name a SoundCloud track differently: yt-dlp gives the number, the phone's extractor
 * gives the `user/track` from the address, because that is what the page it reads carries. A liked set of
 * numbers alone therefore matched nothing on the phone, and the heart stayed empty on a track that had
 * just been liked successfully -- the write worked and looked as though it had not.
 */
class SoundCloudLikedNamesTest {

    private val likes = """
        {"collection":[
          {"kind":"like","track":{"id":181713573,"permalink_url":"https://soundcloud.com/tooore/burial-forgive"}}
        ]}
    """.trimIndent()

    @Test
    fun `a like is recorded under both the number and the address`() {
        val ids = SoundCloudLikeClient().parseLikedIds(likes)

        assertTrue("181713573" in ids, "the desktop identifies this track by its number")
        assertTrue("tooore/burial-forgive" in ids, "the phone identifies the same track by its address")
    }

    @Test
    fun `an address that is not a track contributes nothing`() {
        // A profile, not a track: one path segment rather than two.
        val profiles = """{"collection":[{"track":{"id":1,"permalink_url":"https://soundcloud.com/tooore"}}]}"""

        assertEquals(setOf("1"), SoundCloudLikeClient().parseLikedIds(profiles))
    }

    @Test
    fun `the older shapes still read as they did`() {
        assertEquals(setOf("10", "20"), SoundCloudLikeClient().parseLikedIds("[10,20]"))
        assertEquals(
            setOf("1", "2", "3"),
            SoundCloudLikeClient().parseLikedIds("""{"collection":[1,2,3]}"""),
        )
    }
}
