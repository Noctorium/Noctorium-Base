package app.spice.playback

import app.spice.domain.Album
import app.spice.domain.Artist
import app.spice.domain.Playlist
import app.spice.domain.ProviderType
import app.spice.domain.Track
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

class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

class YtDlpService(
    private val executable: () -> Path? = BackendLocator::ytDlp,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cookieArguments = ConcurrentHashMap<ProviderType, List<String>>()

    @Volatile private var soundCloudUser: String = ""

    /** Cookie arguments for one provider, already translated from the stored session by the caller. */
    fun setCookieArguments(provider: ProviderType, arguments: List<String>) {
        if (arguments.isEmpty()) cookieArguments.remove(provider) else cookieArguments[provider] = arguments
    }

    /**
     * SoundCloud addresses a listener's own playlists by profile name, and cookies do not reveal it, so the
     * name from Settings is kept here alongside the session it belongs to.
     */
    fun setSoundCloudUsername(username: String) {
        soundCloudUser = username.trim().trim('/').substringAfterLast('/')
    }

    internal fun soundCloudUsername(): String = soundCloudUser

    suspend fun search(provider: ProviderType, query: String, limit: Int = 8): List<Track> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Search query cannot be blank" }
        val target = when (provider) {
            ProviderType.YOUTUBE_MUSIC -> {
                val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
                "https://music.youtube.com/search?q=$encodedQuery&sp=EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
            }
            ProviderType.YOUTUBE_VIDEO -> "ytsearch$limit:$query"
            ProviderType.SOUNDCLOUD -> "scsearch$limit:$query"
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
    suspend fun listPlaylists(provider: ProviderType, url: String, limit: Int = 50): List<Playlist> =
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
    suspend fun listTracks(provider: ProviderType, url: String, limit: Int = 200): List<Track> =
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
    suspend fun resolveTracks(provider: ProviderType, url: String, from: Int, to: Int): List<Track> =
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

    suspend fun resolveAudio(sourceUrl: String): String = withContext(Dispatchers.IO) {
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
            *provider?.let(::accountArguments).orEmpty().toTypedArray(),
            "--",
            sourceUrl,
        ).lineSequence().firstOrNull { it.startsWith("http") }
            ?: throw BackendException("yt-dlp returned no playable audio URL")
    }

    suspend fun enrichMetadata(track: Track): Track = withContext(Dispatchers.IO) {
        if (!track.hasPlaceholderArtist()) return@withContext track
        val output = run(
            "--dump-single-json",
            "--skip-download",
            "--no-warnings",
            "--no-playlist",
            *accountArguments(track.provider).toTypedArray(),
            "--",
            track.sourceUrl,
        )
        mapEnrichedTrack(track, json.parseToJsonElement(output).jsonObject)
    }

    suspend fun version(): String = withContext(Dispatchers.IO) { run("--version").trim() }

    /**
     * Writes the provider's browser cookies to [destination] so the caller can lift the one session token it
     * needs. yt-dlp dumps the jar it loaded when `--cookies` accompanies `--cookies-from-browser`; the caller
     * is responsible for deleting the file straight after reading it.
     */
    suspend fun exportCookies(provider: ProviderType, destination: Path): Boolean = withContext(Dispatchers.IO) {
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
                "scsearch1:spice session",
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
                ProviderType.LOCAL -> null
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

    private fun run(vararg arguments: String): String = run(90, arguments)

    private fun run(timeoutSeconds: Long, arguments: Array<out String>): String {
        val binary = executable() ?: throw BackendException(
            "yt-dlp is missing. Install it in Spice Settings or set SPICE_YTDLP_PATH.",
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
