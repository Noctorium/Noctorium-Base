package app.spiceity.connect

import app.spiceity.account.SpiceityAccountClient
import app.spiceity.domain.Track
import app.spiceity.settings.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

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

/**
 * What Connect needs the player to be able to do.
 *
 * Deliberately the smallest surface that covers a remote control and a handover. `AppState` implements it,
 * and nothing in `connect` knows anything else about the player -- which is what keeps this testable
 * without a running application, and keeps the transport out of the player.
 */
interface ConnectHost {
    fun snapshot(): PlaybackSnapshot

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

/**
 * Spiceity Connect.
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
    private val accountClient: SpiceityAccountClient,
    private val token: () -> String?,
    private val kind: DeviceKind,
    private val defaultDeviceName: () -> String,
    private val presence: NetworkPresence = NetworkPresence.None,
    private val client: ConnectClient = ConnectClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : AutoCloseable, ConnectCommands {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = mutableState.asStateFlow()

    private val discovery = ConnectDiscovery(presence, clock, log)
    private val server = ConnectServer(this, ::key, clock, log)

    private var deviceId: String = ""

    /**
     * The key, in memory.
     *
     * Read from the secret store once rather than on demand. On Windows the store is DPAPI, reached by
     * running PowerShell, and the announcement loop asks for this every three seconds -- fetching it each
     * time would spawn a process twenty times a minute for a string that does not change.
     */
    @Volatile
    private var cachedKey: String? = null
    private var deviceName: String = ""
    private var pollJob: Job? = null

    /** Set when another device has taken us over, and cleared when it hands us back or goes quiet. */
    private var driver: Pair<String, Long>? = null

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
        scope.launch { startNow(deviceId, deviceName) }
    }

    private fun startNow(deviceId: String, deviceName: String) {
        this.deviceId = deviceId
        this.deviceName = deviceName.ifBlank { defaultDeviceName() }
        loadKey()
        if (key() == null) {
            // Said out loud. Without an account key there is nothing to announce and nothing that could
            // be trusted if it answered, so this is a legitimate stop -- but a silent one leaves somebody
            // looking at an empty device list with nothing anywhere telling them why.
            log("Not starting: no connect key stored yet. Sign in to Spiceity, or check the service.")
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
        mutableState.update {
            it.copy(available = true, listening = discovery.running, thisDevice = this.deviceName, message = null)
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        discovery.stop()
        server.stop()
        mutableState.update {
            it.copy(available = false, listening = false, devices = emptyList(), target = null, remote = null)
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

    // -- driving another device ------------------------------------------------------------------

    /**
     * Moves the music to [peer], from exactly where it is.
     *
     * The local player is stopped only after the other device says it has it. Stopping first would leave a
     * silent gap on every transfer and, if the other end refused, silence and nothing playing anywhere.
     */
    fun transferTo(peer: ConnectPeer, tracks: List<Track>, index: Int, positionMs: Long) {
        val secret = key() ?: return
        scope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            val reply = client.command(
                peer, secret, announcement() ?: return@launch,
                Command(
                    type = CommandType.TAKE_OVER,
                    tracks = tracks.map { it.toWire() },
                    index = index,
                    positionMs = positionMs,
                ),
            )
            if (reply.ok) {
                host.standDown()
                mutableState.update {
                    it.copy(busy = false, target = peer, remote = reply.state, message = "Playing on ${peer.name}.")
                }
                watch(peer)
            } else {
                mutableState.update { it.copy(busy = false, message = reply.message ?: "${peer.name} refused.") }
            }
        }
    }

    /** Sends one instruction to the device being driven. */
    fun control(build: () -> Command) {
        val peer = mutableState.value.target ?: return
        val secret = key() ?: return
        scope.launch {
            val reply = client.command(peer, secret, announcement() ?: return@launch, build())
            if (reply.ok) mutableState.update { it.copy(remote = reply.state ?: it.remote) }
            else mutableState.update { it.copy(message = reply.message) }
        }
    }

    /** Stops driving, and leaves the other device playing. */
    fun release(message: String? = null) {
        pollJob?.cancel()
        pollJob = null
        mutableState.update { it.copy(target = null, remote = null, message = message) }
    }

    /** Asks the device being driven to stop, so the music can come back here. */
    fun bringItBack(): Pair<List<Track>, Long>? {
        val remote = mutableState.value.remote ?: return null
        control { Command(type = CommandType.HAND_BACK) }
        release("Brought back from ${mutableState.value.target?.name.orEmpty()}.")
        val track = remote.track?.toTrack() ?: return null
        return listOf(track) to remote.positionMs
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    /** Keeps the remote control honest while it is on screen. */
    private fun watch(peer: ConnectPeer) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val secret = key() ?: return@launch
            while (mutableState.value.target?.id == peer.id) {
                val seen = client.state(peer, secret, announcement() ?: break)
                if (seen != null) mutableState.update { it.copy(remote = seen) }
                delay(POLL_EVERY_MS)
            }
        }
    }

    // -- being driven ----------------------------------------------------------------------------

    override suspend fun state(): RemoteState = snapshotAsRemote()

    override suspend fun handle(command: Command): CommandReply {
        command.fromDeviceName?.let { driver = it to clock() }
        runCatching {
            when (command.type) {
                CommandType.TAKE_OVER -> {
                    // Whoever is driving us now, we are no longer driving anyone else.
                    release()
                    host.takeOver(command.tracks.map(WireTrack::toTrack), command.index, command.positionMs ?: 0)
                }
                CommandType.HAND_BACK -> {
                    host.standDown()
                    driver = null
                }
                CommandType.PLAY -> host.resume()
                CommandType.PAUSE -> host.pause()
                CommandType.NEXT -> host.next()
                CommandType.PREVIOUS -> host.previous()
                CommandType.SEEK -> host.seekTo(command.positionMs ?: 0)
                CommandType.VOLUME -> host.setVolume(command.volume ?: 1f)
                CommandType.SHUFFLE -> host.setShuffle(command.enabled ?: false)
                CommandType.REPEAT -> host.setRepeat(command.repeat ?: "OFF")
            }
        }.onFailure {
            log("Command ${command.type} failed: $it")
            return CommandReply(ok = false, message = "That did not work here.")
        }
        mutableState.update { it.copy(controlledBy = currentDriver()) }
        return CommandReply(ok = true, state = snapshotAsRemote())
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
        val (name, at) = driver ?: return null
        if (clock() - at > DRIVER_FORGOTTEN_AFTER_MS) {
            driver = null
            return null
        }
        return name
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
        const val CONNECT_KEY = "spiceity.connect_key"

        /** Fast enough that a remote seek bar moves, slow enough to be invisible on a battery. */
        private const val POLL_EVERY_MS = 1_000L

        private const val DRIVER_FORGOTTEN_AFTER_MS = 30_000L
    }
}
