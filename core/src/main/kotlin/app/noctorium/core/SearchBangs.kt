package app.noctorium.core

import java.util.Locale

/** A bang that chose where a search goes: the word as it was typed, "!sc", and the search it means. */
data class SearchBang(val typed: String, val mode: SearchMode)

/** What a search box's text asks for: the words to look for, and the bang that said where, if one did. */
data class BangQuery(val query: String, val bang: SearchBang?) {
    /** The search the bang chose, or null for the one the search page is set to. */
    val mode: SearchMode? get() = bang?.mode
}

/**
 * Bangs: a word starting with "!" that sends one search to one service -- `!ytm daft punk`, or `daft punk !sc`.
 *
 * At the start or at the end of what was typed, in any case. Only a bang this knows counts: anything else that
 * starts with "!" is left in the words and searched for like the rest of them, since a song can be called
 * "!!!" and a band can be called "!Action Pact!". A bang at the start is taken over one at the end. Every
 * search box on every player understands them, because [AppState.search] does.
 */
object SearchBangs {
    /** Every bang, without its "!", and the search it chooses. */
    val ALL: Map<String, SearchMode> = linkedMapOf(
        "ytm" to SearchMode.YOUTUBE_MUSIC,
        "yt" to SearchMode.YOUTUBE_MUSIC,
        "youtube" to SearchMode.YOUTUBE_MUSIC,
        "ytv" to SearchMode.YOUTUBE_VIDEO,
        "sc" to SearchMode.SOUNDCLOUD,
        "soundcloud" to SearchMode.SOUNDCLOUD,
        "sp" to SearchMode.SPOTIFY,
        "spotify" to SearchMode.SPOTIFY,
        "bc" to SearchMode.BANDCAMP,
        "bandcamp" to SearchMode.BANDCAMP,
        "vk" to SearchMode.VK,
        "dz" to SearchMode.DEEZER,
        "deezer" to SearchMode.DEEZER,
        "td" to SearchMode.TIDAL,
        "tidal" to SearchMode.TIDAL,
        "all" to SearchMode.HYBRID,
    )

    /** [text] read for a bang. Without one, the query is the text exactly as it was. */
    fun parse(text: String): BangQuery {
        val trimmed = text.trim()
        val first = trimmed.substringBefore(' ').substringBefore('\t')
        bangIn(first)?.let { bang -> return BangQuery(trimmed.removePrefix(first).trim(), bang) }
        val last = trimmed.substringAfterLast(' ').substringAfterLast('\t')
        bangIn(last)?.let { bang -> return BangQuery(trimmed.removeSuffix(last).trim(), bang) }
        return BangQuery(text, null)
    }

    /** The bang a search box can suggest for [mode]: the shortest that means it, "!sc" for SoundCloud. */
    fun bangFor(mode: SearchMode): String = "!" + ALL.filterValues { it == mode }.keys.minBy { it.length }

    private fun bangIn(word: String): SearchBang? {
        if (word.length < 2 || !word.startsWith("!")) return null
        val mode = ALL[word.substring(1).lowercase(Locale.ROOT)] ?: return null
        return SearchBang(word, mode)
    }
}
