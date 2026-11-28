package app.noctorium.account

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The spans the statistics are read over, by the codes the service takes. */
enum class StatsRange(val code: String, val label: String) {
    WEEK("7d", "7 days"),
    MONTH("30d", "30 days"),
    QUARTER("90d", "90 days"),
    YEAR("365d", "Year"),
    ALL("all", "All time"),
}

/**
 * Everything the stats service works out from the account's listens on every device: the same figures and charts as
 * Noctorium Stats and stats.noctorium.xyz. Days and hours are on the clock the caller asked with.
 */
@Serializable
data class AccountStats(
    val streams: Int = 0,
    val uniqueTracks: Int = 0,
    val artists: Int = 0,
    val msPlayed: Long = 0,
    val byProvider: List<Share> = emptyList(),
    val topTracks: List<TopTrack> = emptyList(),
    val topArtists: List<TopArtist> = emptyList(),
    val timeline: Timeline = Timeline(),
    val hourly: List<Slot> = emptyList(),
    val weekdays: List<Day> = emptyList(),
    val recent: List<Play> = emptyList(),
    /** Days in a row with something played, over the whole history whatever the span. */
    val streak: Streak = Streak(),
) {
    @Serializable data class Share(val provider: String, val streams: Int = 0, val hours: Double = 0.0)
    @Serializable data class TopTrack(val title: String, val artist: String, val provider: String = "", val streams: Int = 0, val minutes: Int = 0)
    @Serializable data class TopArtist(val artist: String, val streams: Int = 0, val minutes: Int = 0, val tracks: Int = 0)
    /** [bucket] is "day", or "month" over the longest spans; each point starts on its day, as YYYY-MM-DD. */
    @Serializable data class Timeline(val bucket: String = "day", val points: List<Point> = emptyList())
    @Serializable data class Point(val start: String, val streams: Int = 0, val minutes: Int = 0)
    /** [hour] is 0 to 23. */
    @Serializable data class Slot(val hour: Int, val streams: Int = 0, val minutes: Int = 0)
    /** [day] is 1 for Monday to 7 for Sunday. */
    @Serializable data class Day(val day: Int, val streams: Int = 0, val minutes: Int = 0)
    @Serializable data class Play(val title: String, val artist: String, val provider: String = "", val playedAt: String, val msPlayed: Long = 0)
    @Serializable data class Streak(val current: Int = 0, val longest: Int = 0)

    /** The split by service with the two YouTubes as one, as the listener thinks of them, largest first. */
    val services: List<Pair<String, Int>>
        get() = byProvider.groupBy { providerName(it.provider) }
            .map { (name, shares) -> name to shares.sumOf { it.streams } }
            .sortedByDescending { it.second }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun parse(body: String): AccountStats? = runCatching { json.decodeFromString(serializer(), body) }.getOrNull()

        /** A service's name from the code the service records it by. */
        fun providerName(code: String): String = when (code) {
            "YOUTUBE_MUSIC", "YOUTUBE_VIDEO" -> "YouTube Music"
            "SOUNDCLOUD" -> "SoundCloud"
            "BANDCAMP" -> "Bandcamp"
            "SPOTIFY" -> "Spotify"
            "VK" -> "VK"
            "LOCAL" -> "Your files"
            else -> code.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
        }
    }
}
