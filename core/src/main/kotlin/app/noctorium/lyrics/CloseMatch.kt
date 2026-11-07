package app.noctorium.lyrics

import app.noctorium.domain.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The lyrics of a song no source knows by the name it was given, found by a looser search.
 *
 * A song uploaded by somebody else comes with their channel as its artist and a title such as
 * "Daft Punk - Get Lucky (slowed + reverb) [lyrics]": every source is asked for that title by that artist, and
 * none has it. This asks LRCLIB's search again with what is left of the title once the uploader's additions are
 * gone -- with the artist read out of the title where it is there -- and takes the candidate that matches it best,
 * if one matches well enough. What it finds is marked a close match, so the lyrics can say they may not be right.
 */
internal class CloseMatchFinder(private val http: LyricsHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The best close match for [track], or null when nothing comes near enough to show. */
    suspend fun find(track: Track): LyricsProviderOutcome? {
        val guesses = guessesFor(track)
        if (guesses.isEmpty()) return null
        val seen = HashSet<Long>()
        var best: Scored? = null
        // A few searches at most: each is a request to a volunteer-run service.
        for (guess in guesses.take(MAX_SEARCHES)) {
            val response = http.get(searchUrl(guess))
            if (response.status !in 200..299) continue
            val candidates = runCatching { json.parseToJsonElement(response.body).jsonArray }.getOrNull() ?: continue
            for (element in candidates) {
                val root = element.jsonObject
                val id = root.long("id")
                if (id != null && !seen.add(id)) continue
                val title = root.string("trackName") ?: continue
                val artist = root.string("artistName").orEmpty()
                val score = score(guesses, title, artist, root.double("duration"), track.durationMs?.div(1_000))
                if (score < THRESHOLD || (best != null && score <= best.score)) continue
                val outcome = LrclibProvider(http).outcomeFrom(root)
                if (outcome.status != LyricsProviderStatus.FOUND) continue
                best = Scored(score, outcome, CloseMatch(title, artist))
            }
            // A near-certain match needs no more searching.
            if (best != null && best.score >= CERTAIN) break
        }
        val found = best ?: return null
        return found.outcome.copy(
            result = found.outcome.result?.copy(closeMatch = found.match),
            detail = "Close match: ${found.match.title} by ${found.match.artist}",
        )
    }

    private fun searchUrl(guess: Guess): String = buildQuery(
        "https://lrclib.net/api/search",
        if (guess.artist == null) mapOf("q" to guess.title) else mapOf("track_name" to guess.title, "artist_name" to guess.artist),
    )

    private data class Scored(val score: Double, val outcome: LyricsProviderOutcome, val match: CloseMatch)

    companion object {
        /** How many searches one song is worth: one for each guess a dashed title with an artist can make. */
        const val MAX_SEARCHES = 4

        /** How alike a candidate has to be to be shown at all, from 0 to 1. */
        const val THRESHOLD = 0.62

        /** Alike enough that searching on would only find the same song again. */
        const val CERTAIN = 0.92
    }
}

/** A title to search for, and the artist with it where one could be told apart from the title. */
internal data class Guess(val title: String, val artist: String?)

/**
 * What [track] might be called where the lyrics are: its title without what uploaders add to one, split at its
 * dash into artist and title when it has one -- "Daft Punk - Get Lucky" -- and the title alone, which LRCLIB's
 * free search matches against both. The track's own artist is used only where it is not a channel's name.
 */
internal fun guessesFor(track: Track): List<Guess> {
    val title = stripUploaderNoise(track.title)
    if (title.isBlank()) return emptyList()
    val artist = track.artists.firstOrNull()?.name?.let(::stripUploaderNoise)?.takeUnless { looksLikeAChannel(it) }
    val guesses = mutableListOf<Guess>()
    val dash = Regex("""\s+[-–—]\s+""").find(title)
    if (dash != null) {
        val left = title.substring(0, dash.range.first).trim()
        val right = title.substring(dash.range.last + 1).trim()
        if (left.isNotBlank() && right.isNotBlank()) {
            guesses += Guess(right, left)
            // "Song - Artist", the other way round, as some uploaders write it.
            guesses += Guess(left, right)
        }
    }
    if (artist != null) guesses += Guess(title, artist)
    guesses += Guess(if (artist != null) "$artist $title" else title, null)
    // Last, the title alone: an uploader's name that looks like an artist's -- "shxhmir" -- would otherwise keep
    // every search to songs by nobody LRCLIB has heard of.
    if (artist != null) guesses += Guess(title, null)
    return guesses.distinct()
}

/**
 * [title] without what an uploader adds to a song's name: "(slowed + reverb)", "[lyrics]", "| 1 hour",
 * "(official video)", "ft. somebody", "nightcore", emoji. Kept: remixes and versions, whose lyrics may differ.
 */
internal fun stripUploaderNoise(title: String): String {
    var text = title
    // Anything in brackets that is about the upload rather than the song.
    text = text.replace(Regex("""[\[(【「][^\])】」]*(slowed|reverb|sped|speed|nightcore|lyric|letra|audio|video|visuali[sz]er|official|hd|hq|4k|8d|bass\s*boost|1\s*hour|extended|loop|clean|explicit|tiktok|edit audio|karaoke|instrumental version|cover art)[^\])】」]*[\])】」]""", RegexOption.IGNORE_CASE), " ")
    // The same, after a separator at the end: "Song | slowed + reverb", "Song // lyrics".
    text = text.replace(Regex("""\s*(\||//|~)\s*.*$"""), " ")
    text = text.replace(Regex("""\b(slowed(\s*(and|&|\+)\s*reverb)?|sped\s*up|nightcore|reverb|8d\s*audio|bass\s*boosted)\b""", RegexOption.IGNORE_CASE), " ")
    // A featured artist is the song's, but no source's title has it.
    text = text.replace(Regex("""\s*[\[(]?\b(ft|feat|featuring)\b\.?\s+[^\])\-–—]*[\])]?""", RegexOption.IGNORE_CASE), " ")
    // Emoji and pictographs.
    text = text.replace(Regex("""[\p{So}\p{Cs}]"""), " ")
    return text.replace(Regex("""\s+"""), " ").trim(' ', '-', '–', '—', ':', '|')
}

/** A YouTube channel's name rather than an artist's: "Daft Punk - Topic", "SomeMusicVEVO", "Chill Beats Uploads". */
internal fun looksLikeAChannel(name: String): Boolean {
    val lowered = name.lowercase()
    return lowered.endsWith(" - topic") || lowered.endsWith("vevo") || lowered.startsWith("youtube") ||
        listOf("music", "records", "uploads", "lyrics", "beats", "vibes", "channel", "official", "tv", "sounds", "audio", "nightcore", "slowed")
            .any { word -> lowered.split(Regex("""[^\p{L}\p{N}]+""")).contains(word) }
}

/**
 * How alike a candidate is to the best of the guesses, from 0 to 1: the title counts most, then the artist where
 * a guess has one, with a little for a length close to the song's -- not much, since a slowed or sped-up upload
 * is the right song at the wrong length.
 */
internal fun score(guesses: List<Guess>, title: String, artist: String, candidateSeconds: Double?, songSeconds: Long?): Double {
    val candidateTitle = normalizeMatchText(title)
    val candidateArtist = normalizeMatchText(artist)
    val both = "$candidateArtist $candidateTitle"
    val best = guesses.maxOf { guess ->
        val guessTitle = normalizeMatchText(guess.title)
        if (guess.artist == null) {
            // Free text: the candidate's artist and title together against it, with the title having to be in it.
            val inside = similarity(candidateTitle, guessTitle, containment = true)
            if (inside < 0.6) 0.0 else 0.55 * similarity(both, guessTitle) + 0.45 * inside
        } else {
            val titleScore = similarity(candidateTitle, guessTitle)
            val artistScore = similarity(candidateArtist, normalizeMatchText(guess.artist))
            0.7 * titleScore + 0.3 * artistScore
        }
    }
    val lengthBonus = if (candidateSeconds == null || songSeconds == null || songSeconds <= 0) 0.0 else {
        val ratio = candidateSeconds / songSeconds
        // Slowed uploads run up to a third longer, sped-up ones as much shorter.
        if (ratio in 0.75..1.35) 0.05 else -0.05
    }
    return (best + lengthBonus).coerceIn(0.0, 1.0)
}

/**
 * How many words two names share, from 0 to 1 (the Dice coefficient of their words). With [containment], how
 * many of [b]'s words are in [a] -- for a title that has to be found within a longer free text.
 */
internal fun similarity(a: String, b: String, containment: Boolean = false): Double {
    val left = a.split(' ').filter(String::isNotBlank).toSet()
    val right = b.split(' ').filter(String::isNotBlank).toSet()
    if (left.isEmpty() || right.isEmpty()) return 0.0
    val shared = left.intersect(right).size.toDouble()
    return if (containment) shared / left.size else 2 * shared / (left.size + right.size)
}
