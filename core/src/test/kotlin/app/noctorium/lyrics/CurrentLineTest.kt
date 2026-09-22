package app.noctorium.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurrentLineTest {

    private val synced = LyricsResult(
        provider = LyricsProviderId.LRCLIB,
        lines = listOf(LyricLine("First", 1_000), LyricLine("Second", 5_000), LyricLine("  ", 9_000), LyricLine("Last", 12_000)),
        synced = true,
    )

    private fun state(vararg outcomes: LyricsProviderOutcome, selected: LyricsProviderId? = null) =
        LyricsUiState(trackKey = "x", outcomes = outcomes.toList(), selectedProvider = selected)

    @Test
    fun `the line being sung, and nothing before the first one`() {
        val state = state(LyricsProviderOutcome(LyricsProviderId.LRCLIB, LyricsProviderStatus.FOUND, synced))
        assertNull(state.currentLine(500))
        assertEquals("First", state.currentLine(1_000))
        assertEquals("Second", state.currentLine(8_999))
        assertEquals("Last", state.currentLine(60_000))
    }

    @Test
    fun `a blank line shows nothing rather than an empty bar`() {
        val state = state(LyricsProviderOutcome(LyricsProviderId.LRCLIB, LyricsProviderStatus.FOUND, synced))
        assertNull(state.currentLine(10_000))
    }

    @Test
    fun `unsynced lyrics are not a line to follow`() {
        val plain = synced.copy(synced = false)
        val state = state(LyricsProviderOutcome(LyricsProviderId.LRCLIB, LyricsProviderStatus.FOUND, plain))
        assertNull(state.currentLine(5_000))
    }

    @Test
    fun `the provider the listener picked wins over the first that answered`() {
        val other = synced.copy(provider = LyricsProviderId.SYNCLRC, lines = listOf(LyricLine("Other", 0)))
        val state = state(
            LyricsProviderOutcome(LyricsProviderId.LRCLIB, LyricsProviderStatus.FOUND, synced),
            LyricsProviderOutcome(LyricsProviderId.SYNCLRC, LyricsProviderStatus.FOUND, other),
            selected = LyricsProviderId.SYNCLRC,
        )
        assertEquals("Other", state.currentLine(5_000))
    }
}
