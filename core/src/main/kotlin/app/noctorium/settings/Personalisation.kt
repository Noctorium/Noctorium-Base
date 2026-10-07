package app.noctorium.settings

import app.noctorium.domain.HomeSection
import app.noctorium.domain.ProviderType
import kotlinx.serialization.Serializable
import kotlin.math.ln

/*
 * The ways a listener can make Noctorium their own, beyond the theme: how it sounds, what typeface it reads
 * in, how the lyrics look, which buttons the player shows, what Home is made of and what the navigation
 * offers. Kept here, in the core, so the desktop and the phone mean the same thing by each one, and a
 * listener who sets one on either finds the same name for it on the other.
 */

/**
 * The equaliser: ten bands an octave apart, a preamp, and the presets people expect to find.
 *
 * Ten bands because that is what every equaliser people have used before shows, and because an octave per
 * band is fine enough to shape music without inviting anybody to carve notches in it. Each player builds
 * its own filter from these numbers: mpv on a computer, Android's own equaliser on the phone, which has
 * bands of its own and asks [Equalizer.gainAt] what the curve is at each of them.
 */
@Serializable
data class EqualizerSettings(
    val enabled: Boolean = false,
    val preset: EqualizerPreset = EqualizerPreset.FLAT,
    /** Decibels for each of [Equalizer.BANDS_HZ], used when [preset] is CUSTOM. */
    val customGains: List<Float> = Equalizer.FLAT,
    /** Decibels added before the bands, to make room for a boost or simply to turn everything down. */
    val preampDb: Float = 0f,
) {
    /** The curve in force: the preset's, or the listener's own, always ten values within range. */
    val gains: List<Float> get() = Equalizer.normalise(preset.gains ?: customGains)

    /** Whether it changes the sound at all; a flat or switched-off equaliser should cost nothing. */
    val shapesSound: Boolean get() = enabled && (gains.any { it != 0f } || preampDb != 0f)
}

object Equalizer {
    /** The centre of each band, an octave apart, as every ten-band equaliser has them. */
    val BANDS_HZ: List<Int> = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

    /** How far a band goes either way. Further than this is not shaping, it is breaking. */
    const val MAX_GAIN_DB = 12f

    val FLAT: List<Float> = List(BANDS_HZ.size) { 0f }

    /** Ten values, each within range, whatever was stored. */
    fun normalise(gains: List<Float>): List<Float> =
        List(BANDS_HZ.size) { i -> gains.getOrNull(i)?.takeIf { it.isFinite() }?.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) ?: 0f }

    /** A band's label as an equaliser shows it: "31", "1k", "16k". */
    fun label(hz: Int): String = if (hz >= 1_000) "${hz / 1_000}k" else "$hz"

    /**
     * The curve's gain at any frequency, for a player whose bands are not these ten.
     *
     * Read between the two nearest bands on a logarithmic scale -- which is how hearing and every equaliser
     * lay frequency out -- and held flat beyond the ends.
     */
    fun gainAt(gains: List<Float>, hz: Double): Float {
        val curve = normalise(gains)
        if (hz <= BANDS_HZ.first()) return curve.first()
        if (hz >= BANDS_HZ.last()) return curve.last()
        val upper = BANDS_HZ.indexOfFirst { it >= hz }
        val lower = upper - 1
        val t = (ln(hz) - ln(BANDS_HZ[lower].toDouble())) / (ln(BANDS_HZ[upper].toDouble()) - ln(BANDS_HZ[lower].toDouble()))
        return (curve[lower] + (curve[upper] - curve[lower]) * t).toFloat()
    }
}

/**
 * The curves people expect to find, as decibels for 31 Hz up to 16 kHz.
 *
 * Gentle on purpose. A preset is somebody's first try at an equaliser, and one that pushes twelve decibels
 * into the bass sounds broken on half the headphones it is tried on; these move six at most, which is
 * plenty to hear and still sounds like the record.
 */
@Serializable
enum class EqualizerPreset(val displayName: String, val gains: List<Float>?) {
    FLAT("Flat", Equalizer.FLAT),
    BASS_BOOST("Bass boost", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS_REDUCER("Less bass", listOf(-6f, -5f, -4f, -2f, 0f, 0f, 0f, 0f, 0f, 0f)),
    TREBLE_BOOST("Treble boost", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
    VOCAL("Vocals", listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
    ROCK("Rock", listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
    POP("Pop", listOf(-1f, 0f, 2f, 3f, 4f, 3f, 1f, 0f, -1f, -1f)),
    ELECTRONIC("Electronic", listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f)),
    HIP_HOP("Hip-hop", listOf(5f, 4f, 2f, 3f, -1f, -1f, 1f, 0f, 2f, 3f)),
    JAZZ("Jazz", listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
    CLASSICAL("Classical", listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
    ACOUSTIC("Acoustic", listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 2f, 2f, 1f)),
    /** The ends lifted for listening quietly, where the ear hears least of them. */
    QUIET("Quiet listening", listOf(5f, 3f, 1f, 0f, 0f, 0f, 0f, 1f, 3f, 4f)),
    SPOKEN("Spoken word", listOf(-3f, -2f, 0f, 1f, 3f, 4f, 3f, 1f, -1f, -3f)),
    CUSTOM("Your own", null),
}

/**
 * The typeface the whole interface is set in.
 *
 * The three every platform has without anything being downloaded: the system's own, a serif for a page that
 * reads like a sleeve note, and a monospace for anybody who would rather their music player looked like
 * their terminal.
 */
@Serializable
enum class FontChoice(val displayName: String, val description: String) {
    DEFAULT("Default", "The system's own typeface."),
    SERIF("Serif", "A book face, like the notes in a record sleeve."),
    MONO("Monospace", "Every letter the same width, like a terminal."),
}

/** How the lyrics are set, wherever they are shown. The defaults are how they have always looked. */
@Serializable
data class LyricsLook(
    val size: LyricsSize = LyricsSize.DEFAULT,
    val alignment: LyricsAlignment = LyricsAlignment.AUTO,
    /** Lines not being sung are dimmed, so the eye finds the one that is. */
    val dimOtherLines: Boolean = true,
)

@Serializable
enum class LyricsSize(val displayName: String, val scale: Float) {
    SMALL("Small", .85f),
    DEFAULT("Default", 1f),
    LARGE("Large", 1.2f),
    HUGE("Huge", 1.45f),
}

@Serializable
enum class LyricsAlignment(val displayName: String) {
    /** However each screen lays them out by itself. */
    AUTO("As laid out"),
    START("Left"),
    CENTRE("Centred"),
}

/**
 * The buttons a player can do without. Play, pause, next and previous are not here: a player without them is
 * not one. Each platform keeps its own set of hidden ones, because its player is a different shape.
 */
@Serializable
enum class PlayerButton(val displayName: String) {
    SHUFFLE("Shuffle"),
    REPEAT("Repeat"),
    LIKE("Like"),
    LYRICS("Lyrics"),
    QUEUE("Queue"),
    SLEEP_TIMER("Sleep timer"),
    VOLUME("Volume"),
    /** The playback speed, a button of its own rather than a part of the volume's. */
    SPEED("Speed"),
    DEVICES("Devices and Connect"),
}

/** What Home is made of, each of which can be put away. */
@Serializable
enum class HomePart(val displayName: String, val description: String) {
    GREETING("Greeting", "Good morning, and your name."),
    PINNED("Pinned", "What you pinned to Home."),
    RECENT("Played lately", "What you played last."),
    YOUTUBE_MUSIC("From YouTube Music", "Its mixes and suggestions for you."),
    SOUNDCLOUD("From SoundCloud", "Your stream and its suggestions."),
    BANDCAMP("From Bandcamp", "Best-sellers, new releases and the genres you picked."),
    SPOTIFY("From Spotify", "Your top songs, and what you played lately."),
    VK("From VK Music", "VK's suggestions for you, and what is popular there."),
}

/** Home's suggested rows, without the ones from a service the listener put away. */
fun List<HomeSection>.withoutHidden(hidden: Set<HomePart>): List<HomeSection> = filterNot { section ->
    when (section.provider) {
        ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> HomePart.YOUTUBE_MUSIC in hidden
        ProviderType.SOUNDCLOUD -> HomePart.SOUNDCLOUD in hidden
        ProviderType.BANDCAMP -> HomePart.BANDCAMP in hidden
        ProviderType.SPOTIFY -> HomePart.SPOTIFY in hidden
        ProviderType.VK -> HomePart.VK in hidden
        else -> false
    }
}
