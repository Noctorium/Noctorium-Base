package app.noctorium.widgets

import kotlinx.serialization.Serializable

/**
 * The looks a stream widget comes in.
 *
 * A stream widget is a web page on this computer that a streaming program -- OBS, Streamlabs and the rest, as a
 * "Browser source" -- lays over the stream, saying what Noctorium is playing. Each look is one page, kept with the
 * rest of `core`'s resources and served by [StreamWidgetServer] at `/widget/<id>`.
 *
 * [id] is what goes in that address, and so what a streamer has pasted into their streaming program: it is never
 * changed once shipped, whatever the look comes to be called. [width] and [height] are the size to give the browser
 * source. The page is drawn for that shape and scales to whatever size the source is actually given, so the size is
 * a recommendation; a source of another shape leaves room either side, except for the [TICKER], which runs the whole
 * width of whatever it is given.
 */
@Serializable
enum class StreamWidgetTheme(
    val id: String,
    val displayName: String,
    val description: String,
    val width: Int,
    val height: Int,
) {
    GLASS(
        "glass",
        "Glass",
        "A frosted dark card with the cover, the title and the artist, the service's logo and a thin progress bar.",
        640, 168,
    ),
    MINIMAL(
        "minimal",
        "Minimal",
        "Only words: a small \"Now playing\", the title and the artist, white with a soft shadow, readable over any game.",
        640, 140,
    ),
    VINYL(
        "vinyl",
        "Vinyl",
        "A record that turns while the music plays and stops when it is paused, with the cover as its label and the song beside it.",
        640, 220,
    ),
    NEON(
        "neon",
        "Neon",
        "A dark see-through strip with a glowing outline, and the song written in light in your accent colour.",
        680, 128,
    ),
    TICKER(
        "ticker",
        "Ticker",
        "A slim bar for the edge of the screen: the artist and the title on one line, scrolling gently when too long, over a hairline of progress.",
        1280, 44,
    ),
    AFTERGLOW(
        "afterglow",
        "Afterglow",
        "A large cover over a soft glow in its own colours, with the title and the artist beneath. Tall, for a corner of the stream.",
        360, 500,
    ),
    WINDOWS_98(
        "windows98",
        "Windows 98",
        "A tiny Now Playing window from 1998: a navy title bar, a grey bevelled face, the song, and a progress bar of blocks.",
        600, 224,
    ),
    ;

    /**
     * The picture of this look that Settings shows beside its name: the real page, playing a made-up song with a
     * made-up cover, over a made-up scene. Kept in the `jvm` module's resources, since only a computer streams and a
     * phone has no use for seven pictures of it; read with `javaClass.getResourceAsStream(previewResource)`.
     */
    val previewResource: String get() = "/app/noctorium/widgets/previews/$id.png"

    companion object {
        /** The look whose address names it, ignoring case; null for one that is not, or not yet, a look. */
        fun byId(id: String): StreamWidgetTheme? = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }
}

/**
 * The stream widgets, as the listener set them up.
 *
 * Off unless switched on: they are a web server, however small, and nothing here listens on a port nobody asked to
 * have opened. The rest are what every widget shows unless its own address says otherwise (see
 * [StreamWidgetServer]), so that two widgets in two looks can run at once on one stream, one with a cover and one
 * without.
 */
@Serializable
data class StreamWidgetPreferences(
    val enabled: Boolean = false,
    /** Where the widgets are served, on this computer: see [DEFAULT_PORT]. Read through [portInUse]. */
    val port: Int = DEFAULT_PORT,
    /** The look the plain address, without one named, shows -- and the one Settings offers first. */
    val theme: StreamWidgetTheme = StreamWidgetTheme.GLASS,
    val showCover: Boolean = true,
    val showProgress: Boolean = true,
    /** The mark of the service the song comes from: Spotify's for a Spotify song, even one matched on YouTube Music. */
    val showServiceLogo: Boolean = true,
    /**
     * Whether a widget fades away once the music has been paused for [hideAfterSeconds]. On, since a widget naming a
     * song nobody can hear is a caption for nothing; it comes back the moment the music does.
     */
    val hideWhenPaused: Boolean = true,
    /** How long a pause lasts before [hideWhenPaused] hides the widget. Read through [hideAfterSecondsInUse]. */
    val hideAfterSeconds: Int = 5,
    /**
     * Whether another computer on the network can open the widgets: a second PC that does the streaming, say. Off,
     * so that the server answers this computer and nothing else -- it is bound to 127.0.0.1 -- unless asked.
     */
    val allowNetwork: Boolean = false,
) {
    /** [port], or [DEFAULT_PORT] for one a settings file holds that no program could listen on. */
    val portInUse: Int get() = port.takeIf { it in PORTS } ?: DEFAULT_PORT

    val hideAfterSecondsInUse: Int get() = hideAfterSeconds.coerceIn(HIDE_AFTER_SECONDS)

    companion object {
        /**
         * Above the ports a program needs to be an administrator for, below the ones Windows hands out to programs
         * as they connect, and clear of the ones streaming programs and their plugins already use -- OBS's own
         * remote control is on 4455, and its older one on 4444. Nothing well known listens here; somebody whose
         * computer has something that does can choose another in Settings.
         */
        const val DEFAULT_PORT = 4851

        /** The ports a listener may choose: any that needs no administrator to listen on. */
        val PORTS: IntRange = 1024..65535

        /** From a moment to a minute: long enough to skip a song, short enough not to look forgotten. */
        val HIDE_AFTER_SECONDS: IntRange = 1..60
    }
}
