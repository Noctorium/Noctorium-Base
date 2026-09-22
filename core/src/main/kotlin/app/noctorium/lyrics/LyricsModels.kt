package app.noctorium.lyrics

enum class LyricsProviderId(
    val displayName: String,
    val keyEnvironment: String? = null,
) {
    LRCLIB("LRCLIB"),
    BETTER_LYRICS("Better Lyrics"),
    KARALYR("Karalyr"),
    SYNCLRC("SyncLRC"),
    LYRICS_OVH("lyrics.ovh"),
    MUSIXMATCH("Musixmatch", "NOCTORIUM_MUSIXMATCH_API_KEY"),
    HAPPI("Happi", "NOCTORIUM_HAPPI_API_KEY"),
    GENIUS("Genius", "NOCTORIUM_GENIUS_ACCESS_TOKEN"),
}

enum class LyricsProviderStatus {
    SEARCHING,
    FOUND,
    LINK_ONLY,
    NOT_FOUND,
    NEEDS_KEY,
    ERROR,
}

data class LyricLine(
    val text: String,
    val startTimeMs: Long? = null,
)

data class LyricsResult(
    val provider: LyricsProviderId,
    val lines: List<LyricLine>,
    val synced: Boolean,
    val sourceUrl: String? = null,
    val attribution: String? = null,
    val message: String? = null,
)

data class LyricsProviderOutcome(
    val provider: LyricsProviderId,
    val status: LyricsProviderStatus,
    val result: LyricsResult? = null,
    val detail: String? = null,
)

data class LyricsUiState(
    val trackKey: String? = null,
    val loading: Boolean = false,
    val outcomes: List<LyricsProviderOutcome> = emptyList(),
    val selectedProvider: LyricsProviderId? = null,
    val errorMessage: String? = null,
)

/** The synced lyrics being shown, if what is shown is synced: the chosen provider's, else the first found. */
fun LyricsUiState.syncedResult(): LyricsResult? {
    val chosen = selectedProvider?.let { id -> outcomes.firstOrNull { it.provider == id }?.result }
    val result = chosen ?: outcomes.firstOrNull { it.status == LyricsProviderStatus.FOUND }?.result
    return result?.takeIf { it.synced && it.lines.isNotEmpty() }
}

/**
 * The line being sung at [positionMs], or null when there is none to show.
 *
 * Null before the first line starts and whenever the lyrics are not timed, so a caller can fall back to
 * whatever it showed before -- the artist, usually -- instead of a blank. The idea of a lyric line that
 * follows you around the application rather than living only on one screen is SpMp's.
 */
fun LyricsUiState.currentLine(positionMs: Long): String? {
    val result = syncedResult() ?: return null
    val index = result.lines.indexOfLast { (it.startTimeMs ?: Long.MAX_VALUE) <= positionMs }
    return result.lines.getOrNull(index)?.text?.trim()?.takeIf(String::isNotEmpty)
}
