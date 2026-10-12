package app.noctorium.core

import app.noctorium.connect.ConnectPeer
import app.noctorium.connect.ConnectState
import app.noctorium.connect.DeviceKind
import app.noctorium.connect.MusicPlace
import app.noctorium.connect.NowPlaying
import app.noctorium.connect.QueueSnapshot
import app.noctorium.connect.RemoteState
import app.noctorium.connect.toWire
import app.noctorium.core.AppStateHarness.song
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.QueueState
import app.noctorium.playback.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** What the player shows while it is another device's remote control, and where the music is said to be. */
class ConnectPlayerTest {

    private val songs = listOf(song("a"), song("b"), song("c"))
    private val speaker = ConnectPeer("speaker", "Speaker", DeviceKind.DESKTOP, "192.168.1.20", 41234, 0)
    private val local = PlaybackState(status = PlaybackStatus.PAUSED, track = songs[0], positionMs = 5_000, volume = 0.5f)

    private fun driving(playing: Boolean = true, at: Long = 1_000_000, queue: QueueSnapshot? = null, index: Int = 1) = ConnectState(
        target = speaker,
        remote = RemoteState(
            deviceId = "speaker",
            deviceName = "Speaker",
            kind = DeviceKind.DESKTOP,
            playing = playing,
            track = songs[1].toWire(),
            positionMs = 60_000,
            durationMs = 200_000,
            volume = 0.8f,
            queueIndex = index,
            shuffle = true,
            repeat = "ONE",
        ),
        remoteAtMs = at,
        remoteQueue = queue,
    )

    @Test
    fun `with no other device driven the player shows its own playback and queue`() {
        val queue = QueueState(tracks = songs, currentIndex = 0)
        assertSame(local, ConnectState().playbackToShow(local, 0))
        assertSame(queue, ConnectState().queueToShow(queue))
    }

    @Test
    fun `the other device's song moves on by the clock while it plays, and stays put while it does not`() {
        val shown = driving().playbackToShow(local, nowMs = 1_002_500)
        assertEquals(PlaybackStatus.PLAYING, shown.status)
        assertEquals("b", shown.track?.id)
        assertEquals(62_500, shown.positionMs)
        assertEquals(200_000, shown.durationMs)
        assertEquals(0.8f, shown.volume)
        assertFalse(shown.volumeBoostAvailable, "this device's booster offered for another device")

        val paused = driving(playing = false).playbackToShow(local, nowMs = 1_002_500)
        assertEquals(PlaybackStatus.PAUSED, paused.status)
        assertEquals(60_000, paused.positionMs)

        // Never past the end, however late the next answer.
        assertEquals(200_000, driving().playbackToShow(local, nowMs = 9_000_000).positionMs)
    }

    @Test
    fun `before the other device has said anything the player shows a song on its way`() {
        assertEquals(PlaybackStatus.RESOLVING, ConnectState(target = speaker).playbackToShow(local, 0).status)
    }

    @Test
    fun `the queue shown is the other device's, found by the song it says it is on`() {
        val whole = driving(queue = QueueSnapshot(songs, 1)).queueToShow(QueueState())
        assertEquals(songs.map { it.id }, whole.tracks.map { it.id })
        assertEquals(1, whole.currentIndex)
        assertEquals(true, whole.shuffleEnabled)
        assertEquals(RepeatMode.ONE, whole.repeatMode)

        // An index newer than the queue fetched: the song is looked for rather than trusted to the number.
        val moved = driving(queue = QueueSnapshot(listOf(songs[2], songs[1], songs[0]), 0), index = 0).queueToShow(QueueState())
        assertEquals(1, moved.currentIndex)

        // Before the queue has come, or from a device that cannot give it: the one song.
        val one = driving().queueToShow(QueueState())
        assertEquals(listOf("b"), one.tracks.map { it.id })
        assertEquals(0, one.currentIndex)
    }

    @Test
    fun `the music is where it is driven, then where it plays, then where it waits`() {
        val playing = speaker.copy(nowPlaying = NowPlaying(title = "Song", playing = true))
        val waiting = speaker.copy(nowPlaying = NowPlaying(title = "Song", playing = false))

        assertEquals(MusicPlace.Driving(speaker), driving().musicPlace(herePlaying = true, hereHasSong = true))
        assertEquals(MusicPlace.Here, ConnectState(devices = listOf(playing)).musicPlace(herePlaying = true, hereHasSong = true))
        assertEquals(MusicPlace.Elsewhere(playing), ConnectState(devices = listOf(playing)).musicPlace(herePlaying = false, hereHasSong = true))
        assertEquals(MusicPlace.Here, ConnectState(devices = listOf(waiting)).musicPlace(herePlaying = false, hereHasSong = true))
        assertEquals(MusicPlace.Elsewhere(waiting), ConnectState(devices = listOf(waiting)).musicPlace(herePlaying = false, hereHasSong = false))
        assertEquals(MusicPlace.Nowhere, ConnectState(devices = listOf(speaker)).musicPlace(herePlaying = false, hereHasSong = false))
    }
}
