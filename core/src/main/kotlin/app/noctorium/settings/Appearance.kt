package app.noctorium.settings

import kotlinx.serialization.Serializable

/**
 * How the chrome is painted: in the theme's own colours, or as liquid glass.
 *
 * Glass here means the real thing rather than translucency, and the difference is three properties that
 * a translucent panel does not have. What sits behind the pane is *bent* at its rim, the way a thick lens
 * bends it -- that refraction is the defining trait, and without it the effect is 2013's frosted glass.
 * The pane frosts only what is actually behind it, not a pre-blurred page. And its edge catches light, a
 * specular rim that says "slab" rather than "hole".
 *
 * It also goes on the right layer. Glass is for the controls that float over content -- the player, the
 * tabs -- with the content scrolling *underneath*, which is the only arrangement in which there is
 * anything to see through it. The first version of this made the content cards translucent instead,
 * which gave the glass nothing to show and made the content harder to read. Content stays solid.
 *
 * Solid stays the default, and not out of timidity: a music library is a wall of artwork, and the panels
 * between the covers are meant to be the quiet part.
 */
@Serializable
enum class SurfaceStyle(val displayName: String, val description: String) {
    SOLID("Solid", "Panels and cards in the theme's own colours."),
    GLASS("Liquid glass", "The player and the tabs float as glass, bending whatever scrolls beneath them."),
    ;

    val isGlass: Boolean get() = this == GLASS
}

/**
 * The numbers a pane of glass is made of, and the lens itself, so both players make the same glass.
 *
 * Distances are density-independent pixels and the rest are fractions; none of it is a colour, because
 * the colours are the theme's. That is what keeps glass from turning Solarized Light into a dark theme.
 */
object Glass {
    /**
     * How far, at most, what is behind the pane is pulled at its very edge.
     *
     * Zero in the flat middle of the pane and this much at the rim, on a circular profile across the
     * [BEZEL_DP] -- flat for most of the way and then steep, like the rounded edge of a real slab.
     */
    const val REFRACTION_DP = 16

    /** How wide the curved rim is: the band inside the edge where the bending happens. */
    const val BEZEL_DP = 20

    /**
     * How much the three colours come apart at the rim, as a fraction of the bend.
     *
     * Real glass disperses, and a trace of it -- a warm fringe on one side of a white edge, a cool one on
     * the other -- is much of why the rim reads as glass rather than as a distortion filter. A trace:
     * much past this and it reads as a broken monitor.
     */
    const val DISPERSION = .09f

    /**
     * The frost on what shows through, in density-independent pixels.
     *
     * Light. Liquid glass is mostly clear -- what is behind it should still be recognisable, bent -- and
     * frosting it heavily turns it back into the old frosted look this replaces.
     *
     * But not so light that words survive it. At 4 the second line of a track title scrolling under the
     * mini player stayed readable, right beside the artist name the player was trying to show, and two
     * lines of text on top of each other is a legibility problem whatever it looks like. At this, a cover
     * behind the glass is still a cover and a line of text behind it is just a line.
     */
    const val FROST_DP = 6

    /** How much of the theme's panel colour lies over the pane, so it has a colour of its own. */
    const val TINT_ALPHA = .14f

    /** The brightest point of the specular rim, which is along the top: the light is overhead. */
    const val RIM_ALPHA = .68f

    /** The gap between floating chrome and the edges of the screen or window. */
    const val FLOAT_INSET_DP = 12

    /**
     * How far the artwork wash on the page is pulled toward the theme's background colour.
     *
     * Toward the *background*, not toward black. Dimming toward black is the obvious way to do this and
     * it is wrong on a light theme: it leaves a pale page with a bruise on it.
     */
    const val BACKDROP_TOWARD_BACKGROUND = .74f

    /** Blur radius for that wash, in density-independent pixels. Enough that no cover is recognisable. */
    const val BACKDROP_BLUR_DP = 72

    /**
     * The lens: one shader, compiled by both players -- AGSL on the phone, SkSL on the desktop, which
     * for this are the same language.
     *
     * It takes what is behind the pane as `content`, already frosted, and for every pixel works out how
     * far it is from the pane's rounded edge. In the flat middle it samples straight through. Across the
     * rim it samples from further *out*, on a circular profile, so the band just beyond the edge is
     * squeezed into the rim and wraps round it -- the look of a thick slab's rounded edge, and the thing
     * people recognise Liquid Glass by. The three colours are sampled a little apart for the dispersion.
     *
     * Outward and not inward, which was the first attempt: sampling from further in only magnifies what
     * is already under the pane, and over a list that reads as nothing much. Sampling outward needs the
     * pane to see past its own edges, which is what `origin` is for -- the players hand the lens a copy
     * of the backdrop larger than the pane, and `origin` is where the pane sits inside it.
     *
     * The normal comes from the distance field's own slope rather than from geometry, so the same code
     * is right for a pill, a rounded rectangle and every corner of either.
     */
    const val LENS_SHADER: String = """
uniform shader content;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float bezel;
uniform float strength;
uniform float dispersion;

float roundedBox(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + float2(r, r);
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - origin - halfSize;
    float d = roundedBox(p, halfSize, radius);
    float dx = roundedBox(p + float2(1.0, 0.0), halfSize, radius) - roundedBox(p - float2(1.0, 0.0), halfSize, radius);
    float dy = roundedBox(p + float2(0.0, 1.0), halfSize, radius) - roundedBox(p - float2(0.0, 1.0), halfSize, radius);
    float2 slope = float2(dx, dy);
    float steep = length(slope);
    float2 normal = steep > 0.0001 ? slope / steep : float2(0.0, 0.0);
    float t = clamp(1.0 + d / bezel, 0.0, 1.0);
    float bend = 1.0 - sqrt(max(1.0 - t * t, 0.0));
    float2 shift = normal * bend * strength;
    half4 green = content.eval(coord + shift);
    if (dispersion <= 0.0) {
        return green;
    }
    half4 red = content.eval(coord + shift * (1.0 + dispersion));
    half4 blue = content.eval(coord + shift * (1.0 - dispersion));
    return half4(red.r, green.g, blue.b, green.a);
}
"""
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
