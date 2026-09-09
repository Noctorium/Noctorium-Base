package app.spiceity.social

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Finds the public client identifier SoundCloud's own website uses.
 *
 * Their API refuses requests that do not carry one, and since app registration has been closed for years there
 * is no way to be issued one. The website publishes its own in plain JavaScript, so Spiceity reads it from there,
 * the same way every other third-party SoundCloud client does. It rotates occasionally, which is why
 * [invalidate] exists — a stale identifier is refetched rather than treated as a permanent failure.
 */
class SoundCloudClientIdProvider internal constructor(
    private val fetch: suspend (String) -> String? = ::fetchText,
) {
    @Volatile private var cached: String? = null

    suspend fun clientId(): String? {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            val page = fetch(HOME) ?: return@withContext null
            val scripts = SCRIPT.findAll(page).map { it.groupValues[1] }.toList().reversed()
            // The identifier lives in one of the last bundles, so searching from the end finds it sooner.
            for (script in scripts) {
                val body = fetch(script) ?: continue
                extractClientId(body)?.let { found ->
                    cached = found
                    return@withContext found
                }
            }
            null
        }
    }

    /** Forgets the identifier so the next call goes looking again, used when SoundCloud starts refusing it. */
    fun invalidate() {
        cached = null
    }

    internal companion object {
        const val HOME = "https://soundcloud.com/"
        private val SCRIPT = Regex("""src="(https://[^"]+\.js[^"]*)"""")
        private val CLIENT_ID = Regex("""client_id[=:]"?([A-Za-z0-9]{28,40})"?""")

        fun extractClientId(script: String): String? = CLIENT_ID.find(script)?.groupValues?.get(1)
    }
}

private suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            // SoundCloud serves a different page to clients that do not look like browsers.
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        }
        connection.getInputStream().use { it.readBytes().decodeToString() }
    }.getOrNull()
}
