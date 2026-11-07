package app.noctorium.lyrics

import app.noctorium.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class LyricsRepository internal constructor(
    http: LyricsHttpClient = DefaultLyricsHttpClient(),
) {
    /** What the platforms build. The constructor above is internal so tests can script the HTTP. */
    constructor() : this(DefaultLyricsHttpClient())

    private val providers: List<LyricsProvider> = listOf(
        LrclibProvider(http),
        BetterLyricsProvider(http),
        KaralyrProvider(http),
        SyncLrcProvider(http),
        LyricsOvhProvider(http),
        MusixmatchProvider(http),
        HappiProvider(http),
        GeniusProvider(http),
    )
    private val closeMatches = CloseMatchFinder(http)
    private val cache = ConcurrentHashMap<String, List<LyricsProviderOutcome>>()

    val providerIds: List<LyricsProviderId> get() = providers.map(LyricsProvider::id)

    suspend fun findAll(track: Track, forceRefresh: Boolean = false): List<LyricsProviderOutcome> {
        val cacheKey = "${track.queueKey}|${track.title}|${track.artistLine}|${track.durationMs ?: 0}"
        if (!forceRefresh) cache[cacheKey]?.let {
            LyricsLog.event("cache_hit", mapOf("track" to track.queueKey, "providers" to it.size))
            return it
        }
        val query = LyricsQuery.from(track)
        LyricsLog.event(
            "search_started",
            mapOf(
                "track" to track.queueKey,
                "title" to query.title,
                "artist" to query.artist,
                "providers" to providers.size,
                "forceRefresh" to forceRefresh,
            ),
        )
        val exact = supervisorScope {
            providers.map { provider ->
                async {
                    val startedAt = System.nanoTime()
                    val outcome = runCatching { fetchWithRetry(provider, query) }.getOrElse { error ->
                        LyricsProviderOutcome(
                            provider.providerId(),
                            LyricsProviderStatus.ERROR,
                            detail = error.message?.take(120) ?: "Provider failed",
                        )
                    }
                    LyricsLog.event(
                        "provider_result",
                        mapOf(
                            "track" to track.queueKey,
                            "provider" to provider.id.name,
                            "status" to outcome.status.name,
                            "lines" to (outcome.result?.lines?.size ?: 0),
                            "synced" to (outcome.result?.synced ?: false),
                            "detail" to outcome.detail,
                            "elapsedMs" to ((System.nanoTime() - startedAt) / 1_000_000),
                        ),
                    )
                    outcome
                }
            }.awaitAll()
        }
        val outcomes = withCloseMatch(track, exact)
        cache[cacheKey] = outcomes
        LyricsLog.event(
            "search_finished",
            mapOf(
                "track" to track.queueKey,
                "found" to outcomes.count { it.status == LyricsProviderStatus.FOUND },
                "links" to outcomes.count { it.status == LyricsProviderStatus.LINK_ONLY },
                "errors" to outcomes.count { it.status == LyricsProviderStatus.ERROR },
            ),
        )
        return outcomes
    }

    /**
     * [outcomes], with a close match in LRCLIB's place when no source found the song by its own name: the
     * lyrics of a song uploaded under somebody else's, marked as a close match. Asked for only then, so a song
     * with lyrics of its own never waits for it, and a failure here leaves the outcomes as they were.
     */
    private suspend fun withCloseMatch(track: Track, outcomes: List<LyricsProviderOutcome>): List<LyricsProviderOutcome> {
        if (outcomes.any { it.status == LyricsProviderStatus.FOUND }) return outcomes
        val found = runCatching { withTimeout(12_000) { closeMatches.find(track) } }
            .onFailure { if (it is CancellationException && it !is TimeoutCancellationException) throw it }
            .getOrNull() ?: return outcomes
        LyricsLog.event("close_match", mapOf("track" to track.queueKey, "detail" to found.detail))
        return outcomes.map { if (it.provider == found.provider) found else it }
    }

    private suspend fun fetchWithRetry(provider: LyricsProvider, query: LyricsQuery): LyricsProviderOutcome {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                return withTimeout(10_000) { provider.fetch(query) }
            } catch (error: TimeoutCancellationException) {
                lastError = error
                if (attempt == 0) delay(300)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                if (attempt == 0) delay(300)
            }
        }
        throw lastError ?: IllegalStateException("Provider failed")
    }

    private fun LyricsProvider.providerId(): LyricsProviderId = id
}
