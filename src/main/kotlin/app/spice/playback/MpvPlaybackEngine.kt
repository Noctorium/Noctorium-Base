package app.spice.playback

import app.spice.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Path

class MpvPlaybackEngine(
    private val resolver: YtDlpService,
    private val executable: () -> Path? = BackendLocator::mpv,
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var process: Process? = null

    override suspend fun play(track: Track) {
        mutableState.value = mutableState.value.copy(status = PlaybackStatus.RESOLVING, track = track, errorMessage = null)
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

    override suspend fun stop() {
        stopProcess()
        mutableState.value = mutableState.value.copy(status = PlaybackStatus.IDLE, track = null)
    }

    private fun stopProcess() {
        process?.takeIf { it.isAlive }?.destroy()
        process = null
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

    override fun close() = stopProcess()
}
