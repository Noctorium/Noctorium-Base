package app.noctorium.tidal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeping a usable Tidal token in hand: the listener's own, once they have signed in, and otherwise the app's,
 * for reading the catalogue without anybody signed in.
 *
 * The listener's token is refreshed from the stored refresh token as Spotify's is ([app.noctorium.spotify.SpotifyAccess]
 * is the same shape, for the same reasons): under a lock, so two readers at once make one refresh between them,
 * and with the stored sign-in forgotten only when Tidal refuses it, never because there was no connection. The
 * app's token comes from its client id and secret, and is kept in memory only, since it can always be had again.
 *
 * Which app is in force is read from the credential store once and then held, because reading that store runs
 * PowerShell on Windows and this is asked on every screen that might show Tidal.
 */
class TidalAccess(
    private val refresh: suspend (clientId: String, refreshToken: String) -> TidalAuth.Result,
    private val clientCredentials: suspend (TidalApp) -> TidalAuth.Result,
    /** The listener's own Tidal app, from the credential store, or null when they registered none. */
    private val readApp: () -> TidalApp?,
    private val readRefreshToken: () -> String?,
    private val writeRefreshToken: (String) -> Unit,
    private val clearRefreshToken: () -> Unit,
) {
    private val lock = Mutex()
    @Volatile private var tokens: TidalTokens? = null
    @Volatile private var appTokens: TidalTokens? = null
    @Volatile private var app: TidalApp? = null
    @Volatile private var appRead = false

    sealed interface Access {
        data class Ready(val accessToken: String) : Access

        /** No Tidal app to go through: Noctorium has none of its own yet, and the listener has given none. */
        data object NotConfigured : Access

        /** An app, but nobody has signed in -- and, for the catalogue, no secret to read it without signing in. */
        data object NotConnected : Access
        data class Failed(val detail: String) : Access
    }

    /** The listener's own app, read once from where it is kept. */
    fun ownApp(): TidalApp? {
        if (!appRead) {
            app = runCatching { readApp() }.getOrNull()?.takeIf { it.clientId.isNotBlank() }
            appRead = true
        }
        return app
    }

    /** Uses [own] from now on, or Noctorium's app again for null. Whatever was signed in through the old one is let go. */
    fun useApp(own: TidalApp?) {
        app = own?.takeIf { it.clientId.isNotBlank() }
        appRead = true
        appTokens = null
    }

    /** The client id in force: the listener's app's, or Noctorium's -- which is empty for now. */
    fun clientId(): String = TidalApplication.clientId(ownApp()?.clientId)

    fun isConfigured(): Boolean = clientId().isNotBlank()

    /** Whether there is a stored sign-in to work from. Says nothing about whether Tidal still accepts it. */
    fun isConnected(): Boolean = isConfigured() && !readRefreshToken().isNullOrBlank()

    /** Whether the catalogue can be read with nobody signed in, which takes an app with its secret. */
    fun readsCatalogueSignedOut(): Boolean = ownApp()?.readsCatalogue == true

    /** Takes the tokens a fresh sign-in produced, and keeps the refresh token for next launch. */
    fun adopt(fresh: TidalTokens) {
        tokens = fresh
        fresh.refreshToken?.takeIf(String::isNotBlank)?.let(writeRefreshToken)
    }

    /** Forgets the sign-in here and on disk. Tidal itself is untouched; nothing was ever written to it. */
    fun disconnect() {
        tokens = null
        clearRefreshToken()
    }

    /** Lets go of [accessToken] after Tidal refused it, so the next request has a fresh one made. */
    fun refused(accessToken: String) {
        if (tokens?.accessToken == accessToken) tokens = null
        if (appTokens?.accessToken == accessToken) appTokens = null
    }

    /** The signed-in listener's token, refreshed when it has run out. */
    suspend fun user(): Access {
        val id = clientId()
        if (id.isBlank()) return Access.NotConfigured
        tokens?.takeIf { it.isFresh() }?.let { return Access.Ready(it.accessToken) }
        return lock.withLock {
            // Checked again inside the lock: whoever held it before this may have just refreshed.
            tokens?.takeIf { it.isFresh() }?.let { return@withLock Access.Ready(it.accessToken) }
            val stored = readRefreshToken()?.takeIf(String::isNotBlank) ?: return@withLock Access.NotConnected
            when (val result = refresh(id, stored)) {
                is TidalAuth.Result.Success -> {
                    adopt(result.tokens)
                    Access.Ready(result.tokens.accessToken)
                }
                is TidalAuth.Result.Failure -> if (result.refused) {
                    // Only a refusal clears the stored token; being offline must not sign anybody out.
                    tokens = null
                    clearRefreshToken()
                    Access.NotConnected
                } else {
                    Access.Failed(result.detail)
                }
            }
        }
    }

    /**
     * A token to read the catalogue with: the listener's, when they are signed in -- it reads what their country
     * has -- and otherwise the app's, from its id and secret, when it has one.
     */
    suspend fun catalogue(): Access {
        val signedIn = user()
        if (signedIn is Access.Ready || signedIn == Access.NotConfigured) return signedIn
        val own = ownApp()?.takeIf { it.readsCatalogue } ?: return signedIn
        appTokens?.takeIf { it.isFresh() }?.let { return Access.Ready(it.accessToken) }
        return lock.withLock {
            appTokens?.takeIf { it.isFresh() }?.let { return@withLock Access.Ready(it.accessToken) }
            when (val result = clientCredentials(own)) {
                is TidalAuth.Result.Success -> {
                    appTokens = result.tokens
                    Access.Ready(result.tokens.accessToken)
                }
                is TidalAuth.Result.Failure -> Access.Failed(result.detail)
            }
        }
    }
}
