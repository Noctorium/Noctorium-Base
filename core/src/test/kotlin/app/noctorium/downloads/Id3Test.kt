package app.noctorium.downloads

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MP3 tag, read back byte by byte. The things that matter: the audio after the tag is untouched, an old
 * tag is replaced rather than stacked in front of, and the lengths are right, since a player trusts them to
 * find where the music starts.
 */
class Id3Test {

    /** Two MPEG frame headers' worth of something that is plainly not a tag. */
    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 1, 2, 3, 4, 5, 6, 7, 8)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 9, 9, 9)

    @Test
    fun `a tag is written in front of the audio and the audio is left as it was`() {
        val file = Files.createTempFile("id3", ".mp3")
        try {
            Files.write(file, audio)
            Id3.retag(file, AudioTags("Archangel", "Burial", "Untrue", jpeg))
            val written = Files.readAllBytes(file)
            val tagLength = Id3.existingTagLength(written)
            assertTrue(tagLength > 10, "no tag at the front")
            assertContentEquals(audio, written.copyOfRange(tagLength, written.size), "the audio changed")
            assertEquals(3, written[3].toInt(), "not version 2.3")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** What mpv leaves: its own 2.4 tag with a title and an artist. Replaced, not kept under the new one. */
    @Test
    fun `an existing tag is replaced, not stacked in front of`() {
        val file = Files.createTempFile("id3", ".mp3")
        try {
            val old = Id3.tagFor(AudioTags("old title", "old artist")).also { it[3] = 4 }
            Files.write(file, old + audio)
            Id3.retag(file, AudioTags("New", "Artist"))
            val written = Files.readAllBytes(file)
            val tagLength = Id3.existingTagLength(written)
            assertContentEquals(audio, written.copyOfRange(tagLength, written.size))
            assertEquals(1, Regex("ID3").findAll(String(written, Charsets.ISO_8859_1)).count(), "two tags")
            assertEquals("New", textOf(written, "TIT2"), "the old title survived")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `the frames carry the text in UTF-16 and the cover as the front cover`() {
        val tag = Id3.tagFor(AudioTags("Ça plane pour moi", "Plastic Bertrand", null, jpeg))
        val text = String(tag, Charsets.ISO_8859_1)
        assertTrue("TIT2" in text && "TPE1" in text && "APIC" in text)
        assertTrue("TALB" !in text, "an album frame with nothing in it")
        assertEquals("Ça plane pour moi", textOf(tag, "TIT2"), "the accented title did not survive")
        assertEquals("Plastic Bertrand", textOf(tag, "TPE1"))
        val apic = text.indexOf("APIC")
        val frameLength = tag.copyOfRange(apic + 4, apic + 8).fold(0) { total, byte -> (total shl 8) or (byte.toInt() and 0xff) }
        // Encoding, "image/jpeg", its terminator, the picture type, the empty description, then the picture.
        assertEquals(1 + "image/jpeg".length + 1 + 1 + 1 + jpeg.size, frameLength)
        assertEquals(3, tag[apic + 10 + 1 + "image/jpeg".length + 1].toInt(), "not marked as the front cover")
    }

    @Test
    fun `the tag's own length is the length of everything after its header`() {
        val tag = Id3.tagFor(AudioTags("a", "b"))
        assertEquals(tag.size, Id3.existingTagLength(tag + audio))
    }

    @Test
    fun `only pictures a player can show are embedded`() {
        assertEquals("image/jpeg", Id3.pictureMime(jpeg))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0)
        assertEquals("image/png", Id3.pictureMime(png))
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
        assertNull(Id3.pictureMime(webp))
        assertTrue("APIC" !in String(Id3.tagFor(AudioTags("a", "b", cover = webp)), Charsets.ISO_8859_1))
    }

    @Test
    fun `a file with no tag, or one too short to have one, has none`() {
        assertEquals(0, Id3.existingTagLength(audio))
        assertEquals(0, Id3.existingTagLength(byteArrayOf('I'.code.toByte(), 'D'.code.toByte())))
    }
}

/** The value of one text frame, read the way a player reads it: find the name, take the length, decode. */
private fun textOf(tag: ByteArray, id: String): String? {
    val at = String(tag, Charsets.ISO_8859_1).indexOf(id).takeIf { it >= 0 } ?: return null
    val length = tag.copyOfRange(at + 4, at + 8).fold(0) { total, byte -> (total shl 8) or (byte.toInt() and 0xff) }
    val data = tag.copyOfRange(at + 10, at + 10 + length)
    check(data[0].toInt() == 1 && data[1] == 0xFF.toByte() && data[2] == 0xFE.toByte()) { "not UTF-16 with a byte order mark" }
    return String(data, 3, data.size - 3, Charsets.UTF_16LE).trimEnd('\u0000')
}
