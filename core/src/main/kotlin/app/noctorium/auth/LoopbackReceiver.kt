package app.noctorium.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Where a service's sign-in reply is caught. Closing it gives the port back. */
interface RedirectReceiver : Closeable {
    /** The query fields of the first reply to arrive, or null if none did. */
    suspend fun awaitReply(): Map<String, String>?
}

/**
 * Catches a sign-in's redirect on the loopback address, using a plain socket: Spotify's, and Tidal's.
 *
 * A raw [ServerSocket] rather than `com.sun.net.httpserver`, which reads as the obvious choice on the
 * desktop and does not exist on Android at all. Everything this has to do is read one request line and
 * write one reply, so the whole HTTP server it replaces was being used for a single GET.
 *
 * Bound to loopback explicitly, not to every interface. This port is open only for the seconds a sign-in
 * takes, but for those seconds it accepts an authorization code, and nothing outside the machine has any
 * business handing one over. [service] is only what the page left in the browser calls the service.
 */
class LoopbackReceiver(
    port: Int,
    private val service: String,
    private val timeoutMillis: Int = 5 * 60 * 1_000,
) : RedirectReceiver {
    private val socket = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).apply {
        soTimeout = timeoutMillis
    }

    override suspend fun awaitReply(): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            socket.accept().use { connection ->
                val reader = connection.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                // "GET /noctorium/spotify?code=...&state=... HTTP/1.1" — the only line that matters, and
                // the rest of the request is left unread because nothing here depends on a header.
                val requestLine = reader.readLine().orEmpty()
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                val fields = queryFieldsOf(target)

                val message = when {
                    // Written into the page as text, never as markup: anything at all can arrive in this field.
                    fields["error"] != null -> "$service refused: ${escaped(fields.getValue("error"))}. You can close this tab."
                    fields["code"] != null ->
                        "Signed in to $service. You can close this tab and go back to Noctorium."
                    else -> "That reply carried no authorisation. You can close this tab."
                }
                connection.getOutputStream().use { out ->
                    out.write(httpReply(message).toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
                fields.takeIf { it.isNotEmpty() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timedOutOrClosed: Exception) {
            // Nobody came back: the tab was closed, the wait ran out, or the socket was shut. All of them
            // mean the same thing to the caller, which reports that no authorisation arrived.
            null
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        fun queryFieldsOf(target: String): Map<String, String> =
            target.substringAfter('?', "")
                .split('&')
                .mapNotNull { pair ->
                    val parts = pair.split('=', limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank()) {
                        parts[0] to decode(parts[1])
                    } else {
                        null
                    }
                }
                .toMap()

        fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)

        fun escaped(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** A whole HTTP reply, written by hand because there is exactly one of them. */
        fun httpReply(message: String): String {
            val body = """
                <!doctype html><meta charset="utf-8"><title>Noctorium</title>
                <body style="background:#08070c;color:#f3f1f8;font:15px system-ui;display:grid;place-items:center;height:100vh;margin:0">
                <p>$message</p>
            """.trimIndent()
            val bytes = body.toByteArray(StandardCharsets.UTF_8).size
            return "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: $bytes\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
        }
    }
}
