package app.noctorium.vk

import app.noctorium.net.HttpReply
import app.noctorium.playback.HlsRelay
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** VK's sign-in cookies, its songs' addresses, and the client's dealings with VK. */
class VkTest {
    private class Scripted(private val answer: (url: String, form: Map<String, String>) -> HttpReply) : VkHttp {
        val asked = mutableListOf<Pair<String, Map<String, String>>>()
        val times = mutableListOf<Long>()
        override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpReply {
            asked += url to form
            times += System.currentTimeMillis()
            return answer(url, form)
        }
    }

    private val okay = """{"type":"okay","data":{"access_token":"vk1.fresh","expires":4102444800,"user_id":42}}"""

    @Test
    fun `the two cookies are found in whatever was pasted`() {
        assertEquals(VkCookies("P1", "R1"), VkCookies.parse("remixsid=R1; p=P1"))
        assertEquals(VkCookies("P1", "R1"), VkCookies.parse("Cookie: p=P1; remixlang=0; remixsid=R1"))
        assertEquals(VkCookies("P1", "R1"), VkCookies.parse("p=P1\nremixsid=R1\n"))
        assertNull(VkCookies.parse("remixsid=R1"), "remixsid alone is not a session")
    }

    @Test
    fun `a song's address is its page, with its key out of sight`() {
        val audio = VkAudio(-2001, 123, "A", "T", 200, "", accessKey = "k9")
        val address = VkSource.of(audio)
        assertEquals("https://vk.ru/audio-2001_123#vk-access=k9", address)
        val source = VkSource.parse(address)!!
        assertEquals("-2001_123_k9", source.requestId)
        assertEquals("https://vk.ru/audio-2001_123", VkSource.page(address))
        assertEquals("371745461_456289486", VkSource.parse("https://vk.com/audio371745461_456289486")!!.requestId)
        assertNull(VkSource.parse("https://vk.ru/feed"))
    }

    @Test
    fun `the session is traded for a token, and cookies VK replaces are kept`() = runBlocking {
        var kept: VkCookies? = null
        val http = object : VkHttp {
            override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpReply {
                assertEquals("p=P1; remixsid=R1", headers["Cookie"])
                assertEquals("6287487", form["app_id"])
                return HttpReply(200, okay, mapOf("Set-Cookie" to listOf("remixsid=R2; path=/; secure")))
            }
        }
        val client = VkClient(http, { VkCookies("P1", "R1") }, { kept = it })
        val token = client.exchange()
        assertEquals("vk1.fresh", token.accessToken)
        assertEquals(42, token.userId)
        assertEquals(VkCookies("P1", "R2"), kept)
    }

    @Test
    fun `a session VK no longer knows means signing in again`() {
        val http = Scripted { _, _ -> HttpReply(200, """{"type":"error","error_info":"unauthorized"}""") }
        val client = VkClient(http, { VkCookies("P1", "R1") }, {})
        val failure = assertFailsWith<VkUnavailable> { runBlocking { client.exchange() } }
        assertTrue(failure.signedOut)
    }

    @Test
    fun `a token VK stops taking is renewed once from the cookies`() = runBlocking {
        var calls = 0
        val http = Scripted { url, _ ->
            when {
                "web_token" in url -> HttpReply(200, okay)
                "users.get" in url -> {
                    calls++
                    if (calls == 1) HttpReply(200, """{"error":{"error_code":5,"error_msg":"User authorization failed"}}""")
                    else HttpReply(200, """{"response":[{"first_name":"Ivan","last_name":"P"}]}""")
                }
                else -> HttpReply(404, "")
            }
        }
        val client = VkClient(http, { VkCookies("P1", "R1") }, {})
        assertEquals("Ivan P", client.profileName())
        assertEquals(2, http.asked.count { "web_token" in it.first })
    }

    @Test
    fun `requests are spaced the way one person's would be`() = runBlocking {
        val http = Scripted { url, _ ->
            if ("web_token" in url) HttpReply(200, okay) else HttpReply(200, """{"response":{"count":0,"items":[]}}""")
        }
        val client = VkClient(http, { VkCookies("P1", "R1") }, {})
        client.search("one")
        client.search("two")
        val apiTimes = http.asked.zip(http.times).filter { "audio.search" in it.first.first }.map { it.second }
        assertTrue(apiTimes[1] - apiTimes[0] >= VkClient.MIN_GAP_MS - 5, "two requests ${apiTimes[1] - apiTimes[0]} ms apart")
    }

    @Test
    fun `flood control is said out loud, not pressed against`() {
        val http = Scripted { url, _ ->
            if ("web_token" in url) HttpReply(200, okay) else HttpReply(200, """{"error":{"error_code":9,"error_msg":"Flood control"}}""")
        }
        val failure = assertFailsWith<VkUnavailable> { runBlocking { VkClient(http, { VkCookies("P", "R") }, {}).search("x") } }
        assertTrue(failure.message!!.contains("flood control"))
        assertEquals(1, http.asked.count { "audio.search" in it.first })
    }

    @Test
    fun `a song VK holds back here says so`() {
        val http = Scripted { url, _ ->
            if ("web_token" in url) HttpReply(200, okay)
            else HttpReply(200, """{"response":[{"id":1,"owner_id":2,"artist":"A","title":"T","duration":3,"url":"","content_restricted":6}]}""")
        }
        val provider = VkMusicProvider(VkClient(http, { VkCookies("P", "R") }, {}))
        val failure = assertFailsWith<VkUnavailable> { runBlocking { provider.streamFor("https://vk.ru/audio2_1") } }
        assertTrue(failure.message!!.contains("from where you are"))
    }

    @Test
    fun `a playable song is HLS to be decrypted, asked for by its full id`() = runBlocking {
        val http = Scripted { url, form ->
            if ("web_token" in url) HttpReply(200, okay)
            else {
                assertEquals("2_1_key", form["audios"])
                HttpReply(200, """{"response":[{"id":1,"owner_id":2,"artist":"A","title":"T","duration":3,"url":"https://cs1.vkuseraudio.ru/s/v1/ac/x/index.m3u8?siren=1"}]}""")
            }
        }
        val stream = VkMusicProvider(VkClient(http, { VkCookies("P", "R") }, {})).streamFor("https://vk.ru/audio2_1#vk-access=key")!!
        assertTrue(stream.isHls && stream.decrypt)
        assertTrue(stream.address.endsWith("index.m3u8?siren=1"))
        assertNull(VkMusicProvider(VkClient(http, { null }, {})).streamFor("https://youtube.com/watch?v=x"))
    }

    @Test
    fun `nobody signed in means no VK rows and no requests`() = runBlocking {
        val http = Scripted { _, _ -> error("should not be asked") }
        val provider = VkMusicProvider(VkClient(http, { null }, {}))
        assertTrue(provider.getHome().isEmpty())
        assertTrue(provider.getLibraryPlaylists().isEmpty())
        assertFalse(VkClient(http, { null }, {}).isSignedIn())
    }
}

/**
 * The relay against a real HLS stream served on this machine: segments alternating between AES-128 and
 * the clear, the way VK serves them, with the IV left out so the sequence number is used.
 */
class HlsRelayTest {
    @Test
    fun `every segment comes out in the clear, in order`() {
        val key = ByteArray(16) { (it * 7 + 3).toByte() }
        val plain = listOf(ByteArray(376) { 1 }, ByteArray(188) { 2 }, ByteArray(564) { 3 })
        val sequence = 5L
        // The first and third are encrypted, as VK does with roughly one in three.
        val served = plain.mapIndexed { index, bytes ->
            if (index % 2 == 0) encrypt(bytes, key, HlsRelay.sequenceIv(sequence + index)) else bytes
        }
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:$sequence
            #EXT-X-KEY:METHOD=AES-128,URI="key.pub?siren=1"
            #EXTINF:1.984,
            seg-0.ts?siren=1
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:3.994,
            seg-1.ts?siren=1
            #EXT-X-KEY:METHOD=AES-128,URI="key.pub?siren=1"
            #EXTINF:2.5,
            seg-2.ts?siren=1
            #EXT-X-ENDLIST
        """.trimIndent()

        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = when {
                path.endsWith("index.m3u8") -> playlist.toByteArray()
                path.endsWith("key.pub") -> key
                path.contains("seg-") -> served[path.substringAfter("seg-").substringBefore(".ts").toInt()]
                else -> ByteArray(0)
            }
            exchange.sendResponseHeaders(if (body.isEmpty()) 404 else 200, body.size.toLong().coerceAtLeast(-1))
            exchange.responseBody.use { it.write(body) }
        }
        upstream.start()
        val relay = HlsRelay()
        try {
            val local = relay.relay("http://127.0.0.1:${upstream.address.port}/s/v1/ac/x/index.m3u8?siren=1")
            val rewritten = String(get(local))
            assertFalse(rewritten.contains("EXT-X-KEY"), rewritten)
            assertEquals(3, rewritten.lines().count { it.startsWith("s/") })
            plain.forEachIndexed { index, expected ->
                assertContentEquals(expected, get(URI(local).resolve("s/$index").toString()), "segment $index")
            }
            // Anything without the secret is nobody's business.
            val bare = "http://127.0.0.1:${URI(local).port}/elsewhere/index.m3u8"
            assertNotNull(runCatching { get(bare) }.exceptionOrNull())
        } finally {
            relay.close()
            upstream.stop(0)
        }
    }

    @Test
    fun `the default IV is the sequence number, big-endian`() {
        val iv = HlsRelay.sequenceIv(258)
        assertEquals(16, iv.size)
        assertEquals(1, iv[14].toInt())
        assertEquals(2, iv[15].toInt())
        assertTrue(iv.take(14).all { it == 0.toByte() })
    }

    private fun encrypt(bytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(bytes)
        }

    private fun get(url: String): ByteArray {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            if (connection.responseCode != 200) throw java.io.IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
