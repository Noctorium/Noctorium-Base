package app.noctorium.playback

import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.downloads.ExportFormat
import app.noctorium.settings.CookieSource

/** Something the backend could not do, worded for a reader rather than for a log. */
class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * How Noctorium finds out what a track is and where its audio can be fetched from.
 *
 * This is the deepest platform difference in the application, and the one with no shared implementation
 * possible. On the desktop it is yt-dlp: a Python program, invoked as a process, that reads what a
 * service's own web player reads and prints a stream address. On a phone it cannot be — there is no yt-dlp
 * for Android, and since Android 10 an app may not execute a binary from its own data directory even if one
 * were shipped — so it is NewPipeExtractor, a library doing the same job in the same process.
 *
 * Both answer the same questions, which is what lets everything above this line be written once. None of
 * these methods reaches for a network client of its own: an implementation is expected to be the only thing
 * in Noctorium that knows how its service is actually read.
 */
interface MusicBackend {
    /**
     * Which session to read a provider's catalogue with.
     *
     * A [CookieSource] rather than the arguments or the header an implementation will end up building,
     * because those are not the same shape on both sides: the desktop turns this into yt-dlp flags, the
     * phone into a `Cookie` header for its own requests. Passing either one through would make the caller
     * responsible for a detail belonging to the platform.
     */
    fun useSession(provider: ProviderType, source: CookieSource)

    /**
     * SoundCloud addresses a listener's own playlists by profile name, and cookies do not reveal it, so it
     * is kept alongside the session it belongs to.
     */
    fun useSoundCloudProfile(username: String)

    val soundCloudProfile: String

    suspend fun search(provider: ProviderType, query: String, limit: Int = 8): List<Track>

    suspend fun listPlaylists(provider: ProviderType, url: String, limit: Int = 50): List<Playlist>

    suspend fun listTracks(provider: ProviderType, url: String, limit: Int = 200): List<Track>

    /** A 1-based slice, fully resolved, for filling in the artwork and lengths a listing leaves out. */
    suspend fun resolveTracks(provider: ProviderType, url: String, from: Int, to: Int): List<Track>

    /** Fills in what a listing did not carry for one track, returning it unchanged if nothing can be. */
    suspend fun enrichMetadata(track: Track): Track

    /** An address the player can actually read audio from. Not durable — these expire. */
    suspend fun resolveAudio(sourceUrl: String): String

    /**
     * Looks the address up now so that asking for it later costs nothing.
     *
     * Called for whatever the queue has lined up next while the current track plays, which is what makes
     * the step from one track to the next immediate rather than a wait. Failures are not reported: the
     * real ask, when it comes, will fail in its own words.
     */
    suspend fun prefetchAudio(sourceUrl: String) {
        runCatching { resolveAudio(sourceUrl) }
    }

    /**
     * Drops whatever this backend remembered about a source's address.
     *
     * The player calls it when a service refused an address it had been given -- most often one bound to
     * a network the device has since left -- so that the next [resolveAudio] goes back to the service.
     */
    fun forgetAudio(sourceUrl: String) {}

    /** SoundCloud's own API answers by numeric id; its pages are addressed by profile name. */
    suspend fun resolveSoundCloudPermalink(userId: String): String?

    /** Keeps the audio on this device, at [outputTemplate], reporting progress from 0 to 1. */
    suspend fun downloadAudio(
        sourceUrl: String,
        outputTemplate: String,
        onProgress: (Float) -> Unit = {},
    )

    /** Whether audio can be re-encoded here, which is the only thing standing between us and MP3. */
    fun canConvertAudio(): Boolean

    /** Writes a file for the listener to keep and take elsewhere, rather than one for Noctorium to play. */
    suspend fun exportAudio(
        sourceUrl: String,
        outputTemplate: String,
        format: ExportFormat,
        onProgress: (Float) -> Unit = {},
    )

    /**
     * Harvests a session out of another browser on this machine.
     *
     * Answers false by default because it is meaningless on a phone: an Android app cannot read another
     * app's cookies, and it does not need to — its own WebView sign-in hands them over directly. On the
     * desktop this is how a SoundCloud token is lifted from a browser the listener already signed in with.
     */
    suspend fun exportCookies(provider: ProviderType, destination: java.nio.file.Path): Boolean = false

    /**
     * What this backend is, for the diagnostics screen.
     *
     * A version string where there is a separate program to have one, and a plain statement where the
     * backend is compiled in and cannot be absent.
     */
    suspend fun describe(): String

    /**
     * What the diagnostics screen should say about this backend.
     *
     * Asked of the backend rather than assembled by the caller, because the questions are not the same:
     * a desktop has three separate programs that can each be missing, and a phone has a library compiled
     * into the APK that cannot be. The default is the honest answer for the second case.
     */
    suspend fun diagnostics(): List<app.noctorium.settings.DiagnosticResult> = listOf(
        app.noctorium.settings.DiagnosticResult(
            name = "Playback backend",
            detail = describe(),
            level = app.noctorium.settings.DiagnosticLevel.PASS,
        ),
    )
}
