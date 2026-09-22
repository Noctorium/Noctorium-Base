package app.noctorium.connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/** What a device can be asked to do, once it has been established that the asker is entitled to ask. */
interface ConnectCommands {
    suspend fun state(): RemoteState
    suspend fun handle(command: Command): CommandReply
}

/**
 * The half of Connect that answers.
 *
 * HTTP over a raw socket, written out by hand. The desktop could use the JDK's own http server and the
 * phone could use any of several libraries, but `core` is one codebase compiled for both, and the JDK's
 * server does not exist on Android at any API level -- the same trap that had settings silently failing to
 * save when this project first reached a phone. Two hundred lines of a well-understood protocol, shared,
 * beats two implementations that only look the same.
 *
 * Only what Connect needs is implemented: one request per connection, no chunking, no keep-alive, a hard
 * cap on the body. A request that does not fit that description is refused rather than guessed at.
 */
class ConnectServer(
    private val commands: ConnectCommands,
    private val key: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null

    /** Ephemeral, and told to the network in the announcement, so two Noctoriums on one machine both work. */
    val port: Int get() = server?.localPort ?: 0

    private val seenNonces = LinkedHashMap<String, Long>()

    fun start(): Int {
        if (server != null) return port
        val bound = runCatching { ServerSocket(0) }.getOrElse {
            log("Connect server could not bind: ${it.message}")
            return 0
        }
        server = bound
        val started = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = started
        started.launch {
            while (started.isActive && !bound.isClosed) {
                val client = runCatching { bound.accept() }.getOrNull() ?: continue
                started.launch { serve(client) }
            }
        }
        log("Connect server listening on ${bound.localPort}")
        return bound.localPort
    }

    fun stop() {
        server?.close()
        server = null
        scope?.cancel()
        scope = null
    }

    private suspend fun serve(client: Socket) {
        client.use { socket ->
            // A connection that stops talking mid-request must not hold a thread open forever.
            runCatching { socket.soTimeout = READ_TIMEOUT_MS }
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val request = runCatching { readRequest(input) }.getOrNull()
            if (request == null) {
                respond(output, 400, """{"ok":false,"message":"Bad request."}""")
                return
            }

            val secret = key()
            if (secret == null) {
                respond(output, 503, """{"ok":false,"message":"This device is not signed in to Noctorium."}""")
                return
            }
            val refusal = reasonToRefuse(request, secret)
            if (refusal != null) {
                // Deliberately identical for every reason. Telling a caller whether the signature was wrong
                // or the nonce was used before tells it which half of the guess to keep.
                log("Refused ${request.method} ${request.path}: $refusal")
                respond(output, 401, """{"ok":false,"message":"Not authorised."}""")
                return
            }

            val reply = runCatching {
                when {
                    request.method == "GET" && request.path == "/state" ->
                        ConnectProtocol.json.encodeToString(RemoteState.serializer(), commands.state())

                    request.method == "POST" && request.path == "/command" -> {
                        val command = ConnectProtocol.json.decodeFromString(Command.serializer(), request.body)
                        ConnectProtocol.json.encodeToString(CommandReply.serializer(), commands.handle(command))
                    }

                    else -> null
                }
            }.getOrElse {
                log("Handler failed for ${request.path}: $it")
                respond(output, 500, """{"ok":false,"message":"That went wrong on the other device."}""")
                return
            }

            if (reply == null) respond(output, 404, """{"ok":false,"message":"No such thing."}""")
            else respond(output, 200, reply)
        }
    }

    /** Null when the request is allowed; otherwise why it is not, for the log and nowhere else. */
    private fun reasonToRefuse(request: Request, secret: String): String? {
        val nonce = request.headers[HEADER_NONCE] ?: return "no nonce"
        val stamp = request.headers[HEADER_TIMESTAMP]?.toLongOrNull() ?: return "no timestamp"
        val presented = request.headers[HEADER_AUTH] ?: return "no signature"

        if (kotlin.math.abs(clock() - stamp) > CLOCK_TOLERANCE_MS) return "stale timestamp"
        if (nonce.length !in 8..128) return "implausible nonce"

        val message = ConnectSignature.requestMessage(request.method, request.path, stamp, nonce, request.body)
        if (!ConnectSignature.matches(secret, message, presented)) return "bad signature"

        // Checked last, and only once the signature is known good, so that an unsigned flood cannot fill
        // the table and push out the nonces of real requests.
        if (!remember(nonce)) return "replayed nonce"
        return null
    }

    /** False when this nonce has been seen before, within the window a timestamp is accepted for. */
    private fun remember(nonce: String): Boolean = synchronized(seenNonces) {
        val now = clock()
        seenNonces.entries.removeAll { now - it.value > CLOCK_TOLERANCE_MS * 2 }
        if (seenNonces.containsKey(nonce)) return false
        seenNonces[nonce] = now
        // A ceiling as well as an expiry: a burst of signed requests should not grow this without bound.
        while (seenNonces.size > MAX_REMEMBERED_NONCES) {
            val oldest = seenNonces.keys.firstOrNull() ?: break
            seenNonces.remove(oldest)
        }
        true
    }

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readRequest(input: InputStream): Request? {
        val head = readUntilBlankLine(input) ?: return null
        val lines = head.split("\r\n").filter(String::isNotEmpty)
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null

        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.take(colon).trim().lowercase() to line.drop(colon + 1).trim()
        }.toMap()

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY_BYTES) return null
        val body = if (length <= 0) "" else {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val got = input.read(bytes, read, length - read)
                if (got < 0) return null
                read += got
            }
            String(bytes, Charsets.UTF_8)
        }
        // Only the path, never the query: the signature covers what is signed, and anything smuggled in a
        // query string would not be.
        return Request(requestLine[0].uppercase(), requestLine[1].substringBefore('?'), headers, body)
    }

    private fun readUntilBlankLine(input: InputStream): String? {
        val buffer = StringBuilder()
        var last4 = 0
        while (buffer.length < MAX_HEAD_BYTES) {
            val next = input.read()
            if (next < 0) return null
            buffer.append(next.toChar())
            last4 = (last4 shl 8) or next
            if (last4 and 0xffffffff.toInt() == CRLFCRLF) return buffer.toString()
        }
        return null
    }

    private fun respond(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            output.write(head.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    companion object {
        const val HEADER_NONCE = "x-noctorium-nonce"
        const val HEADER_TIMESTAMP = "x-noctorium-timestamp"
        const val HEADER_AUTH = "x-noctorium-auth"
        const val HEADER_DEVICE = "x-noctorium-device"

        /** How far apart two devices' clocks may be before their requests stop being believed. */
        const val CLOCK_TOLERANCE_MS = 5 * 60 * 1000L

        private const val CRLFCRLF = 0x0D0A0D0A
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 512 * 1024
        private const val MAX_REMEMBERED_NONCES = 2048
        private const val READ_TIMEOUT_MS = 10_000
    }
}
