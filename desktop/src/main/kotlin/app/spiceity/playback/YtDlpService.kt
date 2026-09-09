package app.spiceity.playback

import app.spiceity.settings.CookieSource
import app.spiceity.domain.Album
import app.spiceity.downloads.ExportFormat
import app.spiceity.domain.Artist
import app.spiceity.domain.Playlist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class YtDlpService(
    private val executable: () -> Path? = BackendLocator::ytDlp,
    /** Injected rather than looked up, so what happens without it can be exercised on a machine that has it. */
    private val ffmpeg: () -> Path? = BackendLocator::ffmpeg,
) : MusicBackend {
    private val json = Json { ignoreUnknownKeys = true }
    private val cookieArguments = ConcurrentHashMap<ProviderType, List<String>>()

    @Volatile private var soundCloudUser: String = ""

    /**
     * Turns a stored session into the flags yt-dlp reads a catalogue with.
     *
     * The translation belongs here rather than at the call site. A cookie file and
     * `--cookies-from-browser` are yt-dlp's own vocabulary, and the phone has no use for either word.
     */
    override fun useSession(provider: ProviderType, source: CookieSource) {
        val arguments = source.ytDlpArguments()
        if (arguments.isEmpty()) cookieArguments.remove(provider) else cookieArguments[provider] = arguments
    }

    /**
     * SoundCloud addresses a listener's own playlists by profile name, and cookies do not reveal it, so the
     * name from Settings is kept here alongside the session it belongs to.
     */
    override fun useSoundCloudProfile(username: String) {
        soundCloudUser = username.trim().trim('/').substringAfterLast('/')
    }

    override val soundCloudProfile: String get() = soundCloudUser

    override suspend fun search(provider: ProviderType, query: String, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Search query cannot be blank" }
        val target = when (provider) {
            ProviderType.YOUTUBE_MUSIC -> {
                val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
                "https://music.youtube.com/search?q=$encodedQuery&sp=EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
            }
            ProviderType.YOUTUBE_VIDEO -> "ytsearch$limit:$query"
            ProviderType.SOUNDCLOUD -> "scsearch$limit:$query"
            // Spotify is read through its own interface, not fetched; a track from it is searched for on
            // the other services at the moment it is played.
            ProviderType.SPOTIFY -> throw BackendException("Spotify is not searched through yt-dlp")
            ProviderType.LOCAL -> throw BackendException("Local search is not supported by yt-dlp")
        }
        val arguments = mutableListOf(
            "--flat-playlist", "--dump-single-json", "--no-warnings", "--no-playlist",
        )
        arguments += accountArguments(provider)
        if (provider == ProviderType.YOUTUBE_MUSIC) arguments += listOf("--playlist-end", limit.toString())
        arguments += target
        val output = run(*arguments.toTypedArray())
        val root = json.parseToJsonElement(output).jsonObject
        root["entries"]?.jsonArray.orEmpty().mapNotNull { mapTrack(provider, it.jsonObject) }
    }

    /** Lists the playlists behind a collection page, such as a YouTube playlists feed or SoundCloud `/sets`. */
    override suspend fun listPlaylists(provider: ProviderType, url: String, limit: Int): List<Playlist> =
        withContext(Dispatchers.IO) {
            val output = run(
                "--flat-playlist",
                "--dump-single-json",
                "--no-warnings",
                "--playlist-end", limit.toString(),
                *accountArguments(provider).toTypedArray(),
                "--",
                url,
            )
            mapPlaylists(provider, json.parseToJsonElement(output).jsonObject)
        }

    /**
     * Lists a playlist's tracks in one flat request. Fast, but SoundCloud answers with stubs that carry no
     * artwork and, inside a set, no title either — [resolveTracks] fills those in afterwards.
     */
    override suspend fun listTracks(provider: ProviderType, url: String, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val root = json.parseToJsonElement(
                playlistJson(provider, listOf("--flat-playlist", "--playlist-end", limit.toString()), url, 90),
            ).jsonObject
            root["entries"]?.jsonArray.orEmpty().mapNotNull { mapTrack(provider, it.jsonObject) }
        }

    /**
     * Fully resolves a 1-based slice of a playlist, which costs one provider request per track but returns real
     * titles, durations and artwork. Slices keep the wait short enough to show results as they arrive.
     */
    override suspend fun resolveTracks(provider: ProviderType, url: String, from: Int, to: Int): List<Track> =
        withContext(Dispatchers.IO) {
            require(from in 1..to) { "Playlist slice must be 1-based and ordered" }
            val root = json.parseToJsonElement(
                playlistJson(provider, listOf("--playlist-items", "$from-$to"), url, 180),
            ).jsonObject
            root["entries"]?.jsonArray.orEmpty().mapNotNull { mapTrack(provider, it.jsonObject) }
        }

    private fun playlistJson(
        provider: ProviderType,
        selection: List<String>,
        url: String,
        timeoutSeconds: Long,
    ): String = run(
        timeoutSeconds = timeoutSeconds,
        arguments = buildList {
            add("--dump-single-json")
            add("--skip-download")
            add("--no-warnings")
            addAll(selection)
            addAll(accountArguments(provider))
            add("--")
            add(url)
        }.toTypedArray(),
    )

    internal fun mapPlaylists(provider: ProviderType, root: JsonObject): List<Playlist> =
        root["entries"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val url = item.string("webpage_url") ?: item.string("url") ?: return@mapNotNull null
            val id = item.string("id") ?: url
            val title = item.string("title")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            Playlist(
                id = id,
                title = title,
                provider = provider,
                ownerName = item.string("uploader") ?: item.string("channel"),
                artworkUrl = item.bestThumbnail(),
                sourceUrl = url,
                trackCount = item["playlist_count"]?.jsonPrimitive?.intOrNull,
            )
        }

    override suspend fun resolveAudio(sourceUrl: String): String = withContext(Dispatchers.IO) {
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) {
            "Only HTTP media sources are accepted"
        }
        val provider = when {
            "music.youtube.com" in sourceUrl -> ProviderType.YOUTUBE_MUSIC
            "youtube.com" in sourceUrl || "youtu.be" in sourceUrl -> ProviderType.YOUTUBE_VIDEO
            "soundcloud.com" in sourceUrl -> ProviderType.SOUNDCLOUD
            else -> null
        }
        run(
            "--format", "bestaudio/best",
            "--get-url",
            "--no-playlist",
            "--no-warnings",
            *provider?.let(::playbackArguments).orEmpty().toTypedArray(),
            "--",
            sourceUrl,
        ).lineSequence().firstOrNull { it.startsWith("http") }
            ?: throw BackendException("yt-dlp returned no playable audio URL")
    }

    /**
     * The cookies to attach when asking for a stream URL, which are not always the ones used for browsing.
     *
     * YouTube scores the whole request: a signed-in session arriving from something that is not a browser is
     * refused at the player with "The page needs to be reloaded", and no player client escapes it, while the
     * same track resolves immediately with no cookies at all. Browsing is unaffected, so the session stays in
     * place everywhere else and only stream resolution goes out anonymous. SoundCloud has no such check and
     * needs its session here to reach private and subscriber-only audio, so it keeps its cookies.
     */
    internal fun playbackArguments(provider: ProviderType): List<String> = when (provider) {
        ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> emptyList()
        else -> accountArguments(provider)
    }


    override suspend fun enrichMetadata(track: Track): Track = withContext(Dispatchers.IO) {
        if (!track.hasPlaceholderArtist()) return@withContext track
        val output = run(
            "--dump-single-json",
            "--skip-download",
            "--no-warnings",
            "--no-playlist",
            // A player-level request, so it is refused for exactly the same reason a stream is: see
            // [playbackArguments]. With cookies attached this silently failed and left every YouTube
            // Music track showing its provider name where the artist belongs.
            *playbackArguments(track.provider).toTypedArray(),
            "--",
            track.sourceUrl,
        )
        mapEnrichedTrack(track, json.parseToJsonElement(output).jsonObject)
    }

    suspend fun version(): String = withContext(Dispatchers.IO) { run("--version").trim() }

    override suspend fun describe(): String =
        "yt-dlp " + runCatching { version() }.getOrDefault("(not found)")

    /**
     * Turns a numeric SoundCloud account id into its profile name.
     *
     * `api.soundcloud.com/users/<id>` is a page yt-dlp resolves without any credentials, and every item it
     * lists lives under the account's own profile — so the first path segment of any item's link is the name
     * that addresses their playlists. Returns null for an account with nothing public to list.
     */
    override suspend fun resolveSoundCloudPermalink(userId: String): String? = withContext(Dispatchers.IO) {
        if (userId.isBlank() || !userId.all(Char::isDigit)) return@withContext null
        val output = runCatching {
            run(
                "--flat-playlist",
                "--dump-single-json",
                "--no-warnings",
                "--playlist-end", "3",
                "--",
                "https://api.soundcloud.com/users/$userId",
            )
        }.getOrNull() ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return@withContext null
        root["entries"]?.jsonArray.orEmpty().firstNotNullOfOrNull { element ->
            val url = element.jsonObject.string("webpage_url") ?: element.jsonObject.string("url")
            url?.let(::permalinkFromTrackUrl)
        }
    }

    internal fun permalinkFromTrackUrl(url: String): String? = url
        .substringAfter("soundcloud.com/", missingDelimiterValue = "")
        .substringBefore('/')
        .substringBefore('?')
        .takeIf { it.isNotBlank() && it != "you" && !it.startsWith("users") }

    /**
     * Writes the provider's browser cookies to [destination] so the caller can lift the one session token it
     * needs. yt-dlp dumps the jar it loaded when `--cookies` accompanies `--cookies-from-browser`; the caller
     * is responsible for deleting the file straight after reading it.
     */
    override suspend fun exportCookies(provider: ProviderType, destination: Path): Boolean = withContext(Dispatchers.IO) {
        val cookieArgs = accountArguments(provider)
        if (cookieArgs.isEmpty()) return@withContext false
        runCatching {
            run(
                "--cookies", destination.toString(),
                "--simulate",
                "--no-warnings",
                "--playlist-items", "1",
                *cookieArgs.toTypedArray(),
                "--",
                "scsearch1:spiceity session",
            )
        }
        Files.isRegularFile(destination)
    }

    internal fun accountArguments(provider: ProviderType): List<String> = cookieArguments[provider]
        ?: if (provider == ProviderType.YOUTUBE_VIDEO) {
            cookieArguments[ProviderType.YOUTUBE_MUSIC].orEmpty()
        } else emptyList()

    internal fun mapTrack(provider: ProviderType, item: JsonObject): Track? {
        val id = item.string("id") ?: return null
        val uploader = item.string("artist") ?: item.string("uploader") ?: item.string("channel") ?: provider.displayName
        val url = item.string("webpage_url") ?: item.string("original_url") ?: item.string("url")?.let { raw ->
            if (raw.startsWith("http")) raw else when (provider) {
                ProviderType.YOUTUBE_MUSIC -> "https://music.youtube.com/watch?v=$raw"
                ProviderType.YOUTUBE_VIDEO -> "https://www.youtube.com/watch?v=$raw"
                ProviderType.SOUNDCLOUD -> null
                ProviderType.SPOTIFY, ProviderType.LOCAL -> null
            }
        } ?: return null
        // Playlist stubs can arrive without a title; the page slug keeps the track playable and named until
        // metadata enrichment replaces it, rather than dropping it from the playlist entirely.
        val title = item.string("title")?.takeIf(String::isNotBlank) ?: titleFromUrl(url) ?: return null
        val artist = Artist("${provider.name}:$uploader", uploader, provider)
        val durationMs = item["duration"]?.jsonPrimitive?.doubleOrNull?.times(1_000)?.toLong()
        val thumbnail = item.bestThumbnail()
            ?: if (provider == ProviderType.YOUTUBE_MUSIC || provider == ProviderType.YOUTUBE_VIDEO)
                "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            else null
        return Track(provider, id, title, listOf(artist), durationMs = durationMs, artworkUrl = thumbnail, sourceUrl = url)
    }

    internal fun mapEnrichedTrack(track: Track, item: JsonObject): Track {
        val rawArtist = item.string("uploader")
            ?: item.string("channel")
            ?: item.string("artist")?.substringBefore(',')
            ?: item.string("creator")?.substringBefore(',')
        val artistName = rawArtist
            ?.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals(track.provider.displayName, ignoreCase = true) }
            ?: return track
        val canonicalTitle = item.string("track")?.takeIf(String::isNotBlank)
            ?: item.string("title")?.takeIf(String::isNotBlank)
            ?: track.title
        val artist = Artist("${track.provider.name}:$artistName", artistName, track.provider)
        val album = item.string("album")?.takeIf(String::isNotBlank)?.let { albumTitle ->
            Album(
                id = "${track.provider.name}:album:$albumTitle",
                title = albumTitle,
                artists = listOf(artist),
                provider = track.provider,
                artworkUrl = track.artworkUrl,
            )
        } ?: track.album
        val durationMs = item["duration"]?.jsonPrimitive?.doubleOrNull?.times(1_000)?.toLong() ?: track.durationMs
        val artwork = item.bestThumbnail() ?: track.artworkUrl
        return track.copy(
            title = canonicalTitle,
            artists = listOf(artist),
            album = album,
            durationMs = durationMs,
            artworkUrl = artwork,
        )
    }

    private fun Track.hasPlaceholderArtist(): Boolean = artists.isEmpty() || artists.all { artist ->
        artist.name.isBlank() ||
            artist.name.equals(provider.displayName, ignoreCase = true) ||
            artist.name.equals("YouTube Music", ignoreCase = true) ||
            artist.name.equals("YouTube", ignoreCase = true) ||
            artist.name.equals("SoundCloud", ignoreCase = true)
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    /**
     * Picks the smallest artwork variant still large enough to look sharp. yt-dlp's own `thumbnail` is the
     * original upload — a SoundCloud cover measured at 271 KB against 65 KB for its 500px variant — which adds
     * up fast down a playlist of two hundred rows.
     */
    internal fun JsonObject.bestThumbnail(minimumWidth: Int = 250): String? {
        val sized = this["thumbnails"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val url = item.string("url") ?: return@mapNotNull null
            val width = item["width"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            width to url
        }
        return sized.filter { it.first >= minimumWidth }.minByOrNull { it.first }?.second
            ?: string("thumbnail")
            ?: sized.maxByOrNull { it.first }?.second
            // Some listings omit width entirely; those arrays run smallest to largest.
            ?: this["thumbnails"]?.jsonArray?.lastOrNull()?.jsonObject?.string("url")
    }

    private fun titleFromUrl(url: String): String? = url.trimEnd('/')
        .substringAfterLast('/')
        .substringBefore('?')
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
        .takeIf { it.isNotBlank() && !it.startsWith("watch") }
        ?.replaceFirstChar(Char::uppercaseChar)

    /**
     * Downloads a track's audio to a file, reporting progress as it goes.
     *
     * The stream is taken in whatever container it already comes in. Converting it would mean depending on
     * ffmpeg being present and re-encoding audio for no gain, and mpv plays every container yt-dlp hands
     * back. [outputTemplate] carries yt-dlp's own `%(ext)s`, since which container it is only becomes known
     * once the stream is chosen.
     *
     * Uses [playbackArguments], not the browsing session: this is a player-level request and YouTube
     * refuses those from a signed-in non-browser exactly as it refuses a stream. Sending cookies here would
     * make every YouTube download fail the way playback once did.
     */
    override suspend fun downloadAudio(
        sourceUrl: String,
        outputTemplate: String,
        onProgress: (Float) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) {
            "Only HTTP media sources are accepted"
        }
        val provider = providerOf(sourceUrl)
        stream(
            DOWNLOAD_TIMEOUT_SECONDS,
            arrayOf(
                "--format", "bestaudio/best",
                "--no-playlist",
                "--no-warnings",
                // One progress line per update instead of a carriage-returned bar, so it can be read here.
                "--newline",
                "--progress",
                // Written straight to its final name, and never resumed onto: yt-dlp continues a partial
                // file by default, which after an interrupted attempt appends to a truncated one and
                // yields audio that will not play.
                "--no-part",
                "--no-continue",
                "--output", outputTemplate,
                *provider?.let(::playbackArguments).orEmpty().toTypedArray(),
                "--",
                sourceUrl,
            ),
            onLine = { line -> progressOf(line)?.let(onProgress) },
        )
    }

    /** Whether this machine can convert audio, which is the only thing standing between us and MP3. */
    override fun canConvertAudio(): Boolean = ffmpeg() != null

    /**
     * Saves a track as a file for the listener to keep, rather than for Spiceity to play.
     *
     * With ffmpeg present the audio is converted to MP3 and given its title, artist and cover art, since
     * MP3 with tags is the thing that behaves properly on every phone and in every car. Without it, the
     * stream is taken exactly as it comes: no conversion is possible, but none is desirable either —
     * re-encoding lossy audio into another lossy format only ever loses more, and what the services serve
     * is m4a, which phones play anyway.
     */
    override suspend fun exportAudio(
        sourceUrl: String,
        outputTemplate: String,
        format: ExportFormat,
        onProgress: (Float) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) {
            "Only HTTP media sources are accepted"
        }
        if (format == ExportFormat.MP3 && ffmpeg() == null) {
            throw BackendException(
                "Saving as MP3 needs ffmpeg, which is not installed. Put ffmpeg.exe in Spiceity's bin " +
                    "folder, or set SPICEITY_FFMPEG_PATH, and try again.",
            )
        }
        val provider = providerOf(sourceUrl)
        val conversion = if (format == ExportFormat.MP3) {
            listOf(
                "--extract-audio",
                "--audio-format", "mp3",
                // 0 is yt-dlp's best variable bitrate, which for music is worth the extra megabyte.
                "--audio-quality", "0",
                // The tags and the cover, so a phone shows the track rather than a file name.
                "--embed-metadata",
                "--embed-thumbnail",
                "--ffmpeg-location", ffmpeg()!!.parent.toString(),
            )
        } else {
            // m4a for preference, since it needs no conversion and every phone plays it.
            listOf("--format", "bestaudio[ext=m4a]/bestaudio")
        }
        stream(
            EXPORT_TIMEOUT_SECONDS,
            arrayOf(
                *(if (format == ExportFormat.MP3) arrayOf("--format", "bestaudio/best") else emptyArray()),
                "--no-playlist",
                "--no-warnings",
                "--newline",
                "--progress",
                "--no-part",
                "--no-continue",
                *conversion.toTypedArray(),
                "--output", outputTemplate,
                *provider?.let(::playbackArguments).orEmpty().toTypedArray(),
                "--",
                sourceUrl,
            ),
            onLine = { line -> progressOf(line)?.let(onProgress) },
        )
    }

    /** Which provider a media address belongs to, for choosing what to send with a request about it. */
    private fun providerOf(sourceUrl: String): ProviderType? = when {
        "music.youtube.com" in sourceUrl -> ProviderType.YOUTUBE_MUSIC
        "youtube.com" in sourceUrl || "youtu.be" in sourceUrl -> ProviderType.YOUTUBE_VIDEO
        "soundcloud.com" in sourceUrl -> ProviderType.SOUNDCLOUD
        else -> null
    }

    /**
     * Runs yt-dlp and hands over each line as it arrives.
     *
     * Separate from [run] because that one waits for the process and then reads everything at once, which
     * is right for a query answered in a second and useless for something that takes minutes and whose
     * only interesting output is how far along it is.
     */
    private fun stream(timeoutSeconds: Long, arguments: Array<out String>, onLine: (String) -> Unit) {
        val binary = executable() ?: throw BackendException(
            "yt-dlp is missing. Install it in Spiceity Settings or set SPICEITY_YTDLP_PATH.",
        )
        val process = try {
            ProcessBuilder(listOf(binary.toString()) + arguments).redirectErrorStream(true).start()
        } catch (error: IOException) {
            throw BackendException("Could not start yt-dlp: ${error.message}", error)
        }
        val tail = ArrayDeque<String>()
        try {
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    // Keeping only the last few lines: the useful part of a failure is at the end, and a
                    // download of a long track prints thousands of progress lines nobody will read.
                    tail.addLast(line)
                    if (tail.size > 12) tail.removeFirst()
                    onLine(line)
                }
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw BackendException("The download did not finish within ${timeoutSeconds / 60} minutes")
            }
            if (process.exitValue() != 0) {
                val reason = tail.lastOrNull { it.isNotBlank() }.orEmpty().take(300)
                throw BackendException("yt-dlp could not download this track. $reason")
            }
        } finally {
            // Cancelling the surrounding coroutine must not leave yt-dlp running and still writing.
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun run(vararg arguments: String): String = run(90, arguments)


    private fun run(timeoutSeconds: Long, arguments: Array<out String>): String {
        val binary = executable() ?: throw BackendException(
            "yt-dlp is missing. Install it in Spiceity Settings or set SPICEITY_YTDLP_PATH.",
        )
        val command = listOf(binary.toString()) + arguments
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (error: IOException) {
            throw BackendException("Could not start yt-dlp: ${error.message}", error)
        }
        val outputFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw BackendException("yt-dlp timed out after $timeoutSeconds seconds")
        }
        val output = outputFuture.get(5, TimeUnit.SECONDS)
        if (process.exitValue() != 0) {
            val safeMessage = output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(300)
            throw BackendException("yt-dlp could not resolve this track. $safeMessage")
        }
        return output
    }
}

/**
 * How much of a download has arrived, from one of yt-dlp's own progress lines, or null for anything else.
 *
 * The lines look like `[download]   7.6% of    3.27MiB at   27.80MiB/s ETA 00:00`, and there are thousands
 * of them for one track. Only the percentage is wanted; the rest changes every tenth of a second and none
 * of it survives to the screen.
 */
internal fun progressOf(line: String): Float? {
    if (!line.startsWith("[download]")) return null
    val percent = PROGRESS.find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
    return (percent / 100f).coerceIn(0f, 1f)
}

private val PROGRESS = Regex("""\s(\d{1,3}(?:\.\d+)?)%""")

/** A long track on a slow line still finishes well inside this; a stuck one does not sit forever. */
private const val DOWNLOAD_TIMEOUT_SECONDS = 20L * 60L

/** Converting takes longer than fetching, and a whole album saved one after another longer still. */
private const val EXPORT_TIMEOUT_SECONDS = 30L * 60L
