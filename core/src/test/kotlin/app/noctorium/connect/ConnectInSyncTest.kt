package app.noctorium.connect

import app.noctorium.account.NoctoriumAccountClient
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.settings.InMemorySecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Three devices on this machine's real network, each knowing what the others are playing and who is driving
 * whom, and any of them moving the music between the other two.
 *
 * The Connect page on every device shows the same picture, and that picture comes from the announcements: what
 * each device is playing, and who is driving whom, carried in each one and sent at once when it changes. So
 * these listen to real broadcasts, as [ConnectOnTheNetworkTest] does, and wait for what a person would see --
 * never for a fixed time.
 */
class ConnectInSyncTest {

    private val key = "a-key-only-these-three-devices-share"

    private val deskPlayer = SimulatedPlayer(log = { println("[desk player] $it") })
    private val phonePlayer = SimulatedPlayer(log = { println("[phone player] $it") })
    private val laptopPlayer = SimulatedPlayer(log = { println("[laptop player] $it") })

    private val desk = device(deskPlayer, DeviceKind.DESKTOP, "Desk")
    private val phone = device(phonePlayer, DeviceKind.PHONE, "Phone")
    private val laptop = device(laptopPlayer, DeviceKind.TERMINAL, "Laptop")
    private val others = mutableListOf<ConnectManager>()

    private val songs = (1..4).map { track("song-$it", "Song $it") }

    private fun device(player: SimulatedPlayer, kind: DeviceKind, name: String, key: String = this.key, ports: List<Int> = TEST_PORTS) =
        ConnectManager(
            host = player,
            secrets = InMemorySecretStore().apply { put(ConnectManager.CONNECT_KEY, key) },
            accountClient = NoctoriumAccountClient(),
            token = { null },
            kind = kind,
            defaultDeviceName = { name },
            log = { println("[$name] $it") },
            discoveryPorts = ports,
        )

    @BeforeTest
    fun setUp() {
        desk.start(UUID.randomUUID().toString(), "Desk")
        phone.start(UUID.randomUUID().toString(), "Phone")
        laptop.start(UUID.randomUUID().toString(), "Laptop")
    }

    @AfterTest
    fun tearDown() {
        listOf(desk, phone, laptop).forEach(ConnectManager::close)
        others.forEach(ConnectManager::close)
    }

    @Test
    fun `every device knows what each of the others is playing, and hears of a change at once`() = runBlocking {
        deskPlayer.play(songs, 1, 30_000)
        desk.announceNow()

        for (watcher in listOf(phone, laptop)) {
            eventually("${watcher.state.value.thisDevice} to see the desk playing Song 2") {
                watcher.peer("Desk")?.nowPlaying?.let { it.title == "Song 2" && it.playing } == true
            }
        }
        val seen = assertNotNull(phone.peer("Desk"))
        assertTrue(seen.positionAt(System.currentTimeMillis()) >= 30_000, "its place in the song did not move on from where it said")
        assertEquals("Some Artist", seen.nowPlaying?.artist)
        assertEquals("SOUNDCLOUD", seen.nowPlaying?.provider)

        // Paused and played again, three times: each seen well before the next beat would have carried it.
        repeat(3) {
            deskPlayer.pause()
            desk.announceNow()
            eventually("the phone to see the desk paused", timeoutMs = 2_000) { phone.peer("Desk")?.nowPlaying?.playing == false }
            deskPlayer.resume()
            desk.announceNow()
            eventually("the phone to see the desk playing again", timeoutMs = 2_000) { phone.peer("Desk")?.nowPlaying?.playing == true }
        }
    }

    @Test
    fun `every device knows who is driving whom, and when it stops`() = runBlocking {
        phonePlayer.play(songs, 0, 0)
        phone.handTo(phone.await("Desk"), songs, 0, 0)

        eventually("the laptop to see the phone driving the desk") {
            laptop.peer("Desk")?.drivenBy?.name == "Phone" && laptop.peer("Phone")?.driving?.name == "Desk"
        }
        eventually("the desk to see itself driven by the phone") { desk.peer("Phone")?.driving?.name == "Desk" }
        assertEquals("Phone", desk.state.value.controlledBy)

        phone.release()
        eventually("the laptop to see the phone let go") { laptop.peer("Phone")?.driving == null }
    }

    @Test
    fun `a terminal is known as a terminal, even where only desktops and phones are written down`() = runBlocking {
        assertEquals(DeviceKind.TERMINAL, phone.await("Laptop").kind)
        assertEquals(DeviceKind.DESKTOP, phone.await("Desk").kind)
    }

    @Test
    fun `one device moves the music from a second to a third, at the same second, and drives the third`() = runBlocking {
        deskPlayer.play(songs, 2, 40_000)
        val deskPeer = phone.await("Desk")
        val laptopPeer = phone.await("Laptop")

        phone.move(deskPeer, laptopPeer) { fail("Nothing was meant to come to the phone") }

        eventually("the laptop to take the music") { laptopPlayer.playing }
        assertEquals(songs, laptopPlayer.tracks, "the queue did not go with it")
        assertEquals(2, laptopPlayer.index)
        assertTrue(laptopPlayer.positionMs >= 40_000, "it started at ${laptopPlayer.positionMs}ms")
        eventually("the desk to stop") { !deskPlayer.playing }
        eventually("the phone to be the laptop's remote control") { phone.state.value.target?.name == "Laptop" }
        eventually("the laptop to say the phone is driving it") { laptop.state.value.controlledBy == "Phone" }
        assertFalse(phonePlayer.playing, "the phone played as well")
    }

    @Test
    fun `a device takes the music from one it was not driving`() = runBlocking {
        deskPlayer.play(songs, 3, 12_000)
        val deskPeer = phone.await("Desk")

        phone.move(deskPeer, null) { back -> phonePlayer.takeOver(back.tracks, back.index, back.positionMs) }

        eventually("the phone to play it") { phonePlayer.playing }
        assertEquals(songs, phonePlayer.tracks)
        assertEquals(3, phonePlayer.index)
        assertTrue(phonePlayer.positionMs >= 12_000)
        eventually("the desk to stop") { !deskPlayer.playing }
        assertNull(phone.state.value.target)
    }

    @Test
    fun `music a device will not take goes back where it was`() = runBlocking {
        val stranger = device(SimulatedPlayer(), DeviceKind.DESKTOP, "Stranger", key = "somebody-else's-key", ports = emptyList()).also(others::add)
        stranger.start(UUID.randomUUID().toString(), "Stranger")
        var port = 0
        eventually("the stranger to start") { port = stranger.port; port > 0 }
        deskPlayer.play(songs, 0, 5_000)
        val deskPeer = phone.await("Desk")

        phone.move(deskPeer, ConnectPeer("stranger", "Stranger", DeviceKind.DESKTOP, deskPeer.address, port, 0)) {}

        eventually("the phone to say why, and where the music is") {
            phone.state.value.message?.let { "same Noctorium account" in it && "still playing on Desk" in it } == true
        }
        eventually("the desk to be playing it again") { deskPlayer.playing && deskPlayer.tracks == songs }
    }

    @Test
    fun `the remote control shows the other device's whole queue, and changes it`() = runBlocking {
        phonePlayer.play(songs, 0, 0)
        phone.handTo(phone.await("Desk"), songs, 0, 0)
        eventually("the phone to have the desk's queue") { phone.state.value.remoteQueue?.tracks?.map { it.id } == songs.map { it.id } }

        phone.control { Command(CommandType.JUMP, index = 2) }
        eventually("the desk to play the third") { deskPlayer.index == 2 }
        eventually("the phone to see it") { phone.state.value.remote?.queueIndex == 2 }

        val fifth = track("song-5", "Song 5")
        phone.control { Command(CommandType.ADD, tracks = listOf(fifth.toWire()), enabled = true) }
        eventually("the desk to put it next") { deskPlayer.tracks.getOrNull(3)?.id == "song-5" }
        eventually("the phone's copy of the queue to follow") { phone.state.value.remoteQueue?.tracks?.getOrNull(3)?.id == "song-5" }

        phone.control { Command(CommandType.REMOVE, index = 0) }
        eventually("the desk to take the first out") { deskPlayer.tracks.size == 4 && deskPlayer.tracks.first().id == "song-2" }
        phone.control { Command(CommandType.MOVE, index = 0, toIndex = 3) }
        eventually("the desk to move it") { deskPlayer.tracks.last().id == "song-2" }
        eventually("the phone's copy to follow") { phone.state.value.remoteQueue?.tracks?.map { it.id } == deskPlayer.tracks.map { it.id } }
    }

    @Test
    fun `a pause shows on the remote control at once, and stays shown`() = runBlocking {
        phonePlayer.play(songs, 0, 20_000)
        phone.handTo(phone.await("Desk"), songs, 0, 20_000)
        eventually("the phone to see the desk playing") { phone.state.value.remote?.playing == true }

        phone.control { Command(CommandType.PAUSE) }
        // Before the desk has even been asked.
        assertEquals(false, phone.state.value.remote?.playing, "the pause was not shown until the desk answered")
        eventually("the desk to pause") { !deskPlayer.playing }

        // Two more answers from the desk, and it is still shown paused: nothing older came back over it.
        repeat(2) {
            val heard = phone.state.value.remoteAtMs
            eventually("the next answer") { phone.state.value.remoteAtMs > heard }
            assertEquals(false, phone.state.value.remote?.playing)
        }
    }

    @Test
    fun `a seek bar dragged lands where it was let go`() = runBlocking {
        phonePlayer.play(songs, 0, 0)
        phone.handTo(phone.await("Desk"), songs, 0, 0)

        for (at in 1..20) phone.controlLatest(Command(CommandType.SEEK, positionMs = at * 5_000L))

        assertEquals(100_000, phone.state.value.remote?.positionMs, "the bar did not follow the thumb")
        eventually("the desk to land where the thumb was let go") { deskPlayer.calls.lastOrNull { it.startsWith("seek") } == "seek 100000ms" }
        assertTrue(deskPlayer.calls.count { it.startsWith("seek") } <= 20)
    }

    @Test
    fun `an announcement too long for one packet loses the cover first, and still arrives`() = runBlocking {
        val long = Track(
            provider = ProviderType.SOUNDCLOUD,
            id = "long",
            title = "A very long title ".repeat(80),
            artists = listOf(Artist("a", "Someone with a long name ".repeat(20), ProviderType.SOUNDCLOUD)),
            durationMs = 240_000,
            artworkUrl = "https://example.invalid/" + "cover/".repeat(120),
            sourceUrl = "https://soundcloud.com/long",
        )
        deskPlayer.play(listOf(long), 0, 0)
        desk.announceNow()

        eventually("the phone to hear the desk's song, shortened") {
            phone.peer("Desk")?.nowPlaying?.let { it.title.startsWith("A very long title") && it.artworkUrl == null } == true
        }
        assertTrue((phone.peer("Desk")?.nowPlaying?.title?.length ?: 0) <= 60)
    }

    @Test
    fun `a device from before announcements carried a song is listed as it was, and a forged song is not believed`() = runBlocking {
        phone.await("Desk")
        val stamp = System.currentTimeMillis() / 60_000
        val old = DeviceAnnouncement(id = "old-device", name = "Old Desk", kind = DeviceKind.DESKTOP, port = 4_000, stamp = stamp, tag = "")
            .let { it.copy(tag = ConnectSignature.sign(key, it.signable())) }
        val forgedExtra = ConnectProtocol.json.encodeToString(
            AnnouncementExtra.serializer(),
            AnnouncementExtra(nowPlaying = NowPlaying(title = "Not really playing", playing = true)),
        )
        val forged = DeviceAnnouncement(id = "forged-device", name = "Forged", kind = DeviceKind.DESKTOP, port = 4_001, stamp = stamp, tag = "")
            .let { it.copy(tag = ConnectSignature.sign(key, it.signable())) }
            .let { it.copy(extra = forgedExtra, extraTag = ConnectSignature.sign("not-the-key", it.copy(extra = forgedExtra).signableWithExtra())) }

        // Sent again until heard, as a real device would go on announcing: one datagram can always be lost.
        val oldSeen = awaitAnnouncing("Old Desk", old)
        assertNull(oldSeen.nowPlaying)
        assertEquals(DeviceKind.DESKTOP, oldSeen.kind)
        val forgedSeen = awaitAnnouncing("Forged", forged)
        assertNull(forgedSeen.nowPlaying, "a song signed with another key was believed")
    }

    // -- helpers ---------------------------------------------------------------------------------

    private suspend fun ConnectManager.handTo(peer: ConnectPeer, tracks: List<Track>, index: Int, positionMs: Long) {
        transferTo(peer, tracks, index, positionMs)
        eventually("${peer.name} to be the device being driven") { state.value.target?.id == peer.id }
    }

    private fun ConnectManager.peer(name: String): ConnectPeer? = state.value.devices.firstOrNull { it.name == name }

    private suspend fun ConnectManager.await(name: String): ConnectPeer {
        var found: ConnectPeer? = null
        eventually("$name to be found", timeoutMs = 15_000) {
            found = peer(name)
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
        fail("Waited ${timeoutMs}ms for $what. Messages: ${listOf(desk, phone, laptop).map { it.state.value.message }}")
    }

    private suspend fun awaitAnnouncing(name: String, announcement: DeviceAnnouncement): ConnectPeer {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            broadcast(announcement)
            phone.peer(name)?.let { return it }
            delay(300)
        }
        fail("The phone never heard $name")
    }

    /** One announcement, sent as a device sends its own: to every broadcast address, on every test port. */
    private fun broadcast(announcement: DeviceAnnouncement) {
        val bytes = ConnectProtocol.json.encodeToString(DeviceAnnouncement.serializer(), announcement).toByteArray()
        val targets = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.interfaceAddresses.mapNotNull { it.broadcast } } + InetAddress.getByName("255.255.255.255")
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (target in targets.distinct()) for (port in TEST_PORTS) runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, port)) }
        }
    }

    private fun track(id: String, title: String) = Track(
        provider = ProviderType.SOUNDCLOUD,
        id = id,
        title = title,
        artists = listOf(Artist("a", "Some Artist", ProviderType.SOUNDCLOUD)),
        durationMs = 240_000,
        sourceUrl = "https://soundcloud.com/$id",
    )

    private companion object {
        /** Ports of their own, apart from [ConnectOnTheNetworkTest]'s and a real Noctorium's. */
        val TEST_PORTS = listOf(46733, 46734, 46735)
    }
}
