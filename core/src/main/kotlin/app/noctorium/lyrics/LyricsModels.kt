package app.noctorium.lyrics

import kotlinx.serialization.Serializable

/** Saved by name as the listener's preferred source, so an entry is never renamed, only added. */
@Serializable
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
    /**
     * Set when these are not the song's own lyrics as asked for, but the closest a looser search found: a song
     * uploaded under someone else's name, or with "slowed + reverb" in its title, which no source knows by the
     * name it was given. What the lyrics were found under, so the warning above them can say so.
     */
    val closeMatch: CloseMatch? = null,
)

/** The song a close match's lyrics belong to, as the source names it. */
data class CloseMatch(val title: String, val artist: String)

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
