package app.noctorium.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Getting a YouTube Music sign-in onto a device and keeping it: pasted cookies, the phone-to-computer
 * transfer, and renewing a stale session. Nothing here talks to Google; a local server stands in for it
 * where one is needed.
 */
class YouTubeSignInTest {

    // --- Pasted cookies ---

    @Test
    fun `a request header, a cookies file and a cookie-editor export all read as the same cookies`() {
        val header = parsePastedCookies("Cookie: SAPISID=abc; SID=def; HSID=ghi")
        assertEquals(listOf("SAPISID", "SID", "HSID"), header.map { it.name })
        assertTrue(header.all { it.domain == ".youtube.com" && it.expiresEpochSeconds > Instant.now().epochSecond })

        val file = parsePastedCookies(
            "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t1893456000\tSAPISID\tabc\n" +
                "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1893456000\tSID\tdef\n",
        )
        assertEquals(listOf("SAPISID" to "abc", "SID" to "def"), file.map { it.name to it.value })

        val json = parsePastedCookies(
            """[{"domain":".youtube.com","name":"SAPISID","value":"abc","path":"/","secure":true,"expirationDate":1893456000.5},
               {"domain":".google.com","name":"SID","value":"g","path":"/"}]""",
        )
        assertEquals(listOf(".youtube.com", ".google.com"), json.map { it.domain })
        assertEquals(1893456000L, json[0].expiresEpochSeconds)
        assertTrue(isYouTubeSignedIn(json))
    }

    @Test
    fun `nothing pasted, or nothing like cookies, is nothing`() {
        assertTrue(parsePastedCookies("   ").isEmpty())
        assertTrue(parsePastedCookies("just some words").isEmpty())
        assertFalse(isYouTubeSignedIn(parsePastedCookies("HSID=x; SSID=y")), "no signing cookie, no sign-in")
    }

    @Test
    fun `cookies read from two places are one set, the later reading winning`() {
        val first = HarvestedCookie(".youtube.com", "/", "SID", "old", true, 0)
        val again = HarvestedCookie("youtube.com", "/", "SID", "new", true, 0)
        val google = HarvestedCookie(".google.com", "/", "SID", "g", true, 0)
        assertEquals(listOf("new", "g"), mergeCookies(listOf(first), listOf(again, google)).map { it.value })
    }

    // --- Phone to computer ---

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `a sealed session opens with its key and with no other`() {
        val sealed = SessionTransfer.seal("the session", key)
        assertEquals("the session", SessionTransfer.open(sealed, key))
        assertNull(SessionTransfer.open(sealed, ByteArray(32) { 7 }), "opened with the wrong key")
        val bytes = java.util.Base64.getDecoder().decode(sealed).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(SessionTransfer.open(java.util.Base64.getEncoder().encodeToString(bytes), key), "an altered session opened")
        assertTrue("the session" !in sealed, "the session travels readable")
    }

    @Test
    fun `the code in the QR carries the addresses, the port and the key, and nothing else reads as one`() {
        val invite = SessionTransfer.Invite(listOf("192.168.1.20", "10.0.0.5"), 51234, key)
        val parsed = assertNotNull(SessionTransfer.parseInvite(invite.code()))
        assertEquals(listOf("192.168.1.20", "10.0.0.5"), parsed.hosts)
        assertEquals(51234, parsed.port)
        assertTrue(parsed.key.contentEquals(key))
        assertNull(SessionTransfer.parseInvite("https://music.youtube.com/"))
        assertNull(SessionTransfer.parseInvite("noctorium-signin:1:192.168.1.20:51234:c2hvcnQ"), "a short key")
        assertNull(SessionTransfer.parseInvite("noctorium-signin:9:192.168.1.20:51234:" + invite.code().substringAfterLast(':')))
    }

    private val session = TransferredSession(
        cookies = listOf(TransferredCookie(".youtube.com", "/", "SAPISID", "abc", true, 1893456000)),
        authUser = 1,
        pageId = "109876543210",
        channelName = "Second's Band",
        fromDevice = "Phone",
    )

    @Test
    fun `a session sent to the code arrives whole`() = runBlocking {
        coroutineScope {
            var invite: SessionTransfer.Invite? = null
            val received = async { SessionTransfer.receive(timeoutMillis = 10_000, hosts = listOf("127.0.0.1")) { invite = it } }
            while (invite == null) delay(20)
            assertNull(SessionTransfer.send(invite!!, session))
            assertEquals(session, received.await())
        }
    }

    /** A stray connection, or a session sealed for another code, must not end the transfer in progress. */
    @Test
    fun `a session sealed with the wrong key is turned away and the real one still gets through`() = runBlocking {
        coroutineScope {
            var invite: SessionTransfer.Invite? = null
            val received = async { SessionTransfer.receive(timeoutMillis = 10_000, hosts = listOf("127.0.0.1")) { invite = it } }
            while (invite == null) delay(20)
            val forged = SessionTransfer.Invite(invite!!.hosts, invite!!.port, ByteArray(32) { 9 })
            assertNotNull(SessionTransfer.send(forged, session.copy(channelName = "Somebody else")))
            assertNull(SessionTransfer.send(invite!!, session))
            assertEquals("Second's Band", received.await()?.channelName)
        }
    }

    @Test
    fun `nothing arriving in time is null, and a computer that is not there says so`() = runBlocking {
        assertNull(SessionTransfer.receive(timeoutMillis = 300, hosts = listOf("127.0.0.1")) {})
        val nobody = SessionTransfer.Invite(listOf("127.0.0.1"), 9, key)
        assertTrue(SessionTransfer.send(nobody, session)!!.contains("same Wi-Fi"))
    }

    // --- Renewing a stale session ---

    private fun jar(vararg lines: String) = Files.createTempFile("cookies", ".txt").also { file ->
        Files.write(file, listOf("# Netscape HTTP Cookie File") + lines.toList())
    }

    @Test
    fun `a stale session is renewed from the Google cookies behind it, and the file keeps the new ones`() = runBlocking {
        MockWebServer().use { server ->
            // Google's sign-in passing the browser along, and setting fresh cookies as it goes.
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", server.url("/signin").toString())
                    .addHeader("Set-Cookie", "__Secure-1PSIDTS=fresh; Path=/; Max-Age=31536000"),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("signed in"))
            val host = server.hostName
            val file = jar(
                "$host\tFALSE\t/\tFALSE\t1893456000\t__Secure-1PSIDTS\tstale",
                ".google.com\tTRUE\t/\tTRUE\t1893456000\tSID\tgoogle-session",
            )
            try {
                assertTrue(YouTubeSessionRefresh(start = server.url("/ServiceLogin")).refresh(file))
                val kept = readCookieFile(file)
                assertEquals("fresh", kept.single { it.name == "__Secure-1PSIDTS" }.value)
                assertEquals("google-session", kept.single { it.name == "SID" }.value, "the Google session was lost")
                assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("__Secure-1PSIDTS=stale"))
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    @Test
    fun `with no Google cookies there is nothing to renew from, and nothing is asked`() = runBlocking {
        MockWebServer().use { server ->
            val file = jar(".youtube.com\tTRUE\t/\tTRUE\t1893456000\tSAPISID\tabc")
            try {
                assertFalse(YouTubeSessionRefresh(start = server.url("/ServiceLogin")).refresh(file))
                assertEquals(0, server.requestCount)
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    @Test
    fun `a trip that changes nothing writes nothing`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("sign-in form"))
            val file = jar(".google.com\tTRUE\t/\tTRUE\t1893456000\tSID\tg")
            val before = Files.readAllLines(file)
            try {
                assertFalse(YouTubeSessionRefresh(start = server.url("/ServiceLogin")).refresh(file))
                assertEquals(before, Files.readAllLines(file))
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }
}
