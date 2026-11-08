package app.noctorium.settings

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ShadowPictureTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9)

    @Test
    fun `a picture is known by its first bytes, not its name`() {
        assertEquals("png", ShadowPicture.kind(png))
        assertEquals("jpg", ShadowPicture.kind(jpg))
        assertEquals("webp", ShadowPicture.kind("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("gif", ShadowPicture.kind("GIF89a".toByteArray()))
        assertNull(ShadowPicture.kind("not a picture.png".toByteArray()), "a text file called .png is no picture")
        assertNull(ShadowPicture.kind(ByteArray(0)))
    }

    @Test
    fun `a picture is kept once, replacing the last, and nothing else is kept`() {
        val folder = Files.createTempDirectory("noctorium-shadow-picture")
        try {
            val first = assertNotNull(ShadowPicture.keep(png, folder))
            assertEquals("shadow-picture.png", first.fileName.toString())
            assertContentEquals(png, Files.readAllBytes(first))

            val second = assertNotNull(ShadowPicture.keep(jpg, folder))
            assertEquals("shadow-picture.jpg", second.fileName.toString())
            assertFalse(Files.exists(first), "the old picture went")

            assertNull(ShadowPicture.keep("<html>".toByteArray(), folder))
            assertNull(ShadowPicture.keep(png + ByteArray(ShadowPicture.LARGEST), folder), "too big")
            assertContentEquals(jpg, Files.readAllBytes(second), "a refused file leaves the kept picture be")

            ShadowPicture.forget(folder)
            assertFalse(Files.exists(second))
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
