package app.spiceity.lyrics

enum class LyricsProviderId(
    val displayName: String,
    val keyEnvironment: String? = null,
) {
    LRCLIB("LRCLIB"),
    BETTER_LYRICS("Better Lyrics"),
    KARALYR("Karalyr"),
    SYNCLRC("SyncLRC"),
    LYRICS_OVH("lyrics.ovh"),
    MUSIXMATCH("Musixmatch", "SPICEITY_MUSIXMATCH_API_KEY"),
    HAPPI("Happi", "SPICEITY_HAPPI_API_KEY"),
    GENIUS("Genius", "SPICEITY_GENIUS_ACCESS_TOKEN"),
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
