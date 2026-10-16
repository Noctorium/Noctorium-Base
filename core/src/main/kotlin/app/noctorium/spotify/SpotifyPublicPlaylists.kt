package app.noctorium.spotify

import app.noctorium.domain.LinkKind
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.findMusicLink
import app.noctorium.platform.TextFiles
import app.noctorium.settings.AppDirectories
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * What every player says about following a public Spotify playlist without signing in, beside the box that takes
 * its link. Kept here, once, so the desktop, the phone and the terminal say the same thing.
 */
const val SPOTIFY_PUBLIC_PLAYLIST_LIMITS =
    "Without a sign-in, Noctorium can follow public Spotify playlists, and only their first 100 songs, because " +
        "that is all Spotify's public page shows. Private playlists and your Liked Songs need a working sign-in, or " +
        "the import of Spotify's data download. The public page is Spotify's to change at any time, and when it " +
        "changes this stops working until Noctorium is updated."

/** What a followed public playlist says under its name in the library. */
const val SPOTIFY_PUBLIC_NOTE = "Public playlist"

/** And, after that, when the page showed as much as it ever shows. */
const val SPOTIFY_CUT_NOTE = "first 100 songs"

/**
 * The Spotify playlist id in what was pasted: a link from the share button, `open.spotify.com/playlist/<id>?si=…`,
 * the same with a language in front (`/intl-de/playlist/<id>`), the embed page's own `/embed/playlist/<id>`, a
 * `spotify:playlist:<id>` URI, or the bare twenty-two characters. Null for anything else, and for an album, an
 * artist or a song, which are not playlists.
 *
 * A share link that only says where it leads once it is followed -- `spotify.link/…` -- is not answered here; see
 * [isSpotifyShortLink].
 */
fun spotifyPlaylistIdIn(text: String): String? {
    val trimmed = text.trim()
    if (BARE_ID.matches(trimmed)) return trimmed
    findMusicLink(trimmed)?.let { link ->
        if (link.provider == ProviderType.SPOTIFY && link.kind == LinkKind.PLAYLIST && BARE_ID.matches(link.id)) return link.id
    }
    // A path without its host -- "/intl-de/playlist/<id>" -- or an older URI with the owner in it.
    return PLAYLIST_PART.find(trimmed)?.groupValues?.get(1)
}

/** Whether [text] is a Spotify share link that has to be followed before it says what it is. */
fun isSpotifyShortLink(text: String): Boolean =
    findMusicLink(text.trim())?.let { it.provider == ProviderType.SPOTIFY && it.kind == LinkKind.SHORT } == true

private val BARE_ID = Regex("""[A-Za-z0-9]{22}""")
private val PLAYLIST_PART = Regex("""(?:^|[/:])playlist[/:]([A-Za-z0-9]{22})(?![A-Za-z0-9])""")

/**
 * One followed public playlist, as Settings lists it: what it was when last read, and what went wrong the last time
 * it was read, if anything did.
 */
data class SpotifyPublicPlaylistState(
    val id: String,
    /** Blank until the playlist has been read once. */
    val title: String = "",
    val owner: String = "",
    /** How many songs the last good read found. Null until there has been one. */
    val songCount: Int? = null,
    /** The page showed its most, so the playlist may go on past [songCount]. */
    val mayBeLonger: Boolean = false,
    /** Why the last read did not work, when it did not. The copy before it is still the one shown. */
    val lastError: String? = null,
    val artworkUrl: String? = null,
    val readAtEpochSeconds: Long = 0,
) {
    /** Its address on Spotify. */
    val url: String get() = "https://open.spotify.com/playlist/$id"
}

/**
 * The last good copy of each public Spotify playlist the library follows, kept between runs.
 *
 * So that a playlist that cannot be read for a moment -- no connection, Spotify having a bad minute -- is still
 * there, songs and all, rather than vanishing from the library until the next read works. One file, a playlist per
 * entry, each read on its own so that one this build cannot read costs that one and not the rest; written whole
 * through [TextFiles.moveIntoPlace], so there is never a moment with no file.
 */
class SpotifyPublicCopies(
    private val path: Path? = AppDirectories.resolve("spotify", "public-playlists.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val copies = ConcurrentHashMap<String, SpotifyPublicList>()
    @Volatile private var loaded = false

    operator fun get(id: String): SpotifyPublicList? {
        ensureLoaded()
        return copies[id]
    }

    fun put(list: SpotifyPublicList) {
        ensureLoaded()
        if (copies.put(list.id, list) == list) return
        save()
    }

    fun remove(id: String) {
        ensureLoaded()
        if (copies.remove(id) != null) save()
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = path?.takeIf(Files::isRegularFile) ?: return
        // A file that cannot be read is an empty one: everything in it is read again at the next refresh.
        val stored = runCatching { json.parseToJsonElement(TextFiles.read(file).orEmpty()).jsonObject }.getOrNull() ?: return
        stored.forEach { (id, value) ->
            if (value !is JsonObject) return@forEach
            runCatching { json.decodeFromJsonElement(SpotifyPublicList.serializer(), value) }.getOrNull()?.let { copies[id] = it }
        }
    }

    @Synchronized
    private fun save() {
        val file = path ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            TextFiles.write(temporary, json.encodeToString(copies.toMap()))
            TextFiles.moveIntoPlace(temporary, file)
        }
    }
}

/**
 * The public Spotify playlists the library follows with nobody signed in -- or with a sign-in Spotify refuses.
 *
 * Which ones is the listener's choice, kept in the settings ([followed]); each is read from its public page
 * ([SpotifyPublicPages]) every time the library is, which is what keeps it in step with Spotify, and the last good
 * copy of each is kept ([SpotifyPublicCopies]) for when a read fails. What went wrong with each is kept as well, for
 * Settings to say beside it.
 */
class SpotifyPublicLibrary(
    private val pages: SpotifyPublicPages,
    private val copies: SpotifyPublicCopies,
    /** The followed playlists' ids, in the order they were added. */
    private val followed: () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Why each playlist's last read failed, by id; nothing for one whose last read worked. */
    private val problems = ConcurrentHashMap<String, String>()

    /** Pages read moments ago, by id, with when -- so opening a playlist the library just listed reads nothing. */
    private val recent = ConcurrentHashMap<String, Pair<Long, SpotifyPublicList>>()

    /** The ids the library listing last took from their public pages, rather than from the account. */
    @Volatile private var listedFromPages: Set<String> = emptySet()

    fun isFollowed(id: String): Boolean = id in followed()

    /** Whether the library's entry for [id] came from its public page the last time the library was listed. */
    fun listedFromPage(id: String): Boolean = id in listedFromPages

    /**
     * Reads one playlist's or album's page now. A good read is kept -- on disk, when the playlist is followed or
     * [keep] says it is about to be -- and a failed one is remembered for Settings, the copy before it untouched.
     */
    suspend fun read(id: String, keep: Boolean = false): SpotifyPublicRead {
        val read = pages.list(id)
        when (read) {
            is SpotifyPublicRead.Ok -> {
                recent[id] = clock() to read.list
                problems.remove(id)
                if (keep || isFollowed(id)) copies.put(read.list)
            }
            else -> if (isFollowed(id)) problems[id] = read.message.orEmpty()
        }
        return read
    }

    /**
     * The followed playlists as the library lists them, in the order they were added, each read afresh -- unless it
     * was read moments ago -- and its last good copy where the read failed. One the account lists itself, in
     * [excluding], is left to the account and not read at all.
     */
    suspend fun listing(excluding: Set<String> = emptySet()): List<Playlist> {
        val shown = followed().filter { it !in excluding }
        val listed = shown.mapNotNull { id ->
            val list = freshCopy(id, LISTING_REREAD_AFTER_MS)
                ?: (read(id) as? SpotifyPublicRead.Ok)?.list
                ?: copies[id]
            list?.let(::playlistOf)
        }
        listedFromPages = listed.mapTo(HashSet()) { it.id }
        return listed
    }

    /**
     * A playlist's or album's songs from its public page: the copy read moments ago, a fresh read, or -- when the
     * page cannot be read -- the last good copy of one that is followed, the failure kept for Settings to say. The
     * failure itself where there is none of those.
     */
    suspend fun open(id: String): SpotifyPublicRead {
        freshCopy(id, OPEN_REREAD_AFTER_MS)?.let { return SpotifyPublicRead.Ok(it) }
        val read = read(id)
        if (read is SpotifyPublicRead.Ok) return read
        return copies[id]?.takeIf { isFollowed(id) }?.let { SpotifyPublicRead.Ok(it) } ?: read
    }

    /** Lets go of everything about [id]: its copy, its problem, and that it was listed. */
    fun forget(id: String) {
        copies.remove(id)
        problems.remove(id)
        recent.remove(id)
        listedFromPages = listedFromPages - id
    }

    /** What Settings lists: every followed playlist, in order, as it was last read. */
    fun states(): List<SpotifyPublicPlaylistState> = followed().map { id ->
        val copy = copies[id] ?: recent[id]?.second
        SpotifyPublicPlaylistState(
            id = id,
            title = copy?.title.orEmpty(),
            owner = copy?.owner.orEmpty(),
            songCount = copy?.tracks?.size,
            mayBeLonger = copy?.mayBeLonger == true,
            lastError = problems[id],
            artworkUrl = copy?.artworkUrl,
            readAtEpochSeconds = copy?.readAtEpochSeconds ?: 0,
        )
    }

    /**
     * What to say in the library about the followed playlists that could not be read just now: one line each,
     * naming it and saying that the copy shown is the one from before, where there is one.
     */
    fun refreshProblems(): List<String> = followed().mapNotNull { id ->
        val problem = problems[id] ?: return@mapNotNull null
        val copy = copies[id]
        when {
            copy == null -> problem
            problem == SpotifyPublicRead.MISSING_PLAYLIST ->
                "“${copy.title}” is private now, or gone from Spotify, so it is shown as it was last read."
            else -> "“${copy.title}” could not be refreshed from Spotify, so it is shown as it was last read. $problem"
        }
    }

    private fun freshCopy(id: String, within: Long): SpotifyPublicList? =
        recent[id]?.takeIf { (at, _) -> clock() - at <= within }?.second

    companion object {
        /**
         * The library's entry for a page: the playlist with its owner, and a note that it is public -- and cut short,
         * when the page showed as much as it ever does -- where its owner would otherwise be all it says.
         */
        fun playlistOf(list: SpotifyPublicList): Playlist = Playlist(
            id = list.id,
            title = list.title,
            provider = ProviderType.SPOTIFY,
            ownerName = if (list.isAlbum) {
                list.owner.takeIf(String::isNotBlank)
            } else {
                listOfNotNull(
                    list.owner.takeIf(String::isNotBlank),
                    SPOTIFY_PUBLIC_NOTE,
                    SPOTIFY_CUT_NOTE.takeIf { list.mayBeLonger },
                ).joinToString(" · ")
            },
            artworkUrl = list.artworkUrl,
            sourceUrl = list.url,
            trackCount = list.tracks.size,
            isPublic = if (list.isAlbum) null else true,
            ownedByListener = false,
        )

        /** The library is read every few minutes at most, so a page read in the last half minute is still news. */
        const val LISTING_REREAD_AFTER_MS = 30_000L

        /** And a playlist opened within five minutes of being listed opens as it was listed. */
        const val OPEN_REREAD_AFTER_MS = 5 * 60_000L
    }
}

/**
 * What a refused Spotify sign-in ends with, wherever it is explained: the two ways in that need no sign-in at all.
 */
const val SPOTIFY_WITHOUT_SIGN_IN =
    "Without one, public playlists can still be followed by their links, and private playlists and Liked Songs " +
        "imported from Spotify's data download, both in Spotify's settings."
