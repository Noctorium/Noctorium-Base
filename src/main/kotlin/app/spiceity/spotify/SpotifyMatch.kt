package app.spiceity.spotify

import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import java.text.Normalizer
import kotlin.math.abs

/**
 * Finding the song a Spotify entry refers to, somewhere it can actually be played from.
 *
 * Spotify hands over a name, an artist and a length, and no audio -- by design, and there is no way around
 * it that does not mean being Spotify's own player. So a Spotify track is a reference, and this is what
 * resolves the reference: search YouTube Music for the artist and title, then decide whether what came back
 * is really the same recording.
 *
 * That decision is the whole difficulty, and the reason it is a scored one rather than "take the first
 * result". Searching for a song returns its remixes, its live versions, its sped-up edits, hour-long loops
 * of it and covers of it, any of which a search engine is happy to rank first. Playing one of those instead
 * of the song asked for is worse than playing nothing, because nothing is at least obvious. So a candidate
 * has to earn the match, and when none does, none is chosen.
 */
object SpotifyMatch {
    /** Below this, the best candidate is not considered the same recording. */
    internal const val ACCEPT_SCORE = 0.62

    /** Lengths this far apart are a different recording however well the names agree. */
    internal const val MAX_DURATION_GAP_MS = 25_000L

    /** Comfortably the same recording; anything under this is not penalised at all. */
    internal const val CLOSE_DURATION_MS = 4_000L

    /**
     * What to ask a search engine for.
     *
     * Spotify's titles carry edition marks -- "- Remastered 2011", "(Deluxe Edition)" -- that describe a
     * release rather than a song, and searching for them narrows the results to nothing or to the wrong
     * upload. The featured artists are kept, because they are part of how a song is titled elsewhere.
     */
    fun searchQuery(track: Track): String {
        val title = strippedTitle(track.title)
        val artist = track.artists.firstOrNull()?.name?.trim().orEmpty()
        return listOf(artist, title).filter(String::isNotBlank).joinToString(" ").trim()
    }

    /**
     * The candidate that is the same recording, or null.
     *
     * Null is a real answer here and is handled as one by the caller: a track Spotify has and YouTube Music
     * does not is a normal thing to run into, and it is reported rather than substituted.
     */
    fun choose(spotify: Track, candidates: List<Track>): Track? =
        candidates.asSequence()
            .filter { it.provider != ProviderType.SPOTIFY }
            .map { candidate -> candidate to score(spotify, candidate) }
            .filter { (_, score) -> score >= ACCEPT_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first

    /**
     * The track that actually gets played, once a match has been found.
     *
     * It is the matched recording -- its provider, its id, its address -- because that is what makes a
     * sound, and because everything downstream then works without knowing Spotify was ever involved:
     * downloading it, liking it, scrobbling it, showing it on Discord.
     *
     * What it keeps from Spotify is how the song is *written*. Spotify has "Sweet Child O' Mine" where the
     * upload is "Guns N' Roses - Sweet Child O' Mine (Official Music Video)", and its cover art is square
     * album art rather than a video thumbnail. So the playlist row and the player bar go on agreeing with
     * each other, and with the playlist as the listener knows it.
     */
    fun resolved(spotify: Track, match: Track): Track = match.copy(
        title = spotify.title,
        // The matched provider's artists are preferred where it named any, because those are the ones whose
        // pages can actually be opened. Spotify's names stand in when the match carried none.
        artists = match.artists.ifEmpty { spotify.artists.map { it.copy(provider = match.provider) } },
        album = match.album ?: spotify.album?.copy(provider = match.provider),
        artworkUrl = spotify.artworkUrl ?: match.artworkUrl,
        // The length of the recording being played, not the one it stands for. The seek bar is drawn from
        // this, and a bar drawn to Spotify's length would be wrong by however far the two differ.
        durationMs = match.durationMs ?: spotify.durationMs,
    )

    /**
     * How alike two tracks are, from 0 to 1.
     *
     * Weighted towards the artist, because a wrong artist is nearly always a cover or a different song of
     * the same name, while a title that reads differently is often the same recording under a slightly
     * different upload name.
     */
    internal fun score(spotify: Track, candidate: Track): Double {
        val artist = artistScore(spotify, candidate)
        // No artist in common at all: a cover, a tribute, or an unrelated song with the same title. None of
        // those is what was asked for, and no title agreement should be able to rescue it.
        if (artist <= 0.0) return 0.0

        val title = titleScore(spotify.title, candidate.title)
        if (title <= 0.0) return 0.0

        val duration = durationScore(spotify.durationMs, candidate.durationMs)
        // A length far off means a different recording of the same song -- extended, live, looped, or a
        // whole album under one title. Certainty about the names does not change that.
        if (duration <= 0.0) return 0.0

        return (title * .45) + (artist * .35) + (duration * .20)
    }

    /** Whether the recordings share a performer, allowing for how differently credits are written. */
    internal fun artistScore(spotify: Track, candidate: Track): Double {
        val wanted = spotify.artists.map { normalize(it.name) }.filter(String::isNotBlank)
        if (wanted.isEmpty()) return 0.0
        // A YouTube upload often credits the artist only in the video title -- "Artist - Title (Official
        // Video)" on a label's channel -- so the title counts as a place the artist can be named.
        val offered = (candidate.artists.map { normalize(it.name) } + normalize(candidate.title))
            .filter(String::isNotBlank)
        if (offered.isEmpty()) return 0.0

        val primary = wanted.first()
        return when {
            offered.any { it == primary } -> 1.0
            offered.any { it.contains(primary) || primary.contains(it) } -> .9
            // A featured artist matching is weaker evidence, but it is still the right song more often than
            // not: collaborations get uploaded under either name.
            wanted.drop(1).any { extra -> offered.any { it.contains(extra) } } -> .7
            tokensOverlap(primary, offered.joinToString(" ")) >= .6 -> .65
            else -> 0.0
        }
    }

    /** Whether two titles name the same song, once release wording is set aside. */
    internal fun titleScore(spotifyTitle: String, candidateTitle: String): Double {
        val wanted = normalize(strippedTitle(spotifyTitle))
        val offered = normalize(candidateTitle)
        if (wanted.isBlank() || offered.isBlank()) return 0.0

        // Only checked against the candidate: Spotify does not sell remixes as the original, but a search
        // for the original will happily return one, and that is exactly the substitution to refuse.
        val wantedRemix = REMIX_WORDS.any { it in wanted }
        val offeredRemix = REMIX_WORDS.any { it in offered }
        if (offeredRemix && !wantedRemix) return 0.0

        return when {
            offered == wanted -> 1.0
            offered.contains(wanted) -> .9
            wanted.contains(offered) -> .8
            else -> tokensOverlap(wanted, offered).takeIf { it >= .6 } ?: 0.0
        }
    }

    /**
     * How well two lengths agree.
     *
     * The strongest signal available, and the only one that is not a matter of wording: two recordings of
     * the same performance are within a few seconds of each other, and a sped-up edit or an hour-long loop
     * is not, however perfectly its title reads. An unknown length is treated as neutral rather than as
     * disagreement -- YouTube Music listings often carry none, and refusing every one of those would leave
     * most of a library unplayable.
     */
    internal fun durationScore(wantedMs: Long?, offeredMs: Long?): Double {
        if (wantedMs == null || offeredMs == null || wantedMs <= 0 || offeredMs <= 0) return .5
        val gap = abs(wantedMs - offeredMs)
        return when {
            gap > MAX_DURATION_GAP_MS -> 0.0
            gap <= CLOSE_DURATION_MS -> 1.0
            else -> 1.0 - (gap - CLOSE_DURATION_MS).toDouble() / (MAX_DURATION_GAP_MS - CLOSE_DURATION_MS)
        }
    }

    /** The share of the shorter title's words that appear in the longer one. */
    internal fun tokensOverlap(left: String, right: String): Double {
        val leftWords = left.split(' ').filter(String::isNotBlank).toSet()
        val rightWords = right.split(' ').filter(String::isNotBlank).toSet()
        if (leftWords.isEmpty() || rightWords.isEmpty()) return 0.0
        val shared = leftWords.count { it in rightWords }
        return shared.toDouble() / minOf(leftWords.size, rightWords.size)
    }

    /**
     * A title with release wording taken off.
     *
     * "Everlong - Remastered" and "Everlong" are one song, and Spotify is the only place that spells the
     * first. What is in brackets is only dropped when it says something about the edition: "(feat. Someone)"
     * and "(Acoustic)" belong to the song and taking them off would match the wrong version.
     */
    internal fun strippedTitle(title: String): String {
        var working = BRACKETED_EDITION.replace(title.trim(), " ").trim()
        DASH_SUFFIX.find(working)?.let { dash ->
            // Only dropped when the whole tail is edition wording. "Help! - Mono / Remastered" goes;
            // "Marquee Moon - Live" would not, since a live take is a different recording.
            if (isAllEditionWords(working.substring(dash.range.last + 1))) {
                working = working.substring(0, dash.range.first).trim()
            }
        }
        return working.replace(SPACES, " ").trim(' ', '-', '–', ',', '/').ifBlank { title.trim() }
    }

    /** Whether a title's tail says only which release this is, and nothing about the song. */
    private fun isAllEditionWords(tail: String): Boolean {
        val words = tail.split(' ', '/', '·', ',').map { it.trim().lowercase() }.filter(String::isNotBlank)
        if (words.isEmpty()) return false
        return words.all { word -> word in EDITION_WORDS || word.matches(YEAR) }
    }

    /**
     * Text reduced to what can be compared.
     *
     * Accents are decomposed and dropped so "Beyoncé" and "Beyonce" are one name, and punctuation goes
     * because apostrophes and dashes are written differently in every upload.
     */
    internal fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace(NON_WORD, " ")
        .replace(SPACES, " ")
        .trim()

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val SPACES = Regex(" {2,}")

    /** The last " - " in a title, which is where Spotify puts what it has to say about the release. */
    private val DASH_SUFFIX = Regex("""\s[-–]\s(?!.*\s[-–]\s)""")

    private val YEAR = Regex("""\d{4}""")

    /**
     * Words that describe a release rather than a performance.
     *
     * Every one of these can be taken off a title and leave the same recording behind: "Come As You Are -
     * Remastered 2011" is that song. Words that mean a *different* recording -- live, acoustic, remix,
     * demo -- are deliberately absent, because dropping one of those would search for the wrong thing and
     * then match it confidently.
     */
    private val EDITION_WORDS = setOf(
        "remaster", "remastered", "remasterd", "re-master", "re-mastered", "master",
        "mono", "stereo", "single", "album", "version", "edit", "radio",
        "deluxe", "expanded", "bonus", "track", "anniversary", "reissue", "re-issue",
        "digital", "explicit", "clean", "edition", "original", "recording", "the",
    )

    private val BRACKETED_EDITION = Regex(
        """[(\[](\d{4}\s)?(remaster(ed)?|re-?master(ed)?|deluxe|expanded|bonus track|anniversary|""" +
            """explicit|clean|album version|single version|mono|stereo)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Words that mark a different recording rather than a different upload of the same one.
     *
     * Checked after normalizing, so each is bare letters. These are the substitutions a search actually
     * makes: ask for a well-known song and the first result is often a sped-up edit or an hour loop.
     */
    private val REMIX_WORDS = listOf(
        "remix", "bootleg", "flip", "mashup", "cover", "karaoke", "instrumental", "nightcore",
        "sped up", "spedup", "slowed", "reverb", "8d audio", "1 hour", "one hour", "hour loop", "loop",
        "live at", "live in", "live from", "tribute", "in the style of", "made famous by",
    )
}
