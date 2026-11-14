package app.noctorium.remote

import app.noctorium.widgets.StreamWidgetServer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The remote control's web server: its page at `/`, and [RemoteControlApi] under `/api`.
 *
 * HTTP over a plain socket, written out by hand as the stream widgets' server is, and for its reason: `core` runs on
 * a phone too, where the JDK's own server does not exist. One request per connection.
 *
 * It can play and change things, so it is careful about who asks:
 * - It listens on 127.0.0.1 only. Nothing else on the network can reach it.
 * - A request has to name this computer as `localhost` or `127.0.0.1` in its `Host`, so that a web page whose own
 *   name was pointed at 127.0.0.1 is turned away by the name it gave.
 * - A POST from a browser carries an `Origin`; one from anywhere but this server's own page is refused, so no web page
 *   open in the browser can press the buttons. No CORS header is ever sent, so no other page can read the answers
 *   either. A script, curl or a Stream Deck sends no `Origin`, and is let in.
 */
class RemoteControlServer(
    private val api: RemoteControlApi,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var workers: ThreadPoolExecutor? = null
    @Volatile private var acceptor: Thread? = null
    @Volatile private var closed = false

    /** The port listened on, or 0 while nothing is. */
    val port: Int get() = server?.localPort ?: 0

    /** Starts listening on [port] of 127.0.0.1, after stopping whatever was running. Never throws. */
    @Synchronized
    fun start(port: Int): RemoteControlStatus {
        stop()
        if (closed) return RemoteControlStatus.Off
        val socket = ServerSocket()
        try {
            socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), BACKLOG)
        } catch (failure: Exception) {
            runCatching { socket.close() }
            log("Remote control could not listen on $port: ${failure.message}")
            return RemoteControlStatus.Failed(port, failureReason(port, failure))
        }
        val pool = ThreadPoolExecutor(0, MAX_WORKERS, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread(runnable, "noctorium-remote").apply { isDaemon = true }
        }
        server = socket
        workers = pool
        acceptor = Thread({ accept(socket, pool) }, "noctorium-remote-accept").apply { isDaemon = true }.also(Thread::start)
        log("Remote control listening on ${socket.localPort}")
        return RemoteControlStatus.Running(socket.localPort)
    }

    /** Stops listening, and returns once the port is free again: see [StreamWidgetServer.stop] for why it waits. */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptor?.takeIf { it !== Thread.currentThread() }?.join(STOP_WAIT_MS)
        acceptor = null
        workers?.shutdownNow()
        workers = null
    }

    @Synchronized
    override fun close() {
        closed = true
        stop()
    }

    private fun accept(socket: ServerSocket, pool: ThreadPoolExecutor) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (closed: IOException) {
                break
            }
            try {
                pool.execute { serve(connection) }
            } catch (busy: RejectedExecutionException) {
                runCatching { connection.close() }
            }
        }
    }

    private fun serve(connection: Socket) {
        try {
            connection.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val request = readRequest(input)
                val reply = if (request == null) RemoteReply.problem(400, "Bad request.") else answer(request)
                write(output, reply, headOnly = request?.method == "HEAD")
            }
        } catch (dropped: IOException) {
            // The page or the script went away mid-answer.
        } catch (failure: Exception) {
            log("Remote control request failed: $failure")
        }
    }

    internal class Request(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: String?,
        val tooLarge: Boolean = false,
    ) {
        val path: String get() = target.substringBefore('?').substringBefore('#')
        val query: Map<String, String>
            get() = target.substringAfter('?', "").substringBefore('#').split('&').mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts[0].isBlank()) null else decode(parts[0]) to decode(parts.getOrElse(1) { "" })
            }.toMap()
    }

    internal fun answer(request: Request): RemoteReply {
        if (!hostAllowed(request.headers["host"])) return RemoteReply.problem(403, "Open the remote control at http://localhost.")
        if (request.tooLarge) return RemoteReply.problem(413, "That is more than the remote control reads.")
        val path = request.path
        if (request.method == "OPTIONS") return RemoteReply.problem(405, "The remote control answers only its own page.")
        if (request.method == "POST" && !originAllowed(request.headers["origin"])) {
            return RemoteReply.problem(403, "Only the remote control's own page, or a program, can do that.")
        }
        return when {
            path == "/" || path == "/index.html" -> when (request.method) {
                "GET", "HEAD" -> RemoteControlPage.html()?.let { RemoteReply(200, it, "text/html; charset=utf-8") }
                    ?: RemoteReply.problem(500, "The remote control's page is missing from this build.")
                else -> RemoteReply.problem(405, "The page can only be read.")
            }
            path == "/api" || path.startsWith("/api/") -> api.answer(request.method, path, request.query, request.body)
            else -> RemoteReply.problem(404, "Nothing here. The remote control is at / and its API at /api.")
        }
    }

    private fun originAllowed(origin: String?): Boolean {
        if (origin == null) return true
        val port = port
        return origin == "http://localhost:$port" || origin == "http://$LOOPBACK:$port"
    }

    private fun readRequest(input: InputStream): Request? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n", "\n").filter(String::isNotEmpty)
        val requestLine = lines.firstOrNull()?.split(' ')?.filter(String::isNotEmpty) ?: return null
        if (requestLine.size < 2 || !requestLine[1].startsWith("/")) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.take(colon).trim().lowercase() to line.drop(colon + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toLongOrNull() ?: 0
        if (headers["transfer-encoding"] != null || length > MAX_BODY_BYTES) {
            return Request(requestLine[0].uppercase(), requestLine[1], headers, null, tooLarge = true)
        }
        val body = if (length > 0) {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var left = length.toInt()
            while (left > 0) {
                val got = input.read(buffer, 0, minOf(buffer.size, left))
                if (got < 0) break
                bytes.write(buffer, 0, got)
                left -= got
            }
            bytes.toString(Charsets.UTF_8)
        } else {
            null
        }
        return Request(requestLine[0].uppercase(), requestLine[1], headers, body)
    }

    private fun readHead(input: InputStream): String? {
        val buffer = StringBuilder()
        var last4 = 0
        while (buffer.length < MAX_HEAD_BYTES) {
            val next = input.read()
            if (next < 0) return null
            buffer.append(next.toChar())
            last4 = (last4 shl 8) or next
            if (last4 == CRLFCRLF || buffer.endsWith("\n\n")) return buffer.toString()
        }
        return null
    }

    private fun write(output: OutputStream, reply: RemoteReply, headOnly: Boolean) {
        val body = reply.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${reply.status} ${reason(reply.status)}\r\n")
            append("Content-Type: ${reply.type}\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            if (reply.type.startsWith("text/html")) append("Content-Security-Policy: $PAGE_POLICY\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) output.write(body)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        else -> "Error"
    }

    internal companion object {
        const val LOOPBACK = "127.0.0.1"

        /** The page's own scripts and styles, covers from wherever the services keep them, and nothing else. */
        const val PAGE_POLICY = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
            "img-src 'self' https: data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

        private const val CRLFCRLF = 0x0D0A0D0A
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val READ_TIMEOUT_MS = 5_000
        private const val BACKLOG = 32
        private const val STOP_WAIT_MS = 1_000L

        /** A page asking every half second, a few scripts, a search waiting on its answer: sixteen is plenty. */
        private const val MAX_WORKERS = 16

        fun failureReason(port: Int, failure: Throwable): String =
            StreamWidgetServer.failureReason(port, failure).replace("The stream widgets", "The remote control")

        /** Whether a request's `Host` is this computer by a name only this computer answers to. */
        fun hostAllowed(host: String?): Boolean {
            if (host.isNullOrBlank()) return true
            val name = host.trim().lowercase().let { full ->
                when {
                    full.startsWith("[") -> full.substringBefore(']') + "]"
                    full.count { it == ':' } == 1 -> full.substringBefore(':')
                    else -> full
                }
            }.trimEnd('.')
            return name == "localhost" || name == LOOPBACK || name == "[::1]" || name.endsWith(".localhost")
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}

/** The controller page, read once from the resources. */
internal object RemoteControlPage {
    private val page: String? by lazy {
        RemoteControlPage::class.java.getResourceAsStream("/app/noctorium/remote/controller.html")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun html(): String? = page
}
