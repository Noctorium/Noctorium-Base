package app.noctorium.playback

import app.noctorium.net.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A stretch of a video that is not the music: where it starts and ends, and what SponsorBlock calls it. */
data class SkippableSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String,
) {
    fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}

/**
 * SponsorBlock's crowd-sourced map of which parts of a YouTube video are not the music.
 *
 * A music video carries an intro, an outro, a sponsor read, a "like and subscribe", sometimes a minute
 * of dialogue before the song starts. On YouTube Music none of that exists; on YouTube it is the reason
 * a four-minute song is a six-minute video. SimpMusic skips these through SponsorBlock, and so does this,
 * for tracks that come from YouTube rather than YouTube Music -- the only place they occur.
 *
 * The lookup is by the first four characters of the video id's SHA-256, which is how SponsorBlock asks to
 * be asked: the server answers with every video sharing that prefix, and which one was wanted stays on
 * this side. What is fetched is remembered for the run, including "nothing here", so a track on repeat
 * asks once.
 */
class SponsorBlockClient(
    private val http: Http = Http(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val categories: List<String> = DEFAULT_CATEGORIES,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val known = ConcurrentHashMap<String, List<SkippableSegment>>()

    suspend fun segmentsFor(videoId: String): List<SkippableSegment> {
        known[videoId]?.let { return it }
        val fetched = fetch(videoId)
        known[videoId] = fetched
        return fetched
    }

    private suspend fun fetch(videoId: String): List<SkippableSegment> {
        val prefix = sha256Hex(videoId).take(HASH_PREFIX_LENGTH)
        val query = categories.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }
        val reply = http.send("$baseUrl/api/skipSegments/$prefix?categories=${percentEncode(query)}")
        // 404 is SponsorBlock's word for "nobody has marked anything here", and a network failure is
        // Http's status 0. Neither is worth an error: the track plays as it is.
        if (!reply.ok) return emptyList()
        return parse(reply.body, videoId)
    }

    internal fun parse(body: String, videoId: String): List<SkippableSegment> = runCatching {
        json.parseToJsonElement(body).jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["videoID"]?.jsonPrimitive?.contentOrNull == videoId }
            ?.get("segments")?.jsonArray.orEmpty()
            .mapNotNull { element ->
                val segment = element.jsonObject
                val range = segment["segment"]?.jsonArray ?: return@mapNotNull null
                val start = range.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val end = range.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                // A segment shorter than this is a half-second of somebody's opinion, not a stretch worth
                // a seek the listener would hear as a stutter.
                if ((end - start) * 1_000 < MIN_SEGMENT_MS) return@mapNotNull null
                SkippableSegment(
                    startMs = (start * 1_000).toLong(),
                    endMs = (end * 1_000).toLong(),
                    category = segment["category"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                )
            }
            .sortedBy { it.startMs }
    }.getOrDefault(emptyList())

    companion object {
        const val DEFAULT_BASE_URL = "https://sponsor.ajay.app"

        /**
         * Everything that is not the song. Not "filler", which marks tangents inside the content itself
         * and is the one category SponsorBlock's own defaults leave off for being a matter of taste.
         */
        val DEFAULT_CATEGORIES = listOf(
            "sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic",
        )

        private const val HASH_PREFIX_LENGTH = 4
        private const val MIN_SEGMENT_MS = 1_000L

        internal fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun percentEncode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

/**
 * Decides, from where the player is, whether it should be somewhere else.
 *
 * A segment is skipped once per play. Landing inside one again -- because the listener dragged the seek
 * bar back into it -- is taken as wanting to hear it, so it is left alone; a player that keeps throwing
 * you forward out of a place you deliberately went is a player you switch off.
 */
class SegmentSkipper(segments: List<SkippableSegment>) {
    private val pending = segments.toMutableList()

    /** The position to jump to, or null to leave the player where it is. */
    fun skipFrom(positionMs: Long): SkippableSegment? {
        val hit = pending.firstOrNull { it.contains(positionMs) } ?: return null
        pending.remove(hit)
        return hit
    }
}
