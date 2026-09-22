package app.noctorium.connect

import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.settings.InMemorySecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How quickly a device answers, which turns out to matter more than it sounds.
 *
 * Carrying out a handover means resolving a track and starting a player, and that takes seconds. The reply
 * cannot wait for it: the sender times out in four, and a sender that believes the handover failed does not
 * stop its own playback, so the same song comes out of both machines at once.
 */
class ConnectManagerTest {

    private val slowHost = SlowHost()

    private val manager = ConnectManager(
        host = slowHost,
        secrets = InMemorySecretStore().apply { put(ConnectManager.CONNECT_KEY, "a-key") },
        accountClient = app.noctorium.account.NoctoriumAccountClient(),
        token = { null },
        kind = DeviceKind.DESKTOP,
        defaultDeviceName = { "Test" },
    )

    private val track = WireTrack(
        provider = ProviderType.SOUNDCLOUD,
        id = "one",
        title = "One",
        durationMs = 200_000,
        sourceUrl = "https://soundcloud.com/one",
    )

    @Test
    fun `a handover is acknowledged long before it has been carried out`() = runBlocking {
        val reply: CommandReply
        val elapsed = measureTimeMillis {
            reply = manager.handle(
                Command(CommandType.TAKE_OVER, tracks = listOf(track), index = 0, positionMs = 12_000),
            )
        }

        assertTrue(reply.ok, "a handover was refused: ${reply.message}")
        // The real client gives up after four seconds. The host here deliberately takes three.
        assertTrue(elapsed < 1_000, "the reply took ${elapsed}ms, which a sender would have given up on")
        assertFalse(slowHost.tookOver, "the work should still be running, not finished")
    }

    @Test
    fun `the reply describes what is about to play, not what just stopped`() = runBlocking {
        val reply = manager.handle(
            Command(CommandType.TAKE_OVER, tracks = listOf(track), index = 0, positionMs = 12_000),
        )
        // A remote control draws this immediately. Showing the previous track for a second, at the exact
        // moment somebody is watching the transfer happen, reads as a failure.
        assertEquals("One", reply.state?.track?.title)
        assertEquals(12_000, reply.state?.positionMs)
        assertTrue(reply.state?.playing == true)
        assertEquals(1, reply.state?.queueSize)
    }

    @Test
    fun `the work really does happen, just afterwards`() = runBlocking {
        manager.handle(Command(CommandType.TAKE_OVER, tracks = listOf(track), index = 0, positionMs = 12_000))
        // Longer than the host's own delay, so this fails if the command was only ever acknowledged.
        delay(4_000)
        assertTrue(slowHost.tookOver, "the handover was acknowledged and then never carried out")
        assertEquals(12_000, slowHost.position)
    }

    @Test
    fun `a handover carrying no tracks is refused rather than acknowledged`() = runBlocking {
        val reply = manager.handle(Command(CommandType.TAKE_OVER, tracks = emptyList(), index = 0))
        // Otherwise the sender stops playing on the strength of an "ok" for a queue that was never sent.
        assertFalse(reply.ok)
        assertFalse(slowHost.tookOver)
    }

    /** Stands in for a player that has to fetch a stream before it can make a sound. */
    private class SlowHost : ConnectHost {
        @Volatile var tookOver = false
        @Volatile var position = 0L

        override fun snapshot() = PlaybackSnapshot()

        override suspend fun takeOver(tracks: List<Track>, index: Int, positionMs: Long) {
            delay(3_000)
            position = positionMs
            tookOver = true
        }

        override suspend fun resume() = Unit
        override suspend fun pause() = Unit
        override suspend fun next() = Unit
        override suspend fun previous() = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun setVolume(value: Float) = Unit
        override suspend fun setShuffle(enabled: Boolean) = Unit
        override suspend fun setRepeat(mode: String) = Unit
        override suspend fun standDown() = Unit
    }
}
