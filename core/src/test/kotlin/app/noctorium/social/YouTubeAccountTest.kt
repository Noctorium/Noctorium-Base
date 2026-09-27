package app.noctorium.social

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account side of YouTube Music: which account and channel requests act as, whether the session is
 * still signed in, and the calls behind history, following and playlist order.
 *
 * Every reply here is written out by hand in the shape YouTube answers with; nothing is sent anywhere.
 * [Scripted] answers by the endpoint a request was for and keeps what was asked, so each test can say both
 * what came back and what was sent.
 */
class YouTubeAccountTest {
    private val keys = InnertubeKeys("AIzaSyTest", "1.20260920.01.00")
    private val session = YouTubeSession(keys, "SAPISID=abc; SID=x")

    private class Request(val method: String, val url: String, val body: String?, val headers: Map<String, String>)

    private class Scripted(private val answers: List<Pair<String, String>>) : LikeHttpClient {
        val requests = mutableListOf<Request>()
        override suspend fun send(
            method: String,
            url: String,
            token: String,
            cookies: String?,
            body: String?,
            headers: Map<String, String>,
        ): LikeHttpResponse {
            requests += Request(method, url, body, headers)
            val answer = answers.firstOrNull { (fragment, _) -> fragment in url }?.second
            return if (answer != null) LikeHttpResponse(200, answer) else LikeHttpResponse(404, "")
        }
    }

    private fun client(vararg answers: Pair<String, String>) = Scripted(answers.toList()).let { it to YouTubeMusicClient(it) }

    // --- Which account ---

    @Test
    fun `requests act as the chosen Google account, and a brand channel is named in the body too`() {
        val headers = session.copy(authUser = 2, pageId = "112233").headers(1_700_000_000)
        assertEquals("2", headers["X-Goog-AuthUser"], "always the first account, whichever was chosen")
        assertEquals("112233", headers["X-Goog-PageId"])
        val context = YouTubeMusicClient().context(session.copy(pageId = "112233"))
        assertEquals("112233", context["user"]!!.jsonObject["onBehalfOfUser"]!!.jsonPrimitive.content)
        assertNull(YouTubeMusicClient().context(session)["user"], "the default channel is named nowhere")
    }

    /** A phone that signed in as Chrome on Android should not then ask as Chrome on Windows. */
    @Test
    fun `requests say what the browser that signed in said`() {
        val android = "Mozilla/5.0 (Linux; Android 14; 23078PND5G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        assertEquals(android, session.copy(userAgent = android).headers(1_700_000_000)["User-Agent"])
        assertNull(session.headers(1_700_000_000)["User-Agent"], "no identity of our own made up where none was kept")
    }

    /** Two Google accounts in one session, the second with a brand channel, as the avatar menu lists them. */
    private val switcher = """)]}'
        {"code":"SUCCESS","data":{"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[
          {"accountSectionListRenderer":{
            "header":{"googleAccountHeaderRenderer":{"email":{"runs":[{"text":"first@example.com"}]},"name":{"runs":[{"text":"First"}]}}},
            "contents":[{"accountItemSectionRenderer":{"contents":[
              {"accountItem":{"accountName":{"simpleText":"First"},"isSelected":true,
                "channelHandle":{"simpleText":"@first"},
                "accountPhoto":{"thumbnails":[{"url":"//yt3.ggpht.com/a=s48"},{"url":"https://yt3.ggpht.com/a=s88"}]},
                "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                  {"accountStateToken":{"hasChannel":true}},
                  {"accountSigninToken":{"signinUrl":"/signin?action_handle_signin=true&authuser=0&next=%2F"}}]}}}}]}}]}},
          {"accountSectionListRenderer":{
            "header":{"googleAccountHeaderRenderer":{"email":{"runs":[{"text":"second@example.com"}]}}},
            "contents":[{"accountItemSectionRenderer":{"contents":[
              {"accountItem":{"accountName":{"simpleText":"Second"},"isSelected":false,
                "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                  {"accountSigninToken":{"signinUrl":"/signin?action_handle_signin=true&authuser=1&next=%2F"}}]}}}},
              {"accountItem":{"accountName":{"simpleText":"Second's Band"},"isSelected":false,
                "channelHandle":{"simpleText":"@band"},
                "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                  {"pageIdToken":{"pageId":"109876543210"}},
                  {"accountSigninToken":{"signinUrl":"/signin?action_handle_signin=true&authuser=1&pageid=109876543210&next=%2F"}}]}}}}]}}]}}
        ]}}}}]}}
    """.trimIndent()

    @Test
    fun `every account and channel is listed, each with the account that owns it`() {
        val channels = YouTubeMusicClient().parseChannels(switcher)
        assertEquals(listOf("First", "Second", "Second's Band"), channels.map { it.name })
        assertEquals(listOf(0, 1, 1), channels.map { it.authUser })
        assertEquals(listOf("", "", "109876543210"), channels.map { it.pageId })
        assertEquals(listOf("first@example.com", "second@example.com", "second@example.com"), channels.map { it.email })
        assertEquals(listOf(true, false, false), channels.map { it.selected })
        assertEquals("https://yt3.ggpht.com/a=s88", channels[0].photoUrl, "the largest picture, with a scheme")
        assertEquals("@band", channels[2].handle)
        // Two default channels, one per account: both have a blank page id and must not collapse into one.
        assertEquals(3, channels.map { it.key }.toSet().size)
    }

    @Test
    fun `the listing comes from the account switcher, and accounts_list only when that says nothing`() = runBlocking {
        val (http, client) = client("getAccountSwitcherEndpoint" to switcher)
        assertEquals(3, client.channels(session).size)
        assertEquals("GET", http.requests.single().method)

        val (fallbackHttp, fallback) = client(
            "getAccountSwitcherEndpoint" to "not json",
            "account/accounts_list" to """{"contents":[{"accountItem":{"accountName":{"runs":[{"text":"Only"}]}}}]}""",
        )
        assertEquals(listOf("Only"), fallback.channels(session).map { it.name })
        assertEquals(2, fallbackHttp.requests.size)
    }

    // --- Still signed in? ---

    private fun menu(loggedIn: String?) = """
        {"responseContext":{"serviceTrackingParams":[
          {"service":"GFEEDBACK","params":[{"key":"browse_id","value":"x"}]},
          {"service":"CSI","params":[${loggedIn?.let { """{"key":"logged_in","value":"$it"}""" } ?: ""}]}]}}
    """.trimIndent()

    @Test
    fun `a stale session is answered 200 and read as signed out, from the logged_in flag`() = runBlocking {
        assertEquals(YouTubeSignIn.SIGNED_IN, client("account/account_menu" to menu("1")).second.signInState(session))
        assertEquals(YouTubeSignIn.SIGNED_OUT, client("account/account_menu" to menu("0")).second.signInState(session))
        assertEquals(YouTubeSignIn.UNKNOWN, client("account/account_menu" to menu(null)).second.signInState(session))
        assertEquals(YouTubeSignIn.UNKNOWN, client().second.signInState(session), "no answer is not the same as signed out")
        assertEquals(YouTubeSignIn.SIGNED_OUT, YouTubeMusicClient().signInState(YouTubeSession(keys, "HSID=x")))
    }

    // --- History ---

    @Test
    fun `a play is filed the way the player files one`() = runBlocking {
        val (http, client) = client(
            "youtubei/v1/player" to """{"playbackTracking":{
                "videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback?cl=1&docid=vid"},
                "videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?cl=1&docid=vid"}}}""",
            "api/stats/playback" to "",
            "api/stats/watchtime" to "",
        )
        assertTrue(client.recordPlay("vid", session, cpn = "abcdefghijklmnop"))
        val (player, playback, watchtime) = http.requests
        assertEquals("vid", Json.parseToJsonElement(player.body!!).jsonObject["videoId"]!!.jsonPrimitive.content)
        assertTrue(player.headers.containsKey("Authorization"), "asked as somebody signed out, which files nothing")
        assertTrue("ver=2" in playback.url && "c=WEB_REMIX" in playback.url && "cpn=abcdefghijklmnop" in playback.url, playback.url)
        assertTrue("st=0" in watchtime.url && "et=5.54" in watchtime.url && "cpn=abcdefghijklmnop" in watchtime.url, watchtime.url)
    }

    @Test
    fun `nothing is filed without a session or without the player's addresses`() = runBlocking {
        assertFalse(YouTubeMusicClient().recordPlay("vid", YouTubeSession(keys, "HSID=x")))
        val (http, client) = client("youtubei/v1/player" to "{}")
        assertFalse(client.recordPlay("vid", session))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `a playback nonce is sixteen URL-safe characters`() {
        val nonce = playbackNonce()
        assertEquals(16, nonce.length)
        assertTrue(nonce.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    // --- Following ---

    @Test
    fun `the artist is the one the byline links to, and following is read from the artist's page`() = runBlocking {
        val next = """{"contents":{"playlistPanelVideoRenderer":{"longBylineText":{"runs":[
            {"text":"Burial","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist",
              "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},
            {"text":" • "},
            {"text":"Untrue","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_album",
              "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}]}}}}"""
        val page = """{"header":{"musicImmersiveHeaderRenderer":{"subscriptionButton":{"subscribeButtonRenderer":{"subscribed":true,"channelId":"UCartist"}}}}}"""
        val (_, client) = client("youtubei/v1/next" to next, "youtubei/v1/browse" to page)
        assertEquals(YouTubeArtist("UCartist", "Burial", following = true), client.artistOf("vid", session))
    }

    @Test
    fun `following and stopping are YouTube's subscribe and unsubscribe`() = runBlocking {
        val (http, client) = client("subscription/subscribe" to "{}", "subscription/unsubscribe" to "{}")
        assertTrue(client.setFollowing("UCartist", follow = true, session).ok)
        assertTrue(client.setFollowing("UCartist", follow = false, session).ok)
        assertTrue(http.requests[0].url.contains("subscription/subscribe"))
        assertTrue(http.requests[1].url.contains("subscription/unsubscribe"))
        val ids = Json.parseToJsonElement(http.requests[0].body!!).jsonObject["channelIds"]!!.jsonArray
        assertEquals("UCartist", ids.single().jsonPrimitive.content)
    }

    // --- Playlist order ---

    private val playlistPage = """{"contents":[
        {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"a","playlistSetVideoId":"setA"}}},
        {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"b","playlistSetVideoId":"setB"}}},
        {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"c","playlistSetVideoId":"setC"}}},
        {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"d","playlistSetVideoId":"setD"}}}]}"""

    private suspend fun moveAction(from: Int, to: Int): Map<String, String> {
        val (http, client) = client("youtubei/v1/browse?" to playlistPage, "browse/edit_playlist" to "{}")
        assertTrue(client.movePlaylistItem("VLPLmine", from, to, session).ok)
        val edit = Json.parseToJsonElement(http.requests.last().body!!).jsonObject
        assertEquals("PLmine", edit["playlistId"]!!.jsonPrimitive.content, "edited by its bare id")
        return edit["actions"]!!.jsonArray.single().jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun `a song is moved before whatever will follow it in its new place`() = runBlocking {
        // Up: c from 2 to 0 goes before a.
        assertEquals(mapOf("action" to "ACTION_MOVE_VIDEO_BEFORE", "setVideoId" to "setC", "movedSetVideoIdSuccessor" to "setA"), moveAction(2, 0))
        // Down: a from 0 to 2 ends up after c, so before d.
        assertEquals(mapOf("action" to "ACTION_MOVE_VIDEO_BEFORE", "setVideoId" to "setA", "movedSetVideoIdSuccessor" to "setD"), moveAction(0, 2))
        // To the end there is nothing after it, and no successor is named.
        assertEquals(mapOf("action" to "ACTION_MOVE_VIDEO_BEFORE", "setVideoId" to "setB"), moveAction(1, 3))
    }
}
