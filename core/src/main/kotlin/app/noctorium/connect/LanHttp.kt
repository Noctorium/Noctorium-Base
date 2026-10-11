package app.noctorium.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Plain HTTP/1.1 over a socket, for Connect's requests to another device on the local network and for
 * nothing else.
 *
 * Connect used to send these through OkHttp, like everything else in Noctorium, and on a phone not one of
 * them ever arrived. The phone's network security configuration allows plain HTTP to 127.0.0.1 only, so
 * that everything the application fetches from the internet stays https -- and OkHttp asks Android before
 * every plain request and refuses with "CLEARTEXT communication not permitted". A device on the same wifi
 * is reached at an address like 192.168.1.20, over plain HTTP, because there is no certificate anybody
 * could have for it. Discovery is UDP and was never asked, so the two devices saw each other and every
 * attempt to do anything about it failed.
 *
 * The configuration could not have been widened to fit: it names hosts, not networks, and allowing plain
 * HTTP everywhere would give up the https-only rule for every request the application makes in order to
 * let these few through. Android's rule is applied by HTTP libraries, not by sockets, so Connect talks
 * through a socket of its own. Nothing is lost by it: what makes these requests trustworthy was never the
 * transport but the signature each one carries, over its method, path, time, nonce and body -- see
 * [ConnectSignature] -- and the server on the other end is the same kind of hand-written HTTP as this.
 *
 * Only what Connect needs: one request per connection, a body with a stated length, and a reply read
 * whole. Addresses are taken as they come off the packet, IPv6 with its `%scope` included, and never put
 * through a URL parser, which is where a link-local address with a scope would have come apart.
 */
internal class LanHttp(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {
    data class Reply(val status: Int, val body: String, val failure: String? = null) {
        val ok: Boolean get() = status in 200..299
    }

    suspend fun send(
        address: String,
        port: Int,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
    ): Reply = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName(address), port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                socket.tcpNoDelay = true
                val payload = body.toByteArray(Charsets.UTF_8)
                val head = buildString {
                    append("$method $path HTTP/1.1\r\n")
                    append("Host: ${hostHeader(address, port)}\r\n")
                    for ((name, value) in headers) {
                        require(name.none { it == '\r' || it == '\n' || it == ':' } && value.none { it == '\r' || it == '\n' }) {
                            "A header cannot break a line: $name"
                        }
                        append("$name: $value\r\n")
                    }
                    if (method != "GET") {
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Content-Length: ${payload.size}\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                val output = socket.getOutputStream()
                output.write(head.toByteArray(Charsets.ISO_8859_1) + payload)
                output.flush()
                readReply(BufferedInputStream(socket.getInputStream()))
            }
        } catch (error: IOException) {
            Reply(UNREACHABLE, "", error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)
        } catch (error: IllegalArgumentException) {
            Reply(UNREACHABLE, "", error.message ?: "Not an address: $address")
        }
    }

    private fun readReply(input: InputStream): Reply {
        val head = readHead(input) ?: return Reply(UNREACHABLE, "", "the other device closed the connection without answering")
        val lines = head.split("\r\n").filter(String::isNotEmpty)
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
            ?: return Reply(UNREACHABLE, "", "the other device answered with something that is not HTTP")
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.take(colon).trim().lowercase() to line.drop(colon + 1).trim()
        }.toMap()

        val bytes = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            headers["content-length"] != null -> {
                val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 0..MAX_BODY_BYTES }
                    ?: return Reply(UNREACHABLE, "", "the other device's answer was too long")
                readExactly(input, length)
            }
            else -> readToEnd(input)
        } ?: return Reply(UNREACHABLE, "", "the other device stopped part-way through its answer")
        return Reply(status, String(bytes, Charsets.UTF_8))
    }

    private fun readHead(input: InputStream): String? {
        val buffer = StringBuilder()
        var last4 = 0
        while (buffer.length < MAX_HEAD_BYTES) {
            val next = input.read()
            if (next < 0) return null
            buffer.append(next.toChar())
            last4 = (last4 shl 8) or next
            if (last4 == CRLFCRLF) return buffer.toString()
        }
        return null
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val got = input.read(bytes, read, length - read)
            if (got < 0) return null
            read += got
        }
        return bytes
    }

    private fun readToEnd(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val got = input.read(chunk)
            if (got < 0) return out.toByteArray()
            out.write(chunk, 0, got)
            if (out.size() > MAX_BODY_BYTES) return null
        }
    }

    private fun readChunked(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: return null
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
            if (size == 0) {
                // Trailers, if any, up to the blank line that ends them.
                while (true) { if (readLine(input)?.isEmpty() != false) break }
                return out.toByteArray()
            }
            if (out.size() + size > MAX_BODY_BYTES) return null
            out.write(readExactly(input, size) ?: return null)
            readLine(input) ?: return null
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_HEAD_BYTES) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(next.toChar())
        }
        return null
    }

    /** An IPv6 literal goes in brackets, and without its scope, which means nothing to the other end. */
    private fun hostHeader(address: String, port: Int): String =
        if (':' in address) "[${address.substringBefore('%')}]:$port" else "$address:$port"

    companion object {
        /** No answer at all, as opposed to an answer that said no. The same meaning as `Http.UNREACHABLE`. */
        const val UNREACHABLE = 0

        /** Short, because these sit behind a tap on a device's name and a device that has left should say so quickly. */
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 4_000

        private const val CRLFCRLF = 0x0D0A0D0A
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
    }
}
