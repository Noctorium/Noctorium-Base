package app.noctorium.widgets

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pictures of the stream widgets that the desktop's Settings shows: one for every look, where the look says it is,
 * a real PNG, and small. They are made by StreamWidgetLooks in core's tests, from the real pages.
 */
class StreamWidgetPreviewsTest {
    @Test
    fun `every look has its picture, and each is a small PNG of the look's own shape`() {
        for (theme in StreamWidgetTheme.entries) {
            val bytes = assertNotNull(javaClass.getResourceAsStream(theme.previewResource), "no preview for ${theme.id}").use { it.readBytes() }
            val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
            assertTrue(bytes.copyOfRange(0, 8).contentEquals(signature), "${theme.id}'s preview is not a PNG")
            assertTrue(bytes.size in 2_000..100_000, "${theme.id}'s preview is ${bytes.size / 1024} KB")

            // The first chunk of every PNG says how wide and tall it is. Each preview is the look with a margin of
            // scene round it, so it is a little wider and taller than half the look, in roughly the same shape.
            fun int(at: Int) = (0..3).fold(0) { value, i -> (value shl 8) or (bytes[at + i].toInt() and 0xFF) }
            val width = int(16)
            val height = int(20)
            assertTrue(width >= theme.width / 2 && height >= theme.height / 2, "${theme.id}'s preview is ${width}x$height")
            val shape = width.toDouble() / height
            val looks = theme.width.toDouble() / theme.height
            assertTrue(shape in looks * .5..looks * 1.5, "${theme.id}'s preview is ${width}x$height for a look of ${theme.width}x${theme.height}")
        }
    }
}
