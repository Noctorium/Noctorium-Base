package app.noctorium.domain

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** What a pasted link points at. */
enum class LinkKind {
    TRACK,
    PLAYLIST,

    /** A share link that has to be followed before anybody can say what is behind it: `on.soundcloud.com`. */
    SHORT,
}

/**
 * A link somebody pasted, read: which service, what kind of thing, and the plain address of it.
 *
 * [url] is the canonical form rather than what was pasted. Share links carry tracking (`si=`, `utm_`,
 * `feature=share`), a start time, a radio mix to continue into, and hosts that are the same service under
 * another name (`m.`, `youtu.be`). None of that is the track, and a pasted link that differs from a searched
 * one by a tracking parameter would otherwise be a second copy of the same song in the recents, the likes
 * and the downloads.
 */
data class MusicLink(
    val provider: ProviderType,
    val kind: LinkKind,
    /** The video id for YouTube, the playlist id for a playlist, the `user/track` path for SoundCloud. */
    val id: String,
    val url: String,
)

/**
 * The first YouTube Music, YouTube or SoundCloud link in [text], or null when there is none.
 *
 * In the text rather than being the text, because what gets pasted is often a message around a link --
 * "listen to this https://youtu.be/…", or the title and link a share sheet writes together. And a link
 * without its `https://`, which is how people copy one out of an address bar with the scheme hidden, is
 * still a link.
 *
 * Only what can be played is recognised. A channel, a profile or a search page is not a song and not a
 * playlist, and saying "that is not a link Noctorium can play" beats trying to play a profile.
 */
fun findMusicLink(text: String): MusicLink? =
    CANDIDATE.findAll(text).firstNotNullOfOrNull { match -> readLink(match.value.trimEnd(*TRAILING)) }
        ?: spotifyUri(text)

/**
 * A Spotify URI -- `spotify:track:<id>` -- which is what Spotify's desktop app copies with Shift held, and
 * what some share sheets hand over instead of an address.
 */
private fun spotifyUri(text: String): MusicLink? {
    val match = SPOTIFY_URI.find(text) ?: return null
    return spotify(listOf(match.groupValues[1].lowercase(), match.groupValues[2]))
}

private val SPOTIFY_URI = Regex("""spotify:(track|album|playlist|artist):([A-Za-z0-9]{22})""")

/** Whether [text] carries a link [findMusicLink] would read, for deciding whether to offer anything. */
fun hasMusicLink(text: String): Boolean = findMusicLink(text) != null

/**
 * Anything that looks like an address on one of the three services, with or without a scheme. Loose on
 * purpose: [readLink] is the part that decides, this only finds places worth asking about.
 */
private val CANDIDATE = Regex(
    """(?<![a-z0-9.-])(?:https?://)?(?:[a-z0-9-]+\.)*(?:youtube\.com|youtube-nocookie\.com|youtu\.be|soundcloud\.com|soundcloud\.app\.goo\.gl|bandcamp\.com|open\.spotify\.com|spotify\.link|vk\.ru|vk\.com)(?:/[^\s<>"']*)?""",
    RegexOption.IGNORE_CASE,
)

/** What a sentence ends a link with that is not part of it. */
private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '\'', '"')

private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
private val PLAYLIST_ID = Regex("""[A-Za-z0-9_-]{10,}""")
private val SOUNDCLOUD_NAME = Regex("""[A-Za-z0-9_-]+""")

/** First path segments on soundcloud.com that are pages of the site rather than somebody's name. */
private val SOUNDCLOUD_PAGES = setOf(
    "discover", "search", "stream", "upload", "you", "charts", "stations", "pages", "settings", "messages",
    "notifications", "people", "terms-of-use", "imprint", "mobile", "pro", "jobs", "signin", "logout",
)

/** Second path segments that are pages of a profile rather than one of its tracks. */
private val SOUNDCLOUD_PROFILE_PAGES = setOf(
    "tracks", "albums", "popular-tracks", "reposts", "likes", "followers", "following", "comments", "spotlight",
)

private fun readLink(raw: String): MusicLink? {
    val address = if (raw.contains("://")) raw else "https://$raw"
    val uri = runCatching { URI(address) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    val segments = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty).map(::decoded)
    val query = queryOf(uri.rawQuery)
    return when {
        host == "youtu.be" -> youTubeTrack(ProviderType.YOUTUBE_VIDEO, segments.firstOrNull())
        host == "music.youtube.com" -> youTube(ProviderType.YOUTUBE_MUSIC, segments, query)
        host == "youtube.com" || host == "m.youtube.com" || host == "youtube-nocookie.com" ->
            youTube(ProviderType.YOUTUBE_VIDEO, segments, query)
        host == "on.soundcloud.com" || host == "soundcloud.app.goo.gl" ->
            segments.firstOrNull()?.takeIf(SOUNDCLOUD_NAME::matches)?.let { code ->
                MusicLink(ProviderType.SOUNDCLOUD, LinkKind.SHORT, code, "https://$host/$code")
            }
        host == "soundcloud.com" || host == "m.soundcloud.com" -> soundCloud(segments)
        host.endsWith(".bandcamp.com") -> bandcamp(host, segments)
        host == "open.spotify.com" -> spotify(segments)
        host == "spotify.link" -> segments.firstOrNull()?.takeIf(SOUNDCLOUD_NAME::matches)?.let { code ->
            MusicLink(ProviderType.SPOTIFY, LinkKind.SHORT, code, "https://spotify.link/$code")
        }
        host == "vk.ru" || host == "vk.com" || host == "m.vk.ru" || host == "m.vk.com" -> vk(segments)
        else -> null
    }
}

private fun youTube(provider: ProviderType, segments: List<String>, query: Map<String, String>): MusicLink? {
    val first = segments.firstOrNull()
    return when (first) {
        // A song in a mix or a playlist is still that song: `list=` is what to play after it, and the
        // listener pasted the song.
        "watch" -> youTubeTrack(provider, query["v"])
        "shorts", "embed", "live", "v" -> youTubeTrack(provider, segments.getOrNull(1))
        "playlist" -> query["list"]?.takeIf(PLAYLIST_ID::matches)?.let { list ->
            // A radio mix is not a list anybody made, and it has no page of its own to read.
            if (list.startsWith("RD")) return null
            val base = if (provider == ProviderType.YOUTUBE_MUSIC) "https://music.youtube.com" else "https://www.youtube.com"
            MusicLink(provider, LinkKind.PLAYLIST, list, "$base/playlist?list=$list")
        }
        else -> null
    }
}

private fun youTubeTrack(provider: ProviderType, id: String?): MusicLink? {
    val video = id?.takeIf(VIDEO_ID::matches) ?: return null
    val url = if (provider == ProviderType.YOUTUBE_MUSIC) {
        "https://music.youtube.com/watch?v=$video"
    } else {
        "https://www.youtube.com/watch?v=$video"
    }
    return MusicLink(provider, LinkKind.TRACK, video, url)
}

/**
 * A song or an album on an artist's Bandcamp site, `artist.bandcamp.com/track/name` or `/album/name`.
 *
 * Only those two. An artist's front page is a shop rather than a song, and bandcamp.com itself is the
 * site's own pages; artists on a domain of their own cannot be told from any other website by address.
 */
private fun bandcamp(host: String, segments: List<String>): MusicLink? {
    if (host == "daily.bandcamp.com" || host == "www.bandcamp.com") return null
    val kind = when (segments.getOrNull(0)) {
        "track" -> LinkKind.TRACK
        "album" -> LinkKind.PLAYLIST
        else -> return null
    }
    val slug = segments.getOrNull(1)?.takeIf(BANDCAMP_SLUG::matches) ?: return null
    val path = "$host/${segments[0]}/$slug"
    return MusicLink(ProviderType.BANDCAMP, kind, path, "https://$path")
}

private val BANDCAMP_SLUG = Regex("""[A-Za-z0-9_-]+""")

/**
 * A song, an album, a playlist or an artist on Spotify, `open.spotify.com/track/<id>` and the like.
 *
 * A localised address (`/intl-de/track/...`) is the same thing, and so is one with a share token after it.
 * Albums and artists are named the way Noctorium opens them as playlists: `album:<id>`, `artist:<id>`.
 */
private fun spotify(segments: List<String>): MusicLink? {
    val parts = if (segments.firstOrNull()?.startsWith("intl-") == true) segments.drop(1) else segments
    val id = parts.getOrNull(1)?.takeIf(SPOTIFY_ID::matches) ?: return null
    return when (parts.firstOrNull()) {
        "track" -> MusicLink(ProviderType.SPOTIFY, LinkKind.TRACK, id, "https://open.spotify.com/track/$id")
        "album" -> MusicLink(ProviderType.SPOTIFY, LinkKind.PLAYLIST, "album:$id", "https://open.spotify.com/album/$id")
        "artist" -> MusicLink(ProviderType.SPOTIFY, LinkKind.PLAYLIST, "artist:$id", "https://open.spotify.com/artist/$id")
        "playlist" -> MusicLink(ProviderType.SPOTIFY, LinkKind.PLAYLIST, id, "https://open.spotify.com/playlist/$id")
        else -> null
    }
}

private val SPOTIFY_ID = Regex("""[A-Za-z0-9]{22}""")

/**
 * A VK song, `vk.ru/audio<owner>_<id>`, or a VK playlist or album, `vk.ru/music/playlist/<owner>_<id>_<key>`.
 * Either host, vk.ru or vk.com, is the same place.
 */
private fun vk(segments: List<String>): MusicLink? {
    val first = segments.firstOrNull() ?: return null
    VK_SONG.matchEntire(first)?.let { match ->
        val id = "${match.groupValues[1]}_${match.groupValues[2]}"
        return MusicLink(ProviderType.VK, LinkKind.TRACK, id, "https://vk.ru/audio$id")
    }
    if (first == "music" && segments.getOrNull(1) in setOf("playlist", "album")) {
        val id = segments.getOrNull(2)?.takeIf(VK_PLAYLIST::matches) ?: return null
        return MusicLink(ProviderType.VK, LinkKind.PLAYLIST, "playlist:$id", "https://vk.ru/music/playlist/$id")
    }
    return null
}

private val VK_SONG = Regex("""audio(-?\d+)_(\d+)""")
private val VK_PLAYLIST = Regex("""-?\d+_\d+(_[A-Za-z0-9]+)?""")

private fun soundCloud(segments: List<String>): MusicLink? {
    val user = segments.getOrNull(0)?.takeIf(SOUNDCLOUD_NAME::matches) ?: return null
    if (user.lowercase() in SOUNDCLOUD_PAGES) return null
    val second = segments.getOrNull(1)?.takeIf(SOUNDCLOUD_NAME::matches) ?: return null
    if (second == "sets") {
        val set = segments.getOrNull(2)?.takeIf(SOUNDCLOUD_NAME::matches) ?: return null
        // A private set is shared with its secret token as the next part, and without it there is nothing.
        val secret = segments.getOrNull(3)?.takeIf { it.startsWith("s-") && SOUNDCLOUD_NAME.matches(it) }
        val path = listOfNotNull(user, "sets", set, secret).joinToString("/")
        return MusicLink(ProviderType.SOUNDCLOUD, LinkKind.PLAYLIST, path, "https://soundcloud.com/$path")
    }
    if (second.lowercase() in SOUNDCLOUD_PROFILE_PAGES) return null
    val secret = segments.getOrNull(2)?.takeIf { it.startsWith("s-") && SOUNDCLOUD_NAME.matches(it) }
    val path = listOfNotNull(user, second, secret).joinToString("/")
    return MusicLink(ProviderType.SOUNDCLOUD, LinkKind.TRACK, path, "https://soundcloud.com/$path")
}

private fun queryOf(raw: String?): Map<String, String> = raw.orEmpty()
    .split('&')
    .mapNotNull { pair ->
        val name = pair.substringBefore('=').takeIf(String::isNotEmpty) ?: return@mapNotNull null
        decoded(name) to decoded(pair.substringAfter('=', ""))
    }
    .toMap()

private fun decoded(part: String): String =
    runCatching { URLDecoder.decode(part, StandardCharsets.UTF_8) }.getOrDefault(part)

/**
 * The track a link stands for, before anything has been read about it: the service, the id and where it
 * lives, and nothing else. The backend fills in the rest the same way it does for any track whose listing
 * left things out, so a pasted song ends up looking exactly like a searched one.
 */
fun MusicLink.placeholderTrack(): Track = Track(
    provider = provider,
    id = id,
    title = when (provider) {
        ProviderType.SOUNDCLOUD -> id.substringAfter('/').substringBefore('/').replace('-', ' ')
            .replaceFirstChar(Char::uppercaseChar)
        else -> "Opening link"
    },
    artists = emptyList(),
    sourceUrl = url,
)

/**
 * Whether [after] came from [before] by pasting rather than by typing.
 *
 * A box that plays a link the moment it holds one cannot do so for every keystroke: typed by hand,
 * `soundcloud.com/burialuk/a` is already a link to a track, just not the one being typed, and it would
 * start playing three letters into the name. Typing puts in one character at a time, and pasting puts in
 * many, whether into an empty box or over a selection -- which is what this measures: how much new text
 * arrived in one change, between what stayed the same at either end.
 */
fun arrivedAtOnce(before: String, after: String): Boolean {
    val kept = before.commonPrefixWith(after).length
    val keptAtEnd = before.drop(kept).commonSuffixWith(after.drop(kept)).length
    return after.length - kept - keptAtEnd > 1
}
