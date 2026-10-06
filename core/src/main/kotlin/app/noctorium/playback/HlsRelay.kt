package app.noctorium.playback

import app.noctorium.net.Http
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A small HTTP server on this device that hands the player an HLS stream with its encryption already off.
 *
 * VK serves each song as HLS in which about one segment in three is AES-128 encrypted and the rest are
 * not, the key switching on and off down the playlist. A browser's hls.js and Android's player handle
 * that; ffmpeg -- and so mpv -- fetches the key, fails those segments and plays on without them, so a
 * four-minute song plays as three minutes with holes in it and nothing reported. Pointing mpv here
 * instead, every segment arrives in the clear, in order, and mpv has nothing to decrypt.
 *
 * Decryption is what HLS defines (RFC 8216, section 5.2): AES-128 in CBC mode with PKCS#7 padding, the key
 * from the playlist's key address, and the IV either given in the playlist or, as VK does, the segment's
 * media sequence number. It is the part of being an HLS player that mpv gets wrong here, and nothing more:
 * the stream is relayed as it is played and kept nowhere.
 *
 * Only this device can reach it -- it listens on the loopback address -- and only with the secret in its
 * addresses, so another program on the machine cannot use it to fetch anything.
 */
class HlsRelay(
    private val client: OkHttpClient = Http.shared,
    private val userAgent: String = DEFAULT_AGENT,
) : AutoCloseable {
    private val secret = randomToken(18)
    private val streams = ConcurrentHashMap<String, Stream>()
    private val keys = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var workers: ExecutorService? = null

    private class Stream(val upstream: String, val createdAt: Long) {
        @Volatile var playlist: Playlist? = null
    }

    internal data class Key(val uri: String, val iv: ByteArray?)

    internal class Segment(val uri: String, val durationText: String, val key: Key?, val sequence: Long)

    internal class Playlist(
        val targetDuration: String,
        val mediaSequence: Long,
        val segments: List<Segment>,
        val initUri: String?,
        val ended: Boolean,
    )

    /** A local address that plays [upstream] with its segments decrypted. Starts the server on first use. */
    @Synchronized
    fun relay(upstream: String): String {
        val socket = server ?: start()
        val now = System.currentTimeMillis()
        streams.entries.removeIf { now - it.value.createdAt > STREAM_LIFETIME_MS }
        val id = randomToken(9)
        streams[id] = Stream(upstream, now)
        return "http://127.0.0.1:${socket.localPort}/$secret/$id/index.m3u8"
    }

    @Synchronized
    override fun close() {
        runCatching { server?.close() }
        server = null
        workers?.shutdownNow()
        workers = null
        streams.clear()
        keys.clear()
    }

    private fun start(): ServerSocket {
        val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "noctorium-hls-relay").apply { isDaemon = true }
        }
        server = socket
        workers = pool
        Thread({
            while (!socket.isClosed) {
                val connection = try {
                    socket.accept()
                } catch (closed: IOException) {
                    break
                }
                pool.execute {
                    // A connection dropped half way -- the player stopped, or skipped ahead -- ends here quietly.
                    try { serve(connection) } catch (dropped: IOException) { Unit }
                }
            }
        }, "noctorium-hls-relay-accept").apply { isDaemon = true }.start()
        return socket
    }

    private fun serve(connection: Socket) = connection.use { socket ->
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        // The headers are read and ignored: nothing here depends on them.
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        val output = socket.getOutputStream()
        val answer = try {
            route(path)
        } catch (failure: Exception) {
            Answer(502, "text/plain", (failure.message ?: "upstream failure").toByteArray())
        }
        try {
            write(output, answer, headOnly = method == "HEAD")
        } catch (hungUp: IOException) {
            // The player stopped listening -- it was stopped, or skipped ahead -- which is no fault of anyone's.
        }
    }

    private class Answer(val status: Int, val type: String, val body: ByteArray)

    private fun route(path: String): Answer {
        val segments = path.trim('/').split('/')
        if (segments.size < 3 || segments[0] != secret) return Answer(404, "text/plain", ByteArray(0))
        val stream = streams[segments[1]] ?: return Answer(404, "text/plain", ByteArray(0))
        val playlist = stream.playlist ?: load(stream.upstream).also { stream.playlist = it }
        return when (segments[2]) {
            "index.m3u8" -> Answer(200, "application/vnd.apple.mpegurl", rewritten(playlist).toByteArray())
            "init" -> Answer(200, "video/mp4", fetch(playlist.initUri ?: return Answer(404, "text/plain", ByteArray(0))))
            "s" -> {
                val index = segments.getOrNull(3)?.toIntOrNull() ?: return Answer(404, "text/plain", ByteArray(0))
                val segment = playlist.segments.getOrNull(index) ?: return Answer(404, "text/plain", ByteArray(0))
                Answer(200, if (playlist.initUri != null) "video/mp4" else "video/mp2t", clear(segment))
            }
            else -> Answer(404, "text/plain", ByteArray(0))
        }
    }

    /** The playlist as the player sees it: the same segments, here, with no keys. */
    private fun rewritten(playlist: Playlist): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:${playlist.targetDuration}")
        appendLine("#EXT-X-MEDIA-SEQUENCE:${playlist.mediaSequence}")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        if (playlist.initUri != null) appendLine("#EXT-X-MAP:URI=\"init\"")
        playlist.segments.forEachIndexed { index, segment ->
            appendLine("#EXTINF:${segment.durationText},")
            appendLine("s/$index")
        }
        if (playlist.ended) appendLine("#EXT-X-ENDLIST")
    }

    /** A segment's bytes in the clear: decrypted where the playlist says it is encrypted. */
    private fun clear(segment: Segment): ByteArray {
        val bytes = fetch(segment.uri)
        val key = segment.key ?: return bytes
        val secretKey = keys.getOrPut(key.uri) { fetch(key.uri) }
        return decrypt(bytes, secretKey, key.iv ?: sequenceIv(segment.sequence))
    }

    private fun load(upstream: String): Playlist {
        val text = String(fetch(upstream), Charsets.UTF_8)
        // A master playlist names variants rather than segments; the first is followed.
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = text.lines().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: throw IOException("an HLS master playlist with no variants")
            return load(resolve(upstream, variant))
        }
        return parse(upstream, text)
    }

    private fun fetch(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for a stream part")
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun write(output: OutputStream, answer: Answer, headOnly: Boolean) {
        val reason = when (answer.status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Bad Gateway" }
        val head = "HTTP/1.1 ${answer.status} $reason\r\n" +
            "Content-Type: ${answer.type}\r\n" +
            "Content-Length: ${answer.body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        if (!headOnly) output.write(answer.body)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8_192) return null
            line.append(byte.toChar())
        }
    }

    internal companion object {
        const val DEFAULT_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:143.0) Gecko/20100101 Firefox/143.0"
        private const val STREAM_LIFETIME_MS = 6 * 60 * 60 * 1000L

        private val random = SecureRandom()

        private fun randomToken(bytes: Int): String {
            val buffer = ByteArray(bytes)
            random.nextBytes(buffer)
            return buffer.joinToString("") { "%02x".format(it) }
        }

        /** HLS's default IV: the media sequence number, big-endian, in sixteen bytes. */
        fun sequenceIv(sequence: Long): ByteArray = ByteArray(16).also { iv ->
            for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte()
        }

        fun decrypt(bytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(bytes)
        }

        private fun resolve(base: String, reference: String): String = URI(base).resolve(reference.trim()).toString()

        /** A media playlist: its segments, each with the key in force where it appears, in order. */
        internal fun parse(upstream: String, text: String): Playlist {
            var target = "10"
            var sequence = 0L
            var key: Key? = null
            var duration: String? = null
            var init: String? = null
            var ended = false
            val segments = mutableListOf<Segment>()
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim()
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                    line.startsWith("#EXT-X-KEY:") -> key = keyOf(upstream, line.substringAfter(':'))
                    line.startsWith("#EXT-X-MAP:") -> init = attribute(line.substringAfter(':'), "URI")?.let { resolve(upstream, it) }
                    line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').trim()
                    line.startsWith("#EXT-X-ENDLIST") -> ended = true
                    line.startsWith("#") -> Unit
                    else -> {
                        segments += Segment(resolve(upstream, line), duration ?: target, key, sequence + segments.size)
                        duration = null
                    }
                }
            }
            return Playlist(target, sequence, segments, init, ended)
        }

        private fun keyOf(upstream: String, attributes: String): Key? {
            val method = attribute(attributes, "METHOD") ?: return null
            if (method.equals("NONE", ignoreCase = true)) return null
            if (!method.equals("AES-128", ignoreCase = true)) throw IOException("HLS encryption $method is not AES-128")
            val uri = attribute(attributes, "URI") ?: throw IOException("an HLS key with no address")
            val iv = attribute(attributes, "IV")?.removePrefix("0x")?.removePrefix("0X")?.let { hex ->
                hex.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            return Key(resolve(upstream, uri), iv)
        }

        private fun attribute(attributes: String, name: String): String? =
            Regex("""(?:^|,)$name=("([^"]*)"|[^,]*)""").find(attributes)?.let { match ->
                match.groupValues[2].ifEmpty { match.groupValues[1] }
            }
    }
}
