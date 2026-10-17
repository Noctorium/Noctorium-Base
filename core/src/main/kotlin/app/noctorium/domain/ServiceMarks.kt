package app.noctorium.domain

/**
 * Each service's mark, for the badge that says where a song comes from when it is drawn with marks rather than
 * letters ([app.noctorium.settings.ServiceBadgeStyle]).
 *
 * Kept here as SVG path data on a 24-unit square, one path to each, filled in one colour by the nonzero rule that SVG
 * fills by unless told otherwise: text, so that the desktop and the phone draw the very same shapes, each turning them
 * into a vector of its own, and the web player keeps a copy in TypeScript. A `when` rather than a map, so that a
 * service added to [ProviderType] does not build until it has a mark.
 *
 * The services' marks are Simple Icons' (https://simpleicons.org), from simple-icons 16.35.0, whose data is given to
 * the public domain under CC0 1.0. The marks themselves are trademarks of the services they stand for, and are used
 * only to say which service a song comes from: nothing here says that any of them made Noctorium or has anything to do
 * with it. A file on the device has no service, and so no mark; it is drawn as a folder, Material Icons' filled
 * "folder", under the Apache License 2.0.
 */
object ServiceMarks {
    /** The side of the square every path is drawn on: SVG's `viewBox="0 0 24 24"`. */
    const val VIEWPORT = 24f

    /** [provider]'s mark, as SVG path data. */
    fun path(provider: ProviderType): String = when (provider) {
        ProviderType.YOUTUBE_MUSIC -> YOUTUBE_MUSIC
        ProviderType.YOUTUBE_VIDEO -> YOUTUBE
        ProviderType.SOUNDCLOUD -> SOUNDCLOUD
        ProviderType.SPOTIFY -> SPOTIFY
        ProviderType.BANDCAMP -> BANDCAMP
        ProviderType.VK -> VK
        ProviderType.APPLE_MUSIC -> APPLE_MUSIC
        ProviderType.DEEZER -> DEEZER
        ProviderType.TIDAL -> TIDAL
        ProviderType.LOCAL -> FOLDER
    }

    /**
     * [provider]'s short name, for a badge drawn with letters: YTM, SC, SP and the rest, as the players have always
     * written them, and AM, DZ and TD for the services that came after. Kept beside the marks so that a service
     * added later has its letters decided in the same place as its shape.
     */
    fun letters(provider: ProviderType): String = when (provider) {
        ProviderType.YOUTUBE_MUSIC -> "YTM"
        ProviderType.YOUTUBE_VIDEO -> "YT"
        ProviderType.SOUNDCLOUD -> "SC"
        ProviderType.SPOTIFY -> "SP"
        ProviderType.BANDCAMP -> "BC"
        ProviderType.VK -> "VK"
        ProviderType.APPLE_MUSIC -> "AM"
        ProviderType.DEEZER -> "DZ"
        ProviderType.TIDAL -> "TD"
        ProviderType.LOCAL -> "LOCAL"
    }

    /**
     * [provider]'s colour, as opaque ARGB: what its badge is filled with, under its mark in white.
     *
     * The colours the desktop's badges have always had, kept here as well so that something drawn outside the players
     * -- a stream widget, which is a web page -- says the same service in the same colour. Tidal's is black, which on
     * a dark page wants an edge round it to read as a badge at all.
     */
    fun colour(provider: ProviderType): Long = when (provider) {
        ProviderType.YOUTUBE_MUSIC -> 0xFF8B2AB8
        ProviderType.YOUTUBE_VIDEO -> 0xFFB32C35
        ProviderType.SOUNDCLOUD -> 0xFFC45A16
        ProviderType.SPOTIFY -> 0xFF1DB954
        ProviderType.BANDCAMP -> 0xFF408294
        ProviderType.VK -> 0xFF0077FF
        ProviderType.APPLE_MUSIC -> 0xFFFA243C
        ProviderType.DEEZER -> 0xFFA238FF
        ProviderType.TIDAL -> 0xFF000000
        ProviderType.LOCAL -> 0xFF4F46E5
    }

    // Simple Icons' youtubemusic: the play button in a ring.
    private const val YOUTUBE_MUSIC = "M12 0C5.376 0 0 5.376 0 12s5.376 12 12 12 12-5.376 12-12S18.624 0 12 0zm0 19.104c-3.924 0-7.104-3.18-7.104-7.104S8.076 4.896 12 4.896s7.104 3.18 7.104 7.104-3.18 7.104-7.104 7.104zm0-13.332c-3.432 0-6.228 2.796-6.228 6.228S8.568 18.228 12 18.228s6.228-2.796 6.228-6.228S15.432 5.772 12 5.772zM9.684 15.54V8.46L15.816 12l-6.132 3.54z"

    // Simple Icons' youtube: the play button in its rounded rectangle.
    private const val YOUTUBE = "M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z"

    // Simple Icons' soundcloud: the cloud over its bars.
    private const val SOUNDCLOUD = "M23.999 14.165c-.052 1.796-1.612 3.169-3.4 3.169h-8.18a.68.68 0 0 1-.675-.683V7.862a.747.747 0 0 1 .452-.724s.75-.513 2.333-.513a5.364 5.364 0 0 1 2.763.755 5.433 5.433 0 0 1 2.57 3.54c.282-.08.574-.121.868-.12.884 0 1.73.358 2.347.992s.948 1.49.922 2.373ZM10.721 8.421c.247 2.98.427 5.697 0 8.672a.264.264 0 0 1-.53 0c-.395-2.946-.22-5.718 0-8.672a.264.264 0 0 1 .53 0ZM9.072 9.448c.285 2.659.37 4.986-.006 7.655a.277.277 0 0 1-.55 0c-.331-2.63-.256-5.02 0-7.655a.277.277 0 0 1 .556 0Zm-1.663-.257c.27 2.726.39 5.171 0 7.904a.266.266 0 0 1-.532 0c-.38-2.69-.257-5.21 0-7.904a.266.266 0 0 1 .532 0Zm-1.647.77a26.108 26.108 0 0 1-.008 7.147.272.272 0 0 1-.542 0 27.955 27.955 0 0 1 0-7.147.275.275 0 0 1 .55 0Zm-1.67 1.769c.421 1.865.228 3.5-.029 5.388a.257.257 0 0 1-.514 0c-.21-1.858-.398-3.549 0-5.389a.272.272 0 0 1 .543 0Zm-1.655-.273c.388 1.897.26 3.508-.01 5.412-.026.28-.514.283-.54 0-.244-1.878-.347-3.54-.01-5.412a.283.283 0 0 1 .56 0Zm-1.668.911c.4 1.268.257 2.292-.026 3.572a.257.257 0 0 1-.514 0c-.241-1.262-.354-2.312-.023-3.572a.283.283 0 0 1 .563 0Z"

    // Simple Icons' spotify: the three waves in a circle.
    private const val SPOTIFY = "M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z"

    // Simple Icons' bandcamp: the leaning band.
    private const val BANDCAMP = "M0 18.75l7.437-13.5H24l-7.438 13.5H0z"

    // Simple Icons' vk: the letters in their rounded square.
    private const val VK = "m9.489.004.729-.003h3.564l.73.003.914.01.433.007.418.011.403.014.388.016.374.021.36.025.345.03.333.033c1.74.196 2.933.616 3.833 1.516.9.9 1.32 2.092 1.516 3.833l.034.333.029.346.025.36.02.373.025.588.012.41.013.644.009.915.004.98-.001 3.313-.003.73-.01.914-.007.433-.011.418-.014.403-.016.388-.021.374-.025.36-.03.345-.033.333c-.196 1.74-.616 2.933-1.516 3.833-.9.9-2.092 1.32-3.833 1.516l-.333.034-.346.029-.36.025-.373.02-.588.025-.41.012-.644.013-.915.009-.98.004-3.313-.001-.73-.003-.914-.01-.433-.007-.418-.011-.403-.014-.388-.016-.374-.021-.36-.025-.345-.03-.333-.033c-1.74-.196-2.933-.616-3.833-1.516-.9-.9-1.32-2.092-1.516-3.833l-.034-.333-.029-.346-.025-.36-.02-.373-.025-.588-.012-.41-.013-.644-.009-.915-.004-.98.001-3.313.003-.73.01-.914.007-.433.011-.418.014-.403.016-.388.021-.374.025-.36.03-.345.033-.333c.196-1.74.616-2.933 1.516-3.833.9-.9 2.092-1.32 3.833-1.516l.333-.034.346-.029.36-.025.373-.02.588-.025.41-.012.644-.013.915-.009ZM6.79 7.3H4.05c.13 6.24 3.25 9.99 8.72 9.99h.31v-3.57c2.01.2 3.53 1.67 4.14 3.57h2.84c-.78-2.84-2.83-4.41-4.11-5.01 1.28-.74 3.08-2.54 3.51-4.98h-2.58c-.56 1.98-2.22 3.78-3.8 3.95V7.3H10.5v6.92c-1.6-.4-3.62-2.34-3.71-6.92Z"

    // Simple Icons' applemusic: the note in its rounded square. Its arcs' flags are written apart, as SVG also allows:
    // Simple Icons runs them into the next number ("0 00-.24"), which Compose's path parser reads as one number,
    // drawing the mark as a scribble half again the size of its square. The shape is the same.
    private const val APPLE_MUSIC = "M23.994 6.124a9.23 9.23 0 0 0 -.24 -2.19c-.317 -1.31 -1.062 -2.31 -2.18 -3.043a5.022 5.022 0 0 0 -1.877 -.726 10.496 10.496 0 0 0 -1.564 -.15c-.04 -.003 -.083 -.01 -.124 -.013H5.986c-.152 .01 -.303 .017 -.455 .026 -.747 .043 -1.49 .123 -2.193 .4 -1.336 .53 -2.3 1.452 -2.865 2.78 -.192 .448 -.292 .925 -.363 1.408 -.056 .392 -.088 .785 -.1 1.18 0 .032 -.007 .062 -.01 .093v12.223c.01 .14 .017 .283 .027 .424 .05 .815 .154 1.624 .497 2.373 .65 1.42 1.738 2.353 3.234 2.801 .42 .127 .856 .187 1.293 .228 .555 .053 1.11 .06 1.667 .06h11.03a12.5 12.5 0 0 0 1.57 -.1c.822 -.106 1.596 -.35 2.295 -.81a5.046 5.046 0 0 0 1.88 -2.207c.186 -.42 .293 -.87 .37 -1.324 .113 -.675 .138 -1.358 .137 -2.04 -.002 -3.8 0 -7.595 -.003 -11.393zm-6.423 3.99v5.712c0 .417 -.058 .827 -.244 1.206 -.29 .59 -.76 .962 -1.388 1.14 -.35 .1 -.706 .157 -1.07 .173 -.95 .045 -1.773 -.6 -1.943 -1.536a1.88 1.88 0 0 1 1.038 -2.022c.323 -.16 .67 -.25 1.018 -.324 .378 -.082 .758 -.153 1.134 -.24 .274 -.063 .457 -.23 .51 -.516a.904 .904 0 0 0 .02 -.193c0 -1.815 0 -3.63 -.002 -5.443a.725 .725 0 0 0 -.026 -.185c-.04 -.15 -.15 -.243 -.304 -.234 -.16 .01 -.318 .035 -.475 .066 -.76 .15 -1.52 .303 -2.28 .456l-2.325 .47 -1.374 .278c-.016 .003 -.032 .01 -.048 .013 -.277 .077 -.377 .203 -.39 .49 -.002 .042 0 .086 0 .13 -.002 2.602 0 5.204 -.003 7.805 0 .42 -.047 .836 -.215 1.227 -.278 .64 -.77 1.04 -1.434 1.233 -.35 .1 -.71 .16 -1.075 .172 -.96 .036 -1.755 -.6 -1.92 -1.544 -.14 -.812 .23 -1.685 1.154 -2.075 .357 -.15 .73 -.232 1.108 -.31 .287 -.06 .575 -.116 .86 -.177 .383 -.083 .583 -.323 .6 -.714v-.15c0 -2.96 0 -5.922 .002 -8.882 0 -.123 .013 -.25 .042 -.37 .07 -.285 .273 -.448 .546 -.518 .255 -.066 .515 -.112 .774 -.165 .733 -.15 1.466 -.296 2.2 -.444l2.27 -.46c.67 -.134 1.34 -.27 2.01 -.403 .22 -.043 .442 -.088 .663 -.106 .31 -.025 .523 .17 .554 .482 .008 .073 .012 .148 .012 .223 .002 1.91 .002 3.822 0 5.732z"

    // Simple Icons' deezer: the bars of its equaliser.
    private const val DEEZER = "M.693 10.024c.381 0 .693-1.256.693-2.807 0-1.55-.312-2.807-.693-2.807C.312 4.41 0 5.666 0 7.217s.312 2.808.693 2.808ZM21.038 1.56c-.364 0-.684.805-.91 2.096C19.765 1.446 19.184 0 18.526 0c-.78 0-1.464 2.036-1.784 5-.312-2.158-.788-3.536-1.325-3.536-.745 0-1.386 2.704-1.62 6.472-.442-1.932-1.083-3.145-1.793-3.145s-1.35 1.213-1.793 3.145c-.242-3.76-.874-6.463-1.628-6.463-.537 0-1.013 1.378-1.325 3.535C6.938 2.036 6.262 0 5.474 0c-.658 0-1.247 1.447-1.602 3.665-.217-1.291-.546-2.105-.91-2.105-.675 0-1.221 2.807-1.221 6.272 0 3.466.546 6.273 1.221 6.273.277 0 .537-.476.736-1.273.32 2.928.996 4.938 1.776 4.938.606 0 1.143-1.204 1.507-3.11.251 3.622.875 6.195 1.602 6.195.46 0 .875-1.023 1.187-2.677C10.142 21.6 11 24 12.004 24c1.005 0 1.863-2.4 2.235-5.822.312 1.654.727 2.677 1.186 2.677.728 0 1.352-2.573 1.603-6.195.364 1.906.9 3.11 1.507 3.11.78 0 1.455-2.01 1.775-4.938.208.797.46 1.273.737 1.273.675 0 1.22-2.807 1.22-6.273-.008-3.457-.553-6.272-1.23-6.272ZM23.307 10.024c.381 0 .693-1.256.693-2.807 0-1.55-.312-2.807-.693-2.807-.381 0-.693 1.256-.693 2.807s.312 2.808.693 2.808Z"

    // Simple Icons' tidal: the four diamonds.
    private const val TIDAL = "M12.012 3.992L8.008 7.996 4.004 3.992 0 7.996 4.004 12l4.004-4.004L12.012 12l-4.004 4.004 4.004 4.004 4.004-4.004L12.012 12l4.004-4.004-4.004-4.004zM16.042 7.996l3.979-3.979L24 7.996l-3.979 3.979z"

    // Material Icons' filled "folder" (Apache License 2.0): music on the device, which no service gave.
    private const val FOLDER = "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"
}
