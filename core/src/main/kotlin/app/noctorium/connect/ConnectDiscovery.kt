package app.noctorium.connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Permission to hear broadcast traffic.
 *
 * A desktop needs nothing: a socket bound to a port receives what arrives at it. Android switches the
 * wifi chip out of listening for anything not addressed to the device exactly, to save power, and a
 * broadcast is by definition not addressed to anyone exactly -- so without a multicast lock held, the
 * announcements are dropped below the application and discovery simply never finds anybody, silently.
 */
interface NetworkPresence {
    fun acquire() {}
    fun release() {}

    object None : NetworkPresence
}

/** Another Noctorium, on this network, signed in to the same account. */
data class ConnectPeer(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val address: String,
    val port: Int,
    val lastSeenAtMs: Long,
)

/**
 * Finding the listener's other devices, and being found by them.
 *
 * UDP broadcast rather than mDNS. mDNS is the better-behaved protocol and the wrong tool here: it wants a
 * library on both platforms, it is blocked or rate-limited on a lot of home routers, and it publishes a
 * service name to everyone on the network whether or not they have any business seeing it. A broadcast of
 * a few hundred bytes every three seconds costs nothing, needs no dependency, and carries nothing an
 * onlooker can use.
 *
 * The address a peer is reached on is taken from the packet, never from the packet's contents. A device
 * that could name its own address could name somebody else's, and point every other device on the network
 * at a machine that never announced anything.
 */
class ConnectDiscovery(
    private val presence: NetworkPresence = NetworkPresence.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val mutablePeers = MutableStateFlow<List<ConnectPeer>>(emptyList())
    val peers: StateFlow<List<ConnectPeer>> = mutablePeers.asStateFlow()

    private var socket: DatagramSocket? = null
    private var scope: CoroutineScope? = null
    private var jobs: List<Job> = emptyList()

    val running: Boolean get() = socket != null

    /**
     * Starts announcing and listening.
     *
     * [announcement] is read on every beat rather than once, because the name, the port and the account
     * can all change while the application is running, and a device that kept announcing a stale port
     * would be listed and then refuse every connection.
     */
    fun start(announcement: () -> DeviceAnnouncement?, key: () -> String?) {
        if (running) return
        val bound = bindSomething()
        if (bound == null) {
            log("Discovery could not bind any of ${ConnectProtocol.DISCOVERY_PORTS}.")
            return
        }

        presence.acquire()
        socket = bound
        val started = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = started
        jobs = listOf(
            started.launch { announce(bound, announcement) },
            started.launch { listen(bound, announcement, key) },
        )
        log("Discovery listening on ${bound.localPort}, announcing to ${ConnectProtocol.DISCOVERY_PORTS}")
    }

    fun stop() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        // Closed before the scope is cancelled: receive() blocks in a way cancellation cannot interrupt,
        // and closing the socket is what actually wakes it.
        socket?.close()
        socket = null
        scope?.cancel()
        scope = null
        mutablePeers.value = emptyList()
        presence.release()
    }

    /**
     * The first candidate port this machine will actually hand over.
     *
     * Reuse is asked for on every attempt: without it, a second Noctorium on the same machine cannot bind
     * at all, and neither can this one after a crash until the kernel lets the port go.
     */
    private fun bindSomething(): DatagramSocket? {
        for (port in ConnectProtocol.DISCOVERY_PORTS) {
            val attempt = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(port))
                }
            }.getOrNull()
            if (attempt != null) return attempt
            log("Discovery could not bind $port, trying the next.")
        }
        return null
    }

    private suspend fun announce(socket: DatagramSocket, announcement: () -> DeviceAnnouncement?) {
        while (currentlyActive()) {
            val mine = announcement()
            if (mine != null) {
                val bytes = ConnectProtocol.json.encodeToString(DeviceAnnouncement.serializer(), mine)
                    .toByteArray(Charsets.UTF_8)
                // Every candidate port, on every interface: a device that lost the first port is still
                // reached, and nobody has to know which one anybody else settled on.
                for (target in broadcastAddresses()) {
                    for (port in ConnectProtocol.DISCOVERY_PORTS) {
                        runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, port)) }
                    }
                }
            }
            prune()
            delay(ANNOUNCE_EVERY_MS)
        }
    }

    private suspend fun listen(
        socket: DatagramSocket,
        announcement: () -> DeviceAnnouncement?,
        key: () -> String?,
    ) {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        while (currentlyActive()) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { socket.receive(packet); true }.getOrElse {
                if (currentlyActive()) delay(500)
                false
            }
            if (!received) continue

            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            val heard = runCatching {
                ConnectProtocol.json.decodeFromString(DeviceAnnouncement.serializer(), text)
            }.getOrNull() ?: continue

            val secret = key() ?: continue
            if (heard.id == announcement()?.id) continue
            if (!believable(heard, secret)) continue

            val peer = ConnectPeer(
                id = heard.id,
                name = heard.name,
                kind = heard.kind,
                // From the envelope, not the letter. See the class comment.
                address = packet.address.hostAddress ?: continue,
                port = heard.port,
                lastSeenAtMs = clock(),
            )
            // Logged the first time only. "Found" on every beat would be three lines a second, and the
            // one thing anybody debugging Connect wants to know is whether the other device was ever
            // seen at all.
            if (mutablePeers.value.none { it.id == peer.id }) {
                log("Found ${peer.name} (${peer.kind}) at ${peer.address}:${peer.port}")
            }
            // Atomically, because this runs in the listening loop while prune() below runs in the
            // announcing one. Read-modify-write from two coroutines loses whichever update lands
            // second: a device found at the wrong moment vanishes, or one that left comes back.
            mutablePeers.update { peers -> peers.filterNot { it.id == peer.id } + peer }
        }
    }

    /** Right version, signed with our key, and recent enough not to be a recording. */
    private fun believable(heard: DeviceAnnouncement, key: String): Boolean {
        if (heard.v != ConnectProtocol.VERSION) return false
        if (heard.port !in 1..65535) return false
        val minutesOut = kotlin.math.abs(clock() / 60_000 - heard.stamp)
        if (minutesOut > STAMP_TOLERANCE_MINUTES) return false
        return ConnectSignature.matches(key, heard.signable(), heard.tag)
    }

    /** Devices that have stopped announcing are gone, whether they said goodbye or had their wifi turned off. */
    private fun prune() {
        val cutoff = clock() - PEER_FORGOTTEN_AFTER_MS
        // The swap is atomic and the logging happens after it. update{} re-runs its lambda when another
        // coroutine got there first, so a log inside it would print the same departure twice.
        val before = mutablePeers.getAndUpdate { peers -> peers.filter { it.lastSeenAtMs >= cutoff } }
        before.filter { it.lastSeenAtMs < cutoff }.forEach { log("Lost ${it.name}") }
    }

    private fun currentlyActive(): Boolean = scope?.isActive == true && socket?.isClosed == false

    /**
     * Where to shout.
     *
     * Every interface's own broadcast address, and the global one as a fallback. Sending only to
     * 255.255.255.255 is unreliable -- plenty of stacks drop it, and a machine on two networks sends it
     * out of whichever one routing happens to pick, which on a desktop with a virtual adapter is regularly
     * the wrong one.
     */
    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        val found = mutableListOf<InetAddress>()
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback) continue
            for (address in nic.interfaceAddresses) {
                address.broadcast?.let(found::add)
            }
        }
        found += InetAddress.getByName("255.255.255.255")
        found.distinct()
    }.getOrDefault(listOf(InetAddress.getByName("255.255.255.255")))

    private companion object {
        const val ANNOUNCE_EVERY_MS = 3_000L

        /** Four missed announcements. Long enough to ride out a busy network, short enough to notice. */
        const val PEER_FORGOTTEN_AFTER_MS = 12_000L

        /** Allows for clocks that disagree, without allowing a recording from this morning. */
        const val STAMP_TOLERANCE_MINUTES = 5L

        const val MAX_PACKET_BYTES = 2048
    }
}
