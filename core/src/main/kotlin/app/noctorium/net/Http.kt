package app.noctorium.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A reply, read whole. */
data class HttpReply(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val ok: Boolean get() = status in 200..299

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    /** Enough of the body to put in a message, without pasting a hundred kilobytes into a log. */
    fun sample(limit: Int = 220): String = body.take(limit).replace('\n', ' ').trim()
}

/**
 * The one way Noctorium talks HTTP.
 *
 * It is OkHttp, and that choice is the reason this file exists rather than the calls being spread out. Two
 * things ruled out the alternatives, both of them learned the hard way:
 *
 * `java.net.HttpURLConnection` silently drops `Origin` from a request — it is on its restricted-header list
 * and says nothing about removing it. YouTube signs every request over its calling origin and then checks
 * the header against that signature, so a dropped `Origin` is answered with 401 no matter how correct the
 * signature is. That cost a long hunt through the wrong layer.
 *
 * `java.net.http.HttpClient` fixed that and is what the desktop used, but Android does not ship it at all,
 * at any API level. OkHttp runs identically on both and leaves headers alone, which is the whole
 * requirement.
 *
 * Nothing here is clever. Requests are small and replies are read whole, because every caller wants the
 * body as text and the largest of them — a full liked-songs listing — is tens of kilobytes.
 */
class Http(private val client: OkHttpClient = shared) {

    /**
     * Sends one request and reads the whole reply.
     *
     * A transport failure comes back as [HttpReply] with a status of 0 rather than as an exception, because
     * every caller has to say something useful either way, and "could not reach the service" and "the
     * service said no" are handled in the same place by all of them.
     */
    suspend fun send(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String = "application/json; charset=utf-8",
        timeoutSeconds: Long = 20,
    ): HttpReply = withContext(Dispatchers.IO) {
        val payload = when {
            body != null -> body.toRequestBody(contentType.toMediaType())
            // POST, PUT and PATCH need a body even when there is nothing to say, or OkHttp refuses them.
            method.uppercase() in METHODS_NEEDING_A_BODY -> ByteArray(0).toRequestBody()
            else -> null
        }
        val request = Request.Builder()
            .url(url)
            .method(method.uppercase(), payload)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        val call = client.newBuilder()
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
            .newCall(request)

        try {
            call.execute().use { response ->
                HttpReply(
                    status = response.code,
                    // An empty body is "" rather than null: every caller then parses or matches on it
                    // without first asking whether there was one.
                    body = response.body?.string().orEmpty(),
                    headers = response.headers.toMultimap(),
                )
            }
        } catch (error: IOException) {
            HttpReply(status = UNREACHABLE, body = error.message ?: "the request did not complete")
        }
    }

    companion object {
        /**
         * Not a status any server sends, which is the point: it means the request never got an answer.
         *
         * Telling this apart from a real refusal matters more than it looks. A 401 means a session is dead
         * and should be cleared; no connection at all means nothing about the session, and treating the two
         * alike is how somebody gets signed out of a service for being briefly offline.
         */
        const val UNREACHABLE = 0

        private val METHODS_NEEDING_A_BODY = setOf("POST", "PUT", "PATCH")

        /**
         * One client for the whole application, because OkHttp keeps its connection pool and its threads
         * inside it — a client per request would open a new pool each time and never reuse a connection.
         * Per-call timeouts are set by copying it, which shares all of that.
         */
        val shared: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(BrowserlikeDefaults)
            .build()

        /** What a browser sends and a bare HTTP client does not, added only where the caller said nothing. */
        private object BrowserlikeDefaults : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                val request = chain.request()
                val builder = request.newBuilder()
                if (request.header("Accept-Language") == null) {
                    builder.header("Accept-Language", "en-US,en;q=0.9")
                }
                if (request.header("User-Agent") == null) {
                    // SoundCloud sits behind bot protection that scores the whole request, so anything
                    // without a stated agent is judged on OkHttp's default one and can simply be refused.
                    builder.header("User-Agent", DESKTOP_USER_AGENT)
                }
                return chain.proceed(builder.build())
            }
        }

        /**
         * Presented on every request that does not override it, including from the phone.
         *
         * Deliberately the desktop string on both. These services serve different pages and different
         * player configurations to a phone, and the parsing on the other end of this was written against
         * what the desktop site returns.
         */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
    }
}
