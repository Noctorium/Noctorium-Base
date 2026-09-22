package app.noctorium.connect

import app.noctorium.net.Http
import java.security.SecureRandom

/**
 * The half of Connect that asks.
 *
 * Ordinary HTTP to a plain address on the local network, through the same client the rest of Noctorium
 * uses, with three headers added and a signature over everything that decides what the request will do.
 *
 * Timeouts are short on purpose. These calls sit behind a tap on a device name, and a phone that has left
 * the network should be reported as gone within a couple of seconds rather than leaving the button stuck.
 */
class ConnectClient(
    private val http: Http = Http(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()

    suspend fun state(peer: ConnectPeer, key: String, from: DeviceAnnouncement): RemoteState? {
        val reply = send(peer, key, from, "GET", "/state", "")
        if (!reply.ok) return null
        return runCatching {
            ConnectProtocol.json.decodeFromString(RemoteState.serializer(), reply.body)
        }.getOrNull()
    }

    suspend fun command(peer: ConnectPeer, key: String, from: DeviceAnnouncement, command: Command): CommandReply {
        val body = ConnectProtocol.json.encodeToString(
            Command.serializer(),
            command.copy(fromDeviceId = from.id, fromDeviceName = from.name),
        )
        val reply = send(peer, key, from, "POST", "/command", body)
        if (reply.status == Http.UNREACHABLE) {
            return CommandReply(ok = false, message = "${peer.name} did not answer.")
        }
        return runCatching {
            ConnectProtocol.json.decodeFromString(CommandReply.serializer(), reply.body)
        }.getOrElse {
            CommandReply(ok = false, message = if (reply.ok) "${peer.name} said something unexpected." else reply.sample())
        }
    }

    private suspend fun send(
        peer: ConnectPeer,
        key: String,
        from: DeviceAnnouncement,
        method: String,
        path: String,
        body: String,
    ): app.noctorium.net.HttpReply {
        val timestamp = clock()
        val nonce = nonce()
        val signature = ConnectSignature.sign(
            key,
            ConnectSignature.requestMessage(method, path, timestamp, nonce, body),
        )
        return http.send(
            url = "http://${peer.address}:${peer.port}$path",
            method = method,
            headers = mapOf(
                ConnectServer.HEADER_DEVICE to from.id,
                ConnectServer.HEADER_NONCE to nonce,
                ConnectServer.HEADER_TIMESTAMP to timestamp.toString(),
                ConnectServer.HEADER_AUTH to signature,
            ),
            body = body.takeIf { method != "GET" },
            timeoutSeconds = TIMEOUT_SECONDS,
        )
    }

    /**
     * Never reused, and not guessable.
     *
     * The nonce is the only thing stopping a captured request being sent again, so it comes from
     * SecureRandom rather than a counter or a clock: a predictable nonce lets somebody sign a request now
     * and have it accepted later.
     */
    private fun nonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return buildString(32) {
            for (byte in bytes) append("%02x".format(byte.toInt() and 0xff))
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 4L
    }
}
