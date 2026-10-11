package app.noctorium.connect

import app.noctorium.account.NoctoriumAccountClient
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.settings.InMemorySecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.internal.platform.Platform
import java.net.InetAddress
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Two devices on this machine's real network, finding each other and handing the music back and forth.
 *
 * Everything is real except the players: the announcements go out as broadcasts, the address each device
 * is reached on is the one the packet came from -- this machine's address on its network, never loopback --
 * and every request crosses a real socket. The other tests of Connect talk to 127.0.0.1, and that is
 * exactly the address a phone is allowed to talk to in plain HTTP, which is how Connect could pass every
 * test it had and still never once work between a phone and a computer.
 *
 * So these run with OkHttp told what Android tells it: plain HTTP is refused to every address but
 * 127.0.0.1, as the phone's network security configuration says. That is the phone's real rule, applied
 * here, and it is what made every request the phone sent fail with "CLEARTEXT communication not permitted".
 */
class ConnectOnTheNetworkTest {

    private val key = "a-key-only-these-two-devices-share"

    private val deskPlayer = SimulatedPlayer(log = { println("[desk player] $it") })
    private val phonePlayer = SimulatedPlayer(log = { println("[phone player] $it") })

    private val desk = device(deskPlayer, DeviceKind.DESKTOP, "Desk")
    private val phone = device(phonePlayer, DeviceKind.PHONE, "Phone")

    private val songs = (1..4).map { track("song-$it", "Song $it") }

    private val others = mutableListOf<ConnectManager>()

    private fun device(
        player: SimulatedPlayer,
        kind: DeviceKind,
        name: String,
        key: String = this.key,
        ports: List<Int> = TEST_PORTS,
    ) = ConnectManager(
        host = player,
        secrets = InMemorySecretStore().apply { put(ConnectManager.CONNECT_KEY, key) },
        accountClient = NoctoriumAccountClient(),
        token = { null },
        kind = kind,
        defaultDeviceName = { name },
        log = { println("[$name] $it") },
        // Ports of their own, so a Noctorium running on the same machine neither hears these nor is heard.
        discoveryPorts = ports,
    )

    @BeforeTest
    fun setUp() {
        Platform.resetForTests(PhoneRules)
        desk.start(UUID.randomUUID().toString(), "Desk")
        phone.start(UUID.randomUUID().toString(), "Phone")
    }

    @AfterTest
    fun tearDown() {
        desk.close()
        phone.close()
        others.forEach(ConnectManager::close)
        Platform.resetForTests()
    }

    @Test
    fun `the two find each other at an address on the network, not on loopback`() = runBlocking {
        val deskAsSeen = phone.await("Desk")
        val phoneAsSeen = desk.await("Phone")

        for (peer in listOf(deskAsSeen, phoneAsSeen)) {
            assertFalse(
                InetAddress.getByName(peer.address).isLoopbackAddress,
                "${peer.name} was found at ${peer.address}, which is loopback: this would not test the network at all",
            )
        }
        assertEquals(DeviceKind.DESKTOP, deskAsSeen.kind)
        assertEquals(DeviceKind.PHONE, phoneAsSeen.kind)
    }

    @Test
    fun `the phone hands its music to the desktop, which plays it from the same second`() = runBlocking {
        phonePlayer.play(songs, 1, 30_000)
        val deskPeer = phone.await("Desk")

        phone.handTo(deskPeer, songs, 1, 30_000)

        eventually("the desktop to take the music") { deskPlayer.playing }
        assertEquals(songs, deskPlayer.tracks)
        assertEquals(1, deskPlayer.index)
        assertTrue(deskPlayer.positionMs >= 30_000)
        eventually("the phone to stop, once the desktop had it") { !phonePlayer.playing }
        assertEquals(deskPeer.id, phone.state.value.target?.id, phone.state.value.message)
    }

    @Test
    fun `the desktop hands its music to the phone`() = runBlocking {
        deskPlayer.play(songs, 2, 61_000)
        val phonePeer = desk.await("Phone")

        desk.handTo(phonePeer, songs, 2, 61_000)

        eventually("the phone to take the music") { phonePlayer.playing }
        assertEquals("Song 3", phonePlayer.current?.title)
        assertTrue(phonePlayer.positionMs >= 61_000)
        eventually("the desktop to stop") { !deskPlayer.playing }
    }

    @Test
    fun `the device that took the music is played, paused, skipped, sought and turned down from the other`() = runBlocking {
        phonePlayer.play(songs, 0, 5_000)
        val deskPeer = phone.await("Desk")
        phone.handTo(deskPeer, songs, 0, 5_000)
        eventually("the desktop to take the music") { deskPlayer.playing }

        phone.control { Command(CommandType.PAUSE) }
        eventually("the desktop to pause") { !deskPlayer.playing }
        phone.control { Command(CommandType.PLAY) }
        eventually("the desktop to play again") { deskPlayer.playing }
        phone.control { Command(CommandType.NEXT) }
        eventually("the desktop to skip") { deskPlayer.index == 1 }
        phone.control { Command(CommandType.SEEK, positionMs = 90_000) }
        eventually("the desktop to seek") { deskPlayer.positionMs >= 90_000 }
        phone.control { Command(CommandType.VOLUME, volume = 0.25f) }
        eventually("the desktop to turn down") { deskPlayer.volume == 0.25f }

        // And the phone's remote control shows it, from the desktop's own answers.
        eventually("the phone to see the desktop's volume and song") {
            phone.state.value.remote?.let { it.volume == 0.25f && it.track?.title == "Song 2" } == true
        }
        assertFalse(phonePlayer.playing, "the phone started playing again while it was only a remote control")
    }

    @Test
    fun `the remote control follows the other device's position while it plays`() = runBlocking {
        deskPlayer.play(songs, 0, 10_000)
        val phonePeer = desk.await("Phone")
        desk.handTo(phonePeer, songs, 0, 10_000)
        eventually("the phone to take the music") { phonePlayer.playing }

        val first = desk.state.value.remote?.positionMs ?: 0
        eventually("the desktop to see the phone's position move on") {
            (desk.state.value.remote?.positionMs ?: 0) >= first + 1_500
        }
    }

    @Test
    fun `bringing the music back brings the whole queue, from where the other device had got to`() = runBlocking {
        phonePlayer.play(songs, 0, 5_000)
        val deskPeer = phone.await("Desk")
        phone.handTo(deskPeer, songs, 0, 5_000)
        eventually("the desktop to take the music") { deskPlayer.playing }
        // Moved on over there, by its own buttons, which the phone only hears about by asking.
        deskPlayer.next()
        deskPlayer.seekTo(42_000)

        val back = assertNotNull(phone.bringItBack(), "nothing came back")

        assertEquals(songs, back.tracks, "the rest of the queue was lost on the way back")
        assertEquals(1, back.index)
        assertTrue(back.positionMs >= 42_000, "came back at ${back.positionMs}ms")
        eventually("the desktop to stop") { !deskPlayer.playing }
        assertEquals(null, phone.state.value.target)
    }

    @Test
    fun `the device being driven says by whom, and stops saying so once the music is taken back`() = runBlocking {
        phonePlayer.play(songs, 0, 0)
        phone.handTo(phone.await("Desk"), songs, 0, 0)
        eventually("the desktop to say the phone is driving it") { desk.state.value.controlledBy == "Phone" }

        phone.bringItBack()
        eventually("the desktop to stop saying so") { desk.state.value.controlledBy == null }
    }

    @Test
    fun `music started on the remote control stops the device it was driving`() = runBlocking {
        phonePlayer.play(songs, 0, 0)
        phone.handTo(phone.await("Desk"), songs, 0, 0)
        eventually("the desktop to take the music") { deskPlayer.playing }

        phone.playingHereInstead()

        eventually("the desktop to stop, so only one of them is playing") { !deskPlayer.playing }
        assertEquals(null, phone.state.value.target)
    }

    @Test
    fun `a device that does not answer is said not to, and every device can still be chosen`() = runBlocking {
        val nobody = java.net.ServerSocket(0).use { it.localPort }
        val gone = ConnectPeer("gone", "Laptop", DeviceKind.DESKTOP, phone.await("Desk").address, nobody, 0)
        phonePlayer.play(songs, 0, 0)

        phone.transferTo(gone, songs, 0, 0)

        eventually("the phone to say so") { phone.state.value.message?.contains("did not answer") == true }
        assertFalse(phone.state.value.busy, "the device list was left disabled")
        assertTrue(phonePlayer.playing, "the phone stopped although nothing took the music")
    }

    @Test
    fun `a device on another account is refused, and the refusal says what to check`() = runBlocking {
        val strangerPlayer = SimulatedPlayer()
        val stranger = device(strangerPlayer, DeviceKind.DESKTOP, "Stranger", key = "somebody-else's-key", ports = emptyList())
            .also(others::add)
        stranger.start(UUID.randomUUID().toString(), "Stranger")
        val address = phone.await("Desk").address
        val port = eventuallyPort(stranger)
        phonePlayer.play(songs, 0, 0)

        phone.transferTo(ConnectPeer("stranger", "Stranger", DeviceKind.DESKTOP, address, port, 0), songs, 0, 0)

        eventually("the phone to say why") { phone.state.value.message?.contains("same Noctorium account") == true }
        assertFalse(strangerPlayer.playing)
    }

    @Test
    fun `a device that hears no announcements is found at its address, and takes the music there`() = runBlocking {
        val hiddenPlayer = SimulatedPlayer()
        // Announcing nothing and hearing nothing, like a computer inside a virtual machine.
        val hidden = device(hiddenPlayer, DeviceKind.DESKTOP, "Hidden", ports = emptyList()).also(others::add)
        hidden.start(UUID.randomUUID().toString(), "Hidden")
        val address = phone.await("Desk").address
        val port = eventuallyPort(hidden)

        phone.addAddress(address, port)
        val found = phone.await("Hidden")
        assertEquals(address, found.address)

        phonePlayer.play(songs, 3, 1_000)
        phone.handTo(found, songs, 3, 1_000)
        eventually("the hidden device to take the music") { hiddenPlayer.playing }
        assertEquals("Song 4", hiddenPlayer.current?.title)
    }

    @Test
    fun `a device goes on saying who is driving it while it is driven, and stops once it is let go`() = runBlocking {
        // The shelf's own clock runs five times as fast as this one, so half a minute passes in six seconds while
        // everything else -- the polling every second, the asking every three -- goes at its real pace.
        val started = System.currentTimeMillis()
        val shelfPlayer = SimulatedPlayer()
        val shelf = ConnectManager(
            host = shelfPlayer,
            secrets = InMemorySecretStore().apply { put(ConnectManager.CONNECT_KEY, key) },
            accountClient = NoctoriumAccountClient(),
            token = { null },
            kind = DeviceKind.DESKTOP,
            defaultDeviceName = { "Shelf" },
            clock = { started + (System.currentTimeMillis() - started) * 5 },
            log = { println("[Shelf] $it") },
            discoveryPorts = emptyList(),
        ).also(others::add)
        shelf.start(UUID.randomUUID().toString(), "Shelf")
        // Looked for at its address, which means it is asked every few seconds whether it is still there.
        phone.addAddress(phone.await("Desk").address, eventuallyPort(shelf))
        phonePlayer.play(songs, 0, 0)
        phone.handTo(phone.await("Shelf"), songs, 0, 0)
        eventually("the shelf to say the phone is driving it") { shelf.state.value.controlledBy == "Phone" }

        // Forty seconds with no commands, the remote control on screen all the while.
        delay(8_000)
        assertEquals("Phone", shelf.state.value.controlledBy, "a remote control still on screen was forgotten")

        // Let go of, though still asked every few seconds whether it is there: that is not being driven.
        phone.release()
        eventually("the shelf to stop saying it is driven", timeoutMs = 12_000) { shelf.state.value.controlledBy == null }
    }

    // -- helpers ---------------------------------------------------------------------------------

    /**
     * Hands the music over and waits until this device knows it is driving the other, as its screen would show.
     *
     * The handover is sent in the background and the device being driven is noted only once its reply is back; the
     * other device can already be playing before then. A remote control used in that moment has nothing to send
     * to -- a person cannot press that fast, but a test on a slow machine can.
     */
    private suspend fun ConnectManager.handTo(peer: ConnectPeer, tracks: List<Track>, index: Int, positionMs: Long) {
        transferTo(peer, tracks, index, positionMs)
        eventually("${peer.name} to be the device being driven") { state.value.target?.id == peer.id }
    }

    /** The port a device is answering on, read from what it says about itself at its own address. */
    private suspend fun eventuallyPort(manager: ConnectManager): Int {
        var port = 0
        eventually("the device to start") {
            port = manager.port
            port > 0
        }
        return port
    }

    private suspend fun ConnectManager.await(name: String): ConnectPeer {
        var found: ConnectPeer? = null
        eventually("$name to be found", timeoutMs = 15_000) {
            found = state.value.devices.firstOrNull { it.name == name }
            found != null
        }
        return assertNotNull(found)
    }

    private suspend fun eventually(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (condition()) return
            delay(50)
        }
        fail(
            "Waited ${timeoutMs}ms for $what. Desk: ${desk.state.value.message ?: "-"}; " +
                "phone: ${phone.state.value.message ?: "-"}",
        )
    }

    private fun track(id: String, title: String) = Track(
        provider = ProviderType.SOUNDCLOUD,
        id = id,
        title = title,
        artists = emptyList(),
        durationMs = 240_000,
        sourceUrl = "https://soundcloud.com/$id",
    )

    /** What Android's network security configuration says to OkHttp on the phone: plain HTTP to 127.0.0.1 only. */
    private object PhoneRules : Platform() {
        override fun isCleartextTrafficPermitted(hostname: String): Boolean = hostname == "127.0.0.1"
    }

    private companion object {
        /** Clear of the ports a real Noctorium announces on, and of the ranges Windows reserves for itself. */
        val TEST_PORTS = listOf(46633, 46634, 46635)
    }
}
