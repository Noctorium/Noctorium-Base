package app.spiceity.spotify

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeping a usable Spotify token in hand.
 *
 * Spotify's access tokens last an hour, which is shorter than a listening session, so anything that reads
 * from Spotify has to be able to get a fresh one. That is all this does: it hands out the token it has while
 * it is good for something, and quietly trades the refresh token for a new one when it is not.
 *
 * The refresh runs under a lock so that a library refresh and a track resolving at the same moment produce
 * one refresh between them rather than two. Two would not break anything, but the second would be a wasted
 * round trip on the path that makes pressing play feel slow.
 */
class SpotifyAccess(
    /**
     * How a refresh token is traded for a fresh access token.
     *
     * Passed in rather than reached for so this can be exercised against a scripted answer. The behaviour
     * worth testing here is what happens when it fails, and there are two very different kinds of failure.
     */
    private val refresh: (clientId: String, refreshToken: String) -> SpotifyAuth.Result,
    private val clientId: () -> String,
    private val readRefreshToken: () -> String?,
    private val writeRefreshToken: (String) -> Unit,
    private val clearRefreshToken: () -> Unit,
) {
    private val lock = Mutex()
    @Volatile private var tokens: SpotifyTokens? = null

    sealed interface Access {
        data class Ready(val accessToken: String) : Access

        /** No client id yet: the listener has not done the one-time setup. */
        data object NotConfigured : Access

        /** Configured, but nobody has signed in -- or the sign-in has been withdrawn. */
        data object NotConnected : Access
        data class Failed(val detail: String) : Access
    }

    fun isConfigured(): Boolean = clientId().isNotBlank()

    /** Whether there is a stored sign-in to work from. Says nothing about whether Spotify still accepts it. */
    fun isConnected(): Boolean = isConfigured() && !readRefreshToken().isNullOrBlank()

    /** Takes the tokens a fresh sign-in produced, and keeps the refresh token for next launch. */
    fun adopt(fresh: SpotifyTokens) {
        tokens = fresh
        fresh.refreshToken?.takeIf(String::isNotBlank)?.let(writeRefreshToken)
    }

    /** Forgets the sign-in here and on disk. Spotify itself is untouched; nothing was ever written to it. */
    fun disconnect() {
        tokens = null
        clearRefreshToken()
    }

    suspend fun access(): Access {
        val id = clientId()
        if (id.isBlank()) return Access.NotConfigured
        tokens?.takeIf { it.isFresh() }?.let { return Access.Ready(it.accessToken) }

        return lock.withLock {
            // Checked again inside the lock: whoever held it before this may have just refreshed, and
            // refreshing a second time would only spend a round trip to reach the same place.
            tokens?.takeIf { it.isFresh() }?.let { return@withLock Access.Ready(it.accessToken) }

            val stored = readRefreshToken()?.takeIf(String::isNotBlank) ?: return@withLock Access.NotConnected
            when (val result = refresh(id, stored)) {
                is SpotifyAuth.Result.Success -> {
                    adopt(result.tokens)
                    Access.Ready(result.tokens.accessToken)
                }
                is SpotifyAuth.Result.Failure -> {
                    // Only a refusal clears the stored token. A refresh that failed because there is no
                    // connection must not sign the listener out of Spotify for being offline -- that was the
                    // shape of a bug already fixed once, on the YouTube session.
                    if (result.refused) {
                        tokens = null
                        clearRefreshToken()
                        Access.NotConnected
                    } else {
                        Access.Failed(result.detail)
                    }
                }
            }
        }
    }

}
