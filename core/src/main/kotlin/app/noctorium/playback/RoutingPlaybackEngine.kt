package app.noctorium.playback

import app.noctorium.domain.Track
import app.noctorium.settings.EqualizerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One player made of two: this device's own, and one somewhere else that some tracks are played on.
 *
 * Which one a track goes to is decided per track, by [playsElsewhere] -- today, a Spotify song when the
 * listener has chosen to play those on Spotify. Everything else about the pair looks like one player: one
 * state, one set of controls, aimed at whichever played last.
 *
 * The state is this class's own rather than a view of whichever engine is active, for one reason that
 * matters a great deal: the queue moves on when a player goes idle with a track still in it, which is how a
 * song ending looks. An engine left behind holds whatever it last said -- often exactly that -- and showing
 * it for even a moment while switching would skip a song nobody asked to skip. So after a switch, nothing
 * the new engine says is passed on until it is talking about the track it was given.
 */
class RoutingPlaybackEngine(
    private val local: PlaybackEngine,
    private val elsewhere: PlaybackEngine,
    private val playsElsewhere: (Track) -> Boolean,
    private val scope: CoroutineScope,
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(local.state.value)
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    @Volatile private var active: PlaybackEngine = local
    @Volatile private var mirror: Job? = null

    /** The engine playing, as a flow, for the analysis to follow it. */
    private val activeEngine = MutableStateFlow(local)
    private val mutableAnalysis = MutableStateFlow<AudioAnalysis?>(null)

    /**
     * The playing engine's analysis, asked of it only while something here is collecting this -- so the engine
     * underneath, which measures only while it is watched, is watched exactly when this is.
     */
    override val analysis: StateFlow<AudioAnalysis?> = mutableAnalysis.asStateFlow()
    override val analysisKind: AudioAnalysisKind get() = active.analysisKind

    init {
        follow(local, expecting = null)
        scope.launch {
            combine(activeEngine, mutableAnalysis.subscriptionCount.map { it > 0 }.distinctUntilChanged()) { engine, watched ->
                engine.takeIf { watched }
            }.collectLatest { engine ->
                if (engine == null) {
                    mutableAnalysis.value = null
                } else {
                    engine.analysis.collect { mutableAnalysis.value = it }
                }
            }
        }
    }

    /** Whether the last track went to the other player. */
    val isElsewhere: Boolean get() = active === elsewhere

    override suspend fun play(track: Track) {
        val target = if (playsElsewhere(track)) elsewhere else local
        if (target !== active) {
            val leaving = active
            active = target
            activeEngine.value = target
            // Ours first, so nothing the engine being left says on its way out is mistaken for the end of a
            // song; then the one being left is stopped, so two things are never playing at once. The booster
            // is the new engine's from the start: Spotify's app has none, and saying so a moment late would
            // flash a slider that does nothing.
            val arriving = target.state.value
            mutableState.value = PlaybackState(
                status = PlaybackStatus.RESOLVING,
                track = track,
                volume = mutableState.value.volume,
                isMuted = mutableState.value.isMuted,
                durationMs = track.durationMs ?: 0,
                volumeBoostEnabled = arriving.volumeBoostEnabled,
                volumeBoostPercent = arriving.volumeBoostPercent,
                volumeBoostAvailable = arriving.volumeBoostAvailable,
            )
            follow(target, expecting = track)
            runCatching { leaving.stop() }
        }
        target.play(track)
    }

    override suspend fun pause() = active.pause()
    override suspend fun resume() = active.resume()
    override suspend fun seekTo(positionMs: Long) = active.seekTo(positionMs)
    override suspend fun stop() = active.stop()

    /** Kept on this device's player whichever is active, so it is right when that player is next used. */
    override suspend fun setVolume(value: Float) {
        local.setVolume(value)
        if (active === elsewhere) elsewhere.setVolume(value)
    }

    override suspend fun setMuted(muted: Boolean) {
        local.setMuted(muted)
        if (active === elsewhere) elsewhere.setMuted(muted)
    }

    /** Only this device's player has a booster; it is kept there, and in force again when that player next plays. */
    override suspend fun setVolumeBoost(enabled: Boolean) = local.setVolumeBoost(enabled)
    override suspend fun setVolumeBoostPercent(percent: Int) = local.setVolumeBoostPercent(percent)
    override suspend fun setLooping(enabled: Boolean) {
        local.setLooping(enabled)
        elsewhere.setLooping(enabled)
    }
    override suspend fun setEqualizer(settings: EqualizerSettings) = local.setEqualizer(settings)
    override suspend fun setAnalysis(wanted: AudioAnalysisKind) {
        local.setAnalysis(wanted)
        elsewhere.setAnalysis(wanted)
    }
    override suspend fun setSpeed(speed: Float) = local.setSpeed(speed)

    override fun close() {
        mirror?.cancel()
        runCatching { local.close() }
        runCatching { elsewhere.close() }
    }

    private fun follow(engine: PlaybackEngine, expecting: Track?) {
        mirror?.cancel()
        mirror = scope.launch {
            var caughtUp = expecting == null
            engine.state.collect { update ->
                if (!caughtUp) {
                    if (update.track?.queueKey != expecting?.queueKey) return@collect
                    caughtUp = true
                }
                // Only the engine playing speaks here. Cancelling the old mirror is not enough on its own: it may
                // be part-way through passing something on when [play] switches, and its last word would then land
                // on top of the new engine's first -- the booster offered on Spotify's app, for one. [active] is set
                // before [play] writes, and the compare-and-set retries with it, so a word from an engine left
                // behind is dropped even when the two cross.
                mutableState.update { current -> if (active === engine) update else current }
            }
        }
    }
}
