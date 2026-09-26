package app.noctorium.settings

import kotlinx.serialization.Serializable

/**
 * How a surface is painted: as a panel in the theme's own colour, or as glass over what is behind it.
 *
 * Glass is not a colour, it is a relationship -- a pane only reads as glass when there is something worth
 * seeing through it. So choosing it does two things at once: the panels and cards go translucent, and the
 * page behind them stops being flat and becomes a wash of the cover that is playing, blurred past
 * recognition. Either half alone is a disappointment. Translucent panels over flat black are just darker
 * panels, and a blurred cover behind solid panels is a wallpaper nobody can see.
 *
 * Solid stays the default, and not out of timidity: a music library is a wall of artwork, and the panels
 * between the covers are meant to be the quiet part.
 */
@Serializable
enum class SurfaceStyle(val displayName: String, val description: String) {
    SOLID("Solid", "Panels and cards in the theme's own colours."),
    GLASS("Liquid glass", "Translucent panels over a blurred wash of the cover that is playing."),
    ;

    val isGlass: Boolean get() = this == GLASS
}

/**
 * The numbers a pane of glass is made of, kept here so that both players make the same glass.
 *
 * They are fractions and one radius rather than colours, because the colours are the theme's: every
 * value below is applied to whatever palette is in force, which is what keeps glass from turning a
 * Solarized Light theme into a dark one.
 */
object Glass {
    /**
     * How much of the panel colour remains. The rest is whatever is behind it.
     *
     * Higher than the first attempt, and the reason is a limit worth writing down: Compose cannot blur
     * what happens to be behind an arbitrary surface, only what a composable draws itself. So the wash
     * below is pre-blurred and everything else behind a panel -- a dialog over a row of covers, a menu
     * over a track list -- is not. At .56 that came out as a window rather than a pane: the update
     * dialog had a legible album title showing through the middle of its own text.
     *
     * At these values what comes through is colour and shape rather than content, which is the honest
     * version of the effect given what can actually be blurred.
     */
    const val PANEL_ALPHA = .82f

    /** Cards sit on panels, so they are thinner again, or the stack reads as one opaque slab. */
    const val CARD_ALPHA = .68f

    /**
     * How far the artwork wash is pulled toward the theme's background colour.
     *
     * Toward the *background*, not toward black. Dimming toward black is the obvious way to do this and
     * it is wrong on a light theme: it leaves a pale page with a bruise on it. Mixing toward whatever the
     * theme calls its page means the wash is quiet on Night and quiet on Latte for the same reason.
     */
    const val BACKDROP_TOWARD_BACKGROUND = .74f

    /** Blur radius for that wash, in density-independent pixels. Enough that no cover is recognisable. */
    const val BACKDROP_BLUR_DP = 72

    /** A hairline of the text colour along a panel's edge, which is what says "pane" rather than "hole". */
    const val EDGE_ALPHA = .14f
}

/**
 * How round the corners are, everywhere at once.
 *
 * One knob rather than a number per control, because the thing being chosen is not the radius of a chip,
 * it is whether the application looks machined or soft -- and a set of corners that disagree with each
 * other reads as a mistake rather than as a choice.
 *
 * The scale multiplies the shape each player already uses, so Soft is exactly what was there before.
 */
@Serializable
enum class CornerStyle(val displayName: String, val scale: Float) {
    SHARP("Sharp", 0f),
    SOFT("Soft", 1f),
    ROUND("Round", 2f),
}

/**
 * Everything, larger or smaller.
 *
 * Noctorium's text sizes are written in the code as fixed numbers, which is fine until somebody is
 * reading a track list across a room or on a phone held at arm's length. This scales all of them
 * together, so the proportions the screens were designed at survive.
 *
 * It does not replace the operating system's own text size -- that one applies to every application and
 * is the right place to change all of them. This is for wanting Noctorium in particular bigger.
 */
@Serializable
enum class TextSize(val displayName: String, val scale: Float) {
    SMALL("Small", .9f),
    DEFAULT("Default", 1f),
    LARGE("Large", 1.15f),
    LARGEST("Largest", 1.3f),
}
