package app.spiceity.downloads

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Naming a file somebody is going to see.
 *
 * Titles come from whoever uploaded the track, so they contain anything at all, and Windows refuses more
 * than people expect: nine punctuation characters, a handful of reserved words whatever the extension, and
 * — quietly — any name ending in a dot or a space, which it accepts and then cannot find again. Every one
 * of those fails at the moment of writing, long after the download, and reads as the save being broken.
 */
class MusicExportTest {
    private val folder: Path = Files.createTempDirectory("spiceity-export")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun track(title: String, artist: String = "SAIBOTAJE") = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = "abc",
        title = title,
        artists = listOf(Artist("a", artist, ProviderType.YOUTUBE_MUSIC)),
        sourceUrl = "https://music.youtube.com/watch?v=abc",
    )

    @Test
    fun `a file is named artist then title, so one artist stays together on a phone`() {
        assertEquals(
            "SAIBOTAJE - SOUL EATER III.mp3",
            MusicExport.fileNameFor(track("SOUL EATER III"), ExportFormat.MP3),
        )
    }

    @Test
    fun `the extension follows the format`() {
        assertEquals("SAIBOTAJE - x.mp3", MusicExport.fileNameFor(track("x"), ExportFormat.MP3))
        assertEquals("SAIBOTAJE - x.m4a", MusicExport.fileNameFor(track("x"), ExportFormat.ORIGINAL))
    }

    /** The one that would actually happen: a slash in a band's name reads as a folder that is not there. */
    @Test
    fun `a slash in a name does not become a folder`() {
        val name = MusicExport.fileNameFor(track("Highway to Hell", artist = "AC/DC"), ExportFormat.MP3)

        assertFalse(name.contains('/'), "a slash survived: $name")
        assertFalse(name.contains('\\'))
        assertEquals("AC-DC - Highway to Hell.mp3", name)
    }

    @Test
    fun `every character Windows refuses is replaced`() {
        val hostile = MusicExport.fileNameFor(track("""a<b>c:d"e/f\g|h?i*j"""), ExportFormat.MP3)

        listOf('<', '>', ':', '"', '/', '\\', '|', '?', '*').forEach { bad ->
            assertFalse(hostile.contains(bad), "$bad survived in $hostile")
        }
    }

    /**
     * Windows drops a trailing dot or space without complaining, so a file saved as "Intro." is written as
     * "Intro" and then cannot be found under the name it was given.
     */
    @Test
    fun `a name cannot end in a dot or a space`() {
        assertEquals("SAIBOTAJE - Intro.mp3", MusicExport.fileNameFor(track("Intro."), ExportFormat.MP3))
        assertEquals("SAIBOTAJE - Intro.mp3", MusicExport.fileNameFor(track("Intro   "), ExportFormat.MP3))
        assertEquals("SAIBOTAJE - Intro.mp3", MusicExport.fileNameFor(track("Intro ..."), ExportFormat.MP3))
    }

    /** A track genuinely called CON cannot be written under that name, and the error blames permissions. */
    @Test
    fun `a reserved name is made writable`() {
        assertEquals("CON track.mp3", MusicExport.safeName("CON") + ".mp3")
        // The case as typed is kept; only the collision with the reserved word needs settling.
        assertEquals("nul track", MusicExport.safeName("nul"))
        // Only reserved on its own; as part of a longer name it is fine.
        assertEquals("CONCRETE", MusicExport.safeName("CONCRETE"))
    }

    @Test
    fun `a name with nothing usable left still produces a file`() {
        assertEquals("Unknown track", MusicExport.safeName("///"))
        assertEquals("Unknown track", MusicExport.safeName("   "))
        assertEquals("Unknown track.mp3", MusicExport.fileNameFor(track("", artist = ""), ExportFormat.MP3))
    }

    @Test
    fun `a missing artist or title does not leave a dangling dash`() {
        assertEquals("SOUL EATER III.mp3", MusicExport.fileNameFor(track("SOUL EATER III", artist = ""), ExportFormat.MP3))
        assertEquals("SAIBOTAJE.mp3", MusicExport.fileNameFor(track("", artist = "SAIBOTAJE"), ExportFormat.MP3))
    }

    /** A title that already leads with the artist would otherwise read "X - X - Song". */
    @Test
    fun `an artist already in the title is not repeated`() {
        assertEquals(
            "LXAES - OWN PARADISE.mp3",
            MusicExport.fileNameFor(track("LXAES - OWN PARADISE", artist = "LXAES"), ExportFormat.MP3),
        )
    }

    @Test
    fun `a very long title is cut to something writable`() {
        val name = MusicExport.fileNameFor(track("x".repeat(400)), ExportFormat.MP3)

        assertTrue(name.length < 140, "the name is ${name.length} characters")
        assertTrue(name.endsWith(".mp3"))
        assertFalse(name.contains(" .mp3"), "cutting left a space before the extension")
    }

    @Test
    fun `unicode in a title is kept, since the file system takes it`() {
        assertEquals(
            "Tomi Marfă - Antarctica.mp3",
            MusicExport.fileNameFor(track("Antarctica", artist = "Tomi Marfă"), ExportFormat.MP3),
        )
    }

    /**
     * Two different tracks can share an artist and a title — a single and its album version, or the same
     * song from both services. Overwriting silently loses whichever was saved first.
     */
    @Test
    fun `an existing file is not overwritten`() {
        Files.writeString(folder.resolve("Artist - Song.mp3"), "first")

        val next = MusicExport.availableName(folder, "Artist - Song.mp3")

        assertEquals("Artist - Song (2).mp3", next)
        Files.writeString(folder.resolve(next), "second")
        assertEquals("Artist - Song (3).mp3", MusicExport.availableName(folder, "Artist - Song.mp3"))
        assertEquals("first", Files.readString(folder.resolve("Artist - Song.mp3")))
    }

    @Test
    fun `a free name is used as it is`() {
        assertEquals("Artist - Song.mp3", MusicExport.availableName(folder, "Artist - Song.mp3"))
    }

    @Test
    fun `the number goes before the extension, not after it`() {
        Files.writeString(folder.resolve("x.m4a"), "one")

        val next = MusicExport.availableName(folder, "x.m4a")

        assertTrue(next.endsWith(".m4a"), "the extension was lost: $next")
        assertEquals("x (2).m4a", next)
    }

    /** The desktop is the point of the feature: a file somewhere it can be seen and moved. */
    @Test
    fun `the default folder is one that exists`() {
        val chosen = MusicExport.defaultFolder()

        assertTrue(chosen != null, "no folder could be found to save into")
        assertTrue(Files.isDirectory(chosen!!), "$chosen is not a folder")
    }

    @Test
    fun `both formats describe themselves for the interface`() {
        ExportFormat.entries.forEach { format ->
            assertTrue(format.displayName.isNotBlank())
            assertTrue(format.extension.isNotBlank())
            assertFalse(format.extension.startsWith("."), "the extension should not carry its own dot")
        }
    }
}
