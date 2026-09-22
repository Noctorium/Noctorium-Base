package app.noctorium.playback

import app.noctorium.settings.AppDirectories
import app.noctorium.domain.Track
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
import kotlinx.coroutines.CancellationException
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

/**
 * The line out of an mpv log that says what went wrong, or null if nothing did.
 *
 * mpv's log lines are `[   0.026][e][stream] Failed to open ...`: a timestamp, a one letter level and
 * a component, then the message. The level is a single letter -- `e` for error, `f` for fatal -- and not
 * the word, which is worth writing down because a first attempt at this looked for "[error]", matched
 * nothing on any real log, and would have reported "it gave no reason" for every failure there is.
 *
 * The last error is taken rather than the first: mpv keeps going through several, and the one that
 * finally stopped it is at the end.
 */
internal fun complaintIn(lines: List<String>): String? {
    val complaint = lines.lastOrNull { line ->
        // Anchored on "][" so it cannot match an "e" inside a timestamp or a message.
        line.contains("][e][") || line.contains("][f][")
    } ?: return null
    // Strips the leading bracket groups, however many mpv used, leaving the sentence itself.
    return complaint.replace(Regex("^(\\[[^]]*])+"), "").trim().take(200).ifBlank { null }
}

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

    /**
     * One play at a time.
     *
     * `process` and `ipcEndpoint` are ordinary fields, and two plays overlapping is not an exotic case:
     * a double click does it, and so does the queue moving on while somebody is picking something else.
     * Without this the second call can adopt the first one's endpoint and then have its player killed by
     * the first one's cleanup, which leaves a state nothing ever moves off.
     */
    private val playMutex = Mutex()
    private val requestIds = AtomicLong()
    private val json = Json { ignoreUnknownKeys = true }
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private var ipcEndpoint: String? = null

    /** Where mpv was told to write about the current attempt, for when it does not survive it. */
    private var processLog: Path? = null
    private var volumeBeforeBoost = mutableState.value.volume

    /**
     * Whether mpv is to start the file over when it reaches the end, instead of exiting.
     *
     * Kept here as well as told to the running player, because the next track starts a new mpv and it
     * has to be started looping too.
     */
    @Volatile private var looping = false

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
    private val shutdownHook = Thread({ runCatching { process?.destroyForcibly() } }, "noctorium-stop-mpv")

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

    /**
     * Starts a track, and guarantees it does not leave the interface on the spinner.
     *
     * RESOLVING is the status the play button renders as a spinner, and while it shows, that button is
     * disabled -- so a play that sets RESOLVING and then leaves without setting anything else takes the
     * one control that could have recovered it with it. Restarting the application was the only way out.
     *
     * Three things had to change for that to stop being possible. Cancellation is no longer reported as a
     * playback failure, because being interrupted by the listener choosing something else is not an error
     * and swallowing it also stopped the cancellation propagating. Throwable is caught rather than
     * Exception, because an Error -- a missing class, a stack overflow inside a dependency -- went
     * straight past the old catch and left the spinner up with nothing logged anywhere. And the finally
     * below states the invariant outright: whatever happened, RESOLVING is not how this method ends.
     */
    override suspend fun play(track: Track): Unit = playMutex.withLock {
        mutableState.update { it.copy(
            status = PlaybackStatus.RESOLVING,
            track = track,
            errorMessage = null,
            positionMs = 0,
            durationMs = track.durationMs ?: 0,
            loops = 0,
        ) }
        // Logged on the way in as well as the way out, so that next time the difference between "never
        // started" and "started and vanished" is a fact rather than a deduction.
        PlaybackLog.event("playback_requested", mapOf("track" to track.queueKey))
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
            val log = newLogFile()
            processLog = log
            process = withContext(Dispatchers.IO) {
                ProcessBuilder(
                    buildList {
                        add(mpv.toString())
                        add("--no-config")
                        add("--no-video")
                        add("--force-window=no")
                        add("--terminal=no")
                        add("--input-terminal=no")
                        // --terminal=no silences everything mpv would otherwise say, including why it is
                        // about to stop. --log-file is the one channel that still works, and without it a
                        // player that cannot open an audio device exits looking exactly like a track that
                        // finished: a second of nothing, then silence, with no message anywhere.
                        log?.let { add("--log-file=$it") }
                        add("--input-ipc-server=${ipcEndpoint!!}")
                        add("--volume-max=${(BOOSTED_MAX_VOLUME * 100).toInt()}")
                        add("--volume=${(mutableState.value.volume * 100).toInt()}")
                        add("--mute=${if (mutableState.value.isMuted) "yes" else "no"}")
                        // Repeat-one, done by the player: it seeks back to the start out of its own
                        // cache instead of exiting, so there is no gap and nothing is fetched again.
                        if (looping) add("--loop-file=inf")
                        add("--title=Noctorium")
                        add("--")
                        add(mediaUrl)
                    },
                ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }
            delay(400)
            if (process?.isAlive != true) {
                throw BackendException("mpv stopped before any audio started. " + (mpvComplaint() ?: "It gave no reason."))
            }
            waitForIpc()
            mutableState.update { it.copy(status = PlaybackStatus.PLAYING) }
            PlaybackLog.event("playback_started", mapOf("track" to track.queueKey, "processId" to process?.pid()))
            startProgressTicker()
        } catch (cancellation: CancellationException) {
            // Somebody pressed something else. Not a failure, and not something to show a message about
            // -- but the player still has to be stood down and the status still has to leave RESOLVING.
            stopProcess()
            mutableState.update { it.copy(status = PlaybackStatus.IDLE, errorMessage = null) }
            throw cancellation
        } catch (error: Throwable) {
            // Silence first, as the phone does. Resolving happens before the old player is stopped, so a
            // track that fails to resolve otherwise leaves the previous one playing under an error about
            // a different song -- which reads as the error being wrong rather than the track being broken.
            stopProcess()
            mutableState.update { it.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = error.message ?: "Playback failed",
            ) }
            PlaybackLog.event(
                "playback_failed",
                mapOf(
                    "track" to track.queueKey,
                    "message" to (error.message ?: "unknown"),
                    "kind" to error::class.java.name,
                ),
            )
            // An OutOfMemoryError or a StackOverflowError is not this method's to absorb: the state is
            // recorded so the interface recovers, and then it goes on up.
            if (error is VirtualMachineError) throw error
        } finally {
            if (mutableState.value.status == PlaybackStatus.RESOLVING) {
                // Nothing above claimed an ending, which means control left by a route this method does
                // not know about. Any status at all beats the spinner, because the spinner is the one
                // that cannot be pressed out of.
                mutableState.update { it.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = "Playback stopped before it started. Try again.",
                ) }
                PlaybackLog.event("playback_stranded", mapOf("track" to track.queueKey))
            }
        }
    }

    override suspend fun pause() = suspendProcess(paused = true)
    override suspend fun resume() = suspendProcess(paused = false)

    private suspend fun suspendProcess(paused: Boolean) {
        process?.takeIf { it.isAlive } ?: return
        sendCommand("set_property", JsonPrimitive("pause"), JsonPrimitive(paused))
        mutableState.update { it.copy(
            status = if (paused) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING,
        ) }
    }

    override suspend fun setVolume(value: Float) {
        val maximum = if (mutableState.value.volumeBoostEnabled) BOOSTED_MAX_VOLUME else NORMAL_MAX_VOLUME
        val requested = value.coerceIn(0f, maximum)
        try {
            if (process?.isAlive == true) {
                sendCommand("set_property", JsonPrimitive("volume"), JsonPrimitive(requested * 100.0))
                val confirmed = getNumberProperty("volume")?.div(100.0)?.toFloat()?.coerceIn(0f, maximum) ?: requested
                mutableState.update { it.copy(volume = confirmed, errorMessage = null) }
                PlaybackLog.event("volume_changed", mapOf("requestedPercent" to requested * 100, "confirmedPercent" to confirmed * 100))
            } else {
                mutableState.update { it.copy(volume = requested) }
                PlaybackLog.event("volume_staged", mapOf("requestedPercent" to requested * 100))
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = error.message ?: "Volume control failed") }
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
            mutableState.update { it.copy(
                volume = confirmed,
                volumeBoostEnabled = enabled,
                errorMessage = null,
            ) }
            PlaybackLog.event(
                "volume_boost_changed",
                mapOf(
                    "enabled" to enabled,
                    "requestedPercent" to target * 100,
                    "confirmedPercent" to confirmed * 100,
                ),
            )
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = error.message ?: "Volume boost failed") }
            PlaybackLog.event("volume_boost_failed", mapOf("enabled" to enabled, "message" to (error.message ?: "unknown")))
        }
    }

    override suspend fun setMuted(muted: Boolean) {
        try {
            if (process?.isAlive == true) {
                sendCommand("set_property", JsonPrimitive("mute"), JsonPrimitive(muted))
                val confirmed = getBooleanProperty("mute") ?: muted
                mutableState.update { it.copy(isMuted = confirmed, errorMessage = null) }
                PlaybackLog.event("mute_changed", mapOf("requested" to muted, "confirmed" to confirmed))
            } else {
                mutableState.update { it.copy(isMuted = muted) }
                PlaybackLog.event("mute_staged", mapOf("requested" to muted))
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = error.message ?: "Mute control failed") }
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
        mutableState.update { it.copy(positionMs = target) }
    }

    override suspend fun stop() {
        stopProcess()
        mutableState.update { it.copy(status = PlaybackStatus.IDLE, track = null) }
    }

    /**
     * Loops the file that is playing, and every file after it, until told otherwise.
     *
     * Set on the running player at once, so pressing repeat-one halfway through a song takes effect on
     * this song and not the next. A player that has gone away is not an error here: the flag is kept and
     * the next one is started with it.
     */
    override suspend fun setLooping(enabled: Boolean) {
        looping = enabled
        if (process?.isAlive != true) return
        runCatching {
            sendCommand("set_property", JsonPrimitive("loop-file"), JsonPrimitive(if (enabled) "inf" else "no"))
        }.onFailure { error ->
            PlaybackLog.event("loop_failed", mapOf("enabled" to enabled, "message" to (error.message ?: "unknown")))
        }
    }

    /**
     * Somewhere for mpv to write, one file per attempt, cleaned up with the player.
     *
     * Null if it cannot be made, in which case mpv is simply started without one -- losing the
     * explanation is much better than losing the music.
     */
    private fun newLogFile(): Path? = runCatching {
        val directory = AppDirectories.resolve("logs") ?: return@runCatching null
        java.nio.file.Files.createDirectories(directory)
        directory.resolve("mpv-last.log").also { java.nio.file.Files.deleteIfExists(it) }
    }.getOrNull()

    /**
     * What mpv said was wrong, in one line worth putting on screen.
     *
     * Its log is verbose and mostly about codecs. The lines that matter are the ones it marks as errors,
     * and the last of those is nearly always the one that ended it.
     */
    private fun mpvComplaint(): String? = runCatching {
        val log = processLog ?: return@runCatching null
        complaintIn(java.nio.file.Files.readAllLines(log))
    }.getOrNull()

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
                        /*
                         * Whether that was the end of the track or the end of mpv.
                         *
                         * Both used to arrive here and both were treated as the track finishing, so a
                         * player that fell over a second in looked identical to one that had played to
                         * the end -- the queue moved on, nothing was said, and the listener got silence
                         * they could not account for. mpv exits 0 when it reaches the end of a file and
                         * non-zero when it gives up, which is exactly the distinction needed.
                         */
                        val code = runCatching { process?.exitValue() }.getOrNull()
                        if (code != null && code != 0) {
                            val complaint = mpvComplaint()
                            mutableState.update {
                                it.copy(
                                    status = PlaybackStatus.ERROR,
                                    errorMessage = "Playback stopped. " + (complaint ?: "mpv exited with code $code."),
                                )
                            }
                            PlaybackLog.event(
                                "playback_died",
                                mapOf("exit" to code, "detail" to (complaint ?: "no output")),
                            )
                        } else {
                            mutableState.update { it.copy(status = PlaybackStatus.IDLE) }
                        }
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
                    // Where mpv really is, asked only when the clock says the track should have ended.
                    // A looping player is back near the start by then, and the position has to follow it
                    // rather than sit at the end; the wrap is also the one moment a repeat can be counted.
                    val atEnd = looping && current.durationMs > 0 &&
                        current.positionMs + elapsedMs >= current.durationMs
                    val actual = if (atEnd) measuredPositionMs() else null
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
                        val wrapped = actual != null && duration > 0 && actual < duration / 2
                        latest.copy(
                            durationMs = duration,
                            positionMs = when {
                                wrapped -> actual!!
                                actual != null -> actual.coerceAtMost(duration)
                                duration > 0 -> next.coerceAtMost(duration)
                                else -> next
                            },
                            loops = if (wrapped) latest.loops + 1 else latest.loops,
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

    /** Where mpv says it is in the file, or null if it cannot be asked right now. */
    private suspend fun measuredPositionMs(): Long? =
        runCatching { getNumberProperty("time-pos") }.getOrNull()
            ?.takeIf { it.isFinite() && it >= 0 }
            ?.let { (it * 1_000).toLong() }

    private fun createIpcEndpoint(): String {
        val name = "noctorium-mpv-${UUID.randomUUID()}"
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
