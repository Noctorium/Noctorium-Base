package app.noctorium.settings

import app.noctorium.playback.AudioAnalysisKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * The choices that came with the desktop-music-player look: the visualiser, the queue's table, the status bar
 * and the icons. Its player bar, seek bar and layouts sit with the others of their kind: [PlayerBarStyle.TOOLBAR],
 * [PhonePlayerBarStyle.TOOLBAR], [ProgressBarStyle.SLIDER], [NowPlayingLayout.PLAYLIST] and
 * [PhoneNowPlayingLayout.PLAYLIST].
 */

/**
 * The columns of the queue's table in the Playlist layout, in the order the listener put them.
 *
 * The title is always there -- a table of songs without their names is not one -- and each column at most
 * once: [normalised] makes any list so, whatever was saved.
 */
@Serializable
enum class QueueColumn(val displayName: String, val heading: String) {
    NUMBER("Number", "#"),
    TITLE("Title", "Title"),
    ARTIST("Artist", "Artist"),
    ALBUM("Album", "Album"),
    DURATION("Duration", "Duration"),
    SERVICE("Service", "Service"),
    ;

    companion object {
        /** The table as it first appears: the service is left for whoever wants it. */
        val DEFAULT: List<QueueColumn> = listOf(NUMBER, TITLE, ARTIST, ALBUM, DURATION)

        /** [columns] with each column once, in the order given, and the title put first if it was left out. */
        fun normalised(columns: List<QueueColumn>): List<QueueColumn> =
            columns.distinct().let { if (TITLE in it) it else listOf(TITLE) + it }
    }
}

/**
 * The icons every player draws its buttons with.
 *
 * [STANDARD] is each player's icons as they have always been, and the default, so nobody's player changes until
 * they choose. Which set that is differs: Compose's Material icons as the desktop and the phone draw them --
 * `Icons.Default`, which is Material's filled set -- Lucide's lines in the browser, and the terminal's own
 * symbols (the terminal has glyph sets of its own rather than these). The rest are the same everywhere they can
 * be drawn: Material Symbols' styles (Apache 2.0) and Lucide (ISC) for Line. A player may leave out the named set
 * its standard one already is, so that two choices never look the same.
 */
@Serializable
enum class IconSet(val displayName: String, val description: String) {
    STANDARD("Standard", "The icons this player has always drawn."),
    ROUNDED("Rounded", "Solid shapes with softened corners and round ends."),
    OUTLINED("Outlined", "The same shapes drawn as outlines: lighter on the eye."),
    SHARP("Sharp", "Square corners and straight ends, for a crisper look."),
    FILLED("Filled", "Solid shapes, bold at a glance."),
    TWO_TONE("Two-tone", "An outline over a faint fill of the same colour."),
    LINE("Line", "Thin single strokes in one colour, like a desktop's own icon theme."),
}

/**
 * How the small badge that says which service a song comes from is drawn: with the service's own mark, or with the
 * letters the badges always had.
 *
 * [MARKS] is the default, since a mark is known at a glance where "SC" has to be read; [LETTERS] is the badges as
 * they were, for anybody who liked them so. Either way the badge keeps its colour and its size. The terminal has only
 * letters to draw with and keeps them whichever is chosen. The shapes are in [app.noctorium.domain.ServiceMarks].
 */
@Serializable
enum class ServiceBadgeStyle(val displayName: String, val description: String) {
    MARKS("Marks", "Each service's own mark: YouTube Music's ring, SoundCloud's cloud, Spotify's three waves."),
    LETTERS("Letters", "The service's short name, YTM, SC or SP, as the badges always had it."),
}

/**
 * A setting's choices, written as their names, that leaves out a name this build has never heard of instead of
 * failing over it.
 *
 * A list in a settings file is read whole or not at all, and not at all means the whole file: a button or a
 * column added by a later version, read by an earlier one, used to put every setting back to its default. One
 * enum inside a property is forgiven already (see SettingsRepository's coerceInputValues); one inside a list or
 * a set is not, and this is for those.
 */
abstract class ForgivingEnumListSerializer<E : Enum<E>>(private val entries: List<E>) : KSerializer<List<E>> {
    private val names = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = names.descriptor
    override fun serialize(encoder: Encoder, value: List<E>) = encoder.encodeSerializableValue(names, value.map { it.name })
    override fun deserialize(decoder: Decoder): List<E> =
        decoder.decodeSerializableValue(names).mapNotNull { name -> entries.firstOrNull { it.name == name } }
}

/** The same, for a set. */
abstract class ForgivingEnumSetSerializer<E : Enum<E>>(private val entries: List<E>) : KSerializer<Set<E>> {
    private val names = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = names.descriptor
    override fun serialize(encoder: Encoder, value: Set<E>) = encoder.encodeSerializableValue(names, value.map { it.name })
    override fun deserialize(decoder: Decoder): Set<E> =
        decoder.decodeSerializableValue(names).mapNotNullTo(LinkedHashSet()) { name -> entries.firstOrNull { it.name == name } }
}

/** Player buttons, forgiving a button from a later version. */
object PlayerButtonSetSerializer : ForgivingEnumSetSerializer<PlayerButton>(PlayerButton.entries)

/** The queue's columns, forgiving a column from a later version. */
object QueueColumnListSerializer : ForgivingEnumListSerializer<QueueColumn>(QueueColumn.entries)

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
