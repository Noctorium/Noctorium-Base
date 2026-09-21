package app.spiceity.android

import app.spiceity.net.networkFailureMessage
import app.spiceity.net.retryingTransientFailures
import app.spiceity.playback.AudioAddressCache
import app.spiceity.domain.Album
import app.spiceity.domain.Artist
import app.spiceity.domain.Playlist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import app.spiceity.downloads.ExportFormat
import app.spiceity.playback.BackendException
import app.spiceity.playback.MusicBackend
import app.spiceity.settings.CookieSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** The logcat tag every extraction failure is written under. */
private const val LOG_TAG = "SpiceityBackend"

/**
 * What a track is, and where its audio is, read the way NewPipe reads it.
 *
 * The phone's answer to [MusicBackend]. yt-dlp is not an option here — it is a Python program, and since
 * Android 10 an app may not execute a binary out of its own data directory even if one were shipped — so
 * this uses NewPipeExtractor, which does the same job as a library: it reads what a service's own web
 * player reads and hands back a playable address, with no API key involved.
 *
 * The shapes it returns are not the same as yt-dlp's, so everything below is translation. Where NewPipe
 * knows less than yt-dlp did, the field is left null rather than filled with a guess: a track carrying its
 * provider's name where the artist belongs is a bug this project has already had once.
 */
class NewPipeBackend(
    private val http: OkHttpClient,
    /** Where a download is written. Handed in because only the platform knows the app's own directory. */
    private val downloadDirectory: Path,
    /**
     * Whether a download should be refused right now.
     *
     * Asked at the moment of downloading rather than read once, because the answer changes when somebody
     * walks out of the house. Supplied as a function so this class keeps knowing nothing about settings.
     */
    private val refuseDownload: () -> String? = { null },
    /**
     * What the phone knows about its connection at the moment a lookup fails, or null for nothing more
     * than the failure itself says. See `Context.describeNetworkProblem`.
     */
    private val networkProblem: () -> String? = { null },
) : MusicBackend {

    /**
     * What one visit to a track's page found out, kept so the visit is not repeated.
     *
     * Playing a track used to read its page twice, back to back: once to fill in the artist and length,
     * and once more to find the audio. Each read is two to three seconds on a phone, and together they
     * were most of the wait between the tap and the sound. Now the page is read once, the address goes
     * into [addresses] and the facts go here, and whichever is asked for second is answered from memory.
     */
    private class PageFacts(
        val title: String?,
        val uploader: String?,
        val durationMs: Long?,
        val artworkUrl: String?,
    )

    private val facts = ConcurrentHashMap<String, PageFacts>()
    private val addresses = AudioAddressCache()

    /**
     * Sessions, kept per provider.
     *
     * A phone has no other browser to borrow cookies from, so what arrives here is a jar the app's own
     * WebView sign-in wrote. It is read into a `Cookie` header once, rather than per request.
     */
    private val sessions = ConcurrentHashMap<ProviderType, String>()

    @Volatile private var soundCloudUser: String = ""

    override fun useSession(provider: ProviderType, source: CookieSource) {
        val header = source.cookieFile
            .takeIf(String::isNotBlank)
            ?.let { runCatching { cookieHeaderFrom(Path.of(it)) }.getOrNull() }
            ?.takeIf(String::isNotBlank)
        if (header == null) sessions.remove(provider) else sessions[provider] = header
    }

    override fun useSoundCloudProfile(username: String) {
        soundCloudUser = username.trim().trim('/').substringAfterLast('/')
    }

    override val soundCloudProfile: String get() = soundCloudUser

    override suspend fun search(provider: ProviderType, query: String, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) { "Search query cannot be blank" }
            val service = serviceFor(provider) ?: return@withContext emptyList()
            val info = attempt("search $provider") {
                SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(query, songFilter(provider), ""))
            }
            info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .take(limit)
                .map { trackOf(it, provider) }
        }

    override suspend fun listPlaylists(provider: ProviderType, url: String, limit: Int): List<Playlist> {
        // NewPipe reads a named playlist, not an account's list of them. YouTube's playlists feed and
        // SoundCloud's sets page are both signed-in surfaces it has no extractor for, so the library is
        // assembled from the services' own APIs in core instead of from here.
        return emptyList()
    }

    override suspend fun listTracks(provider: ProviderType, url: String, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val service = serviceFor(provider) ?: return@withContext emptyList()
            val info = attempt("list $url") { PlaylistInfo.getInfo(service, url) }
            info.relatedItems.take(limit).map { trackOf(it, provider) }
        }

    /**
     * NewPipe returns a playlist whole, with artwork and lengths already on every row.
     *
     * yt-dlp needed a second pass because its fast listing carries an id and nothing else. There is nothing
     * to fill in here, so the slice is answered from the listing rather than by fetching it again.
     */
    override suspend fun resolveTracks(
        provider: ProviderType,
        url: String,
        from: Int,
        to: Int,
    ): List<Track> {
        val all = listTracks(provider, url, limit = to)
        // A 1-based, inclusive range, matching what the caller means by a slice.
        return all.drop((from - 1).coerceAtLeast(0)).take((to - from + 1).coerceAtLeast(0))
    }

    /**
     * Fills in what a listing left out, from the page if it has to be read and from memory if it need not.
     *
     * A track that already has an artist, a length and artwork is handed back untouched without a single
     * request: NewPipe's listings carry all three for nearly everything, and reading the page again to
     * confirm them was the first of the two page reads that made a tap take five seconds. When something
     * is missing the page is read once, and the address found on the way is kept for the play that comes
     * next, so that one costs nothing.
     */
    override suspend fun enrichMetadata(track: Track): Track {
        if (serviceFor(track.provider) == null) return track
        val complete = track.artists.isNotEmpty() && track.durationMs != null && track.artworkUrl != null
        val known = facts[track.sourceUrl]
            ?: if (complete) {
                return track
            } else {
                runCatching { resolveAudio(track.sourceUrl) }
                facts[track.sourceUrl] ?: return track
            }
        return track.copy(
            title = known.title?.takeIf(String::isNotBlank) ?: track.title,
            artists = track.artists.ifEmpty { artistsOf(known.uploader, track.provider) },
            durationMs = known.durationMs ?: track.durationMs,
            artworkUrl = track.artworkUrl ?: known.artworkUrl,
        )
    }

    /**
     * An address the player can read audio from.
     *
     * Answered from memory when it was found in the last half hour -- by the play before, by the
     * enrichment a moment ago, or by the queue looking ahead -- and read from the page otherwise. The best
     * audio-only stream is chosen by bitrate. Video streams are never considered: this is a music player,
     * and fetching a video's pixels to throw them away would spend a phone's data for nothing.
     */
    override suspend fun resolveAudio(sourceUrl: String): String {
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) {
            "Only HTTP media sources are accepted"
        }
        return addresses.resolve(sourceUrl) { readPage(sourceUrl) }
    }

    override fun forgetAudio(sourceUrl: String) = addresses.forget(sourceUrl)

    /**
     * One visit to the track's page: the facts into [facts], the audio address returned.
     *
     * Through the extractor rather than `StreamInfo.getInfo`, deliberately. `getInfo` reads everything a
     * page can say -- every video stream, subtitles, related items, chapters -- and solving the signature
     * on each video stream is a JavaScript evaluation apiece, on a phone. Only the audio streams are asked
     * for here, and only the four facts a listing might have missed.
     *
     * A lookup that fails the instant the phone changes networks is asked once more before it is
     * reported; see `retryingTransientFailures` for why only a quick failure is worth that.
     */
    private suspend fun readPage(sourceUrl: String): String = withContext(Dispatchers.IO) {
        val provider = providerOf(sourceUrl)
        val service = serviceFor(provider)
            ?: throw BackendException("Spiceity cannot play this address on Android yet.")
        val startedAt = System.nanoTime()
        val extractor = attempt("resolve $sourceUrl") {
            retryingTransientFailures { service.getStreamExtractor(sourceUrl).also { it.fetchPage() } }
        }
        // How long the page took, in the log, because "it feels slow" has to be a number to be worked on.
        android.util.Log.i(LOG_TAG, "Read the page for $sourceUrl in ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        if (facts.size > MAX_REMEMBERED_PAGES) facts.clear()
        facts[sourceUrl] = PageFacts(
            title = runCatching { extractor.name }.getOrNull(),
            uploader = runCatching { extractor.uploaderName }.getOrNull(),
            durationMs = runCatching { extractor.length }.getOrNull()?.takeIf { it > 0 }?.times(1_000),
            artworkUrl = runCatching { extractor.thumbnails.lastOrNull()?.url }.getOrNull(),
        )
        val stream = attempt("read the streams of $sourceUrl") { extractor.audioStreams }
            .filter { !it.content.isNullOrBlank() }
            .maxByOrNull(AudioStream::getAverageBitrate)
            ?: throw BackendException(
                "No audio stream came back for this track. It may be unavailable in your region.",
            )
        stream.content
    }

    /** SoundCloud's API answers by numeric id; its pages are addressed by profile name. */
    override suspend fun resolveSoundCloudPermalink(userId: String): String? = null

    /**
     * Fetches the audio in ranged chunks rather than as one long read.
     *
     * This is not an optimisation, it is the difference between finishing and not. Google's media servers
     * throttle a single open GET hard after the first few megabytes — a download would reach roughly half
     * a track and then crawl, with no error, forever. Asking for a few megabytes at a time and coming back
     * for the next range is what yt-dlp and NewPipe both do, and it keeps each request short enough to
     * stay off the throttle and inside a sane read timeout.
     *
     * A server that ignores Range answers 200 with the whole body instead of 206, which is handled by
     * writing what arrives and stopping.
     */
    override suspend fun downloadAudio(
        sourceUrl: String,
        outputTemplate: String,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // Asked before anything is fetched, so a refusal costs nothing and says why.
        refuseDownload()?.let { throw BackendException(it) }
        val address = resolveAudio(sourceUrl)
        // The template is yt-dlp's vocabulary. Here the caller's chosen stem is all that is used, since
        // nothing on this side substitutes fields into a file name.
        val destination = downloadDirectory.resolve(
            Path.of(outputTemplate).fileName.toString().replace("%(ext)s", "m4a"),
        )
        Files.createDirectories(destination.parent)
        val candidate = destination.resolveSibling("${destination.fileName}.part")

        var written = 0L
        var total = -1L
        Files.newOutputStream(candidate).use { output ->
            while (true) {
                val end = written + CHUNK_BYTES - 1
                val request = OkRequest.Builder()
                    .url(address)
                    .header("Range", "bytes=$written-$end")
                    .build()

                val finished = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // 416 means the last chunk asked for a range past the end, which is simply done.
                        if (response.code == 416 && written > 0) return@use true
                        throw BackendException("The service refused the download (HTTP ${response.code}).")
                    }
                    if (total < 0) total = response.totalLength()

                    val body = response.body ?: throw BackendException("The service sent no audio.")
                    var chunk = 0L
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            chunk += read
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }

                    // Nothing came back, or the server sent the whole thing at once and ignored the range.
                    chunk == 0L || response.code == 200 || (total > 0 && written >= total)
                }
                if (finished) break
            }
        }

        if (written == 0L) throw BackendException("The service sent no audio.")
        // Moved into place only once it is whole, so an interrupted download never leaves a file that
        // looks playable and is not.
        Files.move(candidate, destination, StandardCopyOption.REPLACE_EXISTING)
        onProgress(1f)
    }

    /**
     * How long the whole file is, from whichever header the answer came in.
     *
     * A ranged reply states the total after the slash in Content-Range; an unranged one states it in
     * Content-Length. Taking the second for the first would make a 2 MB chunk read as the entire track and
     * the progress bar finish eight times over.
     */
    private fun okhttp3.Response.totalLength(): Long {
        header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()?.let { return it }
        return body?.contentLength() ?: -1L
    }

    /**
     * No, and it is not worth changing.
     *
     * Converting to MP3 would mean bundling an encoder, and the reason MP3 exists on the desktop is to put
     * a file on a phone — which is this. What the services serve is m4a or opus, and Android plays both.
     */
    override fun canConvertAudio(): Boolean = false

    override suspend fun exportAudio(
        sourceUrl: String,
        outputTemplate: String,
        format: ExportFormat,
        onProgress: (Float) -> Unit,
    ) {
        if (format == ExportFormat.MP3) {
            throw BackendException(
                "Spiceity on Android saves audio as it comes rather than converting it to MP3. Your phone " +
                    "plays it either way.",
            )
        }
        downloadAudio(sourceUrl, outputTemplate, onProgress)
    }

    override suspend fun describe(): String = "NewPipeExtractor ${NewPipe.getDownloader()?.let { "ready" } ?: "not started"}"

    /**
     * Turns whatever NewPipe threw into something a listener can read, and writes down what it really was.
     *
     * The log line matters as much as the message. Extraction breaks for reasons invisible from the
     * outside — a service changed its page, a signature could not be solved, a stream needs a token this
     * version cannot mint — and the exception type together with its cause chain is the only thing that
     * says which. What reaches the screen has room for a line; this has room for the truth.
     */
    private inline fun <T> attempt(what: String, block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val chain = generateSequence(error as Throwable) { it.cause }
            .take(5)
            .joinToString(" <- ") { link ->
                link::class.java.simpleName + ": " + link.message?.take(200)
            }
        android.util.Log.w(LOG_TAG, "Could not $what -- $chain", error)
        // The reason, not the address. What reaches the player bar has room for a few words, and leading
        // with the URL spent all of them before saying anything: a Go+ track reported itself as
        // "Could not resolve https://soundcloud.com/nir…", which names neither the problem nor the fix.
        throw BackendException(explain(error), error)
    }

    /**
     * Why a track will not play, in a sentence.
     *
     * NewPipe's own messages are written for whoever is debugging the extractor. Three of them are common
     * enough to be worth translating, and everything else is passed through as it came rather than
     * flattened into a shrug — an unfamiliar message is still a lead, where "something went wrong" is not.
     */
    private fun explain(error: Throwable): String = when {
        error is org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException ->
            "This is a SoundCloud Go+ track, so only subscribers can hear it."
        error is org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException ->
            "This track is not available in your country."
        error is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException ->
            "This track is age-restricted, which needs a signed-in account."
        error is org.schabi.newpipe.extractor.exceptions.PrivateContentException ->
            "This track is private."
        // No signal is the commonest failure there is and it arrived as a Google hostname. Said plainly,
        // before anything else gets a chance to pass the raw text through -- and said the way the phone
        // sees it when it can: a phone that is online but has cut this app off is not "no internet".
        else -> networkFailureMessage(error)?.let { plain -> networkProblem() ?: plain }
            ?: error.message?.takeIf { it.isNotBlank() }?.take(160)
            ?: "This track could not be played (${error::class.java.simpleName})."
    }

    private fun serviceFor(provider: ProviderType): StreamingService? = when (provider) {
        ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> ServiceList.YouTube
        ProviderType.SOUNDCLOUD -> ServiceList.SoundCloud
        // Spotify hands out no audio, and a local file needs no extractor.
        ProviderType.SPOTIFY, ProviderType.LOCAL -> null
    }

    /** NewPipe's filter names, so an album or an artist is not returned where a song was asked for. */
    private fun songFilter(provider: ProviderType): List<String> = when (provider) {
        ProviderType.YOUTUBE_MUSIC -> listOf("music_songs")
        ProviderType.YOUTUBE_VIDEO -> listOf("videos")
        ProviderType.SOUNDCLOUD -> listOf("tracks")
        ProviderType.SPOTIFY, ProviderType.LOCAL -> emptyList()
    }

    private fun trackOf(item: StreamInfoItem, provider: ProviderType): Track = Track(
        provider = provider,
        id = idOf(item.url, provider),
        title = item.name.orEmpty().ifBlank { "Unknown track" },
        artists = artistsOf(item.uploaderName, provider),
        album = null,
        durationMs = item.duration.takeIf { it > 0 }?.times(1_000),
        artworkUrl = item.thumbnails?.lastOrNull()?.url,
        sourceUrl = item.url,
    )

    /**
     * The artist, or nobody.
     *
     * An empty list where the uploader is unknown, deliberately. Falling back to the provider's own name is
     * how a YouTube Music track once came to be credited to "YouTube Music", and an empty artist line reads
     * as missing information, which is what it is.
     */
    private fun artistsOf(name: String?, provider: ProviderType): List<Artist> =
        name?.trim()?.takeIf(String::isNotBlank)
            ?.let { listOf(Artist("$provider:$it", it, provider)) }
            .orEmpty()

    /** The id the rest of Spiceity addresses a track by, which has to match what the desktop uses. */
    private fun idOf(url: String, provider: ProviderType): String = when (provider) {
        ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO ->
            Regex("""[?&]v=([\w-]{11})""").find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast('/').substringBefore('?')
        else -> url.substringAfter("soundcloud.com/", url).trim('/')
    }

    /**
     * Four megabytes: long enough that the per-request overhead is nothing, short enough that Google's
     * throttle never engages and a stalled chunk fails fast instead of hanging the whole download.
     */
    private val CHUNK_BYTES = 4L * 1024 * 1024

    /** Facts about more pages than this and the memory is simply emptied; a few hundred is an evening. */
    private val MAX_REMEMBERED_PAGES = 400

    private fun providerOf(sourceUrl: String): ProviderType = when {
        "music.youtube.com" in sourceUrl -> ProviderType.YOUTUBE_MUSIC
        "youtube.com" in sourceUrl || "youtu.be" in sourceUrl -> ProviderType.YOUTUBE_VIDEO
        "soundcloud.com" in sourceUrl -> ProviderType.SOUNDCLOUD
        else -> ProviderType.LOCAL
    }

    /** Reads a Netscape cookie jar — the format the desktop writes — into one header. */
    private fun cookieHeaderFrom(file: Path): String {
        if (!Files.isRegularFile(file)) return ""
        return Files.readAllLines(file)
            .asSequence()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size >= 7 && fields[6].isNotBlank()) "${fields[5]}=${fields[6]}" else null
            }
            .joinToString("; ")
    }
}

/**
 * How NewPipeExtractor makes its requests: through the same OkHttp client as everything else.
 *
 * NewPipe requires this to be supplied and calls it synchronously, off whatever thread the extraction is
 * running on. Sharing the client matters more than it looks — it is one connection pool for the whole
 * application instead of two, on a device where opening a TLS connection costs battery.
 */
class OkHttpNewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = OkRequest.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }
        if (request.headers()["User-Agent"] == null) {
            builder.header("User-Agent", app.spiceity.net.Http.DESKTOP_USER_AGENT)
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body?.string(),
                    response.request.url.toString(),
                )
            }
        } catch (error: IOException) {
            // NewPipe expects a Response or an IOException, so this is left as one rather than being
            // wrapped into something it does not know how to report.
            throw error
        }
    }
}
