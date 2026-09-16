package app.spiceity.connect

import app.spiceity.domain.Album
import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The part of Connect that decides who is allowed to touch the music.
 *
 * Everything else can be re-tried, re-fetched or restarted. This cannot: a mistake here means anybody on
 * the coffee shop wifi can pause your phone, or worse, hand it a queue.
 */
class ConnectSignatureTest {

    private val key = "a-shared-account-key"

    @Test
    fun `a signature made with the key verifies`() {
        val message = ConnectSignature.requestMessage("POST", "/command", 1_700_000_000_000, "abcd1234", "{}")
        assertTrue(ConnectSignature.matches(key, message, ConnectSignature.sign(key, message)))
    }

    @Test
    fun `a signature made with another key does not`() {
        val message = ConnectSignature.requestMessage("POST", "/command", 1_700_000_000_000, "abcd1234", "{}")
        // This is the whole "only if the same Spiceity account" promise, reduced to one assertion.
        assertFalse(ConnectSignature.matches(key, message, ConnectSignature.sign("somebody-elses-key", message)))
    }

    @Test
    fun `changing any part of the request breaks the signature`() {
        val stamp = 1_700_000_000_000
        val original = ConnectSignature.requestMessage("POST", "/command", stamp, "abcd1234", """{"type":"PAUSE"}""")
        val tag = ConnectSignature.sign(key, original)

        // Each of these is an attack: replaying an old request, pointing a captured one at another
        // endpoint, or keeping the signature and swapping the instruction for something worse.
        val tampered = listOf(
            ConnectSignature.requestMessage("GET", "/command", stamp, "abcd1234", """{"type":"PAUSE"}"""),
            ConnectSignature.requestMessage("POST", "/state", stamp, "abcd1234", """{"type":"PAUSE"}"""),
            ConnectSignature.requestMessage("POST", "/command", stamp + 1, "abcd1234", """{"type":"PAUSE"}"""),
            ConnectSignature.requestMessage("POST", "/command", stamp, "different", """{"type":"PAUSE"}"""),
            ConnectSignature.requestMessage("POST", "/command", stamp, "abcd1234", """{"type":"TAKE_OVER"}"""),
        )
        tampered.forEach { assertFalse(ConnectSignature.matches(key, it, tag), "a changed request still verified") }
    }

    @Test
    fun `a signature is not the key, and nothing about the key leaks into it`() {
        val signed = ConnectSignature.sign(key, "anything")
        assertFalse(signed.contains(key))
        assertEquals(64, signed.length, "HMAC-SHA256 is 32 bytes, so 64 hex characters")
    }

    @Test
    fun `an empty or truncated signature is refused rather than treated as absent`() {
        val message = ConnectSignature.requestMessage("GET", "/state", 1L, "abcd1234", "")
        val real = ConnectSignature.sign(key, message)
        assertFalse(ConnectSignature.matches(key, message, ""))
        // A prefix comparison would accept this, which is exactly the bug constant-time compare hides.
        assertFalse(ConnectSignature.matches(key, message, real.take(32)))
        assertFalse(ConnectSignature.matches(key, message, real + "00"))
    }
}

class ConnectAnnouncementTest {

    private val key = "a-shared-account-key"

    private fun announce(
        id: String = "device-a",
        name: String = "Desk",
        port: Int = 40000,
        stamp: Long = 28_000_000,
        signWith: String = key,
    ): DeviceAnnouncement {
        val unsigned = DeviceAnnouncement(id = id, name = name, kind = DeviceKind.DESKTOP, port = port, stamp = stamp, tag = "")
        return unsigned.copy(tag = ConnectSignature.sign(signWith, unsigned.signable()))
    }

    @Test
    fun `an announcement signs everything it advertises`() {
        val original = announce()
        // The port is in the signature on purpose: without it, a captured announcement could be re-sent
        // with the port changed and point every device on the network at somewhere else entirely.
        assertNotEquals(original.signable(), original.copy(port = 40001).signable())
        assertNotEquals(original.signable(), original.copy(name = "Somebody else").signable())
        assertNotEquals(original.signable(), original.copy(id = "device-b").signable())
    }

    @Test
    fun `an announcement carries no account id, address or key`() {
        val wire = ConnectProtocol.json.encodeToString(DeviceAnnouncement.serializer(), announce())
        // Anybody on the network sees this. It should tell them nothing they could use or link to a person.
        assertFalse(wire.contains(key))
        assertFalse(wire.contains("@"))
        assertFalse(wire.contains("user"))
    }

    @Test
    fun `an announcement survives a round trip`() {
        val original = announce()
        val decoded = ConnectProtocol.json.decodeFromString(
            DeviceAnnouncement.serializer(),
            ConnectProtocol.json.encodeToString(DeviceAnnouncement.serializer(), original),
        )
        assertEquals(original, decoded)
        assertTrue(ConnectSignature.matches(key, decoded.signable(), decoded.tag))
    }
}

class ConnectWireTrackTest {

    @Test
    fun `a track survives the wire`() {
        val original = Track(
            provider = ProviderType.SOUNDCLOUD,
            id = "12345",
            title = "Nannou",
            artists = listOf(Artist("aphex", "Aphex Twin", ProviderType.SOUNDCLOUD)),
            album = Album("rdj", "Richard D. James Album", emptyList(), ProviderType.SOUNDCLOUD, "http://art"),
            durationMs = 256_000,
            artworkUrl = "http://art",
            sourceUrl = "https://soundcloud.com/x/nannou",
        )
        val back = original.toWire().toTrack()

        // What a queue on the other device is drawn and played from.
        assertEquals(original.provider, back.provider)
        assertEquals(original.id, back.id)
        assertEquals(original.title, back.title)
        assertEquals(original.artistLine, back.artistLine)
        assertEquals(original.durationMs, back.durationMs)
        assertEquals(original.sourceUrl, back.sourceUrl)
        assertEquals(original.queueKey, back.queueKey)
        assertEquals("Richard D. James Album", back.album?.title)
    }

    @Test
    fun `a track with no album does not arrive with a blank one`() {
        val original = Track(
            provider = ProviderType.YOUTUBE_MUSIC,
            id = "abc",
            title = "Untitled",
            artists = emptyList(),
            sourceUrl = "https://music.youtube.com/watch?v=abc",
        )
        // A blank album line reads worse in a queue than no album at all.
        assertNull(original.toWire().toTrack().album)
    }
}
