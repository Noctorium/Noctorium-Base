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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
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
    /** When it was last heard from, by this device's clock: what [nowPlaying]'s position is as of. */
    val lastSeenAtMs: Long,
    /** What it is playing, or null with nothing to play -- or for a device from before devices said so. */
    val nowPlaying: NowPlaying? = null,
    /** The device driving it, if one is. */
    val drivenBy: DeviceRef? = null,
    /** The device it is driving, if any: then the music is there, and this one is its remote control. */
    val driving: DeviceRef? = null,
) {
    /** How far into its song it is at [nowMs] on this device's clock: moving on from what it last said while it plays. */
    fun positionAt(nowMs: Long): Long = nowPlaying?.positionAfter(nowMs - lastSeenAtMs) ?: 0
}

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
    /** [ConnectProtocol.DISCOVERY_PORTS], except in tests, which keep off the ports a real Noctorium uses. */
    private val ports: List<Int> = ConnectProtocol.DISCOVERY_PORTS,
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
        if (running || ports.isEmpty()) return
        val bound = bindSomething()
        if (bound == null) {
            log("Discovery could not bind any of $ports.")
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
        log("Discovery listening on ${bound.localPort}, announcing to $ports")
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
     * A device that has just been heard from: by its announcement, or by answering at an address it was
     * added by. Either way it stays listed until it has been quiet as long as one that stopped announcing.
     */
    fun heard(peer: ConnectPeer) {
        // Logged when it is new, and when it moves. "Found" on every beat would be three lines a second, and
        // the one thing anybody debugging Connect wants to know is whether the other device was ever seen at
        // all, and where.
        val before = mutablePeers.value.firstOrNull { it.id == peer.id }
        if (before == null || before.address != peer.address || before.port != peer.port) {
            log("Found ${peer.name} (${peer.kind}) at ${hostAndPort(peer.address, peer.port)}")
        }
        // Atomically, because this runs in the listening loop while prune() runs in the announcing one.
        // Read-modify-write from two coroutines loses whichever update lands second: a device found at the
        // wrong moment vanishes, or one that left comes back.
        mutablePeers.update { peers -> peers.filterNot { it.id == peer.id } + peer }
    }

    /**
     * Devices that have stopped announcing are gone, whether they said goodbye or had their wifi turned off.
     *
     * Run on every beat while discovery is running, and by the manager otherwise, so that a device added by
     * its address goes when it stops answering even where nothing is being broadcast.
     */
    fun prune() {
        val cutoff = clock() - PEER_FORGOTTEN_AFTER_MS
        // The swap is atomic and the logging happens after it. update{} re-runs its lambda when another
        // coroutine got there first, so a log inside it would print the same departure twice.
        val before = mutablePeers.getAndUpdate { peers -> peers.filter { it.lastSeenAtMs >= cutoff } }
        before.filter { it.lastSeenAtMs < cutoff }.forEach { log("Lost ${it.name}") }
    }

    /**
     * The first candidate port this machine will actually hand over.
     *
     * Reuse is asked for on every attempt: without it, a second Noctorium on the same machine cannot bind
     * at all, and neither can this one after a crash until the kernel lets the port go.
     */
    private fun bindSomething(): DatagramSocket? {
        for (port in ports) {
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

    /**
     * Says this device is here now, rather than at the next beat.
     *
     * For a change somebody is looking at on another device: a song started, paused or skipped, a device
     * taken over or let go of. Three seconds is fine for "still here" and far too slow for "now paused", which
     * every other device's Connect page should show within the second.
     */
    fun announceNow() {
        wake.trySend(Unit)
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)

    private suspend fun announce(socket: DatagramSocket, announcement: () -> DeviceAnnouncement?) {
        var lastSentAt = 0L
        while (currentlyActive()) {
            val mine = announcement()
            if (mine != null) {
                val bytes = ConnectProtocol.json.encodeToString(DeviceAnnouncement.serializer(), mine)
                    .toByteArray(Charsets.UTF_8)
                // Every candidate port, on every interface: a device that lost the first port is still
                // reached, and nobody has to know which one anybody else settled on.
                for (target in broadcastAddresses()) {
                    for (port in ports) {
                        runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, port)) }
                    }
                }
                lastSentAt = clock()
            }
            prune()
            withTimeoutOrNull(ConnectProtocol.ANNOUNCE_EVERY_MS) { wake.receive() }
            // A few a second at most, however fast things change: a seek bar dragged is a change a frame.
            val since = clock() - lastSentAt
            if (since in 0 until MIN_ANNOUNCE_GAP_MS) delay(MIN_ANNOUNCE_GAP_MS - since)
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
            val extra = extraOf(heard, secret)

            heard(
                ConnectPeer(
                    id = heard.id,
                    name = heard.name,
                    kind = deviceKind(heard.kind, extra?.terminal == true),
                    // From the envelope, not the letter. See the class comment.
                    address = packet.address.hostAddress ?: continue,
                    port = heard.port,
                    lastSeenAtMs = clock(),
                    nowPlaying = extra?.nowPlaying,
                    drivenBy = extra?.drivenBy,
                    driving = extra?.driving,
                ),
            )
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

    /**
     * What else the announcement says, if its own signature holds; nothing otherwise.
     *
     * A device from before these existed sends neither field, and is listed with nothing playing, as it was.
     */
    private fun extraOf(heard: DeviceAnnouncement, key: String): AnnouncementExtra? {
        val text = heard.extra ?: return null
        val tag = heard.extraTag ?: return null
        if (!ConnectSignature.matches(key, heard.signableWithExtra(), tag)) return null
        return runCatching { ConnectProtocol.json.decodeFromString(AnnouncementExtra.serializer(), text) }.getOrNull()
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
        /** Four missed announcements. Long enough to ride out a busy network, short enough to notice. */
        const val PEER_FORGOTTEN_AFTER_MS = 12_000L

        /** Allows for clocks that disagree, without allowing a recording from this morning. */
        const val STAMP_TOLERANCE_MINUTES = 5L

        const val MAX_PACKET_BYTES = 2048

        /** The least time between two announcements, however fast things change. */
        const val MIN_ANNOUNCE_GAP_MS = 200L
    }
}

/** An address and port as they are written, with an IPv6 address in brackets so the port is not read as part of it. */
internal fun hostAndPort(address: String, port: Int): String =
    if (':' in address) "[$address]:$port" else "$address:$port"
