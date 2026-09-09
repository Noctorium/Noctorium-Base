package app.spiceity.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tags written while encoding.
 *
 * mpv takes them as one string of comma-separated pairs, which means a comma inside a value is read as the
 * start of another tag. Artists really do contain them — one credit can read "Vijay Prakash, Krish, Devan"
 * — so left alone the artist would be truncated and the rest turned into tags nobody asked for.
 */
class AudioConverterTest {
    @Test
    fun `a title and artist become a pair of tags`() {
        assertEquals(
            "--oset-metadata=title=Antarctica,artist=SAIBOTAJE",
            AudioConverter.metadataFor("Antarctica", "SAIBOTAJE"),
        )
    }

    @Test
    fun `a comma inside a value cannot start another tag`() {
        val written = AudioConverter.metadataFor("Antarctica", "Vijay Prakash, Krish, Devan")!!

        // Exactly two pairs, however many separators the names contain.
        assertEquals(2, written.removePrefix("--oset-metadata=").split(",").size)
        assertTrue(written.contains("Vijay Prakash; Krish; Devan"), "the credit was mangled: $written")
    }

    /** An equals sign would be read as the boundary between a tag's name and its value. */
    @Test
    fun `an equals sign in a value cannot break the pairing`() {
        val written = AudioConverter.metadataFor("2 + 2 = 5", "Someone")!!

        val pairs = written.removePrefix("--oset-metadata=").split(",")
        assertEquals(2, pairs.size)
        // One separator per pair and no more, so neither value can be read as naming a tag of its own.
        pairs.forEach { pair -> assertEquals(1, pair.count { it == '=' }, "a value carried a separator: $pair") }
        assertTrue(pairs.first().startsWith("title="))
        assertTrue(pairs.last() == "artist=Someone")
    }

    @Test
    fun `what is missing is simply left out`() {
        assertEquals("--oset-metadata=title=Antarctica", AudioConverter.metadataFor("Antarctica", "   "))
        assertEquals("--oset-metadata=artist=SAIBOTAJE", AudioConverter.metadataFor("", "SAIBOTAJE"))
        // Nothing worth writing means no argument at all, rather than an empty one mpv would refuse.
        assertNull(AudioConverter.metadataFor("", ""))
        assertNull(AudioConverter.metadataFor("  ", "  "))
    }

    @Test
    fun `an absurdly long value is cut rather than passed on whole`() {
        val written = AudioConverter.metadataFor("x".repeat(1_000), "y".repeat(1_000))!!

        assertTrue(written.length < 500, "the argument is ${written.length} characters")
    }

    @Test
    fun `values are trimmed, since a stray space would be written into the tag`() {
        assertEquals(
            "--oset-metadata=title=Antarctica,artist=SAIBOTAJE",
            AudioConverter.metadataFor("  Antarctica ", " SAIBOTAJE  "),
        )
    }

    /** mpv is what makes MP3 possible without ffmpeg, so its absence has to be answerable. */
    @Test
    fun `without a player there is no way to make an mp3`() {
        assertFalse(AudioConverter(mpv = { null }).canMakeMp3())
        assertTrue(AudioConverter(mpv = { java.nio.file.Path.of("mpv.exe") }).canMakeMp3())
    }
}
