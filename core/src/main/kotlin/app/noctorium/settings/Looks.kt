package app.noctorium.settings

import app.noctorium.playback.AudioAnalysisKind
import kotlinx.serialization.Serializable

/**
 * The visualiser in the Playlist layout's info bar: what it draws, from the sound itself.
 *
 * Real audio only. A visualiser that drew a pattern while the music played would be lying about the one thing
 * it claims to show, so where the player cannot see the audio it draws less, or nothing: see [shownWith].
 */
@Serializable
enum class VisualiserStyle(val displayName: String, val description: String, val wants: AudioAnalysisKind) {
    BARS(
        "Bars",
        "A bar for each part of the sound, from the bass up, moving with the music.",
        AudioAnalysisKind.SPECTRUM,
    ),
    LEVEL_METER(
        "Level meter",
        "How loud each channel is, the way a mixing desk's meters show it.",
        AudioAnalysisKind.LEVELS,
    ),
    OFF(
        "Off",
        "No visualiser, and nothing measured for one.",
        AudioAnalysisKind.NONE,
    ),
    ;

    /**
     * What is actually drawn where the player can give [available]: bars become a level meter where only levels
     * can be had, and either becomes nothing where nothing can -- Spotify playing in its own app, a song in a
     * browser's embedded player.
     */
    fun shownWith(available: AudioAnalysisKind): VisualiserStyle = when {
        this == OFF || available == AudioAnalysisKind.NONE -> OFF
        this == BARS && available == AudioAnalysisKind.LEVELS -> LEVEL_METER
        else -> this
    }
}
