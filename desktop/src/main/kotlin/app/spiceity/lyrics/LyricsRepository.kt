package app.spiceity.lyrics

import app.spiceity.domain.Track
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
        val outcomes = supervisorScope {
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
