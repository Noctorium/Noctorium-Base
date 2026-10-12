package app.noctorium.connect

import app.noctorium.account.NoctoriumAccountClient
import app.noctorium.domain.Track
import app.noctorium.settings.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
     * The whole queue, for a device taking the music back from this one, or showing it in a queue panel.
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

    /** Plays the song at [index] in the queue: a remote control's queue panel, tapped. */
    suspend fun jumpTo(index: Int) {}

    /** Puts [tracks] in the queue: after the song playing when [next], at the end otherwise. */
    suspend fun addToQueue(tracks: List<Track>, next: Boolean) {}

    suspend fun removeFromQueue(index: Int) {}
    suspend fun moveInQueue(from: Int, to: Int) {}

    /** Stop playing here, because somebody else is taking it. */
    suspend fun standDown()
}

/**
 * Where the music is, as one device sees it: what a Connect page puts first, and what the player shows.
 */
sealed interface MusicPlace {
    /** On this device: playing, paused, or ready to. */
    data object Here : MusicPlace

    /** On [peer], with this device as its remote control: the player here shows and drives that one. */
    data class Driving(val peer: ConnectPeer) : MusicPlace

    /** On [peer], playing by itself: this device can take it, or send it on, but is not driving it. */
    data class Elsewhere(val peer: ConnectPeer) : MusicPlace

    /** Nothing anywhere. */
    data object Nowhere : MusicPlace
}

/** Everything the UI shows about Connect. */
data class ConnectState(
    /** Signed in, with a key, and switched on. Without all three there is nothing to show. */
    val available: Boolean = false,
    val listening: Boolean = false,
    val thisDevice: String = "",
    /** The other devices, each with what it is playing and who is driving whom, as it last said. */
    val devices: List<ConnectPeer> = emptyList(),
    /** The device this one is currently driving, if any. */
    val target: ConnectPeer? = null,
    /** What that device is doing, refreshed while it is being driven. */
    val remote: RemoteState? = null,
    /** The name of the device driving this one, when it is the other way round. */
    val controlledBy: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    /** This device's own id, as the others know it. */
    val thisDeviceId: String = "",
    /** When [remote] was heard, by this device's clock: what its position is as of. */
    val remoteAtMs: Long = 0,
    /**
     * The driven device's whole queue, once it has been asked for: null until then, and from a device too old
     * to give it, when the one song in [remote] is all there is to show.
     */
    val remoteQueue: QueueSnapshot? = null,
    /** The id of the device driving this one, beside [controlledBy], its name. */
    val controlledById: String? = null,
) {
    /** How far into its song the driven device is at [nowMs], moving on from what it last said while it plays. */
    fun remotePositionAt(nowMs: Long): Long {
        val remote = remote ?: return 0
        val moved = if (remote.playing) remote.positionMs + (nowMs - remoteAtMs).coerceAtLeast(0) else remote.positionMs
        return if (remote.durationMs > 0) moved.coerceAtMost(remote.durationMs) else moved
    }

    /**
     * Where the music is, from here.
     *
     * The device being driven, when one is. Otherwise this device when it is playing, then another device
     * that is, then this one if it has a song at all, paused or ready, then another with one paused.
     */
    fun musicPlace(herePlaying: Boolean, hereHasSong: Boolean): MusicPlace {
        target?.let { driven -> return MusicPlace.Driving(devices.firstOrNull { it.id == driven.id } ?: driven) }
        if (herePlaying) return MusicPlace.Here
        devices.firstOrNull { it.nowPlaying?.playing == true }?.let { return MusicPlace.Elsewhere(it) }
        if (hereHasSong) return MusicPlace.Here
        devices.firstOrNull { it.nowPlaying != null }?.let { return MusicPlace.Elsewhere(it) }
        return MusicPlace.Nowhere
    }
}

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
 *
 * Every device also says what it is playing, and who is driving whom, in its announcements -- and says it at
 * once when that changes -- so each device's Connect page shows the same picture of all of them.
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

    /** Wakes the remote control's polling before its second is up: something changed over there. */
    private val pollNow = Channel<Unit>(Channel.CONFLATED)

    /**
     * Counts what the remote control has asked for, so an answer to an older question cannot undo a newer one.
     *
     * Pause is shown paused at once, before the other device has answered. A state read a moment before the
     * pause was sent, and arriving a moment after, would otherwise show it playing again for a second.
     */
    private val asked = AtomicLong()

    /** The key of the driven device's queue as last fetched, so it is fetched again only when it changes. */
    @Volatile private var remoteQueueKey: String? = null

    /** The latest of each kind of instruction sent as fast as a slider moves, and which are on their way. */
    private val latest = ConcurrentHashMap<CommandType, Command>()
    private val sending = ConcurrentHashMap.newKeySet<CommandType>()

    /** Addresses to look for a device at, as well as listening for announcements. See [addAddress]. */
    private val addresses = ConcurrentHashMap<Pair<String, Int>, String>()

    /** Set when another device has taken us over, and cleared when it hands us back or goes quiet. */
    @Volatile private var driver: Driver? = null

    private data class Driver(val id: String?, val name: String, val at: Long)

    init {
        scope.launch {
            discovery.peers.collect { peers ->
                val before = mutableState.value
                mutableState.update { it.copy(devices = peers.sortedBy(ConnectPeer::name)) }
                // The device driving this one says it has let go -- it is driving another, or nobody. Believed
                // within moments, rather than "controlled from" lingering for the half minute it takes to give up on
                // hearing from it; a device that says nothing about whom it drives is still given that half minute.
                // Not while it is still in touch, though: its commands and its remote control's questions come several
                // times a second, and an announcement sent just before it learned the handover had worked can arrive
                // just after -- saying, a moment out of date, that it drives nobody.
                driver?.let { current ->
                    val said = peers.firstOrNull { it.id == current.id }
                    val quiet = clock() - current.at > LET_GO_BELIEVED_AFTER_MS
                    if (said != null && said.tellsWhomItDrives && said.driving?.id != deviceId && quiet) {
                        driver = null
                        mutableState.update { it.copy(controlledBy = null, controlledById = null) }
                        discovery.announceNow()
                    }
                }
                // A device we were driving that has stopped announcing is gone; drop the remote control
                // rather than leaving a screen pointed at nothing.
                val target = before.target ?: return@collect
                val heard = peers.firstOrNull { it.id == target.id }
                if (heard == null) {
                    release("${target.name} left the network.")
                    return@collect
                }
                // It said something changed -- its own buttons, or another remote's -- before the next poll
                // would have found out. Asked now, so this device's player follows within the moment.
                if (differs(heard.nowPlaying, before.remote)) pollNow.trySend(Unit)
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
                    it.copy(available = false, listening = false, thisDevice = this.deviceName, thisDeviceId = deviceId)
                }
                return
            }
            log("Starting as \"${this.deviceName}\" (${this.deviceId})")
            val port = server.start()
            if (port == 0) {
                mutableState.update { it.copy(available = false, message = "Could not open a port for Connect.") }
                return
            }
            discovery.start({ announcement() }, ::key)
            if (housekeeping?.isActive != true) housekeeping = scope.launch { keepHouse() }
            mutableState.update {
                it.copy(
                    available = true,
                    listening = discovery.running,
                    thisDevice = this.deviceName,
                    thisDeviceId = deviceId,
                    message = null,
                )
            }
            discovery.announceNow()
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
                it.copy(
                    available = false,
                    listening = false,
                    devices = emptyList(),
                    target = null,
                    remote = null,
                    remoteQueue = null,
                )
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

    /**
     * Tells the other devices what this one is doing now, rather than at the next beat.
     *
     * The application calls this when its song changes, or starts or stops, so every Connect page on the
     * network shows it within the moment.
     */
    fun announceNow() = discovery.announceNow()

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
            val from = identity() ?: return@launch say("Connect is not running on this device.")
            mutableState.update { it.copy(busy = true, message = null) }
            val reply = client.command(peer, secret, from, takeOver(tracks.map { it.toWire() }, index, positionMs))
            if (reply.ok) {
                log("Handed ${tracks.size} track(s) to ${peer.name} at ${positionMs}ms")
                host.standDown()
                drive(peer, reply.state, "Playing on ${peer.name}.")
            } else {
                log("${peer.name} did not take the handover: ${reply.message}")
                mutableState.update { it.copy(busy = false, message = reply.message ?: "${peer.name} refused.") }
            }
        }
    }

    /**
     * Moves the music from [from] to [to], wherever each is: null for either is this device.
     *
     * From here it is [transferTo], with this device's own queue. To here, the music comes back as it does
     * from a device being driven -- its whole queue, from where it had got to -- whether or not this device was
     * driving it. From one other device to another, it comes back the same way and goes straight on, and this
     * device is the new one's remote control, as it would be had it sent the music there itself. Should the
     * new one refuse, the music goes back to where it was rather than being left nowhere.
     *
     * [here] is what this device should play when the music comes to it: the application starts it.
     */
    fun move(from: ConnectPeer?, to: ConnectPeer?, here: suspend (BroughtBack) -> Unit) {
        if (from?.id == to?.id) return
        if (from == null) {
            val queue = host.queue()
            transferTo(to ?: return, queue.tracks, queue.index, host.snapshot().positionMs)
            return
        }
        val secret = key() ?: return say("Sign in to your Noctorium account to use Connect.")
        scope.launch {
            val identity = identity() ?: return@launch say("Connect is not running on this device.")
            mutableState.update { it.copy(busy = true, message = null) }
            val lastSeen = mutableState.value.remote?.takeIf { mutableState.value.target?.id == from.id }
            if (mutableState.value.target?.id == from.id) release()
            val back = client.command(from, secret, identity, Command(type = CommandType.HAND_BACK))
            val music = musicIn(back, lastSeen)
            if (music == null) {
                return@launch say(if (back.ok) "Nothing is playing on ${from.name}." else back.message ?: "${from.name} did not answer.")
            }
            if (to == null) {
                log("Brought ${music.tracks.size} track(s) here from ${from.name}")
                say(if (back.ok) "Brought here from ${from.name}." else "Playing here. ${from.name} did not answer, so it may still be playing.")
                here(music)
                return@launch
            }
            val wire = music.tracks.map { it.toWire() }
            val reply = client.command(to, secret, identity, takeOver(wire, music.index, music.positionMs))
            if (reply.ok) {
                log("Moved ${music.tracks.size} track(s) from ${from.name} to ${to.name}")
                drive(to, reply.state, "Playing on ${to.name}.")
            } else {
                // Back where it was, rather than nowhere.
                val restored = back.ok && client.command(from, secret, identity, takeOver(wire, music.index, music.positionMs)).ok
                say(
                    (reply.message ?: "${to.name} did not take it.") +
                        if (restored) " It is still playing on ${from.name}." else "",
                )
            }
        }
    }

    /** What came back from a device asked to hand its music over: its whole queue, or the one song it was on. */
    private fun musicIn(back: CommandReply, lastSeen: RemoteState?): BroughtBack? {
        back.handover?.takeIf { it.tracks.isNotEmpty() }?.let { handover ->
            return BroughtBack(
                handover.tracks.map(WireTrack::toTrack),
                handover.index.coerceIn(0, handover.tracks.lastIndex),
                handover.positionMs,
            )
        }
        val said = back.state?.takeIf { it.track != null } ?: lastSeen
        val track = said?.track ?: return null
        return BroughtBack(listOf(track.toTrack()), 0, said.positionMs)
    }

    private fun takeOver(tracks: List<WireTrack>, index: Int, positionMs: Long) = Command(
        type = CommandType.TAKE_OVER,
        tracks = tracks,
        index = index.coerceIn(0, tracks.lastIndex),
        positionMs = positionMs,
    )

    /** From now on this device is [peer]'s remote control. */
    private fun drive(peer: ConnectPeer, state: RemoteState?, message: String) {
        asked.incrementAndGet()
        remoteQueueKey = null
        mutableState.update {
            it.copy(
                busy = false,
                target = peer,
                remote = state,
                remoteAtMs = clock(),
                remoteQueue = null,
                message = message,
            )
        }
        watch(peer)
        discovery.announceNow()
    }

    /** Sends one instruction to the device being driven, and shows what it should do at once. */
    fun control(build: () -> Command) {
        val peer = mutableState.value.target ?: return
        val secret = key() ?: return
        val command = build()
        val question = asked.incrementAndGet()
        expectHere(command)
        scope.launch { send(peer, secret, command, question) }
    }

    /**
     * Sends one instruction of a kind that comes as fast as a slider moves -- seeking, the volume -- and only
     * the latest of them: while one is on its way, those after it replace each other, and the last is sent
     * when it lands. Each is shown at once all the same.
     */
    fun controlLatest(command: Command) {
        val peer = mutableState.value.target ?: return
        val secret = key() ?: return
        asked.incrementAndGet()
        expectHere(command)
        latest[command.type] = command
        if (!sending.add(command.type)) return
        scope.launch {
            try {
                while (true) {
                    val next = latest.remove(command.type) ?: break
                    send(peer, secret, next, asked.get())
                }
            } finally {
                sending.remove(command.type)
            }
            // One that arrived between the last look and letting go would otherwise wait for the next.
            latest.remove(command.type)?.let(::controlLatest)
        }
    }

    private suspend fun send(peer: ConnectPeer, secret: String, command: Command, question: Long) {
        val from = identity() ?: return
        val reply = client.command(peer, secret, from, command)
        if (reply.ok) {
            // Only if nothing newer has been asked since: that has already been shown, and this would undo it.
            if (asked.get() == question && mutableState.value.target?.id == peer.id) {
                reply.state?.let { seen -> mutableState.update { it.copy(remote = seen, remoteAtMs = clock()) } }
            }
        } else {
            log("${command.type} on ${peer.name} failed: ${reply.message}")
            mutableState.update { it.copy(message = reply.message) }
        }
    }

    /** What the driven device will be doing once [command] lands, shown before it has: the player follows at once. */
    private fun expectHere(command: Command) {
        mutableState.update { state ->
            val remote = state.remote ?: return@update state
            val now = clock()
            val expected = when (command.type) {
                CommandType.PLAY -> remote.copy(playing = remote.track != null, positionMs = state.remotePositionAt(now))
                CommandType.PAUSE -> remote.copy(playing = false, positionMs = state.remotePositionAt(now))
                CommandType.SEEK -> remote.copy(positionMs = command.positionMs ?: 0)
                CommandType.VOLUME -> remote.copy(volume = command.volume ?: remote.volume)
                CommandType.SHUFFLE -> remote.copy(shuffle = command.enabled ?: remote.shuffle)
                CommandType.REPEAT -> remote.copy(repeat = command.repeat ?: remote.repeat)
                else -> return@update state
            }
            state.copy(remote = expected, remoteAtMs = now)
        }
    }

    /** Stops driving, and leaves the other device playing. */
    fun release(message: String? = null) {
        val wasDriving = mutableState.value.target != null
        pollJob?.cancel()
        pollJob = null
        remoteQueueKey = null
        mutableState.update { it.copy(target = null, remote = null, remoteQueue = null, message = message) }
        if (wasDriving) discovery.announceNow()
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
        val from = identity()
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
        return musicIn(reply ?: CommandReply(ok = false), lastSeen)
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
            val from = identity() ?: return@launch
            val reply = client.command(peer, secret, from, Command(type = CommandType.HAND_BACK))
            if (!reply.ok) say("${peer.name} did not answer, so it may still be playing.")
        }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    private fun say(message: String) = mutableState.update { it.copy(busy = false, message = message) }

    /**
     * Keeps the remote control honest while it is on screen: what the other device is doing every second,
     * sooner when it says something changed, and its whole queue each time the songs in it change.
     */
    private fun watch(peer: ConnectPeer) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val secret = key() ?: return@launch
            var failing: String? = null
            while (isActive && mutableState.value.target?.id == peer.id) {
                val from = identity() ?: break
                // Wherever it was last heard from, which can change while it is being driven.
                val current = mutableState.value.devices.firstOrNull { it.id == peer.id } ?: peer
                val question = asked.get()
                val reply = client.stateAt(current.address, current.port, secret, from, driving = true)
                val seen = reply.state
                if (seen != null) {
                    if (failing != null) log("${peer.name} is answering again")
                    failing = null
                    if (mutableState.value.target?.id == peer.id && asked.get() == question) {
                        mutableState.update { it.copy(remote = seen, remoteAtMs = clock()) }
                    }
                    // Listed by its answers as well as its announcements: a device that can be driven is here.
                    discovery.heard(peerFrom(seen, current.address, current.port))
                    if (seen.queueKey != null && seen.queueKey != remoteQueueKey) fetchQueue(current, secret, from, seen.queueKey)
                } else if (failing == null) {
                    // Once, not every second.
                    failing = reply.failure
                    log("Lost touch with ${peer.name}: $failing")
                }
                withTimeoutOrNull(POLL_EVERY_MS) { pollNow.receive() }
            }
        }
    }

    private suspend fun fetchQueue(peer: ConnectPeer, secret: String, from: DeviceAnnouncement, key: String) {
        val queue = client.queue(peer, secret, from) ?: return
        if (mutableState.value.target?.id != peer.id) return
        remoteQueueKey = key
        mutableState.update {
            it.copy(remoteQueue = QueueSnapshot(queue.tracks.map(WireTrack::toTrack), queue.index))
        }
    }

    /** Whether what a device announced differs from what its remote control last read of it. */
    private fun differs(heard: NowPlaying?, remote: RemoteState?): Boolean {
        if (remote == null) return false
        val track = remote.track
        if ((heard == null) != (track == null)) return true
        if (heard == null || track == null) return false
        return heard.playing != remote.playing || heard.title != track.title
    }

    // -- being driven ----------------------------------------------------------------------------

    override suspend fun state(drivenBy: String?): RemoteState {
        // Being watched is being driven: a remote control that only reads, every second, is still on screen
        // somewhere, and this device should go on saying so for as long as it is.
        driver?.let { current -> if (drivenBy != null && current.id == drivenBy) driver = current.copy(at = clock()) }
        return snapshotAsRemote()
    }

    override suspend fun queue(): RemoteQueue? {
        val queue = host.queue()
        if (queue.tracks.isEmpty()) return null
        return RemoteQueue(queue.tracks.map { it.toWire() }, queue.index, queueKeyOf(queue.tracks))
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
                    CommandType.JUMP -> host.jumpTo(command.index)
                    CommandType.ADD -> host.addToQueue(command.tracks.map(WireTrack::toTrack), next = command.enabled == true)
                    CommandType.REMOVE -> host.removeFromQueue(command.index)
                    CommandType.MOVE -> host.moveInQueue(command.index, command.toIndex ?: command.index)
                }
            }.onFailure { log("Command ${command.type} failed: $it") }
            // Every other device's Connect page shows what this one is doing; it has just changed.
            discovery.announceNow()
        }

        log("${command.type} from ${command.fromDeviceName ?: "another device"}")
        mutableState.update { it.copy(controlledBy = currentDriver(), controlledById = driver?.id) }
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
     * A handover changes what is playing, and a remote control that drew the previous track for a second would
     * look broken at the exact moment somebody was watching it. The rest say what they will have done -- paused,
     * moved to this second, at this volume -- so the remote control that sent them shows it at once and does not
     * flick back while the work is still under way here.
     */
    private fun expected(command: Command): RemoteState {
        val now = snapshotAsRemote()
        return when (command.type) {
            CommandType.HAND_BACK -> now.copy(playing = false)
            CommandType.PLAY -> now.copy(playing = now.track != null)
            CommandType.PAUSE -> now.copy(playing = false)
            CommandType.SEEK -> now.copy(positionMs = command.positionMs ?: 0)
            CommandType.VOLUME -> now.copy(volume = command.volume ?: now.volume)
            CommandType.SHUFFLE -> now.copy(shuffle = command.enabled ?: now.shuffle)
            CommandType.REPEAT -> now.copy(repeat = command.repeat ?: now.repeat)
            CommandType.TAKE_OVER -> {
                val track = command.tracks.getOrNull(command.index.coerceIn(0, command.tracks.lastIndex))
                now.copy(
                    playing = true,
                    track = track ?: now.track,
                    positionMs = command.positionMs ?: 0,
                    durationMs = track?.durationMs ?: now.durationMs,
                    queueSize = command.tracks.size,
                    queueIndex = command.index.coerceAtLeast(0),
                    queueKey = queueKeyOf(command.tracks.map(WireTrack::toTrack)),
                )
            }
            else -> now
        }
    }

    private fun snapshotAsRemote(): RemoteState {
        val snapshot = host.snapshot()
        val queue = host.queue()
        val driving = mutableState.value.target
        return RemoteState(
            deviceId = deviceId,
            deviceName = deviceName,
            kind = kind.onTheWire,
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
            controlledById = driver?.id,
            driving = driving?.let { DeviceRef(it.id, it.name) },
            terminal = kind == DeviceKind.TERMINAL,
            queueKey = queue.tracks.takeIf { it.isNotEmpty() }?.let(::queueKeyOf),
            tellsWhomItDrives = true,
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
            if (mutableState.value.controlledBy != driving) {
                mutableState.update { it.copy(controlledBy = driving, controlledById = driver?.id) }
                discovery.announceNow()
            }
            delay(ConnectProtocol.ANNOUNCE_EVERY_MS)
        }
    }

    private suspend fun probe(entry: Pair<String, Int>) {
        val (address, port) = entry
        val secret = key() ?: return
        val from = identity() ?: return
        val reply = client.stateAt(address, port, secret, from)
        val seen = reply.state
        val where = hostAndPort(address, port)
        val outcome = when {
            seen == null -> "Nothing answered at $where: ${reply.failure}"
            seen.deviceId == deviceId -> "$where answered, and is this device (${seen.deviceName})"
            else -> "$where answered as ${seen.deviceName} (${seen.deviceKind})"
        }
        // Said when it changes, not every few seconds.
        if (addresses.containsKey(entry) && addresses.put(entry, outcome) != outcome) log(outcome)
        if (seen == null || seen.deviceId == deviceId || !wanted || !addresses.containsKey(entry)) return
        discovery.heard(peerFrom(seen, address, port))
    }

    /** A device as its own answer describes it, reached at [address]. */
    private fun peerFrom(seen: RemoteState, address: String, port: Int) = ConnectPeer(
        id = seen.deviceId,
        name = seen.deviceName,
        kind = seen.deviceKind,
        address = address,
        port = port,
        lastSeenAtMs = clock(),
        nowPlaying = seen.nowPlaying,
        drivenBy = seen.controlledById?.let { DeviceRef(it, seen.controlledBy.orEmpty()) },
        driving = seen.driving,
        tellsWhomItDrives = seen.tellsWhomItDrives,
    )

    // -- identity --------------------------------------------------------------------------------

    private fun key(): String? = cachedKey

    private fun loadKey() {
        cachedKey = runCatching { secrets.get(CONNECT_KEY) }.getOrNull()?.takeIf(String::isNotBlank)
    }

    /** Who this device is, as every request says: an announcement with nothing else in it. */
    private fun identity(): DeviceAnnouncement? {
        val secret = key() ?: return null
        if (deviceId.isBlank()) return null
        val port = server.port.takeIf { it > 0 } ?: return null
        val unsigned = DeviceAnnouncement(
            id = deviceId,
            name = deviceName,
            kind = kind.onTheWire,
            port = port,
            stamp = clock() / 60_000,
            tag = "",
        )
        return unsigned.copy(tag = ConnectSignature.sign(secret, unsigned.signable()))
    }

    /**
     * What this device announces: who it is, what it is playing, and who is driving whom.
     *
     * Kept to one unfragmented packet, and well inside what a device from before these extras reads -- a
     * longer one would reach it cut short and be dropped whole. A cover's address goes first if it does not
     * fit, then the title and artist are shortened, and only then the song itself.
     */
    private fun announcement(): DeviceAnnouncement? {
        val secret = key() ?: return null
        val base = identity() ?: return null
        var extra = extraFromHere()
        repeat(SHORTENINGS) { attempt ->
            val text = ConnectProtocol.json.encodeToString(AnnouncementExtra.serializer(), extra)
            val unsigned = base.copy(extra = text)
            val signed = unsigned.copy(extraTag = ConnectSignature.sign(secret, unsigned.signableWithExtra()))
            val size = ConnectProtocol.json.encodeToString(DeviceAnnouncement.serializer(), signed).toByteArray(Charsets.UTF_8).size
            if (size <= MAX_ANNOUNCEMENT_BYTES) return signed
            val playing = extra.nowPlaying
            extra = when (attempt) {
                0 -> extra.copy(nowPlaying = playing?.copy(artworkUrl = null))
                1 -> extra.copy(nowPlaying = playing?.copy(title = playing.title.take(SHORT_TEXT), artist = playing.artist.take(SHORT_TEXT)))
                else -> extra.copy(nowPlaying = null)
            }
        }
        return base
    }

    private fun extraFromHere(): AnnouncementExtra {
        val snapshot = host.snapshot()
        val driven = mutableState.value.target
        val drivenBy = driver?.takeIf { currentDriver() != null }
        return AnnouncementExtra(
            terminal = kind == DeviceKind.TERMINAL,
            nowPlaying = snapshot.track?.nowPlaying(snapshot.playing, snapshot.positionMs, snapshot.durationMs),
            drivenBy = drivenBy?.let { DeviceRef(it.id.orEmpty(), it.name) },
            driving = driven?.let { DeviceRef(it.id, it.name) },
        )
    }

    companion object {
        const val CONNECT_KEY = "noctorium.connect_key"

        /** Fast enough that a remote seek bar moves, slow enough to be invisible on a battery. */
        private const val POLL_EVERY_MS = 1_000L

        private const val DRIVER_FORGOTTEN_AFTER_MS = 30_000L

        /** How long a driving device must have been quiet before its saying it drives nobody is believed. */
        private const val LET_GO_BELIEVED_AFTER_MS = 1_500L

        /**
         * The most an announcement may be: inside one Ethernet frame, so it is never sent in pieces, and far
         * inside the 2048 bytes a device from before announcements carried a song reads at most.
         */
        internal const val MAX_ANNOUNCEMENT_BYTES = 1_400
        private const val SHORTENINGS = 4
        private const val SHORT_TEXT = 60

        /** A short key for a queue's songs, which changes when they do. Not a security measure; a cache key. */
        internal fun queueKeyOf(tracks: List<Track>): String {
            var hash = 1125899906842597L
            for (track in tracks) for (char in track.queueKey) hash = 31 * hash + char.code
            return "${tracks.size}-${java.lang.Long.toHexString(hash)}"
        }
    }
}
