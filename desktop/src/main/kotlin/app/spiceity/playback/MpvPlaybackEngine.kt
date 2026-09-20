package app.spiceity.playback

import app.spiceity.domain.Track
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal const val NORMAL_MAX_VOLUME = 1f
internal const val BOOSTED_MAX_VOLUME = 10f
internal const val BOOST_START_VOLUME = 2f

/** Ten seconds of asking at the ticker's rate, after which a stream is taken to have no length. */
private const val MAX_DURATION_PROBES = 40

class MpvPlaybackEngine(
    private val resolver: YtDlpService,
    private val executable: () -> Path? = BackendLocator::mpv,
    /**
     * Audio for this track already on the disk, if there is any.
     *
     * A lambda rather than the download library itself, so the engine needs to know nothing about how
     * downloads are kept — only whether there is a file to play instead of a stream to go and find.
     */
    private val downloadedFile: (Track) -> Path? = { null },
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var process: Process? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var progressJob: Job? = null
    private val ipcMutex = Mutex()
    private val requestIds = AtomicLong()
    private val json = Json { ignoreUnknownKeys = true }
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private var ipcEndpoint: String? = null
    private var volumeBeforeBoost = mutableState.value.volume

    /**
     * Kills the player if the application goes without having been closed properly.
     *
     * mpv is a separate program, not part of this process, and on Windows a child outlives its parent.
     * Anything that ends the application without disposing it therefore leaves music playing from a program
     * with no window, which cannot be paused and has to be hunted down in the task manager. Closing tidily
     * is still the normal path — this only catches the times it does not happen.
     *
     * A hook cannot run if the application is killed outright rather than asked to stop; nothing can help
     * there, short of the operating system tying the two together.
     */
    private val shutdownHook = Thread({ runCatching { process?.destroyForcibly() } }, "spiceity-stop-mpv")

    init {
        runCatching { Runtime.getRuntime().addShutdownHook(shutdownHook) }
    }

    /**
     * What mpv is pointed at: a file on the disk if the track has been downloaded, otherwise a stream.
     *
     * This one line is the whole of offline playback. Everywhere else a track is still the same track —
     * liked, shared, added to playlists by its original address — and only the moment of playing it cares
     * where the audio comes from. Visible for testing so it can be shown that a downloaded track asks the
     * network for nothing, which cannot be observed from the outside once it is playing.
     */
    internal suspend fun mediaAddress(track: Track): String =
        downloadedFile(track)?.toString() ?: resolver.resolveAudio(track.sourceUrl)

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
                "mpv is missing, so there is nothing to play through. Settings, then Playback tools, installs it.",
            )
            val mediaUrl = mediaAddress(track)
            stopProcess()
            ipcEndpoint = createIpcEndpoint()
            PlaybackLog.event(
                "playback_starting",
                mapOf(
                    "track" to track.queueKey,
                    "volumePercent" to mutableState.value.volume * 100,
                    "boostEnabled" to mutableState.value.volumeBoostEnabled,
                    "muted" to mutableState.value.isMuted,
                ),
            )
            process = withContext(Dispatchers.IO) {
                ProcessBuilder(
                    mpv.toString(),
                    "--no-config",
                    "--no-video",
                    "--force-window=no",
                    "--terminal=no",
                    "--input-terminal=no",
                    "--input-ipc-server=${ipcEndpoint!!}",
                    "--volume-max=${(BOOSTED_MAX_VOLUME * 100).toInt()}",
                    "--volume=${(mutableState.value.volume * 100).toInt()}",
                    "--mute=${if (mutableState.value.isMuted) "yes" else "no"}",
                    "--title=Spiceity",
                    "--",
                    mediaUrl,
                ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }
            delay(400)
            if (process?.isAlive != true) throw BackendException("mpv exited before audio playback started")
            waitForIpc()
            mutableState.value = mutableState.value.copy(status = PlaybackStatus.PLAYING)
            PlaybackLog.event("playback_started", mapOf("track" to track.queueKey, "processId" to process?.pid()))
            startProgressTicker()
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = error.message ?: "Playback failed",
            )
            PlaybackLog.event("playback_failed", mapOf("track" to track.queueKey, "message" to (error.message ?: "unknown")))
        }
    }

    override suspend fun pause() = suspendProcess(paused = true)
    override suspend fun resume() = suspendProcess(paused = false)

    private suspend fun suspendProcess(paused: Boolean) {
        process?.takeIf { it.isAlive } ?: return
        sendCommand("set_property", JsonPrimitive("pause"), JsonPrimitive(paused))
        mutableState.value = mutableState.value.copy(
            status = if (paused) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING,
        )
    }

    override suspend fun setVolume(value: Float) {
        val maximum = if (mutableState.value.volumeBoostEnabled) BOOSTED_MAX_VOLUME else NORMAL_MAX_VOLUME
        val requested = value.coerceIn(0f, maximum)
        try {
            if (process?.isAlive == true) {
                sendCommand("set_property", JsonPrimitive("volume"), JsonPrimitive(requested * 100.0))
                val confirmed = getNumberProperty("volume")?.div(100.0)?.toFloat()?.coerceIn(0f, maximum) ?: requested
                mutableState.value = mutableState.value.copy(volume = confirmed, errorMessage = null)
                PlaybackLog.event("volume_changed", mapOf("requestedPercent" to requested * 100, "confirmedPercent" to confirmed * 100))
            } else {
                mutableState.value = mutableState.value.copy(volume = requested)
                PlaybackLog.event("volume_staged", mapOf("requestedPercent" to requested * 100))
            }
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(errorMessage = error.message ?: "Volume control failed")
            PlaybackLog.event("volume_failed", mapOf("message" to (error.message ?: "unknown")))
        }
    }

    override suspend fun setVolumeBoost(enabled: Boolean) {
        val current = mutableState.value
        if (current.volumeBoostEnabled == enabled) return

        if (enabled) volumeBeforeBoost = current.volume.coerceIn(0f, NORMAL_MAX_VOLUME)
        val target = if (enabled) max(current.volume, BOOST_START_VOLUME) else volumeBeforeBoost
        try {
            var confirmed = target
            if (process?.isAlive == true) {
                sendCommand("set_property", JsonPrimitive("volume"), JsonPrimitive(target * 100.0))
                confirmed = getNumberProperty("volume")?.div(100.0)?.toFloat()
                    ?.coerceIn(0f, if (enabled) BOOSTED_MAX_VOLUME else NORMAL_MAX_VOLUME)
                    ?: target
            }
            mutableState.value = mutableState.value.copy(
                volume = confirmed,
                volumeBoostEnabled = enabled,
                errorMessage = null,
            )
            PlaybackLog.event(
                "volume_boost_changed",
                mapOf(
                    "enabled" to enabled,
                    "requestedPercent" to target * 100,
                    "confirmedPercent" to confirmed * 100,
                ),
            )
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(errorMessage = error.message ?: "Volume boost failed")
            PlaybackLog.event("volume_boost_failed", mapOf("enabled" to enabled, "message" to (error.message ?: "unknown")))
        }
    }

    override suspend fun setMuted(muted: Boolean) {
        try {
            if (process?.isAlive == true) {
                sendCommand("set_property", JsonPrimitive("mute"), JsonPrimitive(muted))
                val confirmed = getBooleanProperty("mute") ?: muted
                mutableState.value = mutableState.value.copy(isMuted = confirmed, errorMessage = null)
                PlaybackLog.event("mute_changed", mapOf("requested" to muted, "confirmed" to confirmed))
            } else {
                mutableState.value = mutableState.value.copy(isMuted = muted)
                PlaybackLog.event("mute_staged", mapOf("requested" to muted))
            }
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(errorMessage = error.message ?: "Mute control failed")
            PlaybackLog.event("mute_failed", mapOf("requested" to muted, "message" to (error.message ?: "unknown")))
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        val duration = mutableState.value.durationMs
        val target = positionMs.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        if (process?.isAlive == true) {
            val seconds = String.format(Locale.US, "%.3f", target / 1_000.0)
            sendCommand("seek", JsonPrimitive(seconds.toDouble()), JsonPrimitive("absolute+exact"))
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
        process?.takeIf { it.isAlive }?.let { player ->
            // Forcibly, and waited for. Asking politely and moving on leaves the audio playing for as
            // long as it takes the player to notice, which on the way out is until after we have gone.
            player.destroyForcibly()
            runCatching { player.waitFor(2, TimeUnit.SECONDS) }
        }
        process = null
        ipcEndpoint?.takeUnless { isWindows }?.let { runCatching { java.nio.file.Files.deleteIfExists(Path.of(it)) } }
        ipcEndpoint = null
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var lastTick = System.nanoTime()
            var durationAttempts = 0
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
                    // Ask mpv how long the stream is until it can say. A YouTube Music listing carries no
                    // duration field at all, so a track played from one arrives here with nothing to scale
                    // the seek bar against, and only the player itself can supply it.
                    val playingKey = current.track?.queueKey
                    val measured = if (current.durationMs > 0 || durationAttempts >= MAX_DURATION_PROBES) {
                        0L
                    } else {
                        durationAttempts++
                        measuredDurationMs()
                    }
                    mutableState.update { latest ->
                        if (latest.status != PlaybackStatus.PLAYING) return@update latest
                        val duration = when {
                            latest.durationMs > 0 -> latest.durationMs
                            // A different track may have started while mpv was answering; that length is
                            // not this one's.
                            latest.track?.queueKey == playingKey -> measured
                            else -> 0L
                        }
                        val next = latest.positionMs + elapsedMs
                        latest.copy(
                            durationMs = duration,
                            positionMs = if (duration > 0) next.coerceAtMost(duration) else next,
                        )
                    }
                }
            }
        }
    }

    /**
     * The stream's length as mpv measures it, or 0 while that is still unknown.
     *
     * A genuinely endless stream never reports one, so the caller stops asking after [MAX_DURATION_PROBES];
     * a normal track answers within the first tick or two.
     */
    private suspend fun measuredDurationMs(): Long =
        runCatching { getNumberProperty("duration") }.getOrNull()
            ?.takeIf { it.isFinite() && it > 0 }
            ?.let { (it * 1_000).toLong() }
            ?: 0L

    private fun createIpcEndpoint(): String {
        val name = "spiceity-mpv-${UUID.randomUUID()}"
        return if (isWindows) "\\\\.\\pipe\\$name" else Path.of(System.getProperty("java.io.tmpdir"), "$name.sock").toString()
    }

    private suspend fun waitForIpc() {
        var lastError: Exception? = null
        repeat(20) {
            try {
                getNumberProperty("volume")
                return
            } catch (error: Exception) {
                lastError = error
                delay(100)
            }
        }
        throw BackendException("mpv IPC did not become ready", lastError)
    }

    private suspend fun getNumberProperty(name: String): Double? =
        sendCommand("get_property", JsonPrimitive(name))["data"]?.jsonPrimitive?.doubleOrNull

    private suspend fun getBooleanProperty(name: String): Boolean? =
        sendCommand("get_property", JsonPrimitive(name))["data"]?.jsonPrimitive?.booleanOrNull

    private suspend fun sendCommand(name: String, vararg arguments: JsonPrimitive): JsonObject = ipcMutex.withLock {
        withContext(Dispatchers.IO) {
            val endpoint = ipcEndpoint ?: throw BackendException("mpv IPC is not available")
            val requestId = requestIds.incrementAndGet()
            val request = JsonObject(
                mapOf(
                    "command" to JsonArray(listOf(JsonPrimitive(name)) + arguments),
                    "request_id" to JsonPrimitive(requestId),
                ),
            ).toString() + "\n"
            val responseLine = try {
                if (isWindows) exchangeWindows(endpoint, request, requestId) else exchangeUnix(endpoint, request, requestId)
            } catch (error: Exception) {
                throw BackendException("mpv stopped responding to playback controls", error)
            }
            val response = runCatching { json.parseToJsonElement(responseLine).jsonObject }
                .getOrElse { throw BackendException("mpv returned an invalid control response", it) }
            val error = response["error"]?.jsonPrimitive?.content
            if (error != "success") throw BackendException("mpv rejected the control command: ${error ?: "unknown error"}")
            response
        }
    }

    private fun exchangeWindows(endpoint: String, request: String, requestId: Long): String =
        RandomAccessFile(endpoint, "rw").use { pipe ->
            pipe.write(request.toByteArray(Charsets.UTF_8))
            readMatchingResponse(requestId, pipe::readLine)
        }

    private fun exchangeUnix(endpoint: String, request: String, requestId: Long): String =
        SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
            socket.connect(UnixDomainSocketAddress.of(endpoint))
            Channels.newWriter(socket, Charsets.UTF_8).apply {
                write(request)
                flush()
            }
            val reader = Channels.newReader(socket, Charsets.UTF_8).buffered()
            readMatchingResponse(requestId, reader::readLine)
        }

    private fun readMatchingResponse(requestId: Long, readLine: () -> String?): String {
        repeat(100) {
            val line = readLine() ?: throw BackendException("mpv closed its control channel")
            val responseId = runCatching {
                json.parseToJsonElement(line).jsonObject["request_id"]?.jsonPrimitive?.longOrNull
            }.getOrNull()
            if (responseId == requestId) return line
        }
        throw BackendException("mpv sent too many unrelated events before the control response")
    }

    override fun close() {
        stopProcess()
        // Removing the hook once it has nothing left to do; a hook cannot be removed during shutdown, so
        // the failure that throws there is expected and ignored.
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        scope.cancel()
    }
}
