package app.noctorium.update

import app.noctorium.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches an update and proves it is the one that was published.
 *
 * The checksum is not optional. Everything this downloads is then handed to an installer, or to Android's
 * package installer, and asking either of them to run something that arrived over a network without
 * checking what arrived is the one step that turns a broken download into a running program. A release
 * that published no checksum is refused and the listener is sent to the page instead -- they can look at
 * it themselves, which is more than an unverified installer offers.
 *
 * A big file comes down in pieces, over as many as [CONNECTIONS] connections at once, because GitHub's
 * release CDN gives each connection about 2 MB/s however fast the line is: see [CONNECTIONS] for what that
 * measured as. A file under [SPLIT_FROM] comes down over one, as every update did before.
 *
 * Nothing is written under the update's own name until it is whole and has matched. Until then it is
 * `<name>.part`, which no installer is ever handed, and beside it a download in pieces keeps a note of how
 * far each piece has got, `<name>.part.ranges`. A later attempt -- after a dropped connection, a cancelled
 * download, or Noctorium closing half way through one -- carries on from them rather than starting again.
 * The checksum is still of every byte of the whole file, and one carried on from what an earlier attempt
 * left that then fails it is fetched once more from nothing, in case what was left was never this file.
 *
 * The hash is computed as the file arrives rather than by reading it all again afterwards. These are a third
 * of a gigabyte, and the second read would be slower than the download on a fast connection. Over one
 * connection that is while the bytes go past. Over several they arrive out of order, but the pieces are
 * handed out from the front of the file, so the stretch from its first byte that is whole keeps growing
 * behind them, and that is read back and hashed as it grows, while the system still has it in memory.
 */
class UpdateDownloader(
    private val client: OkHttpClient = Http.shared,
    /**
     * How a file of a given size is split. Overridden only by tests, which cannot spend a hundred megabytes
     * to see a file come down in pieces.
     */
    private val split: (bytes: Long) -> Split = Companion::splitFor,
) {
    sealed interface Outcome {
        /** Verified, and sitting somewhere the installer can reach. */
        data class Ready(val file: Path) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Over how many connections at most a file comes down, and in pieces of at most how many bytes. */
    data class Split(val connections: Int, val piece: Long)

    suspend fun fetch(
        file: ReleaseFile,
        expectedSha256: String?,
        into: Path,
        onProgress: (fraction: Float) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        if (expectedSha256.isNullOrBlank()) {
            return@withContext Outcome.Failed(
                "This release did not publish a checksum, so it cannot be verified. Download it yourself.",
            )
        }
        val published = expectedSha256.trim().lowercase()
        val address = file.url.toHttpUrlOrNull()
            ?: return@withContext Outcome.Failed("The release names its file at an address that is not one.")
        val partial = partialOf(into)
        val note = noteOf(into)
        val progress = Progress(onProgress)
        runCatching { Files.createDirectories(into.parent) }
        clearOthers(into)

        // Only ever put there by an earlier attempt that verified it, whose installer was then closed or
        // refused. Checked again, it is the published file wherever it came from and needs no second
        // download; anything else under that name is not the update, and is not left where it could be taken
        // for it.
        if (hashOf(into, file.bytes) == published) {
            clear(partial, note)
            progress.report(1, 1)
            return@withContext Outcome.Ready(into)
        }
        runCatching { Files.deleteIfExists(into) }

        // HTTP/1.1 only. The CDN offers HTTP/2, over which OkHttp carries every request to one server on one
        // connection, and the CDN's pace is per connection: sixteen pieces of the Windows package at once took
        // 131 seconds that way, the same as one. And a pool of its own, big enough to keep every connection
        // from one piece to the next, closed once this is done rather than leaving sixteen idle sockets about.
        val downloading = client.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(CONNECTIONS, 1, TimeUnit.MINUTES))
            .build()
        try {
            download(Source(address, downloading), file.bytes, published, partial, note, into, progress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: Refused) {
            Outcome.Failed("The download was refused.")
        } catch (stopped: Stopped) {
            Outcome.Failed(stopped.sentence)
        } catch (error: IOException) {
            ensureActive()
            Outcome.Failed(networkSentence(error))
        } finally {
            downloading.connectionPool.evictAll()
            // Nothing arrived that is worth carrying on from, so no reason to leave about a file the size of
            // the update, which is what a download in pieces sets aside before its first byte comes.
            if (nothingIn(partial, note)) clear(partial, note)
        }
    }

    private suspend fun download(
        source: Source,
        total: Long,
        published: String,
        partial: Path,
        note: Path,
        into: Path,
        progress: Progress,
    ): Outcome {
        val split = split(total)
        val connections = split.connections.coerceAtLeast(1)
        val piece = split.piece.coerceAtLeast(1)
        val inPieces = total > 0 && (connections > 1 || piece < total)
        var left = leftBehind(partial, note, total, published)
        while (true) {
            val fetched = try {
                val earlier = left
                if (inPieces) {
                    // A note an earlier attempt left is carried on from in pieces of today's size, however it
                    // was split then, so that a download stopped half way comes back over as many connections
                    // as a fresh one would.
                    val ranges = when (earlier) {
                        is Left.Pieces -> earlier.ranges.subdivided(piece)
                        is Left.Prefix -> Ranges.fresh(total, published, piece, earlier.bytes)
                    }
                    fetchPieces(source, partial, note, ranges, connections, progress)
                } else {
                    // Carried on in one piece from whatever is whole at the start.
                    val have = when (earlier) {
                        is Left.Pieces -> earlier.ranges.prefix().also { clear(note) }
                        is Left.Prefix -> earlier.bytes
                    }
                    fetchWhole(source, partial, have, total, progress)
                }
            } catch (notOffered: NotOffered) {
                // The server will not hand the file over in pieces, so it is asked for whole.
                clear(partial, note)
                fetchWhole(source, partial, 0, total, progress)
            }

            if (fetched.sha256 == published) {
                clear(note)
                runCatching { Files.move(partial, into, StandardCopyOption.REPLACE_EXISTING) }
                    .getOrElse { return Outcome.Failed("Could not put the download where it belongs.") }
                return Outcome.Ready(into)
            }
            // Deleted rather than kept. A file that failed its checksum is either a broken download or
            // something that is not what it claims, and neither is worth leaving on the disk -- or worth
            // carrying on from next time.
            clear(partial, note)
            if (fetched.resumedFrom == 0L) {
                return Outcome.Failed("The download did not match its checksum, so it was discarded.")
            }
            left = Left.Prefix(0)
        }
    }

    /**
     * Fetches whatever [ranges] says is still missing from [partial], over as many as [connections] at once,
     * keeping the note at [note] up to date as they go.
     *
     * The pieces wait in order, and each connection takes the first one still waiting as soon as it is free,
     * so the file fills from the front, and a connection that happens to be slow fetches fewer pieces rather
     * than being the one everything waits for at the end.
     *
     * A piece that fails after its [ATTEMPTS] stops any more from being started, and once the ones on their
     * way have finished or failed too, the first failure is what is thrown -- with everything that did arrive
     * in the note, cancelled or not.
     */
    private suspend fun fetchPieces(
        source: Source,
        partial: Path,
        note: Path,
        ranges: Ranges,
        connections: Int,
        progress: Progress,
    ): Fetched {
        val total = ranges.total
        val before = ranges.have()
        // The whole size from the start, so that each piece can be written where it goes.
        local("make room for") { RandomAccessFile(partial.toFile(), "rw").use { it.setLength(total) } }
        ranges.writeTo(note)
        progress.report(before, total)

        val waiting = ConcurrentLinkedQueue(ranges.parts.filter { it.done < it.length })
        val workers = minOf(connections, waiting.size)
        val failure = AtomicReference<Exception?>(null)
        val digest = MessageDigest.getInstance("SHA-256")
        var hashed = 0L
        local("write to") { FileChannel.open(partial, StandardOpenOption.READ, StandardOpenOption.WRITE) }.use { file ->
            try {
                coroutineScope {
                    val arrived = Channel<Unit>(Channel.CONFLATED)
                    val fetching = List(workers) {
                        launch {
                            // A cancelled download starts no more pieces.
                            while (failure.get() == null && isActive) {
                                val part = waiting.poll() ?: break
                                try {
                                    fetchPart(source, file, part, total) { arrived.trySend(Unit) }
                                } catch (notOffered: NotOffered) {
                                    // Outranks a piece that failed: the answer to it is to ask for the file whole.
                                    failure.set(notOffered)
                                } catch (refused: Refused) {
                                    failure.compareAndSet(null, refused)
                                } catch (stopped: Stopped) {
                                    failure.compareAndSet(null, stopped)
                                }
                            }
                        }
                    }
                    launch {
                        fetching.joinAll()
                        arrived.close()
                    }

                    var noted = before
                    arrived.consumeEach {
                        val have = ranges.have()
                        progress.report(have, total)
                        // Every few megabytes rather than at every piece of news: the note is written whole.
                        if (have - noted >= NOTE_EVERY) {
                            noted = have
                            ranges.writeTo(note)
                        }
                        // Likewise the hash, which reads back what the pieces wrote while the system still has it.
                        val whole = ranges.prefix()
                        if (whole - hashed >= HASH_EVERY) {
                            hashSpan(file, digest, hashed, whole)
                            hashed = whole
                        }
                    }
                }
            } finally {
                // Whatever happened, what arrived is noted, for the next attempt to carry on from. Every
                // connection has stopped by now: coroutineScope does not return before they all have.
                ranges.writeTo(note)
            }
            failure.get()?.let { throw it }
            progress.report(total, total)
            hashSpan(file, digest, hashed, total)
        }
        return Fetched(hex(digest), before)
    }

    /**
     * Fetches the rest of one piece into its place in [file], saying how much of it has arrived only once that
     * much is written, so that the note never claims what the file does not have.
     *
     * Each attempt after the first asks only for what is still missing of the piece, so a connection that
     * drops costs the bytes it dropped rather than the piece again, and the other connections carry on
     * meanwhile.
     */
    private suspend fun fetchPart(source: Source, file: FileChannel, part: Part, total: Long, arrived: () -> Unit) {
        val buffer = ByteArray(BUFFER_BYTES)
        var problem: IOException? = null
        repeat(ATTEMPTS) {
            val from = part.start + part.done
            try {
                source.get("bytes=$from-${part.end - 1}") { response ->
                    // Only the range asked for, of a file the size the release says, is this piece. Anything
                    // else -- the whole file, a range from somewhere else, a 416 -- is from a server that will
                    // not split it.
                    val range = ContentRange.of(response)
                    if (response.code != 206 || range == null || range.first != from || range.total != total) {
                        throw NotOffered()
                    }
                    val body = Filling(response.body?.source() ?: throw NotOffered())
                    while (part.done < part.length) {
                        // Never more than the piece, whatever the server sends: the next piece's bytes are not
                        // this one's to write.
                        val read = body.fill(buffer, minOf(BUFFER_BYTES.toLong(), part.length - part.done).toInt())
                        if (read == 0) break
                        file.writeAt(buffer, read, part.start + part.done)
                        part.done += read
                        arrived()
                    }
                }
                if (part.done == part.length) return
                problem = IOException("the connection closed before the piece was whole")
            } catch (error: IOException) {
                // A cancelled download is not a connection to try again.
                currentCoroutineContext().ensureActive()
                problem = error
            }
        }
        throw Stopped(networkSentence(problem))
    }

    /**
     * Fetches the file onto the end of [partial], which already holds its first [from] bytes, over one
     * connection, hashing it as it goes; and says what the whole of it hashes to, and how much of it was there
     * before -- nothing, when the server sent the whole file again.
     */
    private suspend fun fetchWhole(source: Source, partial: Path, from: Long, expected: Long, progress: Progress): Fetched {
        val digest = MessageDigest.getInstance("SHA-256")
        val file = local("write to") {
            FileChannel.open(partial, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        }
        file.use {
            var have = minOf(from, local("read") { file.size() })
            local("write to") { file.truncate(have) }
            // What is there already is hashed first, so the hash at the end is of the whole file and not only
            // of what came down this time.
            hashSpan(file, digest, 0, have)
            var carriedOnFrom = have
            if (expected in 1..have) {
                progress.report(have, expected)
                return Fetched(hex(digest), carriedOnFrom)
            }

            fun startAgain() {
                local("write to") { file.truncate(0) }
                digest.reset()
                have = 0
                carriedOnFrom = 0
            }

            val buffer = ByteArray(BUFFER_BYTES)
            var problem: IOException? = null
            repeat(ATTEMPTS) {
                try {
                    source.get(if (have > 0) "bytes=$have-" else null) { response ->
                        val range = ContentRange.of(response)
                        val total = when {
                            response.code == 206 && range != null && range.first == have -> range.total ?: expected
                            response.code == 206 || response.code == 416 -> {
                                // What is here is not the start of this file -- it was replaced by a shorter one,
                                // say -- so the next attempt asks for all of it.
                                startAgain()
                                throw IOException("the server had none of the file from where it had stopped")
                            }
                            else -> {
                                // Asked for the rest and sent all of it, or asked for all of it: either way from
                                // the start, and nothing written so far counts.
                                if (have > 0) startAgain()
                                response.body?.contentLength()?.takeIf { it > 0 } ?: expected
                            }
                        }
                        progress.report(have, total)
                        val body = Filling(response.body?.source() ?: return@get)
                        while (true) {
                            val read = body.fill(buffer, BUFFER_BYTES)
                            if (read == 0) break
                            file.writeAt(buffer, read, have)
                            digest.update(buffer, 0, read)
                            have += read
                            progress.report(have, total)
                        }
                    }
                    return Fetched(hex(digest), carriedOnFrom)
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    problem = error
                }
            }
            throw Stopped(networkSentence(problem))
        }
    }

    /**
     * Reads what an earlier attempt left, and clears away whatever cannot be carried on from: a `.part` longer
     * than the whole, a note that does not fit its file or this release, or a note with no file.
     */
    private fun leftBehind(partial: Path, note: Path, total: Long, sha256: String): Left {
        val length = runCatching { if (Files.isRegularFile(partial)) Files.size(partial) else null }.getOrNull()
        if (Files.exists(note)) {
            val ranges = Ranges.read(note)
            if (ranges != null && ranges.total == total && ranges.sha256 == sha256 && length == total) {
                return Left.Pieces(ranges)
            }
            clear(note, partial)
            return Left.Prefix(0)
        }
        return when {
            length == null -> Left.Prefix(0)
            total <= 0 || length <= total -> Left.Prefix(length)
            else -> {
                clear(partial)
                Left.Prefix(0)
            }
        }
    }

    /**
     * Where the pieces of one download are asked for.
     *
     * A release file's address is github.com's, and github.com answers every request for it with a redirect
     * to its CDN: a round trip to github.com before each piece can start, and on a connection of its own a
     * handshake with github.com as well. The first answer says where the redirect landed, and the pieces
     * after it are asked for there directly. That address is signed, and expires -- within the hour, as
     * GitHub signs them now -- so anything but an answer from it sends the request to github.com again,
     * which signs a fresh one.
     */
    private class Source(private val address: HttpUrl, private val client: OkHttpClient) {
        private val landed = AtomicReference<HttpUrl?>(null)

        /** Asks for [range] of the file, or for all of it, and hands an answer that is not an error to [read]. */
        suspend fun get(range: String?, read: (Response) -> Unit) {
            val direct = landed.get()
            if (direct != null) {
                var answered = false
                try {
                    return client.newCall(request(direct, range)).readWhileActive { response ->
                        if (!response.isSuccessful && response.code != 416) {
                            throw IOException("the address GitHub redirected to answered ${response.code}")
                        }
                        answered = true
                        read(response)
                    }
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    // Something going wrong part of the way through an answer is the reader's to deal with, not
                    // a reason to ask somewhere else.
                    if (answered) throw error
                }
                landed.compareAndSet(direct, null)
            }
            client.newCall(request(address, range)).readWhileActive { response ->
                when {
                    response.isSuccessful || response.code == 416 -> Unit
                    // Asked again, a server in trouble may answer otherwise. One that said no will say it again.
                    response.code >= 500 -> throw IOException("GitHub answered ${response.code}")
                    else -> throw Refused(response.code)
                }
                val at = response.request.url
                if (response.isSuccessful && at != address) landed.set(at)
                read(response)
            }
        }

        private fun request(url: HttpUrl, range: String?): Request = Request.Builder()
            .url(url)
            .apply { if (range != null) header("Range", range) }
            .build()
    }

    /** What an earlier attempt left of a download. */
    private sealed interface Left {
        /** The first this many bytes, in a `.part` that holds nothing else. Nothing at all is `Prefix(0)`. */
        data class Prefix(val bytes: Long) : Left

        /** A `.part` of the whole size, filled in as far as its note says. */
        data class Pieces(val ranges: Ranges) : Left
    }

    /** What the whole file hashes to, and how much of it was there before this attempt. */
    private class Fetched(val sha256: String, val resumedFrom: Long)

    /**
     * Tells the listener how far a download has got: whenever that has moved by a thousandth, which is finer
     * than any bar on a screen can show, and never from two connections at once.
     */
    private class Progress(private val listener: (Float) -> Unit) {
        private var told = Float.NaN

        @Synchronized
        fun report(have: Long, total: Long) {
            if (total <= 0) return
            val fraction = (have.toDouble() / total).toFloat().coerceIn(0f, 1f)
            val moved = told.isNaN() || fraction < told || fraction - told >= PROGRESS_STEP
            if (moved || (fraction == 1f && told != 1f)) {
                told = fraction
                listener(fraction)
            }
        }
    }

    companion object {
        /**
         * How many connections one big download is split across, at most.
         *
         * GitHub's release CDN gives each connection about 2 MB/s however fast the line is, so until the line
         * itself is full a download takes its size over the number of connections. Measured on a home
         * connection: the 347 MB Windows package of 0.17.1 took 132 seconds over one connection, three times out
         * of three, as every update did before this; and 12 to 13 seconds over sixteen, or up to 20 while the
         * line was busy -- when the same sixteen ranges fetched bare, with nothing written, took as long. The
         * Windows installer measured the same and settled on sixteen: it is all of any line slower than about
         * 250 Mbit/s, and as many as download managers open to one server.
         */
        const val CONNECTIONS = 16

        /**
         * Below this a file comes down in one piece over one connection: a few seconds, and nothing Noctorium
         * updates is that small. The phone's APK, the smallest, is 19 MB, and is split.
         */
        const val SPLIT_FROM: Long = 16L * MEBIBYTE

        /**
         * How many pieces each connection is given of a big download. More pieces than connections, so that one
         * that happens to be slow fetches fewer of them instead of being the one everything waits for at the end.
         */
        private const val PIECES_PER_CONNECTION = 3

        /**
         * The smallest piece worth a second or a third round of requests: two seconds at the CDN's pace, against
         * the round trip that asking for it costs.
         */
        private const val SMALLEST_PIECE: Long = 4L * MEBIBYTE

        /**
         * How a file of [bytes] bytes is split: [PIECES_PER_CONNECTION] pieces for each of [CONNECTIONS], where
         * those would be no smaller than [SMALLEST_PIECE], and otherwise one piece for each connection.
         *
         * The Windows package is forty-eight pieces of 7.2 MB, and the terminal player's archives sixteen of 4 MB.
         * The phone's 19 MB APK is sixteen of 1.2 MB, all at once, where it used to take 7.7 to 7.9 seconds in
         * one. Measured in turn, three times each: 1.8 seconds that way, 1.9 to 2.0 in ten pieces of 2 MiB, 2.7
         * to 2.9 in five of 4 MiB -- which is how the Windows installer would have split it -- and 10 to 11 in
         * one. The round trip a small piece costs is still less than waiting for it behind a bigger one.
         */
        fun splitFor(bytes: Long): Split {
            if (bytes < SPLIT_FROM) return Split(1, bytes.coerceAtLeast(1))
            val oneEach = minOf(ceilDiv(bytes, CONNECTIONS.toLong()), SMALLEST_PIECE)
            val piece = maxOf(ceilDiv(bytes, CONNECTIONS.toLong() * PIECES_PER_CONNECTION), oneEach)
            return Split(minOf(CONNECTIONS.toLong(), ceilDiv(bytes, piece)).toInt(), piece)
        }

        /** How many times one piece is asked for, when its connection breaks or GitHub has a bad moment. */
        private const val ATTEMPTS = 3

        /**
         * Written to the disk 64 KiB at a time rather than as the few kilobytes each read hands over, and no more
         * than that at a time either: what is still in a buffer has not been reported, and on a slow connection a
         * bigger one would hold the bar still for seconds.
         */
        private const val BUFFER_BYTES = 1 shl 16

        /** How often the note is written again, and the front of the file hashed, as a download in pieces goes. */
        private const val NOTE_EVERY = 8 * MEBIBYTE
        private const val HASH_EVERY = 4 * MEBIBYTE

        private const val PROGRESS_STEP = 0.001f

        private fun ceilDiv(dividend: Long, divisor: Long): Long = (dividend + divisor - 1) / divisor
    }
}

private const val MEBIBYTE = 1L shl 20

/** The server answered with a refusal rather than the file, and would answer the same again. */
private class Refused(status: Int) : Exception("answered $status")

/** Why a download did not finish, in a sentence for the listener. */
private class Stopped(val sentence: String) : Exception(sentence)

/** The server sent the whole file, or something else, when asked for a piece of it. */
private class NotOffered : Exception("the server will not send the file in pieces")

/** Where a download is written until it is whole and checked: beside where it is going, so the last step is a rename. */
internal fun partialOf(into: Path): Path = into.resolveSibling(into.fileName.toString() + ".part")

/** Where a download in pieces notes how far each piece has got, so that the next attempt can carry on from it. */
internal fun noteOf(into: Path): Path = into.resolveSibling(into.fileName.toString() + ".part.ranges")

/** One piece of a download: its first byte, the byte after its last, and how much of it has arrived from its first. */
internal class Part(val start: Long, val end: Long, done: Long = 0) {
    /** Written only by the connection fetching this piece, and only once that much is on the disk. */
    @Volatile
    var done: Long = done

    val length: Long get() = end - start
}

/**
 * A download in pieces: the size of the file, the checksum it is being fetched to match, and its pieces in
 * order. The checksum is there so that a note left by a different file of the same name and size -- a
 * release whose file was replaced -- is not carried on from.
 */
internal class Ranges(val total: Long, val sha256: String, val parts: List<Part>) {

    fun have(): Long = parts.sumOf { it.done }

    /** How much of the file from its first byte is whole. */
    fun prefix(): Long {
        var whole = 0L
        for (part in parts) {
            if (part.start != whole) break
            whole += part.done
            if (part.done < part.length) break
        }
        return whole
    }

    /**
     * The same, with whatever is still to come of each piece cut into pieces of at most [piece] bytes: what has
     * arrived of a piece stays a piece of its own, whole, and the rest of it comes after.
     */
    fun subdivided(piece: Long): Ranges = Ranges(
        total,
        sha256,
        parts.flatMap { part ->
            if (part.length - part.done <= piece) return@flatMap listOf(part)
            val from = part.start + part.done
            val arrived = if (part.done > 0) listOf(Part(part.start, from, part.done)) else emptyList()
            arrived + pieces(from, part.end, piece)
        },
    )

    /**
     * Written beside the note and moved over it, so that an attempt stopped in the middle of writing it leaves
     * the last one whole rather than half of a new one. Not being able to write it costs only the carrying on.
     */
    fun writeTo(path: Path) {
        val text = buildString {
            append(HEADER).append('\n')
            append("total ").append(total).append('\n')
            append("sha256 ").append(sha256).append('\n')
            for (part in parts) append(part.start).append(' ').append(part.end).append(' ').append(part.done).append('\n')
        }
        val fresh = path.resolveSibling(path.fileName.toString() + ".new")
        runCatching {
            Files.write(fresh, text.toByteArray())
            Files.move(fresh, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    companion object {
        /** What a note starts with, so that a file that happens to have its name is not read as one. */
        const val HEADER = "noctorium update ranges 1"

        /** [total] bytes in pieces of at most [piece] bytes, after a first one of [have] bytes that is there already. */
        fun fresh(total: Long, sha256: String, piece: Long, have: Long): Ranges {
            val there = have.coerceIn(0, total)
            val first = if (there > 0) listOf(Part(0, there, there)) else emptyList()
            return Ranges(total, sha256, first + pieces(there, total, piece))
        }

        fun read(path: Path): Ranges? {
            val lines = runCatching { Files.readAllLines(path) }.getOrNull()?.filter(String::isNotBlank) ?: return null
            if (lines.size < 3 || lines[0] != HEADER) return null
            val total = lines[1].takeIf { it.startsWith("total ") }?.removePrefix("total ")?.trim()?.toLongOrNull()
                ?: return null
            val sha256 = lines[2].takeIf { it.startsWith("sha256 ") }?.removePrefix("sha256 ")?.trim() ?: return null
            val parts = lines.drop(3).map { line ->
                val numbers = line.trim().split(' ').map { it.toLongOrNull() ?: return null }
                if (numbers.size != 3) return null
                val (start, end, done) = numbers
                if (start > end || done < 0 || done > end - start || end > total) return null
                Part(start, end, done)
            }
            // The pieces have to be the whole file, in order, with nothing missing or twice over.
            var at = 0L
            for (part in parts) {
                if (part.start != at) return null
                at = part.end
            }
            return if (at == total) Ranges(total, sha256, parts) else null
        }

        /** The bytes from [from] to [to], in as few pieces of at most [piece] bytes as hold them, as even as they come. */
        private fun pieces(from: Long, to: Long, piece: Long): List<Part> {
            val length = to - from
            if (length <= 0) return emptyList()
            val count = (length + piece - 1) / piece
            val share = (length + count - 1) / count
            val parts = mutableListOf<Part>()
            var start = from
            while (start < to) {
                val end = minOf(start + share, to)
                parts += Part(start, end)
                start = end
            }
            return parts
        }
    }
}

/**
 * Runs the call and hands its answer to [read], cancelling the call if the coroutine is cancelled meanwhile.
 *
 * OkHttp blocks a thread while it waits for bytes, and a blocked thread does not notice its coroutine being
 * cancelled: the call itself has to be, which makes whatever it is blocked in fail at once. Without this, a
 * cancelled download in pieces would go on until every connection's read timed out.
 */
private suspend fun <T> Call.readWhileActive(read: (Response) -> T): T = coroutineScope {
    val call = this@readWhileActive
    val finished = AtomicBoolean(false)
    val watching = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            // Only a call still running: cancelling one that has finished could close a connection that has
            // gone back to the pool and on to the next piece.
            if (!finished.get()) call.cancel()
        }
    }
    try {
        call.execute().use(read)
    } finally {
        finished.set(true)
        watching.cancel()
    }
}

/** `Content-Range: bytes 1000-1999/2000`: where an answer starts, and how long the whole file is, when it says. */
private class ContentRange(val first: Long, val total: Long?) {
    companion object {
        private val PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")

        fun of(response: Response): ContentRange? {
            val match = PATTERN.find(response.header("Content-Range") ?: return null) ?: return null
            val first = match.groupValues[1].toLongOrNull() ?: return null
            return ContentRange(first, match.groupValues[3].toLongOrNull())
        }
    }
}

/**
 * An answer's body, read a buffer at a time rather than the few kilobytes each read of it hands over.
 *
 * When the connection breaks part of the way through filling a buffer, what had arrived is handed over first and
 * the failure thrown at the next read, so that it is written down and the next attempt asks only for the rest.
 */
private class Filling(private val source: BufferedSource) {
    private var broken: IOException? = null

    /** Reads until [buffer] holds [count] bytes or the body has ended, and says how many it holds. */
    fun fill(buffer: ByteArray, count: Int): Int {
        broken?.let { throw it }
        var filled = 0
        while (filled < count) {
            val read = try {
                source.read(buffer, filled, count - filled)
            } catch (error: IOException) {
                if (filled == 0) throw error
                broken = error
                break
            }
            if (read < 0) break
            filled += read
        }
        return filled
    }
}

/** Writes the first [count] bytes of [bytes] at [position], all of them. */
private fun FileChannel.writeAt(bytes: ByteArray, count: Int, position: Long) = local("write to") {
    val buffer = ByteBuffer.wrap(bytes, 0, count)
    var at = position
    while (buffer.hasRemaining()) at += write(buffer, at)
}

/** Feeds bytes [from] to [to] of [file] through [digest], all of them or a [Stopped]. */
private fun hashSpan(file: FileChannel, digest: MessageDigest, from: Long, to: Long) = local("read back") {
    val buffer = ByteBuffer.allocate(1 shl 18)
    var at = from
    while (at < to) {
        buffer.clear()
        buffer.limit(minOf(buffer.capacity().toLong(), to - at).toInt())
        val read = file.read(buffer, at)
        if (read < 0) throw IOException("it is shorter than what had arrived")
        digest.update(buffer.array(), 0, read)
        at += read
    }
}

/** What the file at [path] hashes to, when it is a file of [bytes] bytes -- or of any size, when that is not known. */
private fun hashOf(path: Path, bytes: Long): String? = runCatching {
    if (!Files.isRegularFile(path) || (bytes > 0 && Files.size(path) != bytes)) return@runCatching null
    FileChannel.open(path, StandardOpenOption.READ).use { file ->
        val digest = MessageDigest.getInstance("SHA-256")
        hashSpan(file, digest, 0, file.size())
        hex(digest)
    }
}.getOrNull()

private fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

/**
 * Runs [block], and turns a failure of this machine's own disk into a [Stopped] that says so, rather than an
 * IOException that would be taken for the network's and tried again.
 */
private inline fun <T> local(what: String, block: () -> T): T = try {
    block()
} catch (error: IOException) {
    throw Stopped("Could not $what the download: ${error.message ?: error.javaClass.simpleName}")
}

/** What to tell the listener about a connection that failed: whether GitHub could be reached at all, and that trying again carries on. */
private fun networkSentence(error: IOException?): String = when (error) {
    is UnknownHostException, is ConnectException -> "Could not reach GitHub to download the update."
    else -> "The download stopped before it was finished. Trying again carries on from where it got to."
}

/** Whether what an attempt left holds nothing at all that the next could carry on from. */
private fun nothingIn(partial: Path, note: Path): Boolean = runCatching {
    if (Files.exists(note)) Ranges.read(note)?.have() == 0L else Files.exists(partial) && Files.size(partial) == 0L
}.getOrDefault(false)

/**
 * Clears away what attempts at other files left beside [into]: an older release's download, given up on part of
 * the way, which nothing will carry on from now that there is a newer one, and which can be hundreds of megabytes.
 */
private fun clearOthers(into: Path) {
    val mine = setOf(partialOf(into), noteOf(into)).map { it.fileName.toString() }
    val directory = into.parent ?: return
    runCatching {
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                val leftOver = name.endsWith(".part") || name.endsWith(".part.ranges")
                if (leftOver && name !in mine) clear(entry)
            }
        }
    }
}

private fun clear(vararg paths: Path) {
    for (path in paths) {
        runCatching { Files.deleteIfExists(path) }
        if (path.fileName.toString().endsWith(".ranges")) {
            runCatching { Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + ".new")) }
        }
    }
}
