package app.spice.playback

import app.spice.domain.Artist
import app.spice.domain.ProviderType
import app.spice.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

class YtDlpService(
    private val executable: () -> Path? = BackendLocator::ytDlp,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(provider: ProviderType, query: String, limit: Int = 8): List<Track> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Search query cannot be blank" }
        val prefix = when (provider) {
            ProviderType.YOUTUBE_MUSIC -> "ytsearch"
            ProviderType.SOUNDCLOUD -> "scsearch"
            ProviderType.LOCAL -> throw BackendException("Local search is not supported by yt-dlp")
        }
        val output = run(
            "--flat-playlist",
            "--dump-single-json",
            "--no-warnings",
            "--no-playlist",
            "$prefix$limit:$query",
        )
        val root = json.parseToJsonElement(output).jsonObject
        root["entries"]?.jsonArray.orEmpty().mapNotNull { mapTrack(provider, it.jsonObject) }
    }

    suspend fun resolveAudio(sourceUrl: String): String = withContext(Dispatchers.IO) {
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) {
            "Only HTTP media sources are accepted"
        }
        run(
            "--format", "bestaudio/best",
            "--get-url",
            "--no-playlist",
            "--no-warnings",
            "--",
            sourceUrl,
        ).lineSequence().firstOrNull { it.startsWith("http") }
            ?: throw BackendException("yt-dlp returned no playable audio URL")
    }

    suspend fun version(): String = withContext(Dispatchers.IO) { run("--version").trim() }

    private fun mapTrack(provider: ProviderType, item: JsonObject): Track? {
        val id = item.string("id") ?: return null
        val title = item.string("title") ?: return null
        val uploader = item.string("artist") ?: item.string("uploader") ?: item.string("channel") ?: "Unknown artist"
        val url = item.string("webpage_url") ?: item.string("original_url") ?: item.string("url")?.let { raw ->
            if (raw.startsWith("http")) raw else when (provider) {
                ProviderType.YOUTUBE_MUSIC -> "https://www.youtube.com/watch?v=$raw"
                ProviderType.SOUNDCLOUD -> null
                ProviderType.LOCAL -> null
            }
        } ?: return null
        val artist = Artist("${provider.name}:$uploader", uploader, provider)
        val durationMs = item["duration"]?.jsonPrimitive?.doubleOrNull?.times(1_000)?.toLong()
        val thumbnail = item.string("thumbnail")
            ?: item["thumbnails"]?.jsonArray?.lastOrNull()?.jsonObject?.string("url")
        return Track(provider, id, title, listOf(artist), durationMs = durationMs, artworkUrl = thumbnail, sourceUrl = url)
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun run(vararg arguments: String): String {
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
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw BackendException("yt-dlp timed out after 90 seconds")
        }
        val output = outputFuture.get(5, TimeUnit.SECONDS)
        if (process.exitValue() != 0) {
            val safeMessage = output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(300)
            throw BackendException("yt-dlp could not resolve this track. $safeMessage")
        }
        return output
    }
}
