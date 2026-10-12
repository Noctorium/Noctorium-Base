package app.noctorium.connect

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What one Noctorium says to another on a local network.
 *
 * Noctorium Connect is deliberately small. Two of a listener's own devices announce themselves over UDP,
 * recognise each other by a shared account secret, and then talk over ordinary TCP: one asks for state, or
 * sends a command, or hands over its entire queue. There is no server in the middle and nothing leaves the
 * network, which is why it keeps working when the internet does not.
 *
 * Everything here is the wire, and only the wire. The domain types it mirrors live in `domain`, and the
 * mirroring is on purpose: a queue handed to another device should not break because a field was added to
 * a model for the UI's benefit, and a model should not have to stay serialisable forever because a wire
 * format once needed it to be.
 */
object ConnectProtocol {
    /**
     * The ports announcements go to.
     *
     * Fixed, because discovery has to start somewhere and both ends must agree without being told. A
     * list rather than one number, for two reasons learned from a real machine.
     *
     * Windows reserves blocks of UDP ports for Hyper-V, WSL and Docker, out of the dynamic range above
     * 49152. A bind inside a reserved block fails with "address already in use" while nothing whatsoever
     * is listening on it, and the block moves between reboots -- so no single port up there is safe to
     * depend on. The first choice here, 57633, turned out to sit inside 57621-57720 on the machine this
     * was written on. These are all below the dynamic range, where Windows does not help itself.
     *
     * A device binds the first of these it can get, and announces to all of them. So a machine that
     * loses the first port still receives on the second, and the devices looking for it still reach it
     * without either side having to be told which one it settled on.
     */
    val DISCOVERY_PORTS = listOf(45633, 45634, 45635)

    /** How often a device says it is here, and how often one added by its address is asked whether it still is. */
    const val ANNOUNCE_EVERY_MS = 3_000L

    /** The wire format version, so a future change can be recognised rather than misread. */
    const val VERSION = 1

    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

/**
 * What sort of device another one is, for its icon and its name in a list.
 *
 * TERMINAL never goes on the wire as itself. A device from before it existed would fail to read an
 * announcement or a state naming it, and drop the device altogether; so a terminal says DESKTOP, as it always
 * did, and adds that it is a terminal in a field the older device ignores. See [onTheWire].
 */
enum class DeviceKind { DESKTOP, PHONE, TERMINAL }

/** What [this] is called on the wire, where only the kinds every version knows are written. */
val DeviceKind.onTheWire: DeviceKind get() = if (this == DeviceKind.TERMINAL) DeviceKind.DESKTOP else this

/** The kind a device said it is, its terminal flag included. */
internal fun deviceKind(kind: DeviceKind, terminal: Boolean): DeviceKind = if (terminal) DeviceKind.TERMINAL else kind

/** One device, named: who is driving, or being driven. */
@Serializable
data class DeviceRef(val id: String, val name: String)

/**
 * What a device is playing, small enough to go out with every announcement.
 *
 * So every device on the network knows what each of the others is playing without asking each one, and a
 * Connect page can show the lot. Only the song, its cover and where it is: the queue and the rest are asked of
 * the one device being driven, once it is. The provider is its name rather than [ProviderType], so a service
 * added later does not make a whole announcement unreadable to a device that has never heard of it.
 */
@Serializable
data class NowPlaying(
    val title: String,
    val artist: String = "",
    val provider: String? = null,
    val artworkUrl: String? = null,
    val playing: Boolean = false,
    /** How far in, when the announcement went out. Moving on from there by the clock while [playing]. */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    /** Where it has got to [elapsedMs] after the announcement was heard: still while paused, moving while playing. */
    fun positionAfter(elapsedMs: Long): Long {
        val moved = if (playing) positionMs + elapsedMs.coerceAtLeast(0) else positionMs
        return if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
    }
}

/**
 * What an announcement says beyond who is announcing: what it is playing and who is driving whom.
 *
 * Carried as JSON text inside the announcement ([DeviceAnnouncement.extra]) and signed as that exact text
 * ([DeviceAnnouncement.extraTag]), so a field added to this later is still covered by the signature a device
 * from before it checks, and is simply not read by that device.
 */
@Serializable
data class AnnouncementExtra(
    val terminal: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    /** The device driving this one, if any. */
    val drivenBy: DeviceRef? = null,
    /** The device this one is driving, if any: the music is there, and this is its remote control. */
    val driving: DeviceRef? = null,
)

/**
 * A device saying it is here.
 *
 * The tag is what makes this mean anything. It is an HMAC, over the rest of the announcement, keyed with
 * the account's connect key -- so a device can only be recognised by another device signed in to the same
 * account, and an eavesdropper learns nothing from watching: no account id, no email, no key, just an
 * opaque device name somebody chose and a number that is useless without the secret.
 *
 * The minute stamp bounds replay. Copying an announcement and shouting it later puts a dead entry in
 * somebody's device list for a few minutes and nothing more, because every actual command is signed
 * separately and over its own nonce.
 */
@Serializable
data class DeviceAnnouncement(
    val v: Int = ConnectProtocol.VERSION,
    val id: String,
    val name: String,
    val kind: DeviceKind,
    /** The TCP port this device is listening for commands on. */
    val port: Int,
    /** Unix minutes. Bounds how long a copied announcement stays believable. */
    val stamp: Long,
    val tag: String,
    /**
     * [AnnouncementExtra] as JSON text: what this device is playing, and who is driving whom.
     *
     * Not covered by [tag], which a device from before this existed checks and goes on checking exactly as it
     * did -- it ignores both fields it does not know. [extraTag] covers everything [tag] does and this text too.
     */
    val extra: String? = null,
    val extraTag: String? = null,
) {
    /** Everything the tag covers, in a fixed order, so both ends build the same string. */
    fun signable(): String = "$v|$id|$name|${kind.name}|$port|$stamp"

    /** Everything [extraTag] covers: what [tag] does, and [extra] exactly as it was sent. */
    fun signableWithExtra(): String = "${signable()}|${extra.orEmpty()}"
}

@Serializable
data class WireArtist(val id: String, val name: String, val provider: ProviderType)

@Serializable
data class WireTrack(
    val provider: ProviderType,
    val id: String,
    val title: String,
    val artists: List<WireArtist> = emptyList(),
    val albumId: String? = null,
    val albumTitle: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val sourceUrl: String,
)

/** Playback as another device currently has it, which is all a remote control needs to draw itself. */
@Serializable
data class RemoteState(
    val deviceId: String,
    val deviceName: String,
    val kind: DeviceKind,
    val playing: Boolean = false,
    val track: WireTrack? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val queueSize: Int = 0,
    val queueIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeat: String = "OFF",
    /** Set when this device is itself being driven by another, so two remotes cannot fight in silence. */
    val controlledBy: String? = null,
    /** The driving device's id, beside its name above. */
    val controlledById: String? = null,
    /** The device this one is itself driving, if any. */
    val driving: DeviceRef? = null,
    /** See [DeviceKind.onTheWire]: [kind] says DESKTOP for a terminal, and this says which it is. */
    val terminal: Boolean = false,
    /**
     * Which queue this device has, as a short key that changes when the songs in it do, so a remote control
     * fetches the whole queue (`GET /queue`) once each time it changes rather than every second.
     */
    val queueKey: String? = null,
)

/** The kind this device said it is. */
val RemoteState.deviceKind: DeviceKind get() = deviceKind(kind, terminal)

/** A device's whole queue, for a remote control's queue panel: `GET /queue`. */
@Serializable
data class RemoteQueue(val tracks: List<WireTrack>, val index: Int, val key: String)

/**
 * The instructions.
 *
 * JUMP, ADD, REMOVE and MOVE work on the driven device's queue, as the remote control's queue panel shows it.
 * A device from before them answers them as something it could not read, and the remote says it went wrong.
 */
enum class CommandType {
    PLAY, PAUSE, NEXT, PREVIOUS, SEEK, VOLUME, SHUFFLE, REPEAT, TAKE_OVER, HAND_BACK,
    /** Play the song at [Command.index] in the queue. */
    JUMP,
    /** Put [Command.tracks] in the queue: next when [Command.enabled], at the end otherwise. */
    ADD,
    /** Take the song at [Command.index] out of the queue. */
    REMOVE,
    /** Move the song at [Command.index] to [Command.toIndex]. */
    MOVE,
}

/**
 * One instruction, and whatever it needs to carry.
 *
 * TAKE_OVER is the interesting one and is what "play on my phone" actually is: the whole queue, where in
 * it we are, and how far into that track, sent in a single message. The receiver starts playing from
 * exactly there and the sender stops. Nothing is streamed between the devices -- the receiver resolves the
 * tracks itself through its own backend, which is why transferring to a phone across the room starts
 * playing at the phone's own quality and keeps working if the sender walks out of the door.
 */
@Serializable
data class Command(
    val type: CommandType,
    val positionMs: Long? = null,
    val volume: Float? = null,
    val enabled: Boolean? = null,
    val repeat: String? = null,
    val tracks: List<WireTrack> = emptyList(),
    val index: Int = -1,
    /** Who sent this, so the receiver can say who is driving it. */
    val fromDeviceId: String? = null,
    val fromDeviceName: String? = null,
    /** Where MOVE puts the song. */
    val toIndex: Int? = null,
)

/**
 * A queue going back the way it came: what a device was playing, where in it, and how far into that song.
 *
 * The answer to HAND_BACK. Without it the device taking the music back knew only the one song the other was
 * on, from the last state it had read, so "bring it back" brought back that song alone and the rest of the
 * queue -- including whatever had been skipped to over there -- was lost.
 */
@Serializable
data class Handover(val tracks: List<WireTrack>, val index: Int, val positionMs: Long)

@Serializable
data class CommandReply(
    val ok: Boolean,
    val message: String? = null,
    val state: RemoteState? = null,
    /** Only in answer to HAND_BACK, from a device new enough to send it. An older one leaves it out. */
    val handover: Handover? = null,
)

// -- conversions ---------------------------------------------------------------------------------

fun Track.toWire(): WireTrack = WireTrack(
    provider = provider,
    id = id,
    title = title,
    artists = artists.map { WireArtist(it.id, it.name, it.provider) },
    albumId = album?.id,
    albumTitle = album?.title,
    durationMs = durationMs,
    artworkUrl = artworkUrl,
    sourceUrl = sourceUrl,
)

fun WireTrack.toTrack(): Track = Track(
    provider = provider,
    id = id,
    title = title,
    artists = artists.map { Artist(it.id, it.name, it.provider) },
    // Rebuilt only when there was one. An album with a blank title reads worse in a queue than no album.
    album = albumTitle?.let { Album(albumId.orEmpty(), it, emptyList(), provider, artworkUrl) },
    durationMs = durationMs,
    artworkUrl = artworkUrl,
    sourceUrl = sourceUrl,
)

/** This song as an announcement carries it. */
fun WireTrack.nowPlaying(playing: Boolean, positionMs: Long, durationMs: Long): NowPlaying = NowPlaying(
    title = title,
    artist = artists.joinToString { it.name },
    provider = provider.name,
    artworkUrl = artworkUrl,
    playing = playing,
    positionMs = positionMs,
    durationMs = durationMs.takeIf { it > 0 } ?: this.durationMs ?: 0,
)

/** What a device said it was playing, in the shape an announcement carries; null with nothing to play. */
val RemoteState.nowPlaying: NowPlaying? get() = track?.nowPlaying(playing, positionMs, durationMs)

// -- signing -------------------------------------------------------------------------------------

/**
 * Proof that a message came from a device holding the account's connect key.
 *
 * HMAC rather than a bare shared token in a header, because a token is replayable by anyone who sees it
 * once and this is a local network where seeing traffic is easy. Every request signs its own method, path,
 * timestamp, nonce and body, so a captured request cannot be pointed at a different endpoint, replayed
 * later, or have its body swapped.
 */
object ConnectSignature {

    fun sign(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Compares in constant time.
     *
     * String equality returns as soon as two characters differ, and the time it took says how much of the
     * guess was right. That is enough to recover a signature one character at a time given enough tries,
     * and a device on the same network has as many tries as it likes.
     */
    fun matches(key: String, message: String, presented: String): Boolean =
        MessageDigest.isEqual(
            sign(key, message).toByteArray(Charsets.UTF_8),
            presented.toByteArray(Charsets.UTF_8),
        )

    /** What a request signs: everything that decides what it will do. */
    fun requestMessage(method: String, path: String, timestamp: Long, nonce: String, body: String): String =
        "$method\n$path\n$timestamp\n$nonce\n${sha256Hex(body)}"

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val v = byte.toInt() and 0xff
            append(HEX[v ushr 4])
            append(HEX[v and 0x0f])
        }
    }

    private const val HEX = "0123456789abcdef"
}
