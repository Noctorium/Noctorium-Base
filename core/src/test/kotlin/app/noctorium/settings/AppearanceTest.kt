package app.noctorium.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The appearance choices, and specifically the ones whose defaults are load-bearing.
 *
 * Two of these settings multiply something the players already drew. That makes their neutral value a
 * fact the rest of the application depends on rather than a number somebody picked: change Soft away
 * from 1 and every corner in Noctorium moves for people who never chose anything.
 */
class AppearanceTest {

    @Test
    fun `the defaults are the application as it was before any of this existed`() {
        val fresh = NoctoriumPreferences()
        assertEquals(SurfaceStyle.SOLID, fresh.surfaceStyle)
        assertEquals(CornerStyle.SOFT, fresh.cornerStyle)
        assertEquals(TextSize.DEFAULT, fresh.textSize)
    }

    /** Both neutral values multiply, so both have to be exactly one or they are not neutral. */
    @Test
    fun `the neutral corner and the neutral text size change nothing`() {
        assertEquals(1f, CornerStyle.SOFT.scale)
        assertEquals(1f, TextSize.DEFAULT.scale)
    }

    @Test
    fun `sharp is square and round is rounder than soft`() {
        assertEquals(0f, CornerStyle.SHARP.scale)
        assertTrue(CornerStyle.ROUND.scale > CornerStyle.SOFT.scale)
    }

    @Test
    fun `the text sizes are in order and none of them is illegible`() {
        val scales = TextSize.entries.map { it.scale }
        assertEquals(scales.sorted(), scales, "the chips read smallest to largest, so the numbers should too")
        assertTrue(scales.all { it in .75f..1.5f }, "a scale outside this is a layout nobody tested")
    }

    @Test
    fun `only glass is glass`() {
        assertTrue(SurfaceStyle.GLASS.isGlass)
        assertFalse(SurfaceStyle.SOLID.isGlass)
    }

    /**
     * The lens bends at the rim and leaves the middle alone.
     *
     * If the bend reached further than the rim is wide, the flat middle of the pane would be distorted
     * too, and the mini player's title would swim. And a pane frosted heavily is the old frosted glass
     * this replaced: liquid glass is mostly clear, and what is behind it stays recognisable.
     */
    @Test
    fun `the glass is a lens with a clear middle`() {
        assertTrue(Glass.REFRACTION_DP in 1..Glass.BEZEL_DP, "a bend wider than the rim reaches into the middle")
        assertTrue(Glass.FROST_DP in 1..12, "past this it is frosted glass, not liquid glass")
        assertTrue(Glass.DISPERSION in 0f..0.2f, "past this the rim reads as a broken monitor")
    }

    /**
     * The shader is compiled at run time on both platforms, so a typo in it is a crash on a phone rather
     * than a failed build. These are the names the players set, and a rename on one side has to be one
     * on the other.
     */
    @Test
    fun `the lens declares every uniform the players set`() {
        listOf("content", "origin", "size", "radius", "bezel", "strength", "dispersion").forEach { name ->
            assertTrue(Regex("""uniform\s+\w+\s+$name\s*;""").containsMatchIn(Glass.LENS_SHADER), "no uniform called $name")
        }
        assertTrue("half4 main(float2" in Glass.LENS_SHADER)
    }

    /** Material's slider draws itself; everything else goes through the players' own canvas. */
    @Test
    fun `every seek bar except Material is drawn`() {
        assertFalse(ProgressBarStyle.MATERIAL.isDrawn)
        ProgressBarStyle.entries.filter { it != ProgressBarStyle.MATERIAL }.forEach {
            assertTrue(it.isDrawn, "$it has no drawing and Material will not draw it either")
        }
    }

    /**
     * The segment pitch is a size, not a count, and the difference is the bug it was written to fix.
     *
     * A fixed count makes the block a different size on every screen -- forty across a phone came out
     * as a row of dots. Keep this in a range where a block is a block and a bar the width of a phone
     * still has enough of them to read as a progress bar.
     */
    @Test
    fun `a segment is a sensible size rather than a count`() {
        assertTrue(SeekBar.SEGMENT_PITCH_DP in 10..24)
        assertTrue(SeekBar.SEGMENT_GAP_RATIO in .1f..0.5f, "no gap is one solid bar; half of it is a row of specks")
    }

    @Test
    fun `the wave is a wave and not a vibration`() {
        assertTrue(SeekBar.WAVE_LENGTH_DP > SeekBar.WAVE_AMPLITUDE_DP * 2, "taller than it is long is a zigzag")
        assertTrue(SeekBar.WAVE_SECONDS_PER_CYCLE >= 1f, "faster than this is a seek bar demanding attention")
    }
}
