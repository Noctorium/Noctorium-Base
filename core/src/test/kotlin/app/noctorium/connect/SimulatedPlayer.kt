package app.noctorium.connect

import app.noctorium.domain.Track
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A player that makes no sound: a queue, a place in it, a position that moves while it plays, and a volume.
 *
 * Everything Connect can ask of the application, and nothing else, so two of these on a real network are
 * two of a listener's devices as far as Connect can tell. The tests that put two devices on this machine's
 * network use it, and so does [ConnectHarness], which stands in for a computer while the phone is tested.
 * Each thing it is asked to do is written to [log] as well as kept in [calls], because the point of the
 * harness is that somebody watching can see exactly what arrived.
 */
class SimulatedPlayer(
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : ConnectHost {

    val calls: MutableList<String> = CopyOnWriteArrayList()

    var tracks: List<Track> = emptyList()
        @Synchronized get
        private set
    var index: Int = -1
        @Synchronized get
        private set
    var playing: Boolean = false
        @Synchronized get
        private set
    var volume: Float = 1f
        @Synchronized get
        private set
    var shuffle: Boolean = false
        @Synchronized get
        private set
    var repeat: String = "OFF"
        @Synchronized get
        private set

    private var positionAtMark = 0L
    private var markedAt = 0L

    val current: Track? @Synchronized get() = tracks.getOrNull(index)

    /** Where in the song it is: still while paused, moving with the clock while playing. */
    val positionMs: Long
        @Synchronized get() {
            val moved = if (playing) clock() - markedAt else 0L
            val duration = current?.durationMs ?: Long.MAX_VALUE
            return (positionAtMark + moved).coerceAtMost(duration)
        }

    /** Starts playing here, as a listener pressing play on this device would. */
    @Synchronized
    fun play(tracks: List<Track>, index: Int, positionMs: Long = 0) {
        this.tracks = tracks
        this.index = index.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        mark(positionMs)
        playing = tracks.isNotEmpty()
    }

    override fun snapshot(): PlaybackSnapshot = synchronized(this) {
        PlaybackSnapshot(
            playing = playing,
            track = current?.toWire(),
            positionMs = positionMs,
            durationMs = current?.durationMs ?: 0,
            volume = volume,
            queueSize = tracks.size,
            queueIndex = index,
            shuffle = shuffle,
            repeat = repeat,
        )
    }

    override fun queue(): QueueSnapshot = synchronized(this) { QueueSnapshot(tracks, index) }

    override suspend fun takeOver(tracks: List<Track>, index: Int, positionMs: Long) {
        record("takeOver ${tracks.size} track(s) from #$index \"${tracks.getOrNull(index)?.title}\" at ${positionMs}ms")
        play(tracks, index, positionMs)
    }

    override suspend fun resume() = change("resume") { if (current != null) { mark(positionMs); playing = true } }
    override suspend fun pause() = change("pause") { mark(positionMs); playing = false }
    override suspend fun next() = change("next") {
        if (index < tracks.lastIndex) { index += 1; mark(0) }
    }
    override suspend fun previous() = change("previous") {
        if (positionMs >= 5_000 || index <= 0) mark(0) else { index -= 1; mark(0) }
    }
    override suspend fun seekTo(positionMs: Long) = change("seek ${positionMs}ms") { mark(positionMs) }
    override suspend fun setVolume(value: Float) = change("volume $value") { volume = value }
    override suspend fun setShuffle(enabled: Boolean) = change("shuffle $enabled") { shuffle = enabled }
    override suspend fun setRepeat(mode: String) = change("repeat $mode") { repeat = mode }
    override suspend fun standDown() = change("standDown") { mark(positionMs); playing = false }

    override suspend fun jumpTo(index: Int) = change("jump $index") {
        if (index in tracks.indices) { this.index = index; mark(0); playing = true }
    }

    override suspend fun addToQueue(tracks: List<Track>, next: Boolean) = change("add ${tracks.size}${if (next) " next" else ""}") {
        val at = if (next) (index + 1).coerceIn(0, this.tracks.size) else this.tracks.size
        this.tracks = this.tracks.take(at) + tracks + this.tracks.drop(at)
    }

    override suspend fun removeFromQueue(index: Int) = change("remove $index") {
        if (index in tracks.indices) {
            tracks = tracks.filterIndexed { i, _ -> i != index }
            if (index < this.index) this.index -= 1
        }
    }

    override suspend fun moveInQueue(from: Int, to: Int) = change("move $from to $to") {
        if (from in tracks.indices && to in tracks.indices) {
            val playingNow = current
            tracks = tracks.toMutableList().apply { add(to, removeAt(from)) }
            index = tracks.indexOf(playingNow)
        }
    }

    private fun mark(positionMs: Long) {
        positionAtMark = positionMs
        markedAt = clock()
    }

    private fun change(what: String, action: () -> Unit) {
        synchronized(this) { action() }
        record(what)
    }

    private fun record(what: String) {
        calls += what
        log(what)
    }
}
