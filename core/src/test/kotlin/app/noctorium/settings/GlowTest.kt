package app.noctorium.settings

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The light some themes lay across their page: where it falls, what colour it takes from a cover, and that the
 * writing on the page still reads where it is brightest, whatever the cover and whatever the window's shape.
 */
class GlowTest {
    private val glowThemes = ThemePreset.entries.filter { it.glow != null }

    /** A desktop window, the one the screenshots were taken in, a phone held upright and on its side, and a wide screen. */
    private val pages = listOf(1200f to 780f, 412f to 892f, 892f to 412f, 1920f to 1080f)

    /** The glow over [page] at [opacity], as a gradient lays a colour over what is under it. */
    private fun lit(page: Long, light: Long, opacity: Float): Long {
        fun channel(shift: Int): Long {
            val under = (page shr shift) and 0xFF
            val over = (light shr shift) and 0xFF
            return (under + (over - under) * opacity).roundToInt().toLong() shl shift
        }
        return 0xFF000000L or channel(16) or channel(8) or channel(0)
    }

    /** The most opaque the glow comes anywhere on a page [width] by [height], looked for every few pixels. */
    private fun brightest(glow: ThemeGlow, width: Float, height: Float): Float {
        var most = 0f
        var y = 0f
        while (y <= height) {
            var x = 0f
            while (x <= width) {
                most = maxOf(most, glow.opacityAt(x, y, width, height))
                x += 4f
            }
            y += 4f
        }
        return most
    }

    @Test
    fun `a light fades from all of it at the middle to none at the rim, smoothly`() {
        assertEquals(1f, GlowShape.falloff(0f))
        assertEquals(0f, GlowShape.falloff(1f))
        assertEquals(0f, GlowShape.falloff(1.5f), "nothing beyond the rim")
        assertEquals(.5f, GlowShape.falloff(.5f))
        // Between two stops it is the straight line a gradient draws.
        assertEquals((.84f + .68f) / 2, GlowShape.falloff(.3125f), 1e-5f)
        val stops = GlowShape.FALLOFF
        assertEquals(0f to 1f, stops.first())
        assertEquals(1f to 0f, stops.last())
        stops.zipWithNext().forEach { (a, b) ->
            assertTrue(b.first > a.first && b.second < a.second, "the stops run outward and down: $a then $b")
        }
    }

    @Test
    fun `each light is its strength where it is brightest and nothing far from it`() {
        glowThemes.forEach { preset ->
            val glow = preset.glow!!
            pages.forEach { (width, height) ->
                val most = brightest(glow, width, height)
                assertTrue(most <= glow.strength + 1e-4f, "${preset.name} at $width by $height comes to $most")
                assertTrue(most >= glow.strength * .97f, "${preset.name} at $width by $height never reaches its strength: $most")
            }
        }
        val top = ThemePreset.VELVET.glow!!
        assertEquals(top.strength, top.opacityAt(600f, 0f, 1200f, 780f), 1e-5f, "a band is brightest along the top")
        assertEquals(0f, top.opacityAt(600f, 600f, 1200f, 780f), "and gone by the lower half")
        assertEquals(top.opacityAt(0f, 100f, 1200f, 780f), top.opacityAt(1200f, 100f, 1200f, 780f), "and the same all the way across")
        val artwork = ThemePreset.AFTERGLOW.glow!!
        assertEquals(0f, artwork.opacityAt(1200f, 780f, 1200f, 780f), "the far corner from the cover is dark")
        assertTrue(artwork.opacityAt(672f, 281f, 1200f, 780f) > artwork.opacityAt(1100f, 100f, 1200f, 780f), "up beside the cover, not in the corner")
    }

    @Test
    fun `the writing on the page reads where the light is brightest`() {
        // Every hue a cover could have, at its most vivid, and the fallback; for a light that does not follow
        // the artwork they are all the one colour.
        val covers = (0 until 360 step 5).map { hue -> hsvArgb(hue.toFloat(), 1f, .9f) } + listOf<Long?>(null)
        glowThemes.forEach { preset ->
            val colours = preset.colours!!
            val glow = preset.glow!!
            covers.forEach { cover ->
                val light = glow.resolvedColour(cover)
                val brightestPage = lit(colours.background, light, glow.strength)
                val text = contrastRatio(colours.text, brightestPage)
                val subtext = contrastRatio(colours.subtext, brightestPage)
                val under = "${preset.name} under ${light.toHexColour()} (${brightestPage.toHexColour()})"
                assertTrue(text >= 4.5, "$under: text is ${"%.2f".format(text)}")
                assertTrue(subtext >= 4.5, "$under: subtext is ${"%.2f".format(subtext)}")
            }
        }
    }

    @Test
    fun `the light is soft, a glow rather than a lamp`() {
        // Lit, the page is still a near-black one: the brightest of the light lifts it a little and no more.
        glowThemes.forEach { preset ->
            val glow = preset.glow!!
            val page = preset.colours!!.background
            val brightestPage = lit(page, glow.resolvedColour(null), glow.strength)
            assertTrue(contrastRatio(brightestPage, page) < 1.6, "${preset.name} lights its page to ${brightestPage.toHexColour()}")
            assertTrue(contrastRatio(brightestPage, page) > 1.05, "${preset.name}'s light can hardly be seen")
        }
    }

    @Test
    fun `an artwork light takes the cover's hue and falls back on its own colour`() {
        val glow = ThemePreset.AFTERGLOW.glow!!
        assertEquals(glow.colour, glow.resolvedColour(null), "nothing playing, or no cover: the fallback red")
        assertEquals(glow.colour, glow.resolvedColour(0xFF808080), "a grey cover has no colour to give")
        assertEquals(glow.colour, glow.resolvedColour(0xFFFFF7F4), "nor does the theme's own white accent, answered for a colourless cover")

        val blue = glow.resolvedColour(0xFF2A7AC8)
        assertNotEquals(glow.colour, blue)
        assertTrue((blue and 0xFF) > ((blue shr 16) and 0xFF) * 4, "a blue cover gives a blue light: ${blue.toHexColour()}")
        // The cover's hue, but the theme's strength: a pale cover and a deep one of the same hue light the page alike.
        assertEquals(glow.resolvedColour(0xFF98B0E0), glow.resolvedColour(0xFF183060))
        assertEquals(glow.resolvedColour(0xFFFF5050), glow.resolvedColour(0xFF801010))
        // And no brighter than twice the fallback, however loud the hue.
        listOf(0xFFFFFF00, 0xFF00FFFF, 0xFF00FF00).forEach { loud ->
            val light = glow.resolvedColour(loud)
            assertTrue(relativeLuminance(light) <= ThemeGlow.BRIGHTEST * relativeLuminance(glow.colour) + .002, "${loud.toHexColour()} gives ${light.toHexColour()}")
        }

        // A light that does not follow the artwork is its own colour whatever is playing.
        val cinder = ThemePreset.CINDER.glow!!
        assertEquals(cinder.colour, cinder.resolvedColour(0xFF2A7AC8))
        assertEquals(ThemePreset.VELVET.glow!!.colour, ThemePreset.VELVET.glow!!.resolvedColour(0xFF2A7AC8))
    }

    @Test
    fun `a cover's colour is its most common vivid hue`() {
        // A dark cover with a red bar down it and a little blue in the corner: the red is what it is known by.
        val width = 96
        val height = 96
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            when {
                x in 40..56 -> 0xFFC0261A.toInt()
                x < 12 && y < 12 -> 0xFF2A7AC8.toInt()
                else -> 0xFF141414.toInt()
            }
        }
        assertEquals(0xFFC0261A, ArtworkColour.of(pixels, width, height))
    }

    @Test
    fun `a cover with nothing vivid in it has no colour`() {
        val greys = IntArray(64 * 64) { i -> if (i % 3 == 0) 0xFFFFFFFF.toInt() else 0xFF303030.toInt() }
        assertNull(ArtworkColour.of(greys, 64, 64), "black and white")
        val nearlyBlack = IntArray(64 * 64) { 0xFF200000.toInt() }
        assertNull(ArtworkColour.of(nearlyBlack, 64, 64), "too dark to be the cover's character")
        val washedOut = IntArray(64 * 64) { 0xFFE8D8D8.toInt() }
        assertNull(ArtworkColour.of(washedOut, 64, 64), "too pale")
        assertFailsWith<IllegalArgumentException> { ArtworkColour.of(IntArray(10), 64, 64) }
    }

    @Test
    fun `a cover's colour is averaged over its hue, so a gradient gives its middle`() {
        val pixels = IntArray(48 * 48) { i ->
            val x = i % 48
            // Reds from a deep one to a bright one, all in one twelfth of the wheel.
            val red = 128 + x * 2
            (0xFF000000 or (red.toLong() shl 16) or (24L shl 8) or 16L).toInt()
        }
        val colour = ArtworkColour.of(pixels, 48, 48)!!
        assertEquals(128 + 47, ((colour shr 16) and 0xFF).toInt(), "the middle of 128 to 222")
        assertEquals(24, ((colour shr 8) and 0xFF).toInt())
    }

    private fun hsvArgb(hue: Float, saturation: Float, value: Float): Long {
        val chroma = value * saturation
        val h = hue / 60f
        val second = chroma * (1f - kotlin.math.abs(h % 2f - 1f))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(chroma, second, 0f)
            1 -> Triple(second, chroma, 0f)
            2 -> Triple(0f, chroma, second)
            3 -> Triple(0f, second, chroma)
            4 -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }
        val m = value - chroma
        fun byte(c: Float) = ((c + m) * 255f).roundToInt().toLong()
        return 0xFF000000L or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}
