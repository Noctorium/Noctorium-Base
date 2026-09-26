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
     * The panels have to stay panels.
     *
     * Translucency below about a half stops reading as a surface and starts reading as a hole -- which
     * is not a matter of taste here but of what can be blurred: Compose can pre-blur the wash behind
     * everything, and cannot blur whatever happens to sit behind one particular panel. The first attempt
     * at these numbers put a legible album title through the middle of a dialog.
     */
    @Test
    fun `glass is translucent without being a window`() {
        assertTrue(Glass.PANEL_ALPHA in .7f..0.95f)
        assertTrue(Glass.CARD_ALPHA in .6f..Glass.PANEL_ALPHA, "a card sits on a panel, so it is never the more solid of the two")
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
