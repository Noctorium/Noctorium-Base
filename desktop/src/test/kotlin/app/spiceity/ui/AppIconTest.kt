package app.spiceity.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The application's mark.
 *
 * Drawn rather than loaded, so it is worth checking that what comes out is actually an icon: a coloured
 * square with rounded corners and a legible letter, at every size Windows might ask for. A blank or
 * transparent image would still compile, still be set on the window, and simply show as nothing.
 */
class AppIconTest {
    @Test
    fun `every size Windows asks for is drawn at that size`() {
        AppIcon.SIZES.forEach { size ->
            val image = AppIcon.image(size)
            assertEquals(size, image.width, "the $size icon is the wrong width")
            assertEquals(size, image.height, "the $size icon is the wrong height")
        }
        // The shell wants the small ones; a set that stops short leaves them scaled down from a big one.
        assertTrue(16 in AppIcon.SIZES && 32 in AppIcon.SIZES && 256 in AppIcon.SIZES)
    }

    @Test
    fun `the middle is painted, so the icon is not simply blank`() {
        AppIcon.SIZES.forEach { size ->
            val centre = AppIcon.image(size).getRGB(size / 2, size / 2)
            assertEquals(255, centre ushr 24, "the $size icon is transparent in the middle")
        }
    }

    /** Square corners would sit oddly against the rounded mark inside the window. */
    @Test
    fun `the corners are rounded away`() {
        val image = AppIcon.image(64)

        listOf(0 to 0, 63 to 0, 0 to 63, 63 to 63).forEach { (x, y) ->
            assertEquals(0, image.getRGB(x, y) ushr 24, "the corner at $x,$y is not rounded")
        }
    }

    @Test
    fun `the mark carries the gradient the sidebar uses, not a flat fill`() {
        val image = AppIcon.image(64)
        // Sampled inside the shape but away from the letter, at opposite ends of the gradient.
        val topLeft = image.getRGB(10, 6)
        val bottomRight = image.getRGB(54, 58)

        assertTrue(topLeft != bottomRight, "the fill is flat; the gradient did not take")
        // Lavender at the start, deeper violet at the end: the start must be the lighter of the two.
        assertTrue(brightness(topLeft) > brightness(bottomRight), "the gradient runs the wrong way")
    }

    @Test
    fun `the letter is drawn, and in white`() {
        val image = AppIcon.image(64)
        var white = 0
        for (x in 0 until 64) {
            for (y in 0 until 64) {
                val pixel = image.getRGB(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 235 && g > 235 && b > 235) white += 1
            }
        }
        // Enough pixels to be a letter rather than a stray edge, and far from the whole square.
        assertTrue(white in 120..900, "found $white near-white pixels, which is not a letter")
    }

    /** The letter must be centred on its own outline, or it sits visibly high in the square. */
    @Test
    fun `the letter sits in the middle of the square`() {
        val image = AppIcon.image(128)
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (x in 0 until 128) {
            for (y in 0 until 128) {
                val pixel = image.getRGB(x, y)
                if (((pixel shr 16) and 0xFF) > 235 && ((pixel shr 8) and 0xFF) > 235 && (pixel and 0xFF) > 235) {
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        assertTrue(minY < maxY, "no letter was found at all")
        val gapAbove = minY
        val gapBelow = 127 - maxY
        assertTrue(
            kotlin.math.abs(gapAbove - gapBelow) <= 6,
            "the letter is off centre: $gapAbove above, $gapBelow below",
        )
    }

    @Test
    fun `the icon file is a real ico, with an entry for every size`() {
        val bytes = AppIcon.icoBytes()

        assertEquals(0, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        // Type 1 is an icon; 2 would be a cursor and Windows would refuse it.
        assertEquals(1, bytes[2].toInt())
        assertEquals(0, bytes[3].toInt())
        assertEquals(AppIcon.SIZES.size, bytes[4].toInt() or (bytes[5].toInt() shl 8))
    }

    @Test
    fun `256 is recorded as zero, which is how the format says 256`() {
        val bytes = AppIcon.icoBytes(listOf(256))

        // First entry begins at byte 6; its width and height are the first two bytes of it.
        assertEquals(0, bytes[6].toInt())
        assertEquals(0, bytes[7].toInt())
    }

    @Test
    fun `each entry points at data that is inside the file`() {
        val bytes = AppIcon.icoBytes()
        val count = bytes[4].toInt() or (bytes[5].toInt() shl 8)

        fun int(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

        repeat(count) { index ->
            val entry = 6 + index * 16
            val length = int(entry + 8)
            val offset = int(entry + 12)
            assertTrue(length > 0, "entry $index has no data")
            assertTrue(offset + length <= bytes.size, "entry $index points past the end of the file")
        }
    }

    /**
     * Everything but the largest is an uncompressed DIB.
     *
     * A file written entirely in PNG is legal and Explorer reads it, but the older imaging APIs do not:
     * one loaded through `System.Drawing` as a semi-transparent smear and silently dropped its 256 entry.
     * Whatever stamps the executable during packaging is one of those, so the encoding is worth pinning.
     */
    @Test
    fun `the small sizes are plain DIBs and only the largest is a PNG`() {
        val bytes = AppIcon.icoBytes()
        val count = bytes[4].toInt() or (bytes[5].toInt() shl 8)

        fun int(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

        repeat(count) { index ->
            val entry = 6 + index * 16
            val declared = bytes[entry].toInt() and 0xFF
            val offset = int(entry + 12)
            val isPng = bytes[offset] == 0x89.toByte() && bytes[offset + 1] == 'P'.code.toByte()

            if (declared == 0) {
                assertTrue(isPng, "the 256 entry should be a PNG")
            } else {
                assertTrue(!isPng, "the $declared entry should be a DIB, not a PNG")
                // A DIB begins with the length of its own header, which is always forty.
                assertEquals(40, int(offset), "the $declared entry has no DIB header")
                // And claims twice its height, counting the mask stacked beneath it.
                assertEquals(declared * 2, int(offset + 8), "the $declared entry's height is wrong")
            }
        }
    }

    /**
     * The icon shipped with the build is a file on disk, while the one in the running window is drawn.
     * If the two drift, the taskbar and the installed application stop looking like each other.
     */
    @Test
    fun `the committed icon file matches what the code draws`() {
        val committed = File("src/main/resources/spiceity.ico")

        assertTrue(committed.isFile, "src/main/resources/spiceity.ico is missing")
        assertContentEquals(
            AppIcon.icoBytes(),
            committed.readBytes(),
            "the committed icon is out of date; regenerate it from AppIcon.icoBytes()",
        )
    }

    private fun brightness(argb: Int): Int =
        ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)
}
