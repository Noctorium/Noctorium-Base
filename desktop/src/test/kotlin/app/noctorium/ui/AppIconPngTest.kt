package app.noctorium.ui

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Linux packages take a PNG where Windows takes an .ico, and it is generated from the same drawing.
 *
 * Guarded the same way as the .ico: a committed file that drifts from the code means the installed
 * application and the running window stop looking like each other, and nothing else would notice.
 */
class AppIconPngTest {

    private val committed = File("src/main/resources/noctorium.png")

    /** Exact only where the face that drew it exists; see the same note in AppIconTest. */
    @Test
    fun `the committed png matches what the code draws`() {
        assertTrue(committed.isFile, "src/main/resources/noctorium.png is missing")
        if (!AppIcon.hasPreferredFace) return
        assertContentEquals(
            AppIcon.pngBytes(),
            committed.readBytes(),
            "the committed png is out of date; regenerate it with -Dnoctorium.writeIcons=true",
        )
    }

    @Test
    fun `it is a real png, square, and the size the packagers are given`() {
        val image = ImageIO.read(committed)
        assertEquals(AppIcon.LINUX_ICON_SIZE, image.width)
        assertEquals(AppIcon.LINUX_ICON_SIZE, image.height)
        // A transparent middle would package and install and then show as nothing in the launcher.
        assertEquals(255, image.getRGB(image.width / 2, image.height / 2) ushr 24, "the icon is transparent")
    }
}
