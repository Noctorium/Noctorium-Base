package app.noctorium.platform

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/**
 * One Noctorium per listener, and the second one to start wakes the first instead.
 *
 * A window that closes into the tray is still running, so opening Noctorium again from the Start menu would
 * otherwise start a second player beside it -- two mpv processes, two Connect servers on one port, two
 * writers of one settings file. Instead the second start finds the first and asks it to show its window,
 * which is what somebody clicking the icon meant.
 *
 * The claim is a lock on a file in Noctorium's own folder, which the operating system lets go of when the
 * process ends however it ends, so a crash never leaves Noctorium refusing to start. Beside it is the port of
 * a small listener on the loopback address, which is how the second start reaches the first.
 */
class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
) : Closeable {

    override fun close() {
        runCatching { server.close() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val WAKE = "show"

        /**
         * Claims [folder] for this process, or reaches the process that already has it.
         *
         * Returns the claim, to be kept until the application ends; or null when another Noctorium holds it,
         * having asked that one to show itself if [wake] -- a start at sign-in into the tray wakes nothing,
         * since nobody asked to see a window. [onWake] runs on a thread of its own each time a later start
         * asks this one to show itself.
         */
        fun claim(folder: Path, wake: Boolean, onWake: () -> Unit): SingleInstance? {
            Files.createDirectories(folder)
            val channel = FileChannel.open(
                folder.resolve("instance.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                if (wake) wakeTheOther(folder)
                return null
            }

            val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            Files.writeString(folder.resolve("instance.port"), server.localPort.toString())
            thread(name = "noctorium-single-instance", isDaemon = true) {
                while (!server.isClosed) {
                    val connection = runCatching { server.accept() }.getOrNull() ?: continue
                    connection.use {
                        it.soTimeout = 2_000
                        val said = runCatching {
                            it.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                        }.getOrNull()
                        if (said?.trim() == WAKE) onWake()
                    }
                }
            }
            return SingleInstance(channel, lock, server)
        }

        /**
         * Asks the running Noctorium to show itself.
         *
         * Tried for a few seconds, because the one holding the lock may be starting in this very moment and
         * not have opened its listener yet. If it never answers this start still steps aside: two players
         * would be worse than a click that seemed to do nothing.
         */
        private fun wakeTheOther(folder: Path) {
            repeat(20) {
                val port = runCatching { Files.readString(folder.resolve("instance.port")).trim().toInt() }.getOrNull()
                if (port != null) {
                    val sent = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                            socket.getOutputStream().write("$WAKE\n".toByteArray(StandardCharsets.UTF_8))
                            socket.getOutputStream().flush()
                        }
                    }.isSuccess
                    if (sent) return
                }
                Thread.sleep(150)
            }
        }
    }
}
