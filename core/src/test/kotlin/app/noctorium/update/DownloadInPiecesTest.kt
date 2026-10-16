package app.noctorium.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A big update over several connections at once, against a stand-in for GitHub's release CDN on this machine.
 *
 * The file is three megabytes in pieces of 400,000 bytes, over four connections: the real thing is three
 * hundred megabytes in pieces of 7 MB over sixteen, which is the same arithmetic and too long for a test. The
 * bytes are not the same all the way through, so that a piece written in the wrong place fails the checksum.
 */
class DownloadInPiecesTest {
    private lateinit var directory: Path
    private val servers = mutableListOf<FakeCdn>()

    @BeforeTest
    fun start() {
        directory = Files.createTempDirectory("noctorium-pieces-test")
    }

    @AfterTest
    fun stop() {
        servers.forEach { it.server.shutdown() }
        directory.toFile().deleteRecursively()
    }

    private val body = ByteArray(3_200_000) { index -> (index * 7 + index / 251).toByte() }
    private val bodySha = sha256(body)
    private val into get() = directory.resolve("Noctorium-0.0.0-windows-x64.msi")

    private fun cdn() = FakeCdn(body).also { servers += it }

    private fun release(cdn: FakeCdn) = ReleaseFile(into.fileName.toString(), cdn.url, body.size.toLong())

    private fun downloader(calls: Calls = Calls(), connections: Int = 4, piece: Long = 400_000) = UpdateDownloader(
        client = OkHttpClient.Builder().eventListener(calls).readTimeout(10, TimeUnit.SECONDS).build(),
        split = { UpdateDownloader.Split(connections, piece) },
    )

    /** Each of the eight pieces, as the Range that asks for it. */
    private val eightPieces = (0 until 8).map { "bytes=${it * 400_000}-${it * 400_000 + 399_999}" }

    @Test
    fun `a big file comes down in pieces at once and is put together in order`() = runBlocking {
        val cdn = cdn().apply { pace = 100_000 }
        val calls = Calls()
        val seen = CopyOnWriteArrayList<Float>()

        val outcome = downloader(calls).fetch(release(cdn), bodySha, into) { seen += it }

        assertTrue(outcome is UpdateDownloader.Outcome.Ready, "$outcome")
        assertEquals(body.toList(), into.readBytes().toList())
        assertEquals(eightPieces, cdn.asked.map { it.range }.sortedBy { it?.removePrefix("bytes=")?.substringBefore('-')?.toLong() })
        assertTrue(calls.most.get() in 2..4, "${calls.most.get()} at once, of four connections")
        assertFalse(partialOf(into).exists() || noteOf(into).exists(), "the partial and its note were left behind")
        assertEquals(0f, seen.first(), "progress starts from nothing")
        assertEquals(1f, seen.last(), "and ends at all of it")
        assertTrue(seen.zipWithNext().all { (a, b) -> a <= b }, "progress only goes forward: $seen")
    }

    @Test
    fun `a piece whose connection drops is asked for again from where it stopped while the rest go on`() = runBlocking {
        // The fourth piece's first answer stops after 200,000 of its 400,000 bytes.
        val cdn = cdn().apply { cutOnceAt = 1_200_000 }

        val outcome = downloader().fetch(release(cdn), bodySha, into)

        assertTrue(outcome is UpdateDownloader.Outcome.Ready, "$outcome")
        assertEquals(body.toList(), into.readBytes().toList())
        val ranges = cdn.asked.map { it.range }
        assertTrue("bytes=1400000-1599999" in ranges, "the rest of the piece was not asked for: $ranges")
        assertEquals(9, ranges.size, "eight pieces and the rest of one of them: $ranges")
    }

    @Test
    fun `pieces after the first go straight to where the redirect landed`() = runBlocking {
        val cdn = cdn().apply { redirects = true }

        val outcome = downloader(connections = 2).fetch(release(cdn), bodySha, into)

        assertTrue(outcome is UpdateDownloader.Outcome.Ready, "$outcome")
        assertEquals(body.toList(), into.readBytes().toList())
        assertTrue(cdn.redirected.get() in 1..2, "only the first pieces went by way of the redirect: ${cdn.redirected.get()}")
        assertEquals(8, cdn.asked.size)
        assertTrue(cdn.asked.all { it.path.startsWith("/cdn/") }, "every piece came from where it pointed")
    }

    /** Six pieces one after another, from addresses that each serve two requests and then refuse. */
    @Test
    fun `an address that has expired is asked for again by way of the file's own`() = runBlocking {
        val cdn = cdn().apply {
            redirects = true
            expiresAfter = 2
        }

        val outcome = downloader(connections = 1, piece = 533_334).fetch(release(cdn), bodySha, into)

        assertTrue(outcome is UpdateDownloader.Outcome.Ready, "$outcome")
        assertEquals(body.toList(), into.readBytes().toList())
        assertEquals(6, cdn.asked.size, "each piece served once")
        assertEquals(3, cdn.redirected.get(), "a fresh address each time the last one expired")
        assertEquals(2, cdn.expired.get())
    }

    @Test
    fun `a download that stopped part of the way is carried on after a restart`() = runBlocking {
        // Every answer stops half way, so each piece gets less and less of the way and then gives up.
        val flaky = cdn().apply { alwaysCut = true }
        val first = downloader().fetch(release(flaky), bodySha, into)
        assertTrue(first is UpdateDownloader.Outcome.Failed, "$first")
        assertFalse(into.exists(), "a half download has the whole one's name")
        val had = assertNotNull(Ranges.read(noteOf(into)), "no note was left to carry on from").have()
        assertTrue(had > 0, "nothing was kept")

        // A new downloader, as after Noctorium is opened again, and a connection that behaves.
        val better = cdn()
        val seen = CopyOnWriteArrayList<Float>()
        val second = downloader().fetch(release(better), bodySha, into) { seen += it }

        assertTrue(second is UpdateDownloader.Outcome.Ready, "$second")
        assertEquals(body.toList(), into.readBytes().toList())
        assertEquals(body.size - had, better.asked.sumOf { it.length() }, "something already here was asked for again")
        assertEquals(had.toFloat() / body.size, seen.first(), 0.001f, "progress starts from what was there")
        assertFalse(partialOf(into).exists() || noteOf(into).exists())
    }

    @Test
    fun `a download that does not match its checksum leaves nothing that looks complete`() = runBlocking {
        val cdn = cdn()

        val outcome = downloader().fetch(release(cdn), sha256("something else".toByteArray()), into)

        assertTrue(outcome is UpdateDownloader.Outcome.Failed, "a mismatched download was kept: $outcome")
        assertEquals(8, cdn.asked.size, "fetched once, not again")
        assertEquals(emptyList(), directory.toFile().list()!!.toList(), "something was left behind")
    }

    /** A partial of the right length that is not the start of this file: a release whose file was replaced. */
    @Test
    fun `what an earlier attempt left that was never this file is fetched again from nothing`() = runBlocking {
        partialOf(into).writeBytes(ByteArray(1_000_000) { 0x5A })
        val cdn = cdn()

        val outcome = downloader().fetch(release(cdn), bodySha, into)

        assertTrue(outcome is UpdateDownloader.Outcome.Ready, "$outcome")
        assertEquals(body.toList(), into.readBytes().toList())
        // Carried on from first, in six pieces after the million bytes that were there; then, once that failed
        // its checksum, the whole file again in eight.
        val (carriedOn, again) = cdn.asked.map { it.range }.let { it.take(6) to it.drop(6) }
        assertTrue(carriedOn.all { it!!.removePrefix("bytes=").substringBefore('-').toLong() >= 1_000_000 }, "$carriedOn")
        assertEquals(eightPieces, again.sortedBy { it!!.removePrefix("bytes=").substringBefore('-').toLong() })
    }

    @Test
    fun `cancelling stops every connection and leaves what arrived for next time`() = runBlocking {
        // Four pieces of 800,000 bytes, one for each connection, at 160 kB/s each: five seconds to finish one.
        val cdn = cdn().apply { pace = 16_000 }
        val calls = Calls()
        val underway = CompletableDeferred<Unit>()

        val fetching = launch(Dispatchers.Default) {
            downloader(calls, piece = 800_000).fetch(release(cdn), bodySha, into) { if (it >= 0.1f) underway.complete(Unit) }
        }
        withTimeout(10_000) { underway.await() }
        fetching.cancel()
        // Not the seconds the pieces under way would take to finish: every connection stops at once.
        withTimeout(1_000) { fetching.join() }

        assertEquals(calls.started.get(), calls.ended.get(), "a connection was still open after the download was cancelled")
        assertFalse(into.exists())
        val had = assertNotNull(Ranges.read(noteOf(into)), "the note of what arrived was not kept").have()
        assertTrue(had >= body.size / 10, "less was noted than had been reported: $had")

        cdn.pace = null
        val before = cdn.asked.size
        val outcome = downloader().fetch(release(cdn), bodySha, into)
        assertTrue(outcome is UpdateDownloader.Outcome.Ready, "$outcome")
        assertEquals(body.toList(), into.readBytes().toList())
        assertEquals(body.size - had, cdn.asked.drop(before).sumOf { it.length() }, "it did not carry on from the note")
    }

    @Test
    fun `a server that will not send pieces is asked for the file whole over one connection`() = runBlocking {
        val cdn = cdn().apply { ranges = false }

        val outcome = downloader().fetch(release(cdn), bodySha, into)

        assertTrue(outcome is UpdateDownloader.Outcome.Ready, "$outcome")
        assertEquals(body.toList(), into.readBytes().toList())
        assertEquals(null, cdn.asked.last().range, "it was not asked for whole in the end: ${cdn.asked}")
        assertFalse(noteOf(into).exists())
    }

    @Test
    fun `the split is chosen by the size of the file`() {
        // The Windows package of 0.17.1: three pieces for each of sixteen connections.
        assertEquals(UpdateDownloader.Split(16, 7_225_087), UpdateDownloader.splitFor(346_804_140))
        // The terminal player's archive: one piece for each connection, just under four mebibytes.
        assertEquals(UpdateDownloader.Split(16, 4_015_849), UpdateDownloader.splitFor(64_253_579))
        // The phone's APK: one piece for each connection, all at once.
        assertEquals(UpdateDownloader.Split(16, 1_184_760), UpdateDownloader.splitFor(18_956_151))
        // Big enough for three pieces each only once they would be four mebibytes.
        assertEquals(UpdateDownloader.Split(16, 4L shl 20), UpdateDownloader.splitFor(100L shl 20))
        // Anything smaller than that comes down whole.
        assertEquals(UpdateDownloader.Split(1, 3_737_317), UpdateDownloader.splitFor(3_737_317))
        assertEquals(UpdateDownloader.Split(1, 1), UpdateDownloader.splitFor(0))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** How many calls were started and ended, and the most there were at once. */
private class Calls : EventListener() {
    val started = AtomicInteger()
    val ended = AtomicInteger()
    val most = AtomicInteger()
    private val open = AtomicInteger()

    override fun callStart(call: Call) {
        started.incrementAndGet()
        most.accumulateAndGet(open.incrementAndGet()) { a, b -> maxOf(a, b) }
    }

    override fun callEnd(call: Call) = ended()
    override fun callFailed(call: Call, ioe: IOException) = ended()

    private fun ended() {
        ended.incrementAndGet()
        open.decrementAndGet()
    }
}

/** One request for the file's bytes: where it was sent, and the Range it asked for, if any. */
private data class Asked(val path: String, val range: String?) {
    /** How many bytes it asked for, of a closed range. */
    fun length(): Long {
        val (first, last) = range!!.removePrefix("bytes=").split('-').map(String::toLong)
        return last - first + 1
    }
}

/**
 * GitHub's release CDN, as far as a download can tell: the whole file, or the bytes a Range asks for, to as many
 * connections at once as ask -- and, when told to, each of the ways the real one goes wrong.
 */
private class FakeCdn(private val body: ByteArray) : Dispatcher() {
    val server = MockWebServer().also {
        it.dispatcher = this
        it.start()
    }
    val url: String = server.url("/file.bin").toString()

    /** False for a server that knows nothing of ranges, and sends the whole file whatever was asked. */
    @Volatile var ranges = true

    /** The file's address answers with a redirect to an address of its own, as github.com's does. */
    @Volatile var redirects = false

    /** How many requests each address redirected to serves before it refuses, as a signed one that has expired does. */
    @Volatile var expiresAfter = Int.MAX_VALUE

    /** The first answer for the range that starts here stops half way, as a dropped connection does. */
    @Volatile var cutOnceAt: Long? = null

    /** Every answer stops half way. */
    @Volatile var alwaysCut = false

    /** Bytes a tenth of a second, for each answer, or as fast as they go. */
    @Volatile var pace: Long? = null

    /** Every request for the file's bytes that was answered with them, in the order they came. */
    val asked = CopyOnWriteArrayList<Asked>()
    val redirected = AtomicInteger()
    val expired = AtomicInteger()
    private val served = ConcurrentHashMap<String, AtomicInteger>()
    private val cut = AtomicBoolean(false)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        if (redirects && path == "/file.bin") {
            val signed = redirected.incrementAndGet()
            return MockResponse().setResponseCode(302).setHeader("Location", server.url("/cdn/$signed/file.bin"))
        }
        if (path.startsWith("/cdn/") && served.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet() > expiresAfter) {
            expired.incrementAndGet()
            return MockResponse().setResponseCode(403)
        }
        val range = request.getHeader("Range")
        asked += Asked(path, range)

        val (first, last) = range?.takeIf { ranges }?.removePrefix("bytes=")?.split('-')
            ?.let { (from, to) -> from.toLong() to (to.toLongOrNull() ?: (body.size - 1L)).coerceAtMost(body.size - 1L) }
            ?: (0L to body.size - 1L)
        if (first >= body.size) return MockResponse().setResponseCode(416)
        val answer = MockResponse().setBody(okio.Buffer().write(body, first.toInt(), (last - first + 1).toInt()))
        if (range != null && ranges) {
            answer.setResponseCode(206).setHeader("Content-Range", "bytes $first-$last/${body.size}")
        }
        if (alwaysCut || (cutOnceAt == first && cut.compareAndSet(false, true))) {
            answer.socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
        }
        pace?.let { answer.throttleBody(it, 100, TimeUnit.MILLISECONDS) }
        return answer
    }
}
