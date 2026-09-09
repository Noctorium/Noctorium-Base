package app.spiceity.lyrics

import app.spiceity.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Long?,
) {
    companion object {
        fun from(track: Track): LyricsQuery {
            val artist = track.artists.firstOrNull()?.name?.takeUnless { it.startsWith("YouTube") } ?: track.artistLine
            return LyricsQuery(
                title = cleanTitle(track.title, artist),
                artist = artist,
                album = track.album?.title,
                durationSeconds = track.durationMs?.div(1_000),
            )
        }

        private fun cleanTitle(title: String, artist: String): String {
            val withoutFormatSuffix = title
                .replace(Regex("""\s*[|\-–—]\s*(official\s+)?(music\s+)?(video|audio|lyrics?|visuali[sz]er).*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*[\[(](official\s+)?(music\s+)?(video|audio|lyrics?|visuali[sz]er)[\])].*$""", RegexOption.IGNORE_CASE), "")
                .trim()
            return withoutFormatSuffix
                .replace(Regex("""^${Regex.escape(artist)}\s*[-–—:]\s*""", RegexOption.IGNORE_CASE), "")
                .trim()
        }
    }
}

internal data class LyricsHttpResponse(val status: Int, val body: String)

internal interface LyricsHttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): LyricsHttpResponse
}

internal class DefaultLyricsHttpClient : LyricsHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): LyricsHttpResponse = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 7_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json, application/xml, text/xml, */*")
        connection.setRequestProperty("User-Agent", "Spiceity/0.1 (desktop music player; lyrics lookup)")
        headers.forEach(connection::setRequestProperty)
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText().take(2_000_000) }.orEmpty()
        connection.disconnect()
        LyricsHttpResponse(status, body)
    }
}

internal interface LyricsProvider {
    val id: LyricsProviderId
    suspend fun fetch(query: LyricsQuery): LyricsProviderOutcome
}

internal abstract class JsonLyricsProvider(
    final override val id: LyricsProviderId,
    protected val http: LyricsHttpClient,
) : LyricsProvider {
    protected val json = Json { ignoreUnknownKeys = true }

    protected fun result(
        lines: List<LyricLine>,
        synced: Boolean,
        sourceUrl: String? = null,
        attribution: String? = null,
        message: String? = null,
        allowShortResult: Boolean = false,
    ): LyricsProviderOutcome {
        val substantial = allowShortResult || lines.isEmpty() || (lines.size >= 3 && lines.sumOf { it.text.length } >= 40)
        return if ((lines.isEmpty() && sourceUrl == null) || !substantial) {
            LyricsProviderOutcome(
                id,
                LyricsProviderStatus.NOT_FOUND,
                detail = if (!substantial) "Result was too short to trust" else "No matching lyrics",
            )
        } else {
        LyricsProviderOutcome(
            id,
            if (lines.isEmpty()) LyricsProviderStatus.LINK_ONLY else LyricsProviderStatus.FOUND,
            LyricsResult(id, lines, synced, sourceUrl, attribution, message),
        )
        }
    }

    protected fun parse(body: String): JsonElement = json.parseToJsonElement(body)
}

internal class LrclibProvider(http: LyricsHttpClient) : JsonLyricsProvider(LyricsProviderId.LRCLIB, http) {
    override suspend fun fetch(query: LyricsQuery): LyricsProviderOutcome {
        val exactResponse = http.get(buildQuery("https://lrclib.net/api/get", exactParameters(query)))
        val exactOutcome = when (exactResponse.status) {
            404 -> LyricsProviderOutcome(id, LyricsProviderStatus.NOT_FOUND, detail = "No exact match")
            in 200..299 -> outcomeFrom(parse(exactResponse.body).jsonObject)
            else -> error("LRCLIB returned ${exactResponse.status}")
        }
        if (exactOutcome.status == LyricsProviderStatus.FOUND) return exactOutcome

        // LRCLIB's exact endpoint can occasionally select a tiny placeholder even when
        // complete synchronized versions exist. Search and rank its catalog before
        // declaring the provider unavailable.
        val searchResponse = http.get(
            buildQuery(
                "https://lrclib.net/api/search",
                mapOf("track_name" to query.title, "artist_name" to query.artist),
            ),
        )
        if (searchResponse.status == 404) return exactOutcome
        if (searchResponse.status !in 200..299) error("LRCLIB search returned ${searchResponse.status}")

        val candidates = parse(searchResponse.body).jsonArray.mapNotNull { element ->
            val root = element.jsonObject
            val outcome = outcomeFrom(root)
            if (outcome.status != LyricsProviderStatus.FOUND) null
            else LrclibCandidate(outcome, scoreCandidate(root, outcome, query))
        }
        return candidates.maxByOrNull(LrclibCandidate::score)?.outcome?.copy(
            detail = "Matched from LRCLIB search",
        ) ?: exactOutcome
    }

    private fun outcomeFrom(root: JsonObject): LyricsProviderOutcome {
        if (root.boolean("instrumental") == true) {
            return result(
                listOf(LyricLine("♪ Instrumental track ♪")),
                false,
                "https://lrclib.net",
                "LRCLIB",
                allowShortResult = true,
            )
        }
        val (lines, synced) = LyricsParser.fromLrc(root.string("syncedLyrics"), root.string("plainLyrics"))
        return result(
            lines,
            synced,
            root.long("id")?.let { "https://lrclib.net/api/get/$it" } ?: "https://lrclib.net",
            "Lyrics from LRCLIB",
        )
    }

    private fun scoreCandidate(
        root: JsonObject,
        outcome: LyricsProviderOutcome,
        query: LyricsQuery,
    ): Int {
        val candidateTitle = normalizeMatchText(root.string("trackName"))
        val candidateArtist = normalizeMatchText(root.string("artistName"))
        val queryTitle = normalizeMatchText(query.title)
        val queryArtist = normalizeMatchText(query.artist)
        val titleScore = when {
            candidateTitle == queryTitle -> 120
            candidateTitle.contains(queryTitle) || queryTitle.contains(candidateTitle) -> 70
            else -> 0
        }
        val artistScore = when {
            candidateArtist == queryArtist -> 90
            candidateArtist.contains(queryArtist) || queryArtist.contains(candidateArtist) -> 55
            else -> 0
        }
        val durationScore = if (query.durationSeconds == null) 0 else {
            val candidateDuration = root.double("duration")?.toLong()
            if (candidateDuration == null) 0 else (35 - kotlin.math.abs(candidateDuration - query.durationSeconds).coerceAtMost(35)).toInt()
        }
        val lineCount = outcome.result?.lines?.size ?: 0
        val qualityScore = lineCount.coerceAtMost(35) + if (outcome.result?.synced == true) 30 else 10
        return titleScore + artistScore + durationScore + qualityScore
    }
}

private data class LrclibCandidate(val outcome: LyricsProviderOutcome, val score: Int)

internal class KaralyrProvider(http: LyricsHttpClient) : JsonLyricsProvider(LyricsProviderId.KARALYR, http) {
    override suspend fun fetch(query: LyricsQuery): LyricsProviderOutcome {
        val response = http.get(buildQuery("https://www.karalyr.com/api/get", exactParameters(query)))
        if (response.status == 404) return LyricsProviderOutcome(id, LyricsProviderStatus.NOT_FOUND, detail = "No karaoke match")
        if (response.status !in 200..299) error("Karalyr returned ${response.status}")
        val root = parse(response.body).jsonObject
        val (lines, synced) = LyricsParser.fromLrc(root.string("syncedLyrics"), root.string("plainLyrics"))
        return result(lines, synced, "https://karalyr.com", "Word-synced lyrics from Karalyr")
    }
}

internal class SyncLrcProvider(http: LyricsHttpClient) : JsonLyricsProvider(LyricsProviderId.SYNCLRC, http) {
    override suspend fun fetch(query: LyricsQuery): LyricsProviderOutcome {
        val parameters = linkedMapOf("track" to query.title, "artist" to query.artist).apply {
            query.album?.let { put("album", it) }
            query.durationSeconds?.let { put("duration", it.toString()) }
        }
        val response = http.get(buildQuery("https://synclrc.dev/lyrics", parameters))
        if (response.status == 404) return LyricsProviderOutcome(id, LyricsProviderStatus.NOT_FOUND, detail = "No match")
        if (response.status !in 200..299) error("SyncLRC returned ${response.status}")
        val root = parse(response.body).jsonObject
        val syncedText = root.string("karaoke") ?: root.string("synced")
        val (lines, synced) = LyricsParser.fromLrc(syncedText, root.string("plain"))
        return result(lines, synced, "https://synclrc.dev", "Lyrics from SyncLRC")
    }
}

internal class LyricsOvhProvider(http: LyricsHttpClient) : JsonLyricsProvider(LyricsProviderId.LYRICS_OVH, http) {
    override suspend fun fetch(query: LyricsQuery): LyricsProviderOutcome {
        val url = "https://api.lyrics.ovh/v1/${pathEncode(query.artist)}/${pathEncode(query.title)}"
        val response = http.get(url)
        if (response.status == 404) return LyricsProviderOutcome(id, LyricsProviderStatus.NOT_FOUND, detail = "No match")
        if (response.status !in 200..299) error("lyrics.ovh returned ${response.status}")
        val lyrics = parse(response.body).jsonObject.string("lyrics")
        val (lines, synced) = LyricsParser.fromLrc(null, lyrics)
        return result(lines, synced, "https://lyrics.ovh", "Lyrics from lyrics.ovh")
    }
}

internal class BetterLyricsProvider(http: LyricsHttpClient) : JsonLyricsProvider(LyricsProviderId.BETTER_LYRICS, http) {
    override suspend fun fetch(query: LyricsQuery): LyricsProviderOutcome {
        val parameters = linkedMapOf("s" to query.title, "a" to query.artist).apply {
            query.album?.let { put("al", it) }
            query.durationSeconds?.let { put("d", it.toString()) }
        }
        val key = System.getenv("SPICEITY_BETTER_LYRICS_API_KEY")?.takeIf(String::isNotBlank)
        val response = http.get(
            buildQuery("https://lyrics-api.boidu.dev/getLyrics", parameters),
            key?.let { mapOf("X-API-Key" to it) }.orEmpty(),
        )
        if (response.status in listOf(401, 404, 429)) {
            return LyricsProviderOutcome(id, LyricsProviderStatus.NOT_FOUND, detail = "No cached match")
        }
        if (response.status !in 200..299) error("Better Lyrics returned ${response.status}")
        val ttml = parse(response.body).jsonObject.string("ttml").orEmpty()
        val lines = LyricsParser.fromTtml(ttml)
        return result(lines, lines.any { it.startTimeMs != null }, "https://better-lyrics.boidu.dev", "Syllable-synced lyrics from Better Lyrics")
    }
}

internal class MusixmatchProvider(http: LyricsHttpClient) : JsonLyricsProvider(LyricsProviderId.MUSIXMATCH, http) {
    override suspend fun fetch(query: LyricsQuery): LyricsProviderOutcome {
        val key = System.getenv(id.keyEnvironment)?.takeIf(String::isNotBlank)
            ?: return LyricsProviderOutcome(id, LyricsProviderStatus.NEEDS_KEY, detail = id.keyEnvironment)
        val parameters = linkedMapOf("q_track" to query.title, "q_artist" to query.artist, "apikey" to key)
        val response = http.get(buildQuery("https://api.musixmatch.com/ws/1.1/matcher.lyrics.get", parameters))
        if (response.status !in 200..299) error("Musixmatch returned ${response.status}")
        val root = parse(response.body).jsonObject
        val lyrics = root["message"]?.jsonObject?.get("body")?.jsonObject?.get("lyrics")?.jsonObject
        val body = lyrics?.string("lyrics_body")
        val copyright = lyrics?.string("lyrics_copyright")
        val (lines, synced) = LyricsParser.fromLrc(null, body)
        return result(lines, synced, musixmatchSearchUrl(query), copyright ?: "Lyrics from Musixmatch")
    }
}

internal class HappiProvider(http: LyricsHttpClient) : JsonLyricsProvider(LyricsProviderId.HAPPI, http) {
    override suspend fun fetch(query: LyricsQuery): LyricsProviderOutcome {
        val key = System.getenv(id.keyEnvironment)?.takeIf(String::isNotBlank)
            ?: return LyricsProviderOutcome(id, LyricsProviderStatus.NEEDS_KEY, detail = id.keyEnvironment)
        val response = http.get(
            buildQuery("https://api.happi.dev/v1/lyrics", mapOf("artist" to query.artist, "track" to query.title)),
            mapOf("x-happi-token" to key),
        )
        if (response.status == 404) return LyricsProviderOutcome(id, LyricsProviderStatus.NOT_FOUND, detail = "No match")
        if (response.status !in 200..299) error("Happi returned ${response.status}")
        val lyrics = parse(response.body).findString(setOf("lyrics", "plainLyrics", "text"))
        val (lines, synced) = LyricsParser.fromLrc(null, lyrics)
        return result(lines, synced, "https://happi.dev", "Lyrics from Happi")
    }
}

internal class GeniusProvider(http: LyricsHttpClient) : JsonLyricsProvider(LyricsProviderId.GENIUS, http) {
    override suspend fun fetch(query: LyricsQuery): LyricsProviderOutcome {
        val token = System.getenv(id.keyEnvironment)?.takeIf(String::isNotBlank)
        val fallbackUrl = "https://genius.com/search?q=${encode("${query.artist} ${query.title}")}"
        if (token == null) {
            return result(
                emptyList(),
                false,
                fallbackUrl,
                "Genius",
                "Genius does not provide full lyrics through its public API. Open the Genius result instead.",
            )
        }
        val response = http.get(
            buildQuery("https://api.genius.com/search", mapOf("q" to "${query.artist} ${query.title}")),
            mapOf("Authorization" to "Bearer $token"),
        )
        if (response.status !in 200..299) error("Genius returned ${response.status}")
        val hit = parse(response.body).jsonObject["response"]?.jsonObject?.get("hits")?.jsonArray?.firstOrNull()?.jsonObject
        val url = hit?.get("result")?.jsonObject?.string("url") ?: fallbackUrl
        return result(
            emptyList(),
            false,
            url,
            "Genius",
            "Open the matched Genius page for full lyrics and annotations.",
        )
    }
}

private fun exactParameters(query: LyricsQuery) = linkedMapOf(
    "track_name" to query.title,
    "artist_name" to query.artist,
).apply {
    query.album?.let { put("album_name", it) }
    query.durationSeconds?.let { put("duration", it.toString()) }
}

private fun buildQuery(base: String, parameters: Map<String, String>): String =
    base + parameters.entries.joinToString(prefix = "?", separator = "&") { (key, value) -> "${encode(key)}=${encode(value)}" }

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
private fun pathEncode(value: String): String = encode(value).replace("+", "%20")
private fun musixmatchSearchUrl(query: LyricsQuery) = "https://www.musixmatch.com/search/${pathEncode("${query.artist} ${query.title}")}"

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
private fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
private fun normalizeMatchText(value: String?): String = value.orEmpty()
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

private fun JsonElement.findString(names: Set<String>): String? {
    return when (this) {
        is JsonObject -> {
            entries.firstNotNullOfOrNull { (key, value) ->
                if (key in names) value.jsonPrimitiveOrNull()?.contentOrNull else null
            } ?: values.firstNotNullOfOrNull { it.findString(names) }
        }
        else -> null
    }
}

private fun JsonElement.jsonPrimitiveOrNull() = runCatching { jsonPrimitive }.getOrNull()
