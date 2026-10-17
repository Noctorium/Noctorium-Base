package app.noctorium.widgets

import app.noctorium.domain.Track
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Whether the stream widgets are being served, and where, for Settings to say.
 *
 * [Running.address] is this computer's own address for them, which is what goes into a streaming program on the same
 * computer; [Running.networkAddress] is the one another computer on the network uses, when the listener allowed that
 * and there is a network to be on. [Failed.reason] is a sentence to show as it is.
 */
sealed interface StreamWidgetStatus {
    data object Off : StreamWidgetStatus

    data class Starting(val port: Int) : StreamWidgetStatus

    data class Running(
        val port: Int,
        val address: String,
        val networkAddress: String? = null,
    ) : StreamWidgetStatus {
        /** The address to give a browser source for [theme] on this computer. */
        fun url(theme: StreamWidgetTheme): String = "$address/widget/${theme.id}"

        /** The same from another computer on the network; null unless that is allowed and this one is on a network. */
        fun networkUrl(theme: StreamWidgetTheme): String? = networkAddress?.let { "$it/widget/${theme.id}" }
    }

    data class Failed(val port: Int, val reason: String) : StreamWidgetStatus
}

/**
 * The stream widgets' web server: the pages a streaming program shows as a browser source, and what they read.
 *
 * HTTP over a plain socket, written out by hand as Connect's server and the VK relay are, and for their reason:
 * `core` runs on a phone too, and the JDK's own server does not exist on one. Less of HTTP is needed here than
 * either of them needs. Everything is read and nothing is changed, so GET and HEAD are answered and every other
 * method is refused; one request per connection; no request body is ever read.
 *
 * - `/widget/<look>` is a widget's page ([StreamWidgetTheme.id]); `/widget` and `/` are the look chosen in Settings.
 *   A page's own address can say otherwise than Settings for that page alone -- `?cover=0`, `?progress=0`,
 *   `?logo=0`, `?hidePaused=0`, `?hideAfter=10`, `?accent=ff3366` -- which is how several widgets, in several looks,
 *   run on one stream at once.
 * - `/api/now-playing` is what is playing, as [NowPlayingFeed] writes it, which a page reads about once a second.
 *   Server-sent events would save the asking, but they hold a connection and a thread open for each widget for as
 *   long as the stream runs, and once a second from one or two pages on the same computer is nothing.
 * - `/api/artwork` is the song's cover, through [WidgetArtwork].
 *
 * Every answer says `Cache-Control: no-store`, since everything here changes from one second to the next, and none
 * carries any CORS header: a streaming program loads the page from this server, so the page reads from its own
 * origin, and a page anywhere else on the web is meant to be unable to.
 *
 * Bound to 127.0.0.1 unless the listener allowed the network, and then to every address. Either way a request has
 * to name this computer as an address, as localhost, or as a name on the local network: a page elsewhere on the web
 * that pointed a name of its own at 127.0.0.1 to read what somebody is playing is turned away by the name it gave.
 */
class StreamWidgetServer(
    private val playback: () -> WidgetPlayback,
    private val preferences: () -> StreamWidgetPreferences,
    private val accent: () -> WidgetAccent = { WidgetAccent(DEFAULT_ACCENT) },
    private val artwork: WidgetArtwork = WidgetArtwork(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** This computer's address on the network, for [StreamWidgetStatus.Running.networkAddress]. */
    private val networkAddress: () -> String? = ::lanAddress,
) : AutoCloseable {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var workers: ThreadPoolExecutor? = null
    @Volatile private var acceptor: Thread? = null
    @Volatile private var closed = false

    /** The covers of the songs lately reported, by the key a page asks for them with. */
    private val covers = object : LinkedHashMap<String, String>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > WidgetArtwork.KEPT
    }

    /** A private address of this computer, and the adapter it is on, for choosing which to tell another computer. */
    internal data class NetworkCandidate(val adapter: String, val address: String)

    /** The port listened on, or 0 while nothing is. */
    val port: Int get() = server?.localPort ?: 0

    /** The address listened on, or null while nothing is: 127.0.0.1, or every address when the network is allowed. */
    internal val listeningOn: InetAddress? get() = server?.inetAddress

    /**
     * Starts listening on [port] -- 127.0.0.1 only, unless [allowNetwork] -- after stopping whatever was running.
     *
     * Binding is quick but is still a call into the system, so this is called off the main thread; it never throws,
     * and a port that cannot be had comes back as [StreamWidgetStatus.Failed] with what to do about it.
     */
    @Synchronized
    fun start(port: Int, allowNetwork: Boolean): StreamWidgetStatus {
        stop()
        // A start that was on its way while Noctorium closed must not leave a server behind it.
        if (closed) return StreamWidgetStatus.Off
        val bindTo = if (allowNetwork) InetSocketAddress(port) else InetSocketAddress(InetAddress.getByName(LOOPBACK), port)
        val socket = ServerSocket()
        try {
            socket.bind(bindTo, BACKLOG)
        } catch (failure: Exception) {
            runCatching { socket.close() }
            log("Stream widgets could not listen on $port: ${failure.message}")
            return StreamWidgetStatus.Failed(port, failureReason(port, failure))
        }
        val pool = ThreadPoolExecutor(0, MAX_WORKERS, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread(runnable, "noctorium-stream-widgets").apply { isDaemon = true }
        }
        server = socket
        workers = pool
        acceptor = Thread({ accept(socket, pool) }, "noctorium-stream-widgets-accept").apply { isDaemon = true }.also(Thread::start)
        log("Stream widgets listening on ${socket.localPort}${if (allowNetwork) " for the network" else ""}")
        return StreamWidgetStatus.Running(
            port = socket.localPort,
            address = "http://$LOOPBACK:${socket.localPort}",
            networkAddress = if (allowNetwork) networkAddress()?.let { "http://$it:${socket.localPort}" } else null,
        )
    }

    /**
     * Stops listening, and returns once the port is free again.
     *
     * On Linux, closing a socket that another thread is waiting on leaves the last of the close to that thread: the
     * port went on taking connections for some 25 ms, and a [start] straight after -- the network allowed or taken
     * back, on the same port -- was refused it as in use. Waiting for the thread that accepts makes stopping mean
     * stopped there as it already does on Windows and macOS. That thread takes no lock of this server, so waiting for
     * it here cannot hold it up.
     */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptor?.takeIf { it !== Thread.currentThread() }?.join(STOP_WAIT_MS)
        acceptor = null
        workers?.shutdownNow()
        workers = null
    }

    /** Stops for good: a [start] after this does nothing. */
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
                // More widgets asking at once than there are threads for, or the server stopping: this one is dropped,
                // and the page asks again in a second.
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
                val reply = if (request == null) Reply.text(400, "Bad request.") else answer(request)
                write(output, reply, headOnly = request?.method == "HEAD")
                if (request != null && request.hasBody) discardTheRest(socket, input)
            }
        } catch (dropped: IOException) {
            // The page went away mid-answer -- the source was hidden, or OBS closed -- which is nobody's fault.
        } catch (failure: Exception) {
            log("Stream widget request failed: $failure")
        }
    }

    private class Request(val method: String, val target: String, val host: String?, val hasBody: Boolean) {
        val path: String get() = target.substringBefore('?').substringBefore('#')

        val query: Map<String, String>
            get() = target.substringAfter('?', "").substringBefore('#').split('&').mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts[0].isBlank()) null else parts[0] to decode(parts.getOrElse(1) { "" })
            }.toMap()
    }

    internal class Reply(
        val status: Int,
        val type: String,
        val body: ByteArray,
        val headers: List<String> = emptyList(),
    ) {
        companion object {
            fun text(status: Int, message: String, headers: List<String> = emptyList()) =
                Reply(status, "text/plain; charset=utf-8", message.toByteArray(Charsets.UTF_8), headers)
        }
    }

    private fun answer(request: Request): Reply {
        if (request.method != "GET" && request.method != "HEAD") {
            return Reply.text(405, "The stream widgets can only be read.", listOf("Allow: GET, HEAD"))
        }
        if (!hostAllowed(request.host)) return Reply.text(403, "Open the widgets from this computer's own address.")
        val path = request.path
        return when {
            path == "/" || path == "/widget" || path == "/widget/" -> page(preferences().theme)
            path.startsWith("/widget/") ->
                StreamWidgetTheme.byId(path.removePrefix("/widget/").trimEnd('/'))?.let(::page) ?: notFound()
            path == "/api/now-playing" -> nowPlaying()
            path == "/api/artwork" -> artwork(request.query["k"])
            else -> notFound()
        }
    }

    private fun page(theme: StreamWidgetTheme): Reply {
        val html = StreamWidgetPages.page(theme) ?: return notFound()
        return Reply(200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8), listOf("Content-Security-Policy: $PAGE_POLICY"))
    }

    private fun nowPlaying(): Reply {
        val json = NowPlayingFeed.json(
            playback = playback(),
            preferences = preferences(),
            accent = accent(),
            serverTimeMs = clock(),
            artworkKey = ::rememberCover,
        )
        return Reply(200, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))
    }

    /** The key [track]'s cover is asked for by, kept with where the cover is; null for a song without one. */
    private fun rememberCover(track: Track): String? {
        val source = track.artworkUrl?.takeIf(String::isNotBlank) ?: return null
        val key = NowPlayingFeed.trackKey(track)
        synchronized(covers) { covers[key] = source }
        return key
    }

    /**
     * The cover a page asks for by its song's key, or the cover of what is playing for a page that names none. Only
     * ever a cover a song in [NowPlayingFeed] had: nothing in the request says where to read from.
     */
    private fun artwork(key: String?): Reply {
        val source = key?.let { synchronized(covers) { covers[it] } }
            ?: NowPlayingFeed.shown(playback())?.artworkUrl?.takeIf(String::isNotBlank)
            ?: return notFound()
        val picture = artwork.picture(source) ?: return notFound()
        return Reply(200, picture.type, picture.bytes)
    }

    private fun notFound() = Reply.text(404, "Nothing here.")

    private fun readRequest(input: InputStream): Request? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n", "\n").filter(String::isNotEmpty)
        val requestLine = lines.firstOrNull()?.split(' ')?.filter(String::isNotEmpty) ?: return null
        if (requestLine.size < 2 || !requestLine[1].startsWith("/")) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.take(colon).trim().lowercase() to line.drop(colon + 1).trim()
        }.toMap()
        val hasBody = headers["transfer-encoding"] != null || (headers["content-length"]?.toLongOrNull() ?: 0) > 0
        return Request(requestLine[0].uppercase(), requestLine[1], headers["host"], hasBody)
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

    /**
     * Reads, briefly and only so much, whatever a refused request was still sending. Closing on top of a body still
     * arriving resets the connection, and a reset can overtake the answer, leaving the other end with an error instead
     * of the reason: the same trouble Connect's server had.
     */
    private fun discardTheRest(socket: Socket, input: InputStream) {
        runCatching {
            socket.shutdownOutput()
            socket.soTimeout = DISCARD_FOR_MS
            val sink = ByteArray(4 * 1024)
            var total = 0
            while (total < MAX_DISCARD_BYTES) {
                val got = input.read(sink)
                if (got < 0) break
                total += got
            }
        }
    }

    private fun write(output: OutputStream, reply: Reply, headOnly: Boolean) {
        val head = buildString {
            append("HTTP/1.1 ${reply.status} ${reason(reply.status)}\r\n")
            append("Content-Type: ${reply.type}\r\n")
            append("Content-Length: ${reply.body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            reply.headers.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) output.write(reply.body)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    internal companion object {
        const val LOOPBACK = "127.0.0.1"

        /** Noctorium Night's violet, for a server with no listener's accent to go by. */
        const val DEFAULT_ACCENT = 0xFFB47CFF

        /**
         * What a page may load, said to the browser as well as kept to in the pages: anything from this server, the
         * cover drawn into a canvas to take its colours, and nothing from anywhere else at all.
         */
        const val PAGE_POLICY = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
            "img-src 'self' data: blob:; connect-src 'self'; base-uri 'none'; form-action 'none'"

        private const val CRLFCRLF = 0x0D0A0D0A
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val MAX_DISCARD_BYTES = 64 * 1024
        private const val READ_TIMEOUT_MS = 5_000
        private const val DISCARD_FOR_MS = 300
        private const val BACKLOG = 32
        /** How long [stop] waits for the port to be let go of; it is gone in a few milliseconds. */
        private const val STOP_WAIT_MS = 1_000L

        /** One or two widgets on a stream ask once a second each; sixteen at once is a page gone wrong. */
        private const val MAX_WORKERS = 16

        /** The sentence Settings shows when [port] cannot be listened on, from what the system said. */
        fun failureReason(port: Int, failure: Throwable): String {
            val said = failure.message.orEmpty()
            return when {
                failure is BindException && (said.contains("in use", ignoreCase = true) || said.contains("EADDRINUSE")) ->
                    "Port $port is in use by another program. Choose another port."
                // Windows keeps whole ranges of ports for itself -- Hyper-V and WSL take some -- and says only that
                // access was forbidden; Linux and macOS say the same for the ports below 1024.
                failure is BindException || said.contains("permission", ignoreCase = true) || said.contains("access", ignoreCase = true) ->
                    "Port $port is not available on this computer: the system keeps it, or does not allow it. Choose another port."
                else -> "The stream widgets could not start on port $port: ${said.ifBlank { failure::class.java.simpleName }}"
            }
        }

        /**
         * Whether a request's `Host` names this computer: an address, localhost, or a name on the local network --
         * one word, or one ending as only local names do. A missing one is let through: only a very old client sends
         * none, and nothing in a browser can.
         */
        fun hostAllowed(host: String?): Boolean {
            if (host.isNullOrBlank()) return true
            val name = host.trim().lowercase().let { full ->
                when {
                    full.startsWith("[") -> full.substringBefore(']') + "]"
                    full.count { it == ':' } == 1 -> full.substringBefore(':')
                    else -> full
                }
            }.trimEnd('.')
            return name == "localhost" || name.endsWith(".localhost") || isAddress(name) || '.' !in name ||
                LOCAL_NAMES.any { name.endsWith(it) }
        }

        private val LOCAL_NAMES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa")

        private fun isAddress(name: String): Boolean =
            name.startsWith("[") && name.endsWith("]") ||
                name.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }

        /**
         * This computer's IPv4 address on its local network, for another computer to reach the widgets by: a private
         * address on an interface that is up, preferring a real network adapter to the virtual ones Hyper-V, WSL,
         * Docker and virtual machines add, and a home network's 192.168 to the rest.
         */
        fun lanAddress(): String? = runCatching {
            val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { adapter ->
                    adapter.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .map { NetworkCandidate(adapter.displayName.orEmpty() + " " + adapter.name.orEmpty(), it.hostAddress) }
                }
            chooseNetworkAddress(candidates)
        }.getOrNull()

        internal fun chooseNetworkAddress(candidates: List<NetworkCandidate>): String? =
            candidates.minByOrNull { candidate ->
                val virtual = VIRTUAL_ADAPTERS.any { candidate.adapter.contains(it, ignoreCase = true) }
                val range = when {
                    candidate.address.startsWith("192.168.") -> 0
                    candidate.address.startsWith("10.") -> 1
                    else -> 2
                }
                (if (virtual) 10 else 0) + range
            }?.address

        private val VIRTUAL_ADAPTERS = listOf("virtual", "vethernet", "hyper-v", "vmware", "vbox", "wsl", "docker", "loopback", "tap", "tun")

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
