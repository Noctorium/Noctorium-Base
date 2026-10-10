package app.noctorium.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings search box: what it finds, in what order, and what it forgives. */
class SettingsSearchTest {
    private val entries = listOf(
        SettingEntry("theme", "Theme", "Look", "The colours everything is drawn in.", listOf("dark mode", "light mode", "colours")),
        SettingEntry("booster", "Volume booster", "Listening › Sound", "Louder than full, from 100% to 1000%, behind a limiter.", listOf("louder", "amplify")),
        SettingEntry("volume-step", "Volume step", "Listening › Sound", "How far the volume keys move it."),
        SettingEntry("equaliser", "Equaliser", "Listening › Sound", "Ten bands and a preamp.", listOf("equalizer", "eq", "bass")),
        SettingEntry("shortcuts", "Shortcuts", "This device", "Keys that do things, in Noctorium and from anywhere.", listOf("hotkeys", "keyboard")),
        SettingEntry("player-bar", "Player bar", "Look", "Where the controls sit and how they are laid out."),
        SettingEntry("cafe", "Café mode", "Look", "Quieter colours for a crowded room."),
    )

    private fun ids(query: String) = SettingsSearch.search(query, entries).map { it.entry.id }

    @Test
    fun `a name that begins with what was typed comes first`() {
        assertEquals(listOf("booster", "volume-step"), ids("vol"))
        assertEquals("booster", ids("volume b").first())
        assertEquals(SettingMatchKind.TITLE_START, SettingsSearch.search("vol", entries).first().kind)
    }

    @Test
    fun `then a name with the word in it, then another word for it, then the description`() {
        val found = SettingsSearch.search("boost", entries)
        assertEquals(listOf("booster"), found.map { it.entry.id })
        assertEquals(SettingMatchKind.TITLE_WORD, found.single().kind)

        val louder = SettingsSearch.search("louder", entries)
        assertEquals("booster", louder.first().entry.id)
        assertEquals(SettingMatchKind.KEYWORD, louder.first().kind)
        assertEquals("louder", louder.first().keyword)

        val colours = SettingsSearch.search("colours", entries)
        assertEquals(listOf("theme", "cafe"), colours.map { it.entry.id }, "the keyword first, then the description")
        assertEquals(listOf(SettingMatchKind.KEYWORD, SettingMatchKind.DESCRIPTION), colours.map { it.kind })
    }

    @Test
    fun `the other words people use find the setting they mean`() {
        assertEquals("theme", ids("dark mode").first())
        assertEquals("shortcuts", ids("hotkeys").first())
        assertEquals("equaliser", ids("equalizer").first())
        assertEquals("equaliser", ids("eq").first())
    }

    @Test
    fun `case and accents do not matter`() {
        assertEquals(listOf("cafe"), ids("CAFE"))
        assertEquals(listOf("cafe"), ids("café"))
        assertEquals(listOf("theme"), ids("THEME"))
    }

    @Test
    fun `one typo is forgiven in a long word, and ranks after the right spelling`() {
        val volumn = SettingsSearch.search("volumn", entries)
        assertEquals(listOf("booster", "volume-step"), volumn.map { it.entry.id })
        assertTrue(volumn.all { it.forgiven })
        assertEquals("shortcuts", ids("shortcts").first(), "a letter missing")
        assertEquals("shortcuts", ids("shrotcuts").first(), "two letters swapped")
        assertEquals("equaliser", ids("equalisre").first())
    }

    @Test
    fun `a short word has to be right`() {
        assertTrue(ids("thme").isEmpty(), "four letters, one wrong, is another word")
        assertTrue(ids("bsas").isEmpty())
    }

    @Test
    fun `two typos are not one`() {
        assertTrue(ids("vloumn").isEmpty())
    }

    @Test
    fun `a short word does not find every description it happens to start`() {
        assertTrue(ids("fu").isEmpty(), "\"full\" is in a description, and two letters are not enough to look there")
        assertEquals(listOf("booster"), ids("ful"))
        assertEquals(listOf("theme", "player-bar", "cafe"), ids("look"), "a section's name finds what is in it, last of all")
        assertTrue(SettingsSearch.search("look", entries).all { it.kind == SettingMatchKind.SECTION })
    }

    @Test
    fun `nothing typed finds nothing`() {
        assertTrue(ids("").isEmpty())
        assertTrue(ids("  !! ").isEmpty())
    }

    @Test
    fun `one edit, exactly`() {
        assertTrue(SettingsSearch.withinOneEdit("volume", "volumn"))
        assertTrue(SettingsSearch.withinOneEdit("short", "shrot"))
        assertTrue(SettingsSearch.withinOneEdit("colour", "color"))
        assertFalse(SettingsSearch.withinOneEdit("colour", "colr"))
        assertFalse(SettingsSearch.withinOneEdit("abcd", "badc"))
    }

    @Test
    fun `words are what is left of the text without its punctuation`() {
        assertEquals(listOf("dont", "stop", "two", "tone"), SettingsSearch.words("Don't stop — Two-tone!"))
        assertEquals("egaliseur", SettingsSearch.normalise("Égaliseur"))
    }
}
