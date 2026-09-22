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

    /** The wire format version, so a future change can be recognised rather than misread. */
    const val VERSION = 1

    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

enum class DeviceKind { DESKTOP, PHONE }

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
) {
    /** Everything the tag covers, in a fixed order, so both ends build the same string. */
    fun signable(): String = "$v|$id|$name|${kind.name}|$port|$stamp"
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
)

enum class CommandType { PLAY, PAUSE, NEXT, PREVIOUS, SEEK, VOLUME, SHUFFLE, REPEAT, TAKE_OVER, HAND_BACK }

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
)

@Serializable
data class CommandReply(val ok: Boolean, val message: String? = null, val state: RemoteState? = null)

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
