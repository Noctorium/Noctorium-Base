package app.noctorium.playback

import app.noctorium.settings.AppDirectories
import app.noctorium.settings.Equalizer
import app.noctorium.settings.EqualizerSettings
import app.noctorium.domain.Track
import app.noctorium.platform.isMacOs
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

internal const val NORMAL_MAX_VOLUME = 1f

/**
 * What the boost actually is: a compressor with make-up gain, and a limiter to catch what it throws.
 *
 * It used to be raw gain -- mpv's `volume` pushed to 200, then as far as 1000. That does not work, and
 * measuring it says why. mpv's volume is a cubic curve, so 200 is not twice as loud, it is eight times
 * the amplitude; against music mastered near full scale the samples saturate at once. Rendered to a file
 * and measured, a tone at volume 200 came out at exactly 100% of full scale, and at volume 400 it was
 * still exactly 100% -- louder by nothing at all, just squarer. Which is what the toggle was doing:
 * distortion, then no further change, which is exactly what it looks like when it appears to do nothing.
 *
 * Making something genuinely louder means raising the quiet parts rather than pushing the loud ones past
 * the ceiling. Same measurement, same tone, this chain: peak held at full scale and RMS -- which is what
 * loudness follows -- up from 8.8% to 63.8%. Seven times, and not a clipped sample in it.
 */
internal const val BOOST_FILTER = "lavfi=[acompressor=threshold=-20dB:ratio=4:makeup=8,alimiter=limit=0.95]"

/**
 * Every filter Noctorium puts in front of the speakers, as one chain for mpv's af: the equaliser, then the
 * boost, then one limiter for whatever either of them pushed towards the ceiling. Empty when there is
 * nothing to do, which is how most tracks play.
 *
 * One chain, set whole, because both are changed while a track plays: setting the property replaces what
 * was there, so the equaliser cannot pile up copies of itself, or knock the boost off by being set.
 *
 * Each band is ffmpeg's peaking filter an octave wide at the band's centre. A curve that lifts anything is
 * given half its highest lift back as headroom before the bands -- most of what is lifted is quieter
 * than the loudest part of the record -- and the limiter catches the rest, so no preset ever clips.
 */
internal fun audioFilterChain(equalizer: EqualizerSettings, boost: Boolean): String {
    val stages = mutableListOf<String>()
    var lifts = false
    if (equalizer.shapesSound) {
        val gains = equalizer.gains
        val highest = gains.maxOrNull() ?: 0f
        val preamp = equalizer.preampDb - (highest.coerceAtLeast(0f) / 2f)
        if (preamp != 0f) stages += "volume=${decibels(preamp)}dB"
        Equalizer.BANDS_HZ.zip(gains).filter { (_, gain) -> gain != 0f }.forEach { (hz, gain) ->
            stages += "equalizer=f=$hz:t=o:w=1:g=${decibels(gain)}"
        }
        lifts = highest > 0f || equalizer.preampDb > 0f
    }
    if (boost) stages += "acompressor=threshold=-20dB:ratio=4:makeup=8"
    if (boost || lifts) stages += "alimiter=limit=0.95"
    return if (stages.isEmpty()) "" else "lavfi=[${stages.joinToString(",")}]"
}

private fun decibels(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

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
 *
 * An address in it is cut down to the server's name. A stream address is several hundred characters of
 * signature that meant nothing on screen, and it carries the computer's own IP address -- which has no
 * business in an error message, or in a log somebody may be asked to send.
 */
internal fun complaintIn(lines: List<String>): String? {
    val complaint = lines.lastOrNull { line ->
        // Anchored on "][" so it cannot match an "e" inside a timestamp or a message.
        line.contains("][e][") || line.contains("][f][")
    } ?: return null
    // Strips the leading bracket groups, however many mpv used, leaving the sentence itself.
    return complaint.replace(Regex("^(\\[[^]]*])+"), "")
        .replace(ADDRESS) { it.groupValues[1] }
        .trim().take(200).ifBlank { null }
}

/** A web address, with the host kept and the path and query -- signatures, IP addresses -- dropped. */
private val ADDRESS = Regex("""https?://([^/?#\s]+)[^\s]*?(?=[.,;:]?(?:\s|$))""")

/**
 * Whether mpv's log says the server turned the address away, which a fresh address fixes and nothing
 * else does.
 *
 * Refused, gone and not found alike: an address bound to a network the computer has left comes back
 * 403, and one that has run out comes back 403 or 410. A missing audio device, or a file mpv cannot
 * decode, ends the player just as fast and says something else, and asking the service again for those
 * would only put a lookup in front of the message.
 */
internal fun refusalIn(lines: List<String>): Boolean = lines.any { line ->
    (line.contains("][e][") || line.contains("][f][")) && HTTP_REFUSAL.containsMatchIn(line)
}

/** curl's way of saying it, then ffmpeg's, which mpv falls back to for some streams. */
private val HTTP_REFUSAL = Regex("""HTTP error 4\d\d|Server returned 4\d\d""")

/** Ten seconds of asking at the ticker's rate, after which a stream is taken to have no length. */
private const val MAX_DURATION_PROBES = 40

/** How many times one track is brought back after mpv gives up on it, before the listener is told. */
internal const val MAX_RECOVERIES = 2

/** How far before the end a clean exit still counts as the end, allowing for mpv's own rounding. */
private const val CUT_SHORT_MARGIN_MS = 10_000L

/** How far back a recovered track resumes, so nothing is lost across the join. */
private const val RESUME_OVERLAP_MS = 1_500L

private const val NETWORK_TIMEOUT_SECONDS = 20
private const val CONNECT_TIMEOUT_SECONDS = 10

/** The piece curl fetches at a time: yt-dlp's own chunk size for YouTube. */
private const val REQUEST_PIECE = "10MiB"

/**
 * Where mpv is told to listen for the controls: a new address for every player started.
 *
 * A named pipe on Windows and a socket file everywhere else. A Mac needs more care with the socket than
 * Linux does, because a socket's path has a hard limit -- 104 bytes on macOS, the terminating zero among
 * them -- and Java's temporary folder there is a per-user one under /var/folders, 48 characters long before
 * the name even starts. The name used on Linux, with a whole UUID in it, brings that to 104 exactly: one
 * too many, and mpv declines to listen at all, which looks like a player that ignores every button. So a
 * Mac gets a shorter name in the same private folder, and /tmp in the unlikely case that still will not fit.
 */
internal fun mpvIpcEndpoint(osName: String, temporaryFolder: String, unique: UUID = UUID.randomUUID()): String {
    val name = "noctorium-mpv-$unique"
    if (osName.startsWith("Windows", ignoreCase = true)) return "\\\\.\\pipe\\$name"
    if (!isMacOs(osName)) return Path.of(temporaryFolder, "$name.sock").toString()
    // Eight hex digits are plenty for a folder only this user writes to, and where each socket is deleted
    // as its player stops.
    val short = "noctorium-mpv-${unique.toString().take(8)}.sock"
    val private = Path.of(temporaryFolder, short).toString()
    return if (private.toByteArray(Charsets.UTF_8).size < MAC_SOCKET_PATH_BYTES) private else "/tmp/$short"
}

/** The size of a socket address's path on macOS, terminating zero included. */
internal const val MAC_SOCKET_PATH_BYTES = 104

/** What each mpv on this machine understands, asked once per executable. */
private val knownOptions = java.util.concurrent.ConcurrentHashMap<Path, Set<String>>()

/**
 * The options [mpv] accepts, from its own `--list-options`.
 *
 * Asked rather than assumed because Noctorium runs whichever mpv it finds, and on Linux that is often the
 * distribution's, years older than the one bundled for Windows. Empty if mpv cannot be asked, which leaves
 * every optional flag off -- playing as before rather than not playing.
 */
internal fun optionsOf(mpv: Path): Set<String> = knownOptions.getOrPut(mpv) {
    runCatching {
        val lister = ProcessBuilder(mpv.toString(), "--no-config", "--list-options")
            .redirectErrorStream(true)
            .start()
        val listing = lister.inputStream.bufferedReader().use { it.readText() }
        lister.waitFor(10, TimeUnit.SECONDS)
        optionNamesIn(listing)
    }.getOrDefault(emptySet())
}

/** The option names out of an mpv `--list-options` listing: the words after a leading `--`. */
internal fun optionNamesIn(listing: String): Set<String> =
    Regex("""^\s*--([a-z0-9][a-z0-9-]*)""", RegexOption.MULTILINE)
        .findAll(listing)
        .map { it.groupValues[1] }
        .toSet()

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

    /**
     * Whether mpv is to start the file over when it reaches the end, instead of exiting.
     *
     * Kept here as well as told to the running player, because the next track starts a new mpv and it
     * has to be started looping too.
     */
    @Volatile private var looping = false

    /** How fast to play. Kept here, like looping, so each new player starts with it. */
    @Volatile private var speed = 1f

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
        // Asked now, off to one side, so the first song of the session is not the one that waits the
        // quarter of a second mpv takes to list what it understands.
        scope.launch(Dispatchers.IO) { executable()?.let(::optionsOf) }
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
    override suspend fun play(track: Track) {
        // A track chosen afresh starts with its full allowance of recoveries. See [recover].
        recoveries = 0
        start(track, startMs = 0)
    }

    /**
     * How many times the current track has been brought back after mpv gave up on it.
     *
     * Bounded, because a stream that fails every time it is fetched afresh is not going to be rescued by a
     * fourth attempt, and a player that silently retries forever is worse than one that says so.
     */
    @Volatile private var recoveries = 0

    private suspend fun start(track: Track, startMs: Long): Unit = playMutex.withLock {
        mutableState.update { it.copy(
            status = PlaybackStatus.RESOLVING,
            track = track,
            errorMessage = null,
            positionMs = startMs,
            durationMs = track.durationMs ?: 0,
            loops = 0,
        ) }
        // Logged on the way in as well as the way out, so that next time the difference between "never
        // started" and "started and vanished" is a fact rather than a deduction.
        PlaybackLog.event("playback_requested", mapOf("track" to track.queueKey, "startMs" to startMs))
        try {
            val mpv = executable() ?: throw BackendException(
                "mpv is missing, so there is nothing to play through. Settings, then Playback tools, installs it.",
            )
            val supported = withContext(Dispatchers.IO) { optionsOf(mpv) }
            launch(track, mpv, mediaAddress(track), supported, startMs)
            delay(400)
            // Addresses are remembered for hours now, and one the server has since stopped accepting --
            // a changed network, a VPN switched on -- is refused in the first third of a second. That is
            // fixed by asking the service again, so it is asked, once, instead of showing an error. Only
            // for a refusal: a missing audio device fails just as fast, and a new address would only
            // put a lookup in front of the message.
            if (process?.isAlive != true && addressRefused() && downloadedFile(track) == null &&
                recoveries < MAX_RECOVERIES
            ) {
                recoveries++
                PlaybackLog.event(
                    "playback_address_refused",
                    mapOf("track" to track.queueKey, "attempt" to recoveries, "detail" to mpvComplaint()),
                )
                resolver.forgetAudio(track.sourceUrl)
                launch(track, mpv, mediaAddress(track), supported, startMs)
                delay(400)
            }
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

    /** Whether the player that just stopped was turned away by the server. See [refusalIn]. */
    private fun addressRefused(): Boolean = runCatching {
        processLog?.let { refusalIn(java.nio.file.Files.readAllLines(it)) } ?: false
    }.getOrDefault(false)

    /** Stands down whatever was playing and starts mpv on [mediaUrl]. Whether it stays up is the caller's to see. */
    private suspend fun launch(track: Track, mpv: Path, mediaUrl: String, supported: Set<String>, startMs: Long) {
        stopProcess()
        // A new process measures its own stream; the last one's length is not this one's.
        streamLengthMs = 0L
        lengthProbes = 0
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
                    add("--volume=${(mutableState.value.volume * 100).toInt()}")
                    // A new process for every track, so the boost and the equaliser have to be asked for
                    // again each time or they would quietly lapse at the end of every song.
                    audioFilterChain(equalizer, mutableState.value.volumeBoostEnabled).takeIf { it.isNotEmpty() }?.let { add("--af=$it") }
                    add("--mute=${if (mutableState.value.isMuted) "yes" else "no"}")
                    // Repeat-one, done by the player: it seeks back to the start out of its own
                    // cache instead of exiting, so there is no gap and nothing is fetched again.
                    if (looping) add("--loop-file=inf")
                    if (speed != 1f) add("--speed=${"%.2f".format(Locale.ROOT, speed)}")
                    add("--title=Noctorium")
                    addAll(streamOptions(mediaUrl, supported))
                    // Resuming after a recovery, a moment before where it stopped so nothing is lost
                    // across the join. Relative to the start of the file, which is what `+` means.
                    if (startMs > 0) add("--start=+${"%.1f".format(Locale.ROOT, startMs / 1000.0)}")
                    add("--")
                    add(mediaUrl)
                },
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
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
        // Unity, boosted or not. The boost is a filter now rather than a number above a hundred, so the
        // slider means the same thing either way and cannot be dragged into distortion.
        val maximum = NORMAL_MAX_VOLUME
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

    /**
     * Turns the loudness chain on or off, leaving the volume alone.
     *
     * It used to move the slider too -- straight to 200% on, back to where it was off -- which is how a
     * boost that only distorted still looked like it had done something.
     */
    override suspend fun setVolumeBoost(enabled: Boolean) {
        if (mutableState.value.volumeBoostEnabled == enabled) return
        try {
            if (process?.isAlive == true) {
                // Replacing the whole chain rather than adding to it, so toggling twice cannot leave two
                // compressors in series; the equaliser is part of the same chain and goes back in with it.
                sendCommand("set_property", JsonPrimitive("af"), JsonPrimitive(audioFilterChain(equalizer, enabled)))
            }
            mutableState.update { it.copy(volumeBoostEnabled = enabled, errorMessage = null) }
            PlaybackLog.event("volume_boost_changed", mapOf("enabled" to enabled, "filter" to audioFilterChain(equalizer, enabled)))
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = error.message ?: "Volume boost failed") }
            PlaybackLog.event("volume_boost_failed", mapOf("enabled" to enabled, "message" to (error.message ?: "unknown")))
        }
    }

    @Volatile
    private var equalizer = EqualizerSettings()

    /** The equaliser: kept for the next track, and put into the one playing now if there is one. */
    override suspend fun setSpeed(speed: Float) {
        val wanted = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (wanted == this.speed) return
        this.speed = wanted
        if (process?.isAlive != true) return
        try {
            // mpv keeps the pitch by itself (audio-pitch-correction is on unless switched off).
            sendCommand("set_property", JsonPrimitive("speed"), JsonPrimitive(wanted.toDouble()))
            PlaybackLog.event("speed_changed", mapOf("speed" to wanted))
        } catch (error: Exception) {
            PlaybackLog.event("speed_failed", mapOf("message" to (error.message ?: "unknown")))
        }
    }

    override suspend fun setEqualizer(settings: EqualizerSettings) {
        if (settings == equalizer) return
        equalizer = settings
        if (process?.isAlive != true) return
        val chain = audioFilterChain(settings, mutableState.value.volumeBoostEnabled)
        try {
            sendCommand("set_property", JsonPrimitive("af"), JsonPrimitive(chain))
            PlaybackLog.event("equalizer_changed", mapOf("filter" to chain))
        } catch (error: Exception) {
            PlaybackLog.event("equalizer_failed", mapOf("message" to (error.message ?: "unknown")))
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
                        val failed = code != null && code != 0
                        val cutShort = !failed && endedEarly(current.positionMs)
                        val track = current.track
                        if ((failed || cutShort) && track != null && recoveries < MAX_RECOVERIES) {
                            recoveries++
                            PlaybackLog.event(
                                "playback_recovering",
                                mapOf(
                                    "track" to track.queueKey,
                                    "exit" to code,
                                    "cutShort" to cutShort,
                                    "atMs" to current.positionMs,
                                    "attempt" to recoveries,
                                    "detail" to (mpvComplaint() ?: "no output"),
                                ),
                            )
                            // From outside this loop: starting again stops this ticker, and a coroutine
                            // that waits on its own cancellation waits forever.
                            scope.launch { recover(track, current.positionMs) }
                        } else if (failed || cutShort) {
                            val complaint = mpvComplaint()
                            mutableState.update {
                                it.copy(
                                    status = PlaybackStatus.ERROR,
                                    errorMessage = "Playback stopped. " + (complaint ?: "mpv exited with code $code."),
                                )
                            }
                            PlaybackLog.event(
                                "playback_died",
                                mapOf("exit" to code, "cutShort" to cutShort, "detail" to (complaint ?: "no output")),
                            )
                        } else {
                            mutableState.update { it.copy(status = PlaybackStatus.IDLE) }
                        }
                        break
                    }
                    // The length as mpv itself measures it, asked for once the stream is playing. Not
                    // for the seek bar, which has the listing's length already, but for telling a track
                    // that finished from one that was cut off: see [endedEarly].
                    if (streamLengthMs == 0L && lengthProbes < MAX_DURATION_PROBES) {
                        lengthProbes++
                        streamLengthMs = measuredDurationMs()
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
    /** The stream's length as mpv measured it for the current process, or 0 while it has not said. */
    @Volatile private var streamLengthMs = 0L
    @Volatile private var lengthProbes = 0

    /**
     * Whether an mpv that exited cleanly stopped before the end of the track.
     *
     * This is the quiet way a song dies, and the one the listener hears as skipping. When a connection
     * drops mid-track and mpv's own retries run out, it treats the end of what it has as the end of the
     * file and exits with 0, exactly as it does after the last second of a song -- so the queue moved on
     * and nothing was said.
     *
     * Judged against the length mpv itself measured and never the listing's. A listing's length is often
     * a few seconds off the audio, and trusting it would call every such track cut short at its own end
     * and try to rescue it. With no measured length there is no judgement at all: an honest end is the
     * safe assumption.
     */
    private fun endedEarly(positionMs: Long): Boolean =
        !looping && streamLengthMs > 0 && positionMs < streamLengthMs - CUT_SHORT_MARGIN_MS

    /**
     * Brings the current track back after mpv gave up on it: a freshly fetched address, from where it
     * stopped.
     *
     * The address goes first. A stream that would not open, or stopped delivering, is most often a stream
     * address that stopped being good -- expired, or refused -- and asking mpv to try the same one again
     * is asking for the same answer. The phone has done this for refused streams all along; the desktop
     * showed an error and stopped.
     */
    private suspend fun recover(track: Track, atMs: Long) {
        resolver.forgetAudio(track.sourceUrl)
        runCatching { start(track, startMs = (atMs - RESUME_OVERLAP_MS).coerceAtLeast(0)) }
    }

    /**
     * How mpv should fetch a stream, as opposed to play a file.
     *
     * First, never mpv's own youtube-dl fallback. Given an address that will not open, mpv runs youtube-dl
     * on it -- on a raw googlevideo address, which no extractor can do anything with -- and then exits
     * reporting "youtube-dl failed: unexpected error occurred". That is the message that stopped songs:
     * seconds of delay, a certain failure, and the real reason the stream would not open replaced by a
     * misleading one. Noctorium resolves every stream itself; mpv's job is to play the address it is given
     * and, if it cannot, say why.
     *
     * Then the client yt-dlp resolved the address as, so mpv fetches it announcing itself the same way.
     *
     * Then how long to wait. mpv's default is a minute of silence before it gives up on a stalled
     * connection; twenty seconds, so that a stream which has gone is noticed and fetched afresh while the
     * listener still thinks it is buffering.
     *
     * And, where this mpv fetches over curl, in bounded pieces rather than one open-ended request -- the way
     * yt-dlp itself downloads from YouTube. A dropped connection then costs one piece, which curl retries,
     * rather than the rest of the song.
     *
     * Each option only if this mpv has it. An option mpv does not recognise stops it from starting at all,
     * which would turn a fix for some songs stopping into every song stopping. The curl options are recent;
     * a distribution's packaged mpv may well not have them.
     */
    private fun streamOptions(mediaUrl: String, supported: Set<String>): List<String> = buildList {
        if ("ytdl" in supported) add("--ytdl=no")
        if (!mediaUrl.startsWith("http://") && !mediaUrl.startsWith("https://")) return@buildList
        if ("user-agent" in supported) resolver.userAgentFor(mediaUrl)?.let { add("--user-agent=$it") }
        if ("network-timeout" in supported) add("--network-timeout=$NETWORK_TIMEOUT_SECONDS")
        if ("curl-connect-timeout" in supported) add("--curl-connect-timeout=$CONNECT_TIMEOUT_SECONDS")
        if ("curl-max-request-size" in supported) add("--curl-max-request-size=$REQUEST_PIECE")
    }

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

    private fun createIpcEndpoint(): String =
        mpvIpcEndpoint(System.getProperty("os.name").orEmpty(), System.getProperty("java.io.tmpdir"))

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
