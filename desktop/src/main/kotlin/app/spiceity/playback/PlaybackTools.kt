package app.spiceity.playback

/**
 * The programs a desktop Spiceity shells out to, and what goes wrong when they are not there.
 *
 * A phone needs none of this: the extractor is compiled into the APK and the player is part of Android. On
 * a desktop both are separate programs, and until this file existed Spiceity simply assumed somebody had
 * installed them. A fresh install could not play a single track, and said so by telling the listener to
 * "install it in Spiceity Settings" -- where there was nothing to install it with.
 */
enum class PlaybackTool(
    val displayName: String,
    val purpose: String,
    /** Without this, Spiceity cannot play anything at all. */
    val required: Boolean,
) {
    YT_DLP("yt-dlp", "Finds the audio behind a link", required = true),
    MPV("mpv", "Plays it", required = true),
    FFMPEG("FFmpeg", "Converts downloads to MP3", required = false),
    ;

    /** What the file is called on this platform. */
    fun executableName(windows: Boolean = isWindows): String = when (this) {
        YT_DLP -> if (windows) "yt-dlp.exe" else "yt-dlp"
        MPV -> if (windows) "mpv.exe" else "mpv"
        FFMPEG -> if (windows) "ffmpeg.exe" else "ffmpeg"
    }

    companion object {
        val isWindows: Boolean get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}

/** Where one tool came from, so the panel can say something more useful than "found". */
enum class ToolOrigin {
    /** In Spiceity's own bin folder, which means Spiceity put it there and may replace it. */
    MANAGED,

    /** Already on this machine, found on PATH or named by an environment variable. Left alone. */
    SYSTEM,

    MISSING,
}

data class ToolStatus(
    val tool: PlaybackTool,
    val origin: ToolOrigin,
    val path: String? = null,
    /** Null until something has asked the program itself, which costs a process launch. */
    val version: String? = null,
)

/**
 * What the Settings panel shows, and what the rest of the application asks before it tries to play.
 */
data class PlaybackToolsState(
    val tools: List<ToolStatus> = emptyList(),
    /** The tool currently being fetched, if any. */
    val installing: PlaybackTool? = null,
    /** Between 0 and 1 while bytes are arriving; null while extracting, or when nothing is running. */
    val progress: Float? = null,
    val message: String? = null,
    /** Set once, so a failed automatic attempt does not retry on a loop. */
    val attemptedAutomatically: Boolean = false,
) {
    val missingRequired: List<PlaybackTool>
        get() = tools.filter { it.tool.required && it.origin == ToolOrigin.MISSING }.map { it.tool }

    /** Whether Spiceity can play a track right now. */
    val ready: Boolean get() = tools.isNotEmpty() && missingRequired.isEmpty()

    val busy: Boolean get() = installing != null

    fun status(tool: PlaybackTool): ToolStatus? = tools.firstOrNull { it.tool == tool }
}
