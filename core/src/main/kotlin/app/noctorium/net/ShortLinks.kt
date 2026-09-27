package app.noctorium.net

import app.noctorium.domain.LinkKind
import app.noctorium.domain.MusicLink
import app.noctorium.domain.findMusicLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Follows a share link to the page it stands for.
 *
 * SoundCloud's share button writes `on.soundcloud.com/AbC12`, which says nothing about what is behind it
 * until it is asked. The answer is a redirect to the real address, and that address is read like any
 * pasted link. The redirects are followed by hand, one at a time, so that the moment one of them names a
 * track or a playlist the following stops: the page it points to is never downloaded, because where it
 * leads is all that matters here.
 *
 * Null when the link leads somewhere that is not a track or a playlist, or nowhere.
 */
class ShortLinks(client: OkHttpClient = Http.shared) {
    private val oneHop = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun expand(link: MusicLink): MusicLink? {
        if (link.kind != LinkKind.SHORT) return link
        return withContext(Dispatchers.IO) {
            runCatching {
                var current = link.url
                repeat(MAX_HOPS) {
                    val next = oneHop.newCall(Request.Builder().url(current).build()).execute().use { reply ->
                        reply.header("Location")?.takeIf { reply.isRedirect }?.let { reply.request.url.resolve(it) }
                    }?.toString() ?: return@runCatching null
                    findMusicLink(next)?.takeIf { it.kind != LinkKind.SHORT }?.let { return@runCatching it }
                    current = next
                }
                null
            }.getOrNull()
        }
    }

    private companion object {
        /** A share link is one redirect, sometimes two. Past a handful it is going round in a circle. */
        const val MAX_HOPS = 5
    }
}
