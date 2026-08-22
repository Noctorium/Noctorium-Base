package app.spice.playback

import app.spice.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.Locale

class MpvPlaybackEngine(
    private val resolver: YtDlpService,
    private val executable: () -> Path? = BackendLocator::mpv,
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var process: Process? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var progressJob: Job? = null

    override suspend fun play(track: Track) {
        mutableState.value = mutableState.value.copy(
            status = PlaybackStatus.RESOLVING,
            track = track,
            errorMessage = null,
            positionMs = 0,
            durationMs = track.durationMs ?: 0,
        )
        try {
            val mpv = executable() ?: throw BackendException(
                "mpv is missing. Install it in Spice Settings or set SPICE_MPV_PATH.",
            )
            val mediaUrl = resolver.resolveAudio(track.sourceUrl)
            stopProcess()
            process = withContext(Dispatchers.IO) {
                ProcessBuilder(
                    mpv.toString(),
                    "--no-video",
                    "--force-window=no",
                    "--terminal=no",
                    "--input-terminal=yes",
                    "--volume=${(mutableState.value.volume * 100).toInt()}",
                    "--title=Spice",
                    "--",
                    mediaUrl,
                ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }
            delay(400)
            if (process?.isAlive != true) throw BackendException("mpv exited before audio playback started")
            mutableState.value = mutableState.value.copy(status = PlaybackStatus.PLAYING)
            startProgressTicker()
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = error.message ?: "Playback failed",
            )
        }
    }

    override suspend fun pause() = suspendProcess(paused = true)
    override suspend fun resume() = suspendProcess(paused = false)

    private suspend fun suspendProcess(paused: Boolean) {
        process?.takeIf { it.isAlive } ?: return
        sendCommand("set pause ${if (paused) "yes" else "no"}")
        mutableState.value = mutableState.value.copy(
            status = if (paused) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING,
        )
    }

    override suspend fun setVolume(value: Float) {
        mutableState.value = mutableState.value.copy(volume = value.coerceIn(0f, 1f))
        if (process?.isAlive == true) sendCommand("set volume ${(value.coerceIn(0f, 1f) * 100).toInt()}")
    }

    override suspend fun seekTo(positionMs: Long) {
        val duration = mutableState.value.durationMs
        val target = positionMs.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        if (process?.isAlive == true) {
            val seconds = String.format(Locale.US, "%.3f", target / 1_000.0)
            sendCommand("seek $seconds absolute exact")
        }
        mutableState.value = mutableState.value.copy(positionMs = target)
    }

    override suspend fun stop() {
        stopProcess()
        mutableState.value = mutableState.value.copy(status = PlaybackStatus.IDLE, track = null)
    }

    private fun stopProcess() {
        progressJob?.cancel()
        progressJob = null
        process?.takeIf { it.isAlive }?.destroy()
        process = null
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var lastTick = System.nanoTime()
            while (isActive) {
                delay(250)
                val now = System.nanoTime()
                val elapsedMs = (now - lastTick) / 1_000_000
                lastTick = now
                val current = mutableState.value
                if (current.status == PlaybackStatus.PLAYING) {
                    if (process?.isAlive != true) {
                        mutableState.value = current.copy(status = PlaybackStatus.IDLE)
                        break
                    }
                    val next = current.positionMs + elapsedMs
                    mutableState.value = current.copy(
                        positionMs = if (current.durationMs > 0) next.coerceAtMost(current.durationMs) else next,
                    )
                }
            }
        }
    }

    private suspend fun sendCommand(command: String) = withContext(Dispatchers.IO) {
        try {
            process?.outputStream?.bufferedWriter()?.apply {
                write(command)
                newLine()
                flush()
            }
        } catch (error: Exception) {
            throw BackendException("mpv stopped responding to playback controls", error)
        }
    }

    override fun close() {
        stopProcess()
        scope.cancel()
    }
}
