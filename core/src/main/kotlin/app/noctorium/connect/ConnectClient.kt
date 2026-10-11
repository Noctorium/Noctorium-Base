package app.noctorium.connect

import java.security.SecureRandom

/**
 * The half of Connect that asks.
 *
 * Plain HTTP to an address on the local network, over [LanHttp] rather than the client the rest of
 * Noctorium uses -- a phone refuses plain HTTP through that one, see [LanHttp] -- with three headers added
 * and a signature over everything that decides what the request will do.
 *
 * Timeouts are short on purpose. These calls sit behind a tap on a device name, and a phone that has left
 * the network should be reported as gone within a couple of seconds rather than leaving the button stuck.
 *
 * A request that fails says why, in [log]. "Did not answer" was all there ever was to go on, on both
 * devices, while every request a phone made was being refused before it left the phone.
 */
class ConnectClient internal constructor(
    private val transport: LanHttp,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) {
    constructor(clock: () -> Long = System::currentTimeMillis, log: (String) -> Unit = {}) :
        this(LanHttp(), clock, log)

    private val random = SecureRandom()

    /** What a device said when asked what it is doing: that, or why there is nothing to show. */
    data class StateReply(val state: RemoteState?, val failure: String? = null)

    suspend fun state(peer: ConnectPeer, key: String, from: DeviceAnnouncement): RemoteState? =
        stateAt(peer.address, peer.port, key, from).state

    /**
     * What the device at [address] is doing, which also says what it is called and what kind of device it is.
     *
     * Asked of an address nobody announced, this is how a device is added by its address: only a device signed
     * in to the same account can answer it at all. Not logged here, because it is asked every second while a
     * device is being driven; the caller says so once when it starts failing.
     *
     * [driving] says this is the remote control asking, rather than a device checking the other is still
     * there: only the first keeps the other device saying who is controlling it.
     */
    suspend fun stateAt(address: String, port: Int, key: String, from: DeviceAnnouncement, driving: Boolean = false): StateReply {
        val reply = send(address, port, key, from, "GET", "/state", "", driving)
        if (!reply.ok) return StateReply(null, describe(reply))
        return runCatching {
            StateReply(ConnectProtocol.json.decodeFromString(RemoteState.serializer(), reply.body))
        }.getOrElse { StateReply(null, "an answer that could not be read: ${it.message}") }
    }

    suspend fun command(peer: ConnectPeer, key: String, from: DeviceAnnouncement, command: Command): CommandReply {
        val body = ConnectProtocol.json.encodeToString(
            Command.serializer(),
            command.copy(fromDeviceId = from.id, fromDeviceName = from.name),
        )
        val reply = send(peer.address, peer.port, key, from, "POST", "/command", body)
        if (!reply.ok) log("${command.type} to ${peer.name} at ${hostAndPort(peer.address, peer.port)} failed: ${describe(reply)}")
        return when (reply.status) {
            LanHttp.UNREACHABLE -> CommandReply(
                ok = false,
                // A computer that can be seen and not reached is nearly always its firewall: the
                // announcements go out from it, and the answers have to come in.
                message = if (peer.kind == DeviceKind.DESKTOP) {
                    "${peer.name} did not answer. Its firewall may be keeping Noctorium out on this network."
                } else {
                    "${peer.name} did not answer."
                },
            )
            // Not the other device's own words, which are deliberately the same for every refusal.
            401 -> CommandReply(
                ok = false,
                message = "${peer.name} did not accept this device. Both have to be signed in to the same " +
                    "Noctorium account, with their clocks right.",
            )
            else -> runCatching {
                ConnectProtocol.json.decodeFromString(CommandReply.serializer(), reply.body)
            }.getOrElse {
                CommandReply(
                    ok = false,
                    message = if (reply.ok) "${peer.name} said something unexpected." else reply.body.take(200),
                )
            }
        }
    }

    private suspend fun send(
        address: String,
        port: Int,
        key: String,
        from: DeviceAnnouncement,
        method: String,
        path: String,
        body: String,
        driving: Boolean = false,
    ): LanHttp.Reply {
        val timestamp = clock()
        val nonce = nonce()
        val signature = ConnectSignature.sign(
            key,
            ConnectSignature.requestMessage(method, path, timestamp, nonce, body),
        )
        return transport.send(
            address = address,
            port = port,
            method = method,
            path = path,
            headers = buildMap {
                put(ConnectServer.HEADER_DEVICE, from.id)
                put(ConnectServer.HEADER_NONCE, nonce)
                put(ConnectServer.HEADER_TIMESTAMP, timestamp.toString())
                put(ConnectServer.HEADER_AUTH, signature)
                if (driving) put(ConnectServer.HEADER_DRIVING, from.id)
            },
            body = body,
        )
    }

    private fun describe(reply: LanHttp.Reply): String =
        reply.failure ?: "HTTP ${reply.status} ${reply.body.take(120)}"


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
}
