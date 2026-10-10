package app.noctorium.core

import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bangs in a search box: `!ytm daft punk`, `daft punk !sc`, and everything that only looks like one. */
class SearchBangsTest {

    @Test
    fun `a bang at the start sends the search to its service`() {
        val parsed = SearchBangs.parse("!ytm daft punk")
        assertEquals("daft punk", parsed.query)
        assertEquals(SearchMode.YOUTUBE_MUSIC, parsed.mode)
        assertEquals("!ytm", parsed.bang?.typed)
    }

    @Test
    fun `a bang at the end does too`() {
        val parsed = SearchBangs.parse("daft punk !sc")
        assertEquals("daft punk", parsed.query)
        assertEquals(SearchMode.SOUNDCLOUD, parsed.mode)
    }

    @Test
    fun `every bang, in any case`() {
        mapOf(
            "!ytm" to SearchMode.YOUTUBE_MUSIC, "!yt" to SearchMode.YOUTUBE_MUSIC, "!YouTube" to SearchMode.YOUTUBE_MUSIC,
            "!ytv" to SearchMode.YOUTUBE_VIDEO, "!SC" to SearchMode.SOUNDCLOUD, "!soundcloud" to SearchMode.SOUNDCLOUD,
            "!sp" to SearchMode.SPOTIFY, "!Spotify" to SearchMode.SPOTIFY, "!bc" to SearchMode.BANDCAMP,
            "!bandcamp" to SearchMode.BANDCAMP, "!vk" to SearchMode.VK, "!VK" to SearchMode.VK, "!all" to SearchMode.HYBRID,
        ).forEach { (bang, mode) ->
            assertEquals(mode, SearchBangs.parse("$bang around the world").mode, bang)
            assertEquals(mode, SearchBangs.parse("around the world $bang").mode, bang)
        }
    }

    @Test
    fun `an unknown bang is left in the words`() {
        val parsed = SearchBangs.parse("!xyz daft punk")
        assertNull(parsed.bang)
        assertEquals("!xyz daft punk", parsed.query)
        assertEquals("daft punk !!!", SearchBangs.parse("daft punk !!!").query)
        assertNull(SearchBangs.parse("!").bang)
    }

    @Test
    fun `only the start and the end count`() {
        val parsed = SearchBangs.parse("daft !sc punk")
        assertNull(parsed.bang)
        assertEquals("daft !sc punk", parsed.query)
    }

    @Test
    fun `the bang at the start is taken over one at the end`() {
        val parsed = SearchBangs.parse("!sc daft punk !bc")
        assertEquals(SearchMode.SOUNDCLOUD, parsed.mode)
        assertEquals("daft punk !bc", parsed.query)
    }

    @Test
    fun `a bang alone chooses the service and searches for nothing yet`() {
        val parsed = SearchBangs.parse("!sp ")
        assertEquals(SearchMode.SPOTIFY, parsed.mode)
        assertEquals("", parsed.query)
    }

    @Test
    fun `text without a bang is left exactly as it was`() {
        assertEquals("  daft punk ", SearchBangs.parse("  daft punk ").query)
    }

    @Test
    fun `the bang a search box suggests is the shortest`() {
        assertEquals("!sc", SearchBangs.bangFor(SearchMode.SOUNDCLOUD))
        assertEquals("!yt", SearchBangs.bangFor(SearchMode.YOUTUBE_MUSIC))
        assertEquals("!all", SearchBangs.bangFor(SearchMode.HYBRID))
        SearchMode.entries.forEach { mode -> assertEquals(mode, SearchBangs.parse(SearchBangs.bangFor(mode) + " x").mode, mode.name) }
    }

    /** Every search box searches through AppState, so every search box understands bangs. */
    @Test
    fun `AppState's search asks only the bang's service, without the bang`() {
        val providers = ProviderType.entries.filter { it != ProviderType.LOCAL }.map(AppStateHarness::Provider)
        AppStateHarness.state(providers = providers).use { state ->
            state.search("daft punk !sc")
            waitFor("the results") { !state.ui.value.searchLoading && state.ui.value.searchResults.tracks.isNotEmpty() }
            val soundCloud = providers.first { it.type == ProviderType.SOUNDCLOUD }
            assertEquals(listOf("daft punk"), soundCloud.asked)
            assertTrue(providers.filter { it !== soundCloud }.all { it.asked.isEmpty() }, "only SoundCloud was asked")
            assertEquals(SearchMode.SOUNDCLOUD, state.ui.value.searchBang?.mode)
            assertEquals(SearchMode.SOUNDCLOUD, state.ui.value.activeSearchMode)
            assertEquals(SearchMode.HYBRID, state.ui.value.searchMode, "the search page's own choice is untouched")
            assertEquals("daft punk !sc", state.ui.value.searchQuery, "the box keeps what was typed")
        }
    }

    @Test
    fun `without a bang the search goes where the page says`() {
        val providers = ProviderType.entries.filter { it != ProviderType.LOCAL }.map(AppStateHarness::Provider)
        AppStateHarness.state(providers = providers).use { state ->
            state.setSearchMode(SearchMode.BANDCAMP)
            state.search("!nope tycho")
            waitFor("the results") { !state.ui.value.searchLoading && state.ui.value.searchResults.tracks.isNotEmpty() }
            assertEquals(listOf("!nope tycho"), providers.first { it.type == ProviderType.BANDCAMP }.asked)
            assertNull(state.ui.value.searchBang)
        }
    }
}
