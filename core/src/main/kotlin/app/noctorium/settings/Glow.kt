package app.noctorium.settings

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A soft light some themes lay across their page.
 *
 * A theme is six flat colours, and a flat near-black page reads as nothing in particular; the same black with
 * a red light spreading out from behind the cover reads as somebody's evening. The glow is that light: one
 * colour, [strength] opaque where it is brightest, fading to nothing over the shapes its [placement] is made of.
 *
 * What a player draws, and in what order:
 *
 * - The page in the theme's background colour, then the glow straight on top of it, then everything else. The
 *   glow is part of the page and lies under every panel, card, bar and backdrop, which keep their own colours
 *   and cover it where they are: a sidebar is a panel and is not lit, the gaps between the cards are. A now
 *   playing backdrop that paints the whole page -- a wash, a blurred cover -- covers the glow as it covers the
 *   page.
 * - Each of the placement's [GlowPlacement.shapes], in order, one over the other, as a gradient of
 *   [resolvedColour]: a radial one for a [GlowShape.Pool], a vertical one for a [GlowShape.Band], its opacity
 *   running from [strength] times the shape's own at its middle or its top edge to none at its rim, through
 *   the stops of [GlowShape.FALLOFF], and clear beyond.
 * - One colour all the way through each gradient, with only its opacity changing. Compose blends a gradient's
 *   stops unpremultiplied and CSS premultiplied, which come out differently whenever the colours differ; with
 *   one colour they cannot, so the desktop, the phone and a browser draw the same light. Fading the colour
 *   into the page's instead would also have made a glow that only worked on the page it was drawn for.
 * - Nothing in a terminal, which has no way to draw a light.
 *
 * It must never cost the writing. A theme's glow is held by the tests to the contrast its page is held to, at
 * the brightest point of it, and [resolvedColour] keeps a light taken from a cover no brighter than that.
 * [opacityAt] is what the gradients come to at any point, for those tests and for anything that has to paint
 * the light by hand.
 */
data class ThemeGlow(
    /** The light's colour; for a glow that follows the artwork, the colour it falls back on with none to follow. */
    val colour: Long,
    /** Whether the light takes its colour from the playing song's cover, and so changes with every song. */
    val followsArtwork: Boolean = false,
    val placement: GlowPlacement,
    /** How opaque the light is where it is brightest, from 0 for none to 1 for the colour itself. */
    val strength: Float,
) {
    /**
     * The light's colour while [artworkArgb] is the playing cover's, as [ArtworkColour.of] finds it: null with
     * nothing playing, no cover, or a cover with no colour in it. A glow that does not follow the artwork
     * ignores it.
     *
     * The cover lends the light its hue and nothing else; how strong a light it is stays the theme's. Taken
     * whole, a pale cover would wash the page out and a dark one leave it unlit, so the hue is given the
     * fallback's saturation and brightness. That alone is not enough, since the same brightness is a much
     * louder light in yellow than in red, so it is then held to [BRIGHTEST] times the fallback's luminance,
     * which keeps grey writing above 4.5:1 at the brightest of the light whatever the cover's hue. A colour
     * too grey to have a hue worth the name is no colour, and the fallback stands -- which is also what a
     * player gets that answers a black-and-white cover with the theme's own white accent.
     *
     * A player that eases the accent from one song's colour to the next should ease this the same way, or
     * the light jumps while everything else glides.
     */
    fun resolvedColour(artworkArgb: Long?): Long {
        if (!followsArtwork || artworkArgb == null) return colour
        val artwork = Hsv.of(artworkArgb)
        if (artwork.saturation < GREYEST) return colour
        val fallback = Hsv.of(colour)
        val tinted = Hsv(artwork.hue, fallback.saturation, fallback.value).argb()
        val ceiling = BRIGHTEST * relativeLuminance(colour)
        val luminance = relativeLuminance(tinted)
        return if (luminance <= ceiling) tinted else dimmed(tinted, ceiling / luminance)
    }

    /**
     * How opaque the light is at [x], [y] on a page [width] by [height], measured from its top left: what the
     * gradients the players draw add up to there. Laid one over another, two lights of one colour let through
     * what each other let through, which is why this multiplies what is left clear rather than adding.
     */
    fun opacityAt(x: Float, y: Float, width: Float, height: Float): Float {
        var clear = 1f
        placement.shapes.forEach { shape -> clear *= 1f - strength * shape.reachAt(x, y, width, height) }
        return 1f - clear
    }

    companion object {
        /** Below this saturation a cover's colour is a grey, and its hue is noise. */
        const val GREYEST = .2f

        /** How much brighter than its fallback a light from the artwork may be, in luminance: see [resolvedColour]. */
        const val BRIGHTEST = 2.0
    }
}

/**
 * Where on the page a glow lies, as the shapes it is drawn with. A new placement is new shapes, and every
 * player that draws the shapes draws it without being told.
 */
enum class GlowPlacement(val shapes: List<GlowShape>) {
    /**
     * Behind the now playing screen's cover and up the side beyond it, as a lamp behind the record would light
     * the wall: a pool low behind the controls under the cover, and a broader, brighter one rising past it
     * towards the words. Placed for the look it comes from, a cover and its controls in a column on the left
     * and the lyrics large beside them, as the desktop's Sing along has it. Every other screen has the same
     * light in the same place, behind whatever the page holds; on a phone held upright the broad pool sits
     * behind the cover and the small one behind the controls.
     */
    ARTWORK(
        listOf(
            GlowShape.Pool(x = .325f, y = .72f, radius = .21f, strength = .82f),
            GlowShape.Pool(x = .56f, y = .36f, radius = .34f),
        ),
    ),

    /** A band of light across the top of the page, gone before the middle, as if from a lamp somewhere above it. */
    TOP(listOf(GlowShape.Band(depth = .62f))),
}

/** One gradient of a glow. */
sealed interface GlowShape {
    /** How much of the glow's strength reaches [x], [y] on a page [width] by [height]: 1 at the brightest, 0 beyond. */
    fun reachAt(x: Float, y: Float, width: Float, height: Float): Float

    /**
     * A round pool of light centred at [x] and [y], fractions of the page's width and height from its top left,
     * reaching [radius] of the page's longer side, with [strength] of the glow's own at its middle.
     *
     * Measured against the longer side so a pool is round and the same size against the page whichever way up
     * a phone is held. In Compose it is `Brush.radialGradient` with the [FALLOFF] stops, centred at x times the
     * width and y times the height; in CSS a `radial-gradient(circle R at X% Y%, ...)` with R worked out in
     * pixels, since a radius written as a percentage makes an ellipse.
     */
    data class Pool(val x: Float, val y: Float, val radius: Float, val strength: Float = 1f) : GlowShape {
        override fun reachAt(x: Float, y: Float, width: Float, height: Float): Float {
            val dx = x - this.x * width
            val dy = y - this.y * height
            return strength * falloff(sqrt(dx * dx + dy * dy) / (radius * max(width, height)))
        }
    }

    /**
     * A band across the whole width, with [strength] of the glow's own along the top edge and gone [depth] of
     * the way down. In Compose `Brush.verticalGradient` from the top to depth times the height; in CSS a
     * `linear-gradient(to bottom, ...)` with each stop at depth times its place.
     */
    data class Band(val depth: Float, val strength: Float = 1f) : GlowShape {
        override fun reachAt(x: Float, y: Float, width: Float, height: Float): Float =
            strength * falloff(y / (depth * height))
    }

    companion object {
        /**
         * How a light fades from its middle to its rim: pairs of how far out, from 0 to 1, and how much of its
         * strength is left there. A gradient's stops, as Compose's colorStops and CSS's percentages both take them.
         *
         * Slow to start and slow to finish, so that it has no bright point at the middle and no edge at the rim,
         * and in stops close enough together that the bends between them do not show as rings, which with
         * half as many they did.
         */
        val FALLOFF: List<Pair<Float, Float>> = listOf(
            0f to 1f, .125f to .96f, .25f to .84f, .375f to .68f, .5f to .5f, .625f to .32f, .75f to .16f, .875f to .04f, 1f to 0f,
        )

        /** [FALLOFF] at [t], between its stops as a gradient draws it: in a straight line from one to the next. */
        fun falloff(t: Float): Float {
            if (t <= 0f) return 1f
            if (t >= 1f) return 0f
            val after = FALLOFF.indexOfFirst { it.first >= t }
            val (from, fromLeft) = FALLOFF[after - 1]
            val (to, toLeft) = FALLOFF[after]
            return fromLeft + (toLeft - fromLeft) * (t - from) / (to - from)
        }
    }
}

/** The glow in force: the preset's. The listener's own colours have none. */
val NoctoriumPreferences.themeGlow: ThemeGlow? get() = theme.glow

/**
 * The colour a cover is known by, worked out the same way on every player: what an artwork glow takes its
 * light from, and what "Match the artwork" can take for the accent.
 *
 * The most common vivid hue, as the average of the pixels in it. The pixels are sorted into twelve hues, which
 * tells a pink cover from a teal one without chasing every gradient; the nearly black, the nearly white and the
 * washed out are passed over, since they are the background of a cover rather than its character. A cover with
 * nothing vivid in it -- a black-and-white photograph, a plain sleeve -- has no colour, and the answer is null
 * rather than a guess, so that the theme's own colour stands.
 *
 * These are the sums the desktop has drawn its now playing backdrop and its artwork accent with, moved here so
 * that the phone, which samples nothing yet, can have the same colour from the same cover. A player decodes a
 * small copy -- a hundred-odd pixels across is plenty, and quick -- and hands its pixels in. The hosted web
 * player, which runs no Kotlin, is to count a canvas the same way.
 */
object ArtworkColour {
    /** [pixels] are [width] by [height] ARGB, a row at a time from the top, as Android's and Java's images give them. */
    fun of(pixels: IntArray, width: Int, height: Int): Long? {
        require(pixels.size >= width * height) { "$width by $height needs ${width * height} pixels, not ${pixels.size}" }
        val step = max(1, min(width, height) / 48)
        val buckets = Array(12) { FloatArray(4) }
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = pixels[y * width + x]
                val red = (pixel shr 16 and 0xFF) / 255f
                val green = (pixel shr 8 and 0xFF) / 255f
                val blue = (pixel and 0xFF) / 255f
                val brightest = max(red, max(green, blue))
                val darkest = min(red, min(green, blue))
                val saturation = if (brightest <= 0f) 0f else (brightest - darkest) / brightest
                if (brightest > .15f && brightest < .97f && saturation > .22f) {
                    val bucket = buckets[(hue(red, green, blue) / 30f).toInt().coerceIn(0, 11)]
                    bucket[0] += red
                    bucket[1] += green
                    bucket[2] += blue
                    bucket[3] += 1f
                }
            }
        }
        val most = buckets.filter { it[3] > 0f }.maxByOrNull { it[3] } ?: return null
        return argb(most[0] / most[3], most[1] / most[3], most[2] / most[3])
    }
}

/** A colour as hue in degrees, saturation and value, for keeping a cover's hue and the theme's strength. */
private data class Hsv(val hue: Float, val saturation: Float, val value: Float) {
    fun argb(): Long {
        val chroma = value * saturation
        val h = (hue / 60f) % 6f
        val second = chroma * (1f - abs(h % 2f - 1f))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(chroma, second, 0f)
            1 -> Triple(second, chroma, 0f)
            2 -> Triple(0f, chroma, second)
            3 -> Triple(0f, second, chroma)
            4 -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }
        val m = value - chroma
        return argb(r + m, g + m, b + m)
    }

    companion object {
        fun of(argb: Long): Hsv {
            val red = (argb shr 16 and 0xFF) / 255f
            val green = (argb shr 8 and 0xFF) / 255f
            val blue = (argb and 0xFF) / 255f
            val brightest = max(red, max(green, blue))
            val darkest = min(red, min(green, blue))
            return Hsv(hue(red, green, blue), if (brightest <= 0f) 0f else (brightest - darkest) / brightest, brightest)
        }
    }
}

/** The hue of a colour in degrees, 0 for a grey. */
private fun hue(red: Float, green: Float, blue: Float): Float {
    val brightest = max(red, max(green, blue))
    val delta = brightest - min(red, min(green, blue))
    if (delta <= 0f) return 0f
    val hue = when (brightest) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }
    return if (hue < 0f) hue + 360f else hue
}

/** [argb] with its light cut to [fraction] of what it was, in linear light so the hue stays put. */
private fun dimmed(argb: Long, fraction: Double): Long {
    fun channel(shift: Int): Float {
        val c = ((argb shr shift) and 0xFF) / 255.0
        val linear = (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)) * fraction
        return (if (linear <= 0.0031308) linear * 12.92 else 1.055 * linear.pow(1 / 2.4) - 0.055).toFloat()
    }
    return argb(channel(16), channel(8), channel(0))
}

/** An opaque colour from three channels between 0 and 1. */
private fun argb(red: Float, green: Float, blue: Float): Long {
    fun byte(c: Float) = (c.coerceIn(0f, 1f) * 255f).roundToInt().toLong()
    return 0xFF000000L or (byte(red) shl 16) or (byte(green) shl 8) or byte(blue)
}
