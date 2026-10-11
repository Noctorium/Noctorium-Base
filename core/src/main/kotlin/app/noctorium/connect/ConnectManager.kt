package app.noctorium.connect

import app.noctorium.account.NoctoriumAccountClient
import app.noctorium.domain.Track
import app.noctorium.settings.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** Playback on this device, as another one would want to see it. */
@Serializable
data class PlaybackSnapshot(
    val playing: Boolean = false,
    val track: WireTrack? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val queueSize: Int = 0,
    val queueIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeat: String = "OFF",
)

/** The queue as this device has it: what is in it, and which of those is the one playing. */
data class QueueSnapshot(val tracks: List<Track> = emptyList(), val index: Int = -1)

/**
 * What Connect needs the player to be able to do.
 *
 * Deliberately the smallest surface that covers a remote control and a handover. `AppState` implements it,
 * and nothing in `connect` knows anything else about the player -- which is what keeps this testable
 * without a running application, and keeps the transport out of the player.
 */
interface ConnectHost {
    fun snapshot(): PlaybackSnapshot

    /**
     * The whole queue, for a device taking the music back from this one.
     *
     * Nothing, by default: a device that took the music back from a host saying nothing gets the one song
     * this was on, which is what it always used to get.
     */
    fun queue(): QueueSnapshot = QueueSnapshot()

    /** The whole handover: this queue, from this track, at this position, playing. */
    suspend fun takeOver(tracks: List<Track>, index: Int, positionMs: Long)

    suspend fun resume()
    suspend fun pause()
    suspend fun next()
    suspend fun previous()
    suspend fun seekTo(positionMs: Long)
    suspend fun setVolume(value: Float)
    suspend fun setShuffle(enabled: Boolean)
    suspend fun setRepeat(mode: String)

    /** Stop playing here, because somebody else is taking it. */
    suspend fun standDown()
}

/** Everything the UI shows about Connect. */
data class ConnectState(
    /** Signed in, with a key, and switched on. Without all three there is nothing to show. */
    val available: Boolean = false,
    val listening: Boolean = false,
    val thisDevice: String = "",
    val devices: List<ConnectPeer> = emptyList(),
    /** The device this one is currently driving, if any. */
    val target: ConnectPeer? = null,
    /** What that device is doing, refreshed while it is being driven. */
    val remote: RemoteState? = null,
    /** The name of the device driving this one, when it is the other way round. */
    val controlledBy: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/** The music, come back from the device that had it: its queue, where in it, and how far into that song. */
data class BroughtBack(val tracks: List<Track>, val index: Int, val positionMs: Long)

/**
 * Noctorium Connect.
 *
 * One listener, several devices, one network. Each announces itself, each recognises the others by a
 * secret only their shared account can produce, and any of them can take the music off any other and carry
 * on from the same second of the same track.
 *
 * Nothing is streamed between devices. A handover sends the queue and a position, and the receiving device
 * resolves and plays the tracks itself -- so the phone plays at the phone's quality, over the phone's own
 * connection, and keeps playing after the desktop is shut down. That is also why it is fast: a queue of
 * fifty tracks is a few kilobytes of JSON, not audio.
 */
class ConnectManager(
    private val host: ConnectHost,
    private val secrets: SecretStore,
    private val accountClient: NoctoriumAccountClient,
    private val token: () -> String?,
    private val kind: DeviceKind,
    private val defaultDeviceName: () -> String,
    private val presence: NetworkPresence = NetworkPresence.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val client: ConnectClient = ConnectClient(clock, log),
    /** Empty switches announcing off, for a device that is only ever reached at its address. */
    discoveryPorts: List<Int> = ConnectProtocol.DISCOVERY_PORTS,
    /** Where this device answers. Every interface, on a port the system picks, unless a harness says otherwise. */
    listenOn: InetSocketAddress = InetSocketAddress(0),
) : AutoCloseable, ConnectCommands {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = mutableState.asStateFlow()

    private val discovery = ConnectDiscovery(presence, clock, log, discoveryPorts)
    private val server = ConnectServer(this, ::key, clock, log, listenOn)

    /** The port this device answers on, once started; 0 before. What another device is told in its announcements. */
    val port: Int get() = server.port

    @Volatile private var deviceId: String = ""
    @Volatile private var deviceName: String = ""

    /**
     * The key, in memory.
     *
     * Read from the secret store once rather than on demand. On Windows the store is DPAPI, reached by
     * running PowerShell, and the announcement loop asks for this every three seconds -- fetching it each
     * time would spawn a process twenty times a minute for a string that does not change.
     */
    @Volatile
    private var cachedKey: String? = null

    /**
     * Starting and stopping, one at a time.
     *
     * The application starts Connect at launch from the stored key and again a moment later once the
     * service has confirmed the account, from two coroutines that could overlap. Each saw no server yet and
     * opened one, and each saw discovery not running and started it: the second socket of each kept
     * answering and announcing with nothing holding it, and switching Connect off closed only the other.
     */
    private val lifecycle = Any()

    /** Whether Connect is meant to be running, so a start still reading its key does not outlive a stop. */
    @Volatile private var wanted = false

    private var pollJob: Job? = null
    private var housekeeping: Job? = null

    /** Addresses to look for a device at, as well as listening for announcements. See [addAddress]. */
    private val addresses = ConcurrentHashMap<Pair<String, Int>, String>()

    /** Set when another device has taken us over, and cleared when it hands us back or goes quiet. */
    @Volatile private var driver: Driver? = null

    private data class Driver(val id: String?, val name: String, val at: Long)

    init {
        scope.launch {
            discovery.peers.collect { peers ->
                mutableState.update { it.copy(devices = peers.sortedBy(ConnectPeer::name)) }
                // A device we were driving that has stopped announcing is gone; drop the remote control
                // rather than leaving a screen pointed at nothing.
                val target = mutableState.value.target
                if (target != null && peers.none { it.id == target.id }) release("${target.name} left the network.")
            }
        }
    }

    /**
     * Switches Connect on, with the identity it should announce.
     *
     * Called again whenever the name or the account changes; restarting is cheap and is simpler to reason
     * about than patching a running announcement.
     */
    fun start(deviceId: String, deviceName: String) {
        wanted = true
        scope.launch { startNow(deviceId, deviceName) }
    }

    private fun startNow(deviceId: String, deviceName: String) {
        // Outside the lock: on Windows this runs PowerShell, and a stop should not have to wait for it.
        loadKey()
        synchronized(lifecycle) {
            if (!wanted) return
            this.deviceId = deviceId
            this.deviceName = deviceName.ifBlank { defaultDeviceName() }
            if (key() == null) {
                // Said out loud. Without an account key there is nothing to announce and nothing that could
                // be trusted if it answered, so this is a legitimate stop -- but a silent one leaves somebody
                // looking at an empty device list with nothing anywhere telling them why.
                log("Not starting: no connect key stored yet. Sign in to Noctorium, or check the service.")
                mutableState.update {
                    it.copy(available = false, listening = false, thisDevice = this.deviceName)
                }
                return
            }
            log("Starting as \"${this.deviceName}\" (${this.deviceId})")
            val port = server.start()
            if (port == 0) {
                mutableState.update { it.copy(available = false, message = "Could not open a port for Connect.") }
                return
            }
            discovery.start(::announcement, ::key)
            if (housekeeping?.isActive != true) housekeeping = scope.launch { keepHouse() }
            mutableState.update {
                it.copy(available = true, listening = discovery.running, thisDevice = this.deviceName, message = null)
            }
        }
    }

    fun stop() {
        synchronized(lifecycle) {
            wanted = false
            pollJob?.cancel()
            pollJob = null
            housekeeping?.cancel()
            housekeeping = null
            discovery.stop()
            server.stop()
            mutableState.update {
                it.copy(available = false, listening = false, devices = emptyList(), target = null, remote = null)
            }
        }
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    /**
     * Fetches the account's connect key and keeps it.
     *
     * Asked for once per sign-in and then cached, because Connect has to work when the internet does not.
     * A device that had to reach Vercel before it could see the speaker in the next room would be useless
     * in exactly the situation it is most wanted.
     */
    suspend fun refreshKey() {
        val session = token() ?: return
        val fetched = accountClient.connectKey(session)
        if (fetched == null) {
            log("Could not fetch the connect key; keeping whatever is stored.")
            return
        }
        runCatching { secrets.put(CONNECT_KEY, fetched) }
            .onFailure { log("Could not store the connect key: ${it.message}") }
        cachedKey = fetched
    }

    fun forgetKey() {
        runCatching { secrets.remove(CONNECT_KEY) }
        cachedKey = null
        stop()
    }

    /**
     * Looks for a device at [address] as well as listening for its announcements.
     *
     * For a network that does not carry broadcasts from one device to the other -- a guest network that keeps
     * its devices apart, a computer inside a virtual machine -- and for testing through adb, which carries TCP
     * and nothing else. The address is asked what it is every few seconds, signed like every other request, so
     * only a device on the same account answers, and it is listed for as long as it does.
     */
    fun addAddress(address: String, port: Int) {
        val entry = address.trim() to port
        if (addresses.putIfAbsent(entry, "") == null) log("Looking for a device at ${hostAndPort(entry.first, port)}")
        scope.launch { probe(entry) }
    }

    fun forgetAddress(address: String, port: Int) {
        addresses.remove(address.trim() to port)
    }

    // -- driving another device ------------------------------------------------------------------

    /**
     * Moves the music to [peer], from exactly where it is.
     *
     * The local player is stopped only after the other device says it has it. Stopping first would leave a
     * silent gap on every transfer and, if the other end refused, silence and nothing playing anywhere.
     */
    fun transferTo(peer: ConnectPeer, tracks: List<Track>, index: Int, positionMs: Long) {
        if (tracks.isEmpty()) {
            say("Nothing is playing here to send to ${peer.name}.")
            return
        }
        val secret = key() ?: return say("Sign in to your Noctorium account to use Connect.")
        scope.launch {
            // Before the device list is disabled, not after: a handover that could not even be started
            // used to leave it greyed out for good.
            val from = announcement() ?: return@launch say("Connect is not running on this device.")
            mutableState.update { it.copy(busy = true, message = null) }
            val reply = client.command(
                peer, secret, from,
                Command(
                    type = CommandType.TAKE_OVER,
                    tracks = tracks.map { it.toWire() },
                    index = index.coerceIn(0, tracks.lastIndex),
                    positionMs = positionMs,
                ),
            )
            if (reply.ok) {
                log("Handed ${tracks.size} track(s) to ${peer.name} at ${positionMs}ms")
                host.standDown()
                mutableState.update {
                    it.copy(busy = false, target = peer, remote = reply.state, message = "Playing on ${peer.name}.")
                }
                watch(peer)
            } else {
                log("${peer.name} did not take the handover: ${reply.message}")
                mutableState.update { it.copy(busy = false, message = reply.message ?: "${peer.name} refused.") }
            }
        }
    }

    /** Sends one instruction to the device being driven. */
    fun control(build: () -> Command) {
        val peer = mutableState.value.target ?: return
        val secret = key() ?: return
        scope.launch {
            val from = announcement() ?: return@launch
            val command = build()
            val reply = client.command(peer, secret, from, command)
            if (reply.ok) {
                mutableState.update { it.copy(remote = reply.state ?: it.remote) }
            } else {
                log("${command.type} on ${peer.name} failed: ${reply.message}")
                mutableState.update { it.copy(message = reply.message) }
            }
        }
    }

    /** Stops driving, and leaves the other device playing. */
    fun release(message: String? = null) {
        pollJob?.cancel()
        pollJob = null
        mutableState.update { it.copy(target = null, remote = null, message = message) }
    }

    /**
     * Takes the music back from the device being driven: it stops, and says what it was playing.
     *
     * Waits for the answer, because the answer is the queue -- every song, where in it the other device had
     * got to, and how far into that one -- and starting here before it arrived would start the wrong thing.
     * A device too old to send its queue back is taken at its word for the one song it said it was on.
     */
    suspend fun bringItBack(): BroughtBack? {
        val peer = mutableState.value.target ?: return null
        val lastSeen = mutableState.value.remote
        release()
        val secret = key()
        val from = announcement()
        val reply = if (secret != null && from != null) {
            client.command(peer, secret, from, Command(type = CommandType.HAND_BACK))
        } else {
            null
        }
        say(
            if (reply?.ok == true) {
                "Brought back from ${peer.name}."
            } else {
                "Brought back here. ${peer.name} did not answer, so it may still be playing."
            },
        )
        val handover = reply?.handover?.takeIf { it.tracks.isNotEmpty() }
        if (handover != null) {
            return BroughtBack(
                handover.tracks.map(WireTrack::toTrack),
                handover.index.coerceIn(0, handover.tracks.lastIndex),
                handover.positionMs,
            )
        }
        val track = (reply?.state ?: lastSeen)?.track ?: return null
        return BroughtBack(listOf(track.toTrack()), 0, (reply?.state ?: lastSeen)?.positionMs ?: 0)
    }

    /**
     * The music has started here while another device was being driven: stop that one, and stop driving it.
     *
     * One listener, one place the music is coming from. Playing something here is choosing here.
     */
    fun playingHereInstead() {
        val peer = mutableState.value.target ?: return
        release("Stopped on ${peer.name}, to play here.")
        val secret = key() ?: return
        scope.launch {
            val from = announcement() ?: return@launch
            val reply = client.command(peer, secret, from, Command(type = CommandType.HAND_BACK))
            if (!reply.ok) say("${peer.name} did not answer, so it may still be playing.")
        }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    private fun say(message: String) = mutableState.update { it.copy(busy = false, message = message) }

    /** Keeps the remote control honest while it is on screen. */
    private fun watch(peer: ConnectPeer) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val secret = key() ?: return@launch
            var failing: String? = null
            while (isActive && mutableState.value.target?.id == peer.id) {
                val from = announcement() ?: break
                // Wherever it was last heard from, which can change while it is being driven.
                val current = mutableState.value.devices.firstOrNull { it.id == peer.id } ?: peer
                val reply = client.stateAt(current.address, current.port, secret, from, driving = true)
                val seen = reply.state
                if (seen != null) {
                    if (failing != null) log("${peer.name} is answering again")
                    failing = null
                    if (mutableState.value.target?.id == peer.id) mutableState.update { it.copy(remote = seen) }
                } else if (failing == null) {
                    // Once, not every second.
                    failing = reply.failure
                    log("Lost touch with ${peer.name}: $failing")
                }
                delay(POLL_EVERY_MS)
            }
        }
    }

    // -- being driven ----------------------------------------------------------------------------

    override suspend fun state(drivenBy: String?): RemoteState {
        // Being watched is being driven: a remote control that only reads, every second, is still on screen
        // somewhere, and this device should go on saying so for as long as it is.
        driver?.let { current -> if (drivenBy != null && current.id == drivenBy) driver = current.copy(at = clock()) }
        return snapshotAsRemote()
    }

    /**
     * Takes the instruction, says so, and gets on with it.
     *
     * The reply confirms receipt, not completion, and that distinction is the whole of this method.
     * Carrying out a handover means resolving a track through yt-dlp and starting a player, which takes
     * seconds; answering only once that finished meant the sender's four second timeout expired first.
     * The receiver would take the music and start playing while the sender concluded it had failed, so
     * it never stopped its own playback and the same song came out of both machines at once -- the one
     * thing a handover must never do.
     *
     * Nothing is lost by replying early. The sender polls state a second later and every second after,
     * so a handover that fails on this end shows up there rather than in a reply nobody waited for.
     *
     * Handing back is the exception that proves it: what is playing is read before anything is stopped,
     * because the answer to it is the queue.
     */
    override suspend fun handle(command: Command): CommandReply {
        if (command.type == CommandType.TAKE_OVER && command.tracks.isEmpty()) {
            return CommandReply(ok = false, message = "There was nothing playing to send.")
        }

        val handover = if (command.type == CommandType.HAND_BACK) queueFromHere() else null
        // Settled here rather than in the work below, so the reply and the screen agree at once.
        driver = when (command.type) {
            CommandType.HAND_BACK -> null
            else -> command.fromDeviceName?.let { Driver(command.fromDeviceId, it, clock()) } ?: driver
        }

        scope.launch {
            runCatching {
                when (command.type) {
                    CommandType.TAKE_OVER -> {
                        // Whoever is driving us now, we are no longer driving anyone else.
                        release()
                        host.takeOver(command.tracks.map(WireTrack::toTrack), command.index, command.positionMs ?: 0)
                    }
                    CommandType.HAND_BACK -> host.standDown()
                    CommandType.PLAY -> host.resume()
                    CommandType.PAUSE -> host.pause()
                    CommandType.NEXT -> host.next()
                    CommandType.PREVIOUS -> host.previous()
                    CommandType.SEEK -> host.seekTo(command.positionMs ?: 0)
                    CommandType.VOLUME -> host.setVolume(command.volume ?: 1f)
                    CommandType.SHUFFLE -> host.setShuffle(command.enabled ?: false)
                    CommandType.REPEAT -> host.setRepeat(command.repeat ?: "OFF")
                }
            }.onFailure { log("Command ${command.type} failed: $it") }
        }

        log("${command.type} from ${command.fromDeviceName ?: "another device"}")
        mutableState.update { it.copy(controlledBy = currentDriver()) }
        return CommandReply(ok = true, state = expected(command), handover = handover)
    }

    private fun queueFromHere(): Handover? {
        val queue = host.queue()
        if (queue.tracks.isEmpty()) return null
        return Handover(
            tracks = queue.tracks.map { it.toWire() },
            index = queue.index.coerceIn(0, queue.tracks.lastIndex),
            positionMs = host.snapshot().positionMs,
        )
    }

    /**
     * What this device is about to be doing, for the reply that goes back before it is doing it.
     *
     * Only a handover needs anticipating: it is the one command that changes what is playing, and a
     * remote control that drew the previous track for a second would look broken at the exact moment
     * somebody was watching it.
     */
    private fun expected(command: Command): RemoteState {
        val now = snapshotAsRemote()
        if (command.type == CommandType.HAND_BACK) return now.copy(playing = false)
        if (command.type != CommandType.TAKE_OVER) return now
        val track = command.tracks.getOrNull(command.index.coerceIn(0, command.tracks.lastIndex))
        return now.copy(
            playing = true,
            track = track ?: now.track,
            positionMs = command.positionMs ?: 0,
            durationMs = track?.durationMs ?: now.durationMs,
            queueSize = command.tracks.size,
            queueIndex = command.index.coerceAtLeast(0),
        )
    }

    private fun snapshotAsRemote(): RemoteState {
        val snapshot = host.snapshot()
        return RemoteState(
            deviceId = deviceId,
            deviceName = deviceName,
            kind = kind,
            playing = snapshot.playing,
            track = snapshot.track,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            volume = snapshot.volume,
            queueSize = snapshot.queueSize,
            queueIndex = snapshot.queueIndex,
            shuffle = snapshot.shuffle,
            repeat = snapshot.repeat,
            controlledBy = currentDriver(),
        )
    }

    /** Forgotten if nothing has been heard for a while, so a closed laptop does not own this forever. */
    private fun currentDriver(): String? {
        val current = driver ?: return null
        if (clock() - current.at > DRIVER_FORGOTTEN_AFTER_MS) {
            driver = null
            return null
        }
        return current.name
    }

    // -- upkeep ----------------------------------------------------------------------------------

    /**
     * The few things that have to happen whether or not anything arrives: asking the addresses devices were
     * added at whether they are still there, forgetting the ones that are not, and letting "being controlled
     * by" go once its device has gone quiet -- which it used to say until the next command, however long that was.
     */
    private suspend fun keepHouse() {
        while (scope.isActive) {
            addresses.keys.forEach { probe(it) }
            discovery.prune()
            val driving = currentDriver()
            if (mutableState.value.controlledBy != driving) mutableState.update { it.copy(controlledBy = driving) }
            delay(ConnectProtocol.ANNOUNCE_EVERY_MS)
        }
    }

    private suspend fun probe(entry: Pair<String, Int>) {
        val (address, port) = entry
        val secret = key() ?: return
        val from = announcement() ?: return
        val reply = client.stateAt(address, port, secret, from)
        val seen = reply.state
        val where = hostAndPort(address, port)
        val outcome = when {
            seen == null -> "Nothing answered at $where: ${reply.failure}"
            seen.deviceId == deviceId -> "$where answered, and is this device (${seen.deviceName})"
            else -> "$where answered as ${seen.deviceName} (${seen.kind})"
        }
        // Said when it changes, not every few seconds.
        if (addresses.containsKey(entry) && addresses.put(entry, outcome) != outcome) log(outcome)
        if (seen == null || seen.deviceId == deviceId || !wanted || !addresses.containsKey(entry)) return
        discovery.heard(ConnectPeer(seen.deviceId, seen.deviceName, seen.kind, address, port, clock()))
    }

    // -- identity --------------------------------------------------------------------------------

    private fun key(): String? = cachedKey

    private fun loadKey() {
        cachedKey = runCatching { secrets.get(CONNECT_KEY) }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun announcement(): DeviceAnnouncement? {
        val secret = key() ?: return null
        if (deviceId.isBlank()) return null
        val port = server.port.takeIf { it > 0 } ?: return null
        val stamp = clock() / 60_000
        val unsigned = DeviceAnnouncement(
            id = deviceId,
            name = deviceName,
            kind = kind,
            port = port,
            stamp = stamp,
            tag = "",
        )
        return unsigned.copy(tag = ConnectSignature.sign(secret, unsigned.signable()))
    }

    companion object {
        const val CONNECT_KEY = "noctorium.connect_key"

        /** Fast enough that a remote seek bar moves, slow enough to be invisible on a battery. */
        private const val POLL_EVERY_MS = 1_000L

        private const val DRIVER_FORGOTTEN_AFTER_MS = 30_000L
    }
}
