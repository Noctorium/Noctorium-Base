package app.noctorium.discord

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Opcodes of Discord's local IPC framing. */
internal object DiscordOpcode {
    const val HANDSHAKE = 0
    const val FRAME = 1
    const val CLOSE = 2
    const val PONG = 4
}

/**
 * Discord's local RPC frame: a little-endian opcode, a little-endian byte count, then UTF-8 JSON.
 * Pure and separated from the socket so the wire format can be tested without Discord running.
 */
internal fun encodeFrame(opcode: Int, payload: String): ByteArray {
    val body = payload.toByteArray(StandardCharsets.UTF_8)
    return ByteBuffer.allocate(8 + body.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(opcode)
        .putInt(body.size)
        .put(body)
        .array()
}

/** The transport Discord listens on. Windows uses a named pipe; everywhere else a unix socket. */
internal interface DiscordTransport {
    fun write(bytes: ByteArray)
    fun close()
}

private class NamedPipeTransport(private val pipe: RandomAccessFile) : DiscordTransport {
    override fun write(bytes: ByteArray) = pipe.write(bytes)
    override fun close() { runCatching { pipe.close() } }
}

private class UnixSocketTransport(private val channel: SocketChannel) : DiscordTransport {
    override fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun close() { runCatching { channel.close() } }
}

/**
 * Talks to the Discord desktop client over its local IPC socket.
 *
 * No library and no network: Discord exposes numbered endpoints on the machine, and the first one that accepts
 * a connection is the running client. Everything here is best-effort — Discord not running is the normal case,
 * not an error worth surfacing.
 */
class DiscordIpcClient internal constructor(
    private val connector: (Int) -> DiscordTransport? = ::connectToEndpoint,
    private val processId: Long = ProcessHandle.current().pid(),
) {
    private var transport: DiscordTransport? = null

    val connected: Boolean get() = transport != null

    /** Connects and performs the handshake. Returns false when Discord is not listening. */
    suspend fun connect(applicationId: String): Boolean = withContext(Dispatchers.IO) {
        if (transport != null) return@withContext true
        if (applicationId.isBlank()) return@withContext false
        // Discord numbers its endpoints 0..9; several exist when more than one client build is installed.
        for (index in 0..9) {
            val candidate = runCatching { connector(index) }.getOrNull() ?: continue
            val handshake = """{"v":1,"client_id":"${applicationId.trim()}"}"""
            val sent = runCatching { candidate.write(encodeFrame(DiscordOpcode.HANDSHAKE, handshake)) }.isSuccess
            if (sent) {
                transport = candidate
                return@withContext true
            }
            candidate.close()
        }
        false
    }

    suspend fun setActivity(activity: JsonObject?, nonce: String): Boolean = withContext(Dispatchers.IO) {
        val live = transport ?: return@withContext false
        val payload = buildString {
            append("""{"cmd":"SET_ACTIVITY","args":{"pid":""")
            append(processId)
            if (activity == null) append(""","activity":null""") else append(""","activity":""").append(activity)
            append("""},"nonce":"""").append(nonce).append(""""}""")
        }
        runCatching { live.write(encodeFrame(DiscordOpcode.FRAME, payload)) }
            .onFailure { disconnect() }
            .isSuccess
    }

    fun disconnect() {
        transport?.close()
        transport = null
    }
}

private fun connectToEndpoint(index: Int): DiscordTransport? {
    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    return if (isWindows) {
        val pipe = RandomAccessFile("""\\.\pipe\discord-ipc-$index""", "rw")
        NamedPipeTransport(pipe)
    } else {
        val directory = listOfNotNull(
            System.getenv("XDG_RUNTIME_DIR"),
            System.getenv("TMPDIR"),
            "/tmp",
        ).first()
        val address = UnixDomainSocketAddress.of(Path.of(directory, "discord-ipc-$index"))
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        if (!channel.connect(address)) {
            channel.close()
            null
        } else {
            UnixSocketTransport(channel)
        }
    }
}
