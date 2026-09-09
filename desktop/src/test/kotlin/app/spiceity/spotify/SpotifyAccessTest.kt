package app.spiceity.spotify

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Keeping a Spotify sign-in, and knowing when it is really gone.
 *
 * The distinction these tests are here for has already been the cause of one bug, on the YouTube session:
 * a refresh that fails because there is no connection looks, from the inside, exactly like one Spotify
 * refused. Treating the first as the second signs somebody out of Spotify for the offence of being offline,
 * and then makes them go and find their client id again.
 */
class SpotifyAccessTest {
    private var stored: String? = "a-stored-refresh-token"
    private var clientId = "a-client-id"
    private val refreshCalls = mutableListOf<Pair<String, String>>()

    private fun access(answer: (String, String) -> SpotifyAuth.Result) = SpotifyAccess(
        refresh = { id, token ->
            refreshCalls += id to token
            answer(id, token)
        },
        clientId = { clientId },
        readRefreshToken = { stored },
        writeRefreshToken = { stored = it },
        clearRefreshToken = { stored = null },
    )

    private fun tokens(access: String = "fresh-access", refresh: String? = "a-stored-refresh-token") =
        SpotifyTokens(access, refresh, java.time.Instant.now().epochSecond + 3_600)

    @Test
    fun `no client id is not configured, and nothing is attempted`() = runBlocking {
        clientId = ""

        assertIs<SpotifyAccess.Access.NotConfigured>(access { _, _ -> error("should not be reached") }.access())
        assertTrue(refreshCalls.isEmpty())
    }

    @Test
    fun `a stored sign-in is traded for a usable token`() = runBlocking {
        val subject = access { _, _ -> SpotifyAuth.Result.Success(tokens()) }

        val ready = subject.access()

        assertIs<SpotifyAccess.Access.Ready>(ready)
        assertEquals("fresh-access", ready.accessToken)
        assertEquals(listOf("a-client-id" to "a-stored-refresh-token"), refreshCalls)
    }

    /** An hour's token is worth reusing; refreshing per request would be a round trip before every read. */
    @Test
    fun `a token still good for something is reused`() = runBlocking {
        val subject = access { _, _ -> SpotifyAuth.Result.Success(tokens()) }

        subject.access()
        subject.access()
        subject.access()

        assertEquals(1, refreshCalls.size, "the token was refreshed when it did not need to be")
    }

    /**
     * Spotify only sometimes returns a new refresh token, and the old one stays valid when it does not.
     * Overwriting the stored one with null would sign the listener out at the next launch.
     */
    @Test
    fun `a refresh that returns no new token keeps the old one`() = runBlocking {
        val subject = access { _, _ -> SpotifyAuth.Result.Success(tokens(refresh = null)) }

        subject.access()

        assertEquals("a-stored-refresh-token", stored)
    }

    /**
     * Exactly what Spotify answers a revoked refresh token with, checked live against its token endpoint:
     * `{"error":"invalid_grant","error_description":"Invalid refresh token"}`. The refusal lives in the
     * code and not in the description, which is what the earlier version of this got wrong — it matched on
     * the description, never recognised a dead token, and would have kept one forever.
     */
    @Test
    fun `a refusal from Spotify clears the sign-in`() = runBlocking {
        val subject = access { _, _ ->
            SpotifyAuth.Result.Failure(
                SpotifyAuth.explain("invalid_grant", "Invalid refresh token"),
                refused = SpotifyAuth.isRefusal("invalid_grant"),
            )
        }

        assertIs<SpotifyAccess.Access.NotConnected>(subject.access())
        assertNull(stored, "a refused sign-in was kept")
    }

    /** A client id belonging to another app, or mistyped: the stored sign-in cannot work against it. */
    @Test
    fun `an unrecognised client id clears the sign-in`() = runBlocking {
        val subject = access { _, _ ->
            SpotifyAuth.Result.Failure(
                SpotifyAuth.explain("invalid_client", "Failed to get client"),
                refused = SpotifyAuth.isRefusal("invalid_client"),
            )
        }

        assertIs<SpotifyAccess.Access.NotConnected>(subject.access())
        assertNull(stored)
    }

    /**
     * The whole point. No connection is not a refusal, and must not throw away a sign-in that is fine.
     */
    @Test
    fun `being offline reports a failure and keeps the sign-in`() = runBlocking {
        val subject = access { _, _ ->
            SpotifyAuth.Result.Failure("Could not reach Spotify: connect timed out")
        }

        val result = subject.access()

        assertIs<SpotifyAccess.Access.Failed>(result)
        assertEquals(
            "a-stored-refresh-token",
            stored,
            "a dropped connection signed the listener out of Spotify",
        )
        assertTrue(result.detail.contains("Could not reach Spotify"))
    }

    /**
     * A 500 from Spotify, a rate limit, or a request this got wrong are all Spotify's problem or Spiceity's
     * — none of them means the listener's sign-in is gone, so none of them may discard it.
     */
    @Test
    fun `a failure Spotify did not attribute to the sign-in keeps it`() = runBlocking {
        val subject = access { _, _ ->
            SpotifyAuth.Result.Failure(
                SpotifyAuth.explain("server_error", "Something went wrong"),
                refused = SpotifyAuth.isRefusal("server_error"),
            )
        }

        assertIs<SpotifyAccess.Access.Failed>(subject.access())
        assertEquals("a-stored-refresh-token", stored)
    }

    @Test
    fun `no stored sign-in reads as not connected`() = runBlocking {
        stored = null
        val subject = access { _, _ -> error("should not be reached") }

        assertIs<SpotifyAccess.Access.NotConnected>(subject.access())
        assertTrue(!subject.isConnected())
        assertTrue(subject.isConfigured(), "a client id is still configured")
    }

    @Test
    fun `disconnecting forgets the sign-in here and on disk`() = runBlocking {
        val subject = access { _, _ -> SpotifyAuth.Result.Success(tokens()) }
        subject.access()

        subject.disconnect()

        assertNull(stored)
        assertIs<SpotifyAccess.Access.NotConnected>(subject.access())
    }
}

/**
 * Remembering which recording each Spotify track was resolved to.
 *
 * Kept because resolving costs a search, but more because it should not *change*: the same search run next
 * week can rank a different upload first, and a playlist that plays a slightly different version each time
 * is unsettling in a way a slow one is not.
 */
class SpotifyMatchStoreTest {
    private val folder = Files.createTempDirectory("spiceity-spotify-matches")
    private val file = folder.resolve("matches.json")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun resolved(id: String) = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = id,
        title = "Everlong",
        artists = listOf(Artist("foo", "Foo Fighters", ProviderType.YOUTUBE_MUSIC)),
        durationMs = 250_000,
        sourceUrl = "https://music.youtube.com/watch?v=$id",
    )

    @Test
    fun `a match survives a restart`() {
        SpotifyMatchStore(file).put("spotify-track-id", resolved("yt-1"))

        val reopened = SpotifyMatchStore(file)

        assertEquals("yt-1", reopened["spotify-track-id"]?.id)
        assertEquals(ProviderType.YOUTUBE_MUSIC, reopened["spotify-track-id"]?.provider)
    }

    @Test
    fun `an unknown track has no match rather than a wrong one`() {
        assertNull(SpotifyMatchStore(file)["never-seen"])
    }

    /** For when the wrong song was chosen: forgetting it is what lets the next play search again. */
    @Test
    fun `a match can be forgotten`() {
        val store = SpotifyMatchStore(file)
        store.put("spotify-track-id", resolved("yt-1"))

        store.forget("spotify-track-id")

        assertNull(store["spotify-track-id"])
        assertNull(SpotifyMatchStore(file)["spotify-track-id"])
    }

    /**
     * The file holds nothing that cannot be worked out again, so a damaged one is started over rather than
     * being allowed to stop Spotify tracks from playing at all.
     */
    @Test
    fun `a damaged file is treated as an empty one`() {
        Files.writeString(file, "{ this is not json")

        val store = SpotifyMatchStore(file)

        assertEquals(0, store.size())
        store.put("spotify-track-id", resolved("yt-1"))
        assertEquals("yt-1", SpotifyMatchStore(file)["spotify-track-id"]?.id)
    }

    @Test
    fun `nowhere to write is not a failure`() {
        val store = SpotifyMatchStore(path = null)

        store.put("spotify-track-id", resolved("yt-1"))

        assertEquals("yt-1", store["spotify-track-id"]?.id, "the match should still be held in memory")
    }
}
