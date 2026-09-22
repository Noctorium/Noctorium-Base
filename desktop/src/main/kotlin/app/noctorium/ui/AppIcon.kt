package app.noctorium.ui

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The application's own mark, drawn rather than loaded.
 *
 * It is the same rounded square, gradient and letter the sidebar shows, so the icon in the title bar, the
 * one on the taskbar and the one beside the name inside the window are recognisably one thing. Drawing it
 * means every size is rendered at that size instead of being scaled down from one bitmap, which is what
 * usually turns a 16-pixel icon into mud.
 */
object AppIcon {
    /** The sidebar's gradient, top-left to bottom-right. */
    private val START = Color(0xD8, 0xB4, 0xFE)
    private val END = Color(0x8B, 0x5C, 0xF6)

    /** The mark's letter: Noctorium's N, the same one the sidebar and the phone's launcher icon show. */
    private const val LETTER = "N"

    /** Proportions taken from the sidebar mark: a 12dp radius and 21sp letter on a 36dp square. */
    private const val CORNER_RATIO = 12f / 36f
    private const val LETTER_RATIO = 21f / 36f

    /** The sizes Windows picks between for the title bar, the task bar, Alt-Tab and the shell. */
    val SIZES = listOf(16, 20, 24, 32, 48, 64, 128, 256)

    fun image(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val radius = size * CORNER_RATIO
            g.paint = GradientPaint(0f, 0f, START, size.toFloat(), size.toFloat(), END)
            g.fill(RoundRectangle2D.Float(0f, 0f, size.toFloat(), size.toFloat(), radius * 2, radius * 2))

            g.color = Color.WHITE
            g.font = letterFont(size)
            // Centred on the glyph's own outline rather than on the font's line metrics: a font's ascent
            // and descent leave room for letters this one does not have, and centring on those sits the
            // letter visibly high in the square.
            val glyph = g.font.createGlyphVector(g.fontRenderContext, LETTER)
            val bounds = glyph.visualBounds
            g.drawString(
                LETTER,
                (size - bounds.width).toFloat() / 2f - bounds.x.toFloat(),
                (size - bounds.height).toFloat() / 2f - bounds.y.toFloat(),
            )
        } finally {
            g.dispose()
        }
        return image
    }

    /** Every size at once, for handing to a window that will choose between them itself. */
    fun images(): List<BufferedImage> = SIZES.map(::image)

    /** The face the committed icon files were drawn with. */
    const val LETTER_FACE = "Segoe UI Black"

    /**
     * Whether this machine has the face the icon is meant to be set in.
     *
     * The drawing deliberately falls back to the platform's own sans without it, so the very same code
     * produces different pixels on a machine that has the face and one that does not. That is right for
     * a window, and it is the reason the committed files can only be compared byte for byte on a machine
     * that could have drawn them.
     */
    val hasPreferredFace: Boolean
        get() = Font(LETTER_FACE, Font.BOLD, 12).family.equals(LETTER_FACE, ignoreCase = true)

    private fun letterFont(size: Int): Font {
        val points = (size * LETTER_RATIO).toInt().coerceAtLeast(6)
        // Segoe UI is the face the rest of Windows is set in; anywhere without it falls back to the
        // platform's own sans, which is the same choice the interface makes.
        val segoe = Font(LETTER_FACE, Font.BOLD, points)
        return if (segoe.family.equals(LETTER_FACE, ignoreCase = true)) {
            segoe
        } else {
            Font(Font.SANS_SERIF, Font.BOLD, points)
        }
    }

    /**
     * The mark as a Windows `.ico`, for the installer and the built executable.
     *
     * Sizes up to 128 go in as uncompressed DIBs and only 256 as PNG. The format has allowed PNG for every
     * size since Vista, and Explorer reads it — but the older imaging APIs do not, and a file written
     * entirely in PNG loaded as a semi-transparent smear through `System.Drawing` and lost its largest
     * entry altogether. Since whatever `jpackage` uses to stamp the executable is one of those older paths,
     * the conservative encoding is the one worth shipping.
     */
    fun icoBytes(sizes: List<Int> = SIZES): ByteArray {
        val entries = sizes.map { size -> size to if (size >= 256) pngEntry(size) else dibEntry(size) }
        val out = ByteArrayOutputStream()

        fun short(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        fun int(value: Int) {
            short(value and 0xFFFF)
            short((value shr 16) and 0xFFFF)
        }

        short(0) // reserved
        short(1) // 1 means an icon rather than a cursor
        short(entries.size)

        // Every directory entry is the same width, so the first image starts after all of them.
        var offset = 6 + entries.size * 16
        entries.forEach { (size, data) ->
            // 256 does not fit in a byte and is written as 0, which the format defines to mean 256.
            out.write(if (size >= 256) 0 else size)
            out.write(if (size >= 256) 0 else size)
            out.write(0) // a palette of no fixed size
            out.write(0) // reserved
            short(1) // colour planes
            short(32) // bits per pixel
            int(data.size)
            int(offset)
            offset += data.size
        }
        entries.forEach { (_, data) -> out.write(data) }
        return out.toByteArray()
    }

    /**
     * The mark as a PNG, which is what Linux packaging wants.
     *
     * dpkg and rpm both take a single square PNG rather than a container of sizes, so there is no .ico
     * equivalent to build here -- one image, large enough that a desktop environment can scale it down
     * for whichever slot it needs.
     */
    fun pngBytes(size: Int = LINUX_ICON_SIZE): ByteArray = pngEntry(size)

    /** Big enough for an application grid on a high-resolution display to scale from, and no bigger. */
    const val LINUX_ICON_SIZE = 512

    private fun pngEntry(size: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image(size), "png", it) }.toByteArray()

    /**
     * One image as the icon format's original DIB: a header, the pixels bottom-up in BGRA, then a
     * one-bit-per-pixel mask.
     *
     * The header claims twice the real height because it counts the mask as a second image stacked under
     * the first. The mask is left empty: with a real alpha channel present it is redundant, but a reader
     * that skips the alpha still expects to find it, and one that is missing shifts everything after it.
     */
    private fun dibEntry(size: Int): ByteArray {
        val image = image(size)
        val out = ByteArrayOutputStream()

        fun short(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        fun int(value: Int) {
            short(value and 0xFFFF)
            short((value shr 16) and 0xFFFF)
        }

        // Each mask row is padded out to a whole number of four-byte words.
        val maskStride = ((size + 31) / 32) * 4

        int(40) // the header's own length
        int(size)
        int(size * 2) // the image and its mask together
        short(1) // colour planes
        short(32) // bits per pixel
        int(0) // uncompressed
        int(size * size * 4 + maskStride * size)
        int(0) // horizontal resolution, unused
        int(0) // vertical resolution, unused
        int(0) // colours used
        int(0) // colours that matter

        // Bottom-up, which is how a DIB stores its rows.
        for (y in size - 1 downTo 0) {
            for (x in 0 until size) {
                val argb = image.getRGB(x, y)
                out.write(argb and 0xFF) // blue
                out.write((argb shr 8) and 0xFF) // green
                out.write((argb shr 16) and 0xFF) // red
                out.write((argb shr 24) and 0xFF) // alpha
            }
        }
        repeat(size * maskStride) { out.write(0) }
        return out.toByteArray()
    }
}
