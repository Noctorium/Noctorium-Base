package app.noctorium.playlists

import app.noctorium.platform.TextFiles
import app.noctorium.settings.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Where the listener keeps their Noctorium playlists. */
@Serializable
enum class PlaylistCloudKind(val displayName: String) {
    /** Nowhere yet: Noctorium playlists are kept here, and go there once somewhere is chosen. */
    NONE("Not set up"),
    /** A folder on this computer, which Google Drive, OneDrive, Dropbox or the like syncs. */
    FOLDER("A synced folder"),
    /** A folder the phone's own file picker chose -- Google Drive, or any cloud app there. */
    PHONE_FOLDER("A folder on the phone"),
    /** The listener's Google Drive, signed in to. */
    GOOGLE_DRIVE("Google Drive"),
}

/** The listener's choice of where Noctorium playlists are kept, in their preferences. */
@Serializable
data class PlaylistCloudPreferences(
    val kind: PlaylistCloudKind = PlaylistCloudKind.NONE,
    /** The folder: a path on a computer, the picker's address for it on a phone. */
    val folder: String = "",
    /** The listener's own Google OAuth client's id. Its secret is kept with the other secrets. */
    val googleClientId: String = "",
)

/** How syncing stands, for Settings and the library to show. */
data class PlaylistSyncState(
    val kind: PlaylistCloudKind = PlaylistCloudKind.NONE,
    /** Where they are, in words: the folder's path, or "Google Drive › Noctorium Playlists". */
    val location: String? = null,
    val syncing: Boolean = false,
    val lastSyncedAtMillis: Long? = null,
    /** What went wrong the last time, in a sentence; null when it went through. */
    val problem: String? = null,
    val googleSignedIn: Boolean = false,
    val googleSigningIn: Boolean = false,
    val googleAppReady: Boolean = false,
)

/**
 * Keeps this device's Noctorium playlists and the listener's storage in step: after a change, a moment later;
 * when Noctorium opens; and every few minutes while it is open, for what another device changed.
 *
 * [current] and [apply] are the library's playlists, read and replaced; [version] goes up with every change made
 * to them here, so a sync that finds the library changed under it leaves the change alone and goes again rather
 * than putting back what was there before. [phoneFolder] is the phone's way into a folder its file picker chose.
 */
class PlaylistSyncController(
    private val scope: CoroutineScope,
    private val credentials: SecretStore,
    private val openBrowser: (String) -> Unit,
    private val device: () -> String,
    private val current: () -> List<LocalPlaylist>,
    private val version: () -> Long,
    private val apply: (List<LocalPlaylist>) -> Unit,
    private val tombstonePath: Path? = LocalPlaylistRepository.defaultStorePath()?.resolveSibling("noctorium-playlists-sync.json"),
    private val phoneFolder: ((String) -> PlaylistCloud)? = null,
    private val googleAuth: GoogleDriveAuth = GoogleDriveAuth(openBrowser),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(PlaylistSyncState())
    val state: StateFlow<PlaylistSyncState> = mutableState.asStateFlow()

    private var preferences = PlaylistCloudPreferences()
    private val syncing = Mutex()
    private var pending: Job? = null
    private var periodic: Job? = null
    @Volatile private var tombstones: Map<String, Long> = loadTombstones()

    /** Takes the listener's choice, as it is now, and syncs with it. Called at start and whenever it changes. */
    fun configure(chosen: PlaylistCloudPreferences) {
        val changed = chosen != preferences
        preferences = chosen
        publishLocation()
        if (periodic == null) {
            periodic = scope.launch {
                while (isActive) {
                    delay(PERIOD_MILLIS)
                    runCatching { syncNow() }
                }
            }
        }
        if (changed || mutableState.value.lastSyncedAtMillis == null) changedSoon(0)
    }

    /** A Noctorium playlist was changed here: synced a moment from now, once a run of changes has settled. */
    fun changedSoon(afterMillis: Long = SETTLE_MILLIS) {
        pending?.cancel()
        pending = scope.launch {
            delay(afterMillis)
            runCatching { syncNow() }
        }
    }

    /**
     * A Noctorium playlist was deleted here: remembered, so the deletion reaches the other devices. [lastChanged] is
     * when the copy deleted was last changed: the deletion is always later than that, even where this device's
     * clock is behind the one that changed it, or the deleted playlist would come back from the other's copy.
     */
    fun deleted(id: String, lastChanged: Long = 0) {
        tombstones = tombstones + (id to maxOf(System.currentTimeMillis(), lastChanged + 1))
        saveTombstones()
        changedSoon()
    }

    /** Syncs now, unless nowhere is chosen. Throws nothing: what went wrong is in [state]. */
    suspend fun syncNow() {
        val cloud = cloud() ?: return
        syncing.withLock {
            mutableState.update { it.copy(syncing = true) }
            try {
                repeat(3) {
                    val before = version()
                    val merge = NoctoriumPlaylistSync(cloud, device()).sync(current(), tombstones)
                    if (version() != before) return@repeat // changed while syncing: go again with the change
                    tombstones = merge.tombstones
                    saveTombstones()
                    if (merge.local != current()) apply(merge.local)
                    mutableState.update { it.copy(syncing = false, problem = null, lastSyncedAtMillis = System.currentTimeMillis()) }
                    return
                }
                mutableState.update { it.copy(syncing = false) }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                mutableState.update { it.copy(syncing = false, problem = failure.message ?: "Syncing failed.") }
            }
        }
    }

    /** The place chosen, or null when there is none to sync with. */
    private fun cloud(): PlaylistCloud? = when (preferences.kind) {
        PlaylistCloudKind.NONE -> null
        PlaylistCloudKind.FOLDER -> preferences.folder.takeIf(String::isNotBlank)?.let { FolderPlaylistCloud(Paths.get(it)) }
        PlaylistCloudKind.PHONE_FOLDER -> preferences.folder.takeIf(String::isNotBlank)?.let { folder -> phoneFolder?.invoke(folder) }
        PlaylistCloudKind.GOOGLE_DRIVE -> if (tokens() == null) null else GoogleDrivePlaylistCloud(accessToken = ::googleAccessToken)
    }

    private fun publishLocation() {
        val cloud = runCatching { cloud() }.getOrNull()
        mutableState.update {
            it.copy(
                kind = preferences.kind,
                location = cloud?.description,
                googleSignedIn = tokens() != null,
                googleAppReady = googleApp()?.ready == true,
                problem = if (preferences.kind == PlaylistCloudKind.GOOGLE_DRIVE && tokens() == null) "Sign in to Google Drive to sync." else it.problem,
            )
        }
    }

    // --- Google Drive ---

    /** The listener's own Google OAuth client, its id from the preferences and its secret from the secrets. */
    private fun googleApp(): GoogleApp? {
        val secret = runCatching { credentials.get(GOOGLE_SECRET_KEY) }.getOrNull() ?: return null
        return GoogleApp(preferences.googleClientId, secret).takeIf { it.clientId.isNotBlank() }
    }

    /** Keeps the secret of the listener's Google OAuth client. The id goes in the preferences. */
    fun setGoogleSecret(secret: String) {
        runCatching { credentials.put(GOOGLE_SECRET_KEY, secret.trim()) }
        publishLocation()
    }

    private fun tokens(): GoogleDriveTokens? = runCatching {
        credentials.get(GOOGLE_TOKENS_KEY)?.let { json.decodeFromString(GoogleDriveTokens.serializer(), it) }
    }.getOrNull()

    private fun keep(tokens: GoogleDriveTokens?) {
        runCatching {
            if (tokens == null) credentials.remove(GOOGLE_TOKENS_KEY)
            else credentials.put(GOOGLE_TOKENS_KEY, json.encodeToString(GoogleDriveTokens.serializer(), tokens))
        }
    }

    /** A token good for now, refreshed first where it has run out. */
    private suspend fun googleAccessToken(): String {
        val tokens = tokens() ?: error("Sign in to Google Drive to sync.")
        if (tokens.expiresAtEpochSeconds > System.currentTimeMillis() / 1000) return tokens.accessToken
        val app = googleApp() ?: error(GoogleDriveAuth.NO_APP)
        val refresh = tokens.refreshToken ?: error("Sign in to Google Drive again.")
        return when (val result = googleAuth.refresh(app, refresh)) {
            is GoogleDriveAuth.Result.Success -> result.tokens.also(::keep).accessToken
            is GoogleDriveAuth.Result.Failure -> {
                if (result.refused) keep(null).also { publishLocation() }
                error(result.detail)
            }
        }
    }

    /** Takes the listener through Google's sign-in, and syncs once it is through. */
    fun signInToGoogle() {
        val app = googleApp()
        if (app == null || !app.ready) {
            mutableState.update { it.copy(problem = GoogleDriveAuth.NO_APP) }
            return
        }
        mutableState.update { it.copy(googleSigningIn = true, problem = null) }
        scope.launch(Dispatchers.IO) {
            when (val result = googleAuth.authorize(app)) {
                is GoogleDriveAuth.Result.Success -> {
                    keep(result.tokens)
                    mutableState.update { it.copy(googleSigningIn = false) }
                    publishLocation()
                    changedSoon(0)
                }
                is GoogleDriveAuth.Result.Failure -> mutableState.update { it.copy(googleSigningIn = false, problem = result.detail) }
            }
        }
    }

    fun signOutOfGoogle() {
        keep(null)
        publishLocation()
    }

    // --- Deletions, remembered between launches ---

    private fun loadTombstones(): Map<String, Long> = runCatching {
        val path = tombstonePath?.takeIf(Files::isRegularFile) ?: return emptyMap()
        json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), TextFiles.read(path).orEmpty())
    }.getOrDefault(emptyMap())

    private fun saveTombstones() {
        val path = tombstonePath ?: return
        runCatching {
            Files.createDirectories(path.parent)
            TextFiles.write(path, json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), tombstones))
        }
    }

    companion object {
        /** A run of edits -- songs added one after another -- settles before it is written out. */
        const val SETTLE_MILLIS = 2_000L
        /** How often what another device changed is looked for, while Noctorium is open. */
        const val PERIOD_MILLIS = 3 * 60_000L
        const val GOOGLE_SECRET_KEY = "noctorium-playlists.google-client-secret"
        const val GOOGLE_TOKENS_KEY = "noctorium-playlists.google-drive-tokens"
    }
}
