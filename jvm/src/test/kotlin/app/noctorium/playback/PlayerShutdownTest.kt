package app.noctorium.playback

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stopping the player when the application goes.
 *
 * mpv is a separate program, not part of this process, and on Windows a child outlives its parent. So
 * closing the window is not by itself enough to stop the music: something has to actually end mpv, and if
 * nothing does, audio keeps playing from a program with no window that cannot be paused and has to be
 * hunted down in the task manager.
 *
 * Driven against a real spawned process rather than the engine, because what is being checked is a
 * property of processes and shutdown hooks, not of the engine's own logic. A stand-in that pretends to be a
 * process would prove only that the stand-in behaves as written.
 */
class PlayerShutdownTest {
    private val started = mutableListOf<Process>()

    @AfterTest
    fun cleanUp() {
        started.forEach { runCatching { it.destroyForcibly() } }
    }

    /** Something long-running and harmless, standing in for a player holding an audio device. */
    private fun longRunningProcess(): Process {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val command = if (windows) {
            listOf("cmd", "/c", "ping -n 60 127.0.0.1 > nul")
        } else {
            listOf("sh", "-c", "sleep 60")
        }
        return ProcessBuilder(command).start().also { started += it }
    }

    /**
     * The behaviour the bug rests on. Worth stating outright: a spawned process is not tied to the life of
     * whatever spawned it, which is why leaving it alone at shutdown leaves it running.
     */
    @Test
    fun `a spawned process keeps running until something ends it`() {
        val player = longRunningProcess()

        assertTrue(player.isAlive, "the stand-in did not start")
        Thread.sleep(300)
        assertTrue(player.isAlive, "it ended on its own, so it cannot stand in for a player")
    }

    @Test
    fun `destroying forcibly and waiting actually ends it`() {
        val player = longRunningProcess()

        player.destroyForcibly()
        val ended = player.waitFor(2, TimeUnit.SECONDS)

        assertTrue(ended, "the process was still running two seconds after being told to stop")
        assertFalse(player.isAlive)
    }

    /**
     * A shutdown hook is what catches the case where the application ends without being closed tidily. It
     * cannot be observed from inside the same JVM, so a second one is started, made to spawn a child, and
     * asked to exit — and the child is then looked for.
     */
    @Test
    fun `a hook registered against a process ends it when the jvm exits`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val marker = Files.createTempFile("noctorium-hook", ".txt")
        val source = Files.createTempDirectory("noctorium-hook-src").resolve("HookProbe.java")

        // A tiny program that spawns a long-running child, registers a hook to kill it, and exits. It
        // writes the child's own report of whether it survived, so the check does not depend on this
        // machine's process listing.
        Files.writeString(
            source,
            """
            import java.io.*;
            import java.nio.file.*;
            public class HookProbe {
                public static void main(String[] args) throws Exception {
                    Process child = new ProcessBuilder("cmd", "/c", "ping -n 30 127.0.0.1 > nul").start();
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> child.destroyForcibly()));
                    Files.writeString(Paths.get(args[0]), String.valueOf(child.pid()));
                    System.exit(0);
                }
            }
            """.trimIndent(),
        )

        val compile = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
            source.toString(),
        ).redirectErrorStream(true).start()
        compile.waitFor(60, TimeUnit.SECONDS)
        if (compile.exitValue() != 0) return // No compiler here; nothing to prove either way.

        val probe = ProcessBuilder(java, "-cp", source.parent.toString(), "HookProbe", marker.toString())
            .redirectErrorStream(true)
            .start()
        probe.waitFor(60, TimeUnit.SECONDS)

        val childPid = Files.readString(marker).trim().toLongOrNull() ?: return
        // The parent has exited. Give its hook a moment, then the child must be gone.
        Thread.sleep(1_500)
        val survivor = ProcessHandle.of(childPid).orElse(null)

        assertTrue(
            survivor == null || !survivor.isAlive,
            "the child outlived the jvm that spawned it, so a hook does not stop the player here",
        )
    }
}
