package app.noctorium.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Windows wants a COLORREF, which is 0x00BBGGRR — red and blue the other way round from the 0xAARRGGBB
 * used everywhere else. Getting it backwards is easy and quiet: most colours still look like a colour, so
 * a swapped pair reads as a design choice rather than a bug. These are asymmetric on purpose.
 */
class WindowChromeTest {
    @Test
    fun `red and blue swap places, green stays put`() {
        // 0xAARRGGBB -> 0x00BBGGRR
        assertEquals(0x0000_00FF, WindowChrome.colorRef(0xFFFF_0000.toInt()), "red should land in the low byte")
        assertEquals(0x00FF_0000, WindowChrome.colorRef(0xFF00_00FF.toInt()), "blue should land in the high byte")
        assertEquals(0x0000_FF00, WindowChrome.colorRef(0xFF00_FF00.toInt()), "green should not move")
    }

    @Test
    fun `a colour with three different channels keeps each one`() {
        // 0x123456 -> B=0x56, G=0x34, R=0x12
        assertEquals(0x0056_3412, WindowChrome.colorRef(0xFF12_3456.toInt()))
    }

    @Test
    fun `the alpha channel is dropped, since a title bar has none`() {
        assertEquals(WindowChrome.colorRef(0xFF12_3456.toInt()), WindowChrome.colorRef(0x0012_3456))
        // The top byte must be clear, or Windows reads it as one of its sentinel values.
        assertEquals(0, WindowChrome.colorRef(0xFFAB_CDEF.toInt()) ushr 24)
    }

    @Test
    fun `pure black stays pure black, which is the whole point of the amoled depth`() {
        assertEquals(0, WindowChrome.colorRef(0xFF00_0000.toInt()))
    }

    @Test
    fun `white survives the round trip`() {
        assertEquals(0x00FF_FFFF, WindowChrome.colorRef(0xFFFF_FFFF.toInt()))
    }

    /** The soft-dark depth is a near-neutral grey; a swap there would be invisible without this. */
    @Test
    fun `an almost neutral colour still has its channels checked`() {
        assertEquals(0x001A_1512, WindowChrome.colorRef(0xFF12_151A.toInt()))
    }

    @Test
    fun `asking to colour a window that does not exist is harmless`() {
        // The call happens as the interface composes, which can be before the window is realised.
        WindowChrome.applyDarkTitleBar(null, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
    }
}
