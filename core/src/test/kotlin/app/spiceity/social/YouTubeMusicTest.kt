package app.spiceity.social

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * YouTube Music is reached through the interface its own web player uses, with the identifiers that page
 * publishes and the signature Google's pages send in place of a bearer token.
 */
class YouTubeMusicTest {
    private val keys = InnertubeKeys("AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30", "1.20260825.00.00")
    private val session = YouTubeSession(keys, "SAPISID=abc123; HSID=x; SSID=y")

    @Test
    fun `the page's api key and client version are read from it`() {
        val page = """window.ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTest","INNERTUBE_CLIENT_VERSION":"1.2026.00"});"""

        val extracted = InnertubeKeyProvider.extractKeys(page)

        assertEquals("AIzaSyTest", extracted?.apiKey)
        assertEquals("1.2026.00", extracted?.clientVersion)
    }

    @Test
    fun `a page missing either identifier yields nothing`() {
        assertNull(InnertubeKeyProvider.extractKeys("""{"INNERTUBE_API_KEY":"only-a-key"}"""))
        assertNull(InnertubeKeyProvider.extractKeys("nothing useful here"))
    }

    // Google's own pages sign requests this way: SHA-1 over time, the SAPISID cookie and the origin.
    @Test
    fun `the request signature is a SHA-1 over time, cookie and origin`() {
        val hash = sapisidHash("abc123", "https://music.youtube.com", 1_700_000_000)

        assertTrue(hash.startsWith("SAPISIDHASH 1700000000_"))
        // Stable for the same inputs, so the value can be asserted rather than merely shaped.
        assertEquals(
            "SAPISIDHASH 1700000000_" +
                java.security.MessageDigest.getInstance("SHA-1")
                    .digest("1700000000 abc123 https://music.youtube.com".toByteArray())
                    .joinToString("") { "%02x".format(it) },
            hash,
        )
    }

    @Test
    fun `the signing cookie is found under either of the names Google uses`() {
        assertEquals("abc", sapisidFrom("SAPISID=abc; OTHER=1"))
        assertEquals("def", sapisidFrom("HSID=x; __Secure-3PAPISID=def"))
        assertNull(sapisidFrom("HSID=x; SSID=y"))
        assertNull(sapisidFrom(null))
    }

    @Test
    fun `liking posts to the like endpoint with the page's key`() = runBlocking {
        val http = RecordingYouTube(LikeHttpResponse(200, "{}"))

        val result = YouTubeMusicClient(http) { 1_700_000_000 }.setLiked("dQw4w9WgXcQ", true, session)

        assertEquals(LikeOutcome.LIKED, result.outcome)
        assertEquals(
            "POST https://music.youtube.com/youtubei/v1/like/like?key=${keys.apiKey}&prettyPrint=false",
            http.calls.single(),
        )
        val body = http.bodies.single().orEmpty()
        assertContains(body, """"videoId":"dQw4w9WgXcQ"""")
        assertContains(body, """"clientName":"WEB_REMIX"""")
        assertContains(body, """"clientVersion":"1.20260825.00.00"""")
    }

    @Test
    fun `unliking uses the opposite endpoint`() = runBlocking {
        val http = RecordingYouTube(LikeHttpResponse(200, "{}"))

        YouTubeMusicClient(http).setLiked("abc", false, session)

        assertContains(http.calls.single(), "/like/removelike?")
    }

    @Test
    fun `without a session cookie nothing is sent`() = runBlocking {
        val http = RecordingYouTube()

        val result = YouTubeMusicClient(http).setLiked("abc", true, YouTubeSession(keys, null))

        assertEquals(LikeOutcome.NEEDS_TOKEN, result.outcome)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `a refused session is named as such rather than as a generic failure`() = runBlocking {
        val rejected = YouTubeMusicClient(RecordingYouTube(LikeHttpResponse(401, "")))
            .setLiked("abc", true, session)

        assertEquals(LikeOutcome.TOKEN_REJECTED, rejected.outcome)
        assertContains(rejected.detail, "Sign in again")
    }

    // Innertube nests playlists inside renderers whose shape shifts, so they are gathered by identity.
    @Test
    fun `playlists are found wherever they sit in the response`() = runBlocking {
        val body = """
            {"contents":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"gridRenderer":{"items":[
                {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Late night"}]},
                 "navigationEndpoint":{"browseEndpoint":{"browseId":"VLPL123"}}}},
                {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Focus"}]},"playlistId":"PL456"}}
              ]}}
            ]}}}}]}}
        """.trimIndent()

        val playlists = YouTubeMusicClient(RecordingYouTube(LikeHttpResponse(200, body))).playlists(session)

        assertEquals(setOf("PL123", "PL456"), playlists.map { it.id }.toSet())
        assertContains(playlists.map { it.title }, "Late night")
        assertEquals("https://music.youtube.com/playlist?list=PL123", playlists.first().sourceUrl)
    }

    @Test
    fun `creating a playlist states its privacy and returns the new id`() = runBlocking {
        val http = RecordingYouTube(LikeHttpResponse(200, """{"playlistId":"PLnew"}"""))

        val result = YouTubeMusicClient(http).createPlaylist("Gym", listOf("v1", "v2"), false, session)

        assertTrue(result.ok)
        assertEquals("PLnew", result.playlistId)
        assertContains(http.calls.single(), "/playlist/create?")
        val body = http.bodies.single().orEmpty()
        assertContains(body, """"privacyStatus":"PRIVATE"""")
        assertContains(body, """"videoIds":["v1","v2"]""")
    }

    @Test
    fun `adding a track sends an action rather than a replacement list`() = runBlocking {
        val http = RecordingYouTube(LikeHttpResponse(200, "{}"))

        val result = YouTubeMusicClient(http).addToPlaylist("PL123", "vid9", session)

        assertTrue(result.ok)
        assertContains(http.calls.single(), "/browse/edit_playlist?")
        val body = http.bodies.single().orEmpty()
        assertContains(body, """"action":"ACTION_ADD_VIDEO"""")
        assertContains(body, """"addedVideoId":"vid9"""")
    }
}

private class RecordingYouTube(private vararg val replies: LikeHttpResponse) : LikeHttpClient {
    val calls = mutableListOf<String>()
    val bodies = mutableListOf<String?>()
    val tokens = mutableListOf<String>()
    val sentHeaders = mutableListOf<Map<String, String>>()

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
        tokens += token
        sentHeaders += headers
        return replies.getOrNull(index) ?: error("no scripted reply")
    }
}

/**
 * The seam the 401 lived in. The signature has to travel as a finished `Authorization` header, because the
 * shared client prefixes SoundCloud's `OAuth ` onto whatever it finds in the token slot — so putting the hash
 * there produced `OAuth SAPISIDHASH ...`, which YouTube refuses.
 */
class YouTubeRequestSigningTest {
    private val session = YouTubeSession(
        InnertubeKeys("AIzaSyTest", "1.20260825.00.00"),
        "SAPISID=abc123; HSID=x",
    )

    @Test
    fun `the signature travels in the headers and never in the OAuth token slot`() = runBlocking {
        val http = RecordingYouTube(LikeHttpResponse(200, "{}"))

        YouTubeMusicClient(http).setLiked("videoId", liked = true, session = session)

        assertEquals("", http.tokens.single())
        val authorization = http.sentHeaders.single().getValue("Authorization")
        assertTrue(authorization.startsWith("SAPISIDHASH "))
        assertFalse(authorization.contains("OAuth"))
    }

    /** YouTube checks the header against the origin the signature covers, so both must be sent together. */
    @Test
    fun `the origin the signature covers is sent alongside it`() = runBlocking {
        val http = RecordingYouTube(LikeHttpResponse(200, "{}"))

        YouTubeMusicClient(http).setLiked("videoId", liked = true, session = session)

        val sent = http.sentHeaders.single()
        assertEquals(MUSIC_ORIGIN, sent["Origin"])
        val stamp = sent.getValue("Authorization").removePrefix("SAPISIDHASH ").substringBefore('_').toLong()
        assertEquals(sapisidHash("abc123", MUSIC_ORIGIN, stamp), sent["Authorization"])
    }
}
