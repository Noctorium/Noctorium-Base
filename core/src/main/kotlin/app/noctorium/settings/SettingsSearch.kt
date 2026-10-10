package app.noctorium.settings

import java.text.Normalizer
import java.util.Locale

/**
 * One setting as the settings search knows it. Each player lists its own -- its settings are not the same as
 * another's -- and [SettingsSearch] finds among them.
 */
data class SettingEntry(
    /** The player's own name for it, to jump to and highlight. */
    val id: String,
    val title: String,
    /** Where it lives, as the search shows it: "Look", or "Look › Player bar". */
    val section: String,
    val description: String = "",
    /**
     * Other words for it: what people call it when they do not know its name -- "dark mode" for Theme, "louder"
     * for the volume booster, "hotkeys" for Shortcuts -- and other spellings of its name, "equalizer" for Equaliser.
     */
    val keywords: List<String> = emptyList(),
)

/** Which part of a setting matched, best first. */
enum class SettingMatchKind {
    /** Its name begins with what was typed: "vol" for Volume booster. */
    TITLE_START,

    /** Every word typed begins a word of its name, wherever: "boost" for Volume booster. */
    TITLE_WORD,

    /** One of its other words: "dark mode" for Theme. */
    KEYWORD,

    /** Every word typed is in what it says it does. */
    DESCRIPTION,

    /** Every word typed is in the name of the section it lives in. */
    SECTION,
}

/** A setting found, how it was found, and whether a typo had to be forgiven to find it. */
data class SettingMatch(
    val entry: SettingEntry,
    val kind: SettingMatchKind,
    val forgiven: Boolean,
    /** The other word it was found by, for a search that wants to say so: "dark mode". Null for any other kind. */
    val keyword: String? = null,
)

/**
 * The settings search box, the same on every player.
 *
 * Ranked as somebody looking for a setting would rank it: a name that begins with what was typed first, then a
 * name with those words in it, then the other words people use for it, then its description, then its section;
 * within each, an exact match before one that needed a typo forgiven, and otherwise the order the player listed
 * its settings in. Case and accents do not matter, and neither does one wrong, missing, extra or swapped letter
 * in a word of five letters or more -- "equaliser" finds Equaliser and so does "equalizer", "volumn" finds Volume.
 * A shorter word has to be right: one letter off in "mode" is a different word.
 */
object SettingsSearch {
    /** Words this short match only a name or another word for it, never every description with "a" in it. */
    private const val SHORTEST_DESCRIPTION_WORD = 3

    /** A word this long or longer is forgiven one typo. */
    const val FORGIVING_FROM = 5

    fun search(query: String, entries: List<SettingEntry>): List<SettingMatch> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        return entries.mapNotNull { entry -> match(words, entry) }
            .sortedWith(compareBy<SettingMatch> { it.kind.ordinal }.thenBy { it.forgiven })
    }

    private fun match(query: List<String>, entry: SettingEntry): SettingMatch? {
        val title = words(entry.title)
        inOrderFromStart(query, title)?.let { return SettingMatch(entry, SettingMatchKind.TITLE_START, it) }
        anywhere(query, title)?.let { return SettingMatch(entry, SettingMatchKind.TITLE_WORD, it) }
        val keywords = entry.keywords.mapNotNull { keyword -> anywhere(query, words(keyword))?.let { keyword to it } }
        (keywords.firstOrNull { !it.second } ?: keywords.firstOrNull())?.let { (keyword, forgiven) ->
            return SettingMatch(entry, SettingMatchKind.KEYWORD, forgiven, keyword)
        }
        if (query.any { it.length < SHORTEST_DESCRIPTION_WORD }) return null
        anywhere(query, words(entry.description))?.let { return SettingMatch(entry, SettingMatchKind.DESCRIPTION, it) }
        anywhere(query, words(entry.section))?.let { return SettingMatch(entry, SettingMatchKind.SECTION, it) }
        return null
    }

    /** Whether the query's words begin the target's, in order from its first word; whether a typo was forgiven. */
    private fun inOrderFromStart(query: List<String>, target: List<String>): Boolean? {
        if (query.size > target.size) return null
        var forgiven = false
        query.forEachIndexed { i, word ->
            when (wordMatch(word, target[i])) {
                WordMatch.EXACT -> Unit
                WordMatch.FORGIVEN -> forgiven = true
                null -> return null
            }
        }
        return forgiven
    }

    /** Whether every word of the query begins some word of the target; whether a typo was forgiven. */
    private fun anywhere(query: List<String>, target: List<String>): Boolean? {
        if (target.isEmpty()) return null
        var forgiven = false
        for (word in query) {
            val matches = target.mapNotNull { wordMatch(word, it) }
            when {
                WordMatch.EXACT in matches -> Unit
                WordMatch.FORGIVEN in matches -> forgiven = true
                else -> return null
            }
        }
        return forgiven
    }

    private enum class WordMatch { EXACT, FORGIVEN }

    /** [typed] against one word: the start of it, or -- for a long enough word -- the start of it with one typo. */
    private fun wordMatch(typed: String, word: String): WordMatch? {
        if (word.startsWith(typed)) return WordMatch.EXACT
        if (typed.length < FORGIVING_FROM) return null
        // The typo can have made what was typed a letter longer or shorter than the part of the word it means.
        val forgiven = (typed.length - 1..typed.length + 1).any { length ->
            length <= word.length && withinOneEdit(typed, word.take(length))
        }
        return if (forgiven) WordMatch.FORGIVEN else null
    }

    /** Whether [a] becomes [b] with at most one letter changed, added, removed, or swapped with its neighbour. */
    internal fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        if (a.length == b.length) {
            val differ = a.indices.filter { a[it] != b[it] }
            return differ.size == 1 ||
                (differ.size == 2 && differ[1] == differ[0] + 1 && a[differ[0]] == b[differ[1]] && a[differ[1]] == b[differ[0]])
        }
        val (shorter, longer) = if (a.length < b.length) a to b else b to a
        val at = shorter.indices.firstOrNull { shorter[it] != longer[it] } ?: return true
        return shorter.substring(at) == longer.substring(at + 1)
    }

    /** [text] as words to match: lower case, accents off, apostrophes gone, everything else not a letter or digit a gap. */
    fun words(text: String): List<String> = normalise(text).split(' ').filter { it.isNotEmpty() }

    /** [text] lower case, without accents, with nothing but letters, digits and single spaces. */
    fun normalise(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("['’]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
