package app.noctorium.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.RandomAccessFile
import java.io.Writer
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

/**
 * One connection to an mpv's IPC, kept open for questions asked many times a second.
 *
 * The controls open a connection for each command, which is right for a press of a button. The visualiser
 * asks thirty times a second, and measured against an mpv playing, a connection kept open answered in under
 * half the time (0.37 ms against 0.79) and cost both programs less: about a percent of one core each, where
 * a fresh connection for every question cost mpv nearly half as much again as the analysis itself.
 *
 * mpv tells every client about everything that happens -- a file loaded, a seek, a property that changed.
 * None of that is wanted here, so the first thing said is to stop, and every line read is an answer.
 * One caller at a time: it is the visualiser's, and only its poller uses it.
 */
internal class MpvConnection(private val endpoint: String, private val windows: Boolean) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private var pipe: RandomAccessFile? = null
    private var socket: SocketChannel? = null
    private var writer: Writer? = null
    private lateinit var reader: BufferedReader
    private var nextId = 0L

    fun open() {
        if (windows) {
            val opened = RandomAccessFile(endpoint, "rw")
            pipe = opened
            reader = Channels.newInputStream(opened.channel).bufferedReader(Charsets.UTF_8)
        } else {
            val opened = SocketChannel.open(StandardProtocolFamily.UNIX)
            opened.connect(UnixDomainSocketAddress.of(endpoint))
            socket = opened
            writer = Channels.newWriter(opened, Charsets.UTF_8)
            reader = Channels.newReader(opened, Charsets.UTF_8).buffered()
        }
        request(JsonPrimitive("disable_event"), JsonPrimitive("all"))
    }

    /** A property's value, or null when mpv has none to give -- not yet, or not at all. Throws if mpv has gone. */
    fun property(name: String): JsonElement? {
        val answer = request(JsonPrimitive("get_property"), JsonPrimitive(name)) ?: return null
        return answer["data"]?.takeUnless { it is JsonNull }
    }

    /** Sends a command and reads its answer: null when mpv said it could not, an exception when mpv is gone. */
    private fun request(vararg command: JsonElement): JsonObject? {
        val id = ++nextId
        val line = JsonObject(mapOf("command" to JsonArray(command.toList()), "request_id" to JsonPrimitive(id))).toString() + "\n"
        val opened = pipe
        if (opened != null) {
            opened.write(line.toByteArray(Charsets.UTF_8))
        } else {
            val out = writer ?: throw BackendException("mpv IPC is not open")
            out.write(line)
            out.flush()
        }
        repeat(MAX_UNRELATED_LINES) {
            val reply = reader.readLine() ?: throw BackendException("mpv closed its control channel")
            val answer = runCatching { json.parseToJsonElement(reply).jsonObject }.getOrNull() ?: return@repeat
            if (answer["request_id"]?.jsonPrimitive?.longOrNull != id) return@repeat
            return answer.takeIf { it["error"]?.jsonPrimitive?.content == "success" }
        }
        throw BackendException("mpv sent too many unrelated lines before its answer")
    }

    override fun close() {
        runCatching { pipe?.close() }
        runCatching { socket?.close() }
        pipe = null
        socket = null
    }

    private companion object {
        /** Lines that are not the answer -- an event mpv sent before it was told not to -- skipped at most. */
        const val MAX_UNRELATED_LINES = 100
    }
}
