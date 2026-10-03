package app.noctorium.platform

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Running another program and hearing what it said, behind an interface a test can stand in for.
 *
 * Most of what Noctorium does on a Mac goes through programs Apple ships: `ditto` to unpack mpv, `xattr` to
 * clear the quarantine flag from it, `security` for the keychain. None of them exist on the Windows machine
 * this is written and tested on, so with the running kept behind this a test can play the program instead
 * -- it sees the exact command and exactly what was written to it, and answers the way the real one does.
 */
fun interface CommandRunner {
    /**
     * Runs [command], writes [input] to it (or nothing) and closes it, and waits up to [timeoutSeconds].
     *
     * Throws when the program could not be started or did not finish in time. A program that ran and
     * failed is not an exception: it is an ordinary [CommandResult] with a non-zero exit code, because
     * several of the programs asked say "not found" that way and the caller needs to tell the two apart.
     */
    fun run(command: List<String>, input: String?, timeoutSeconds: Long): CommandResult

    companion object {
        /** The real thing: a process on this machine. */
        val system: CommandRunner = CommandRunner(::runProcess)
    }
}

/** How a program ended, and what it wrote to each of its two outputs. */
data class CommandResult(val exitCode: Int, val output: String, val error: String) {
    val succeeded: Boolean get() = exitCode == 0
}

private fun runProcess(command: List<String>, input: String?, timeoutSeconds: Long): CommandResult {
    val process = ProcessBuilder(command).start()
    // Both outputs are read while the program runs, each on a thread of its own. Reading them only after it
    // finished works until a program says more than a pipe holds: then it waits for somebody to read, this
    // waits for it to finish, and the two wait for each other until the timeout ends it.
    val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
    val error = CompletableFuture.supplyAsync { process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
    // A program that has already exited cannot be written to. That is not worth an exception of its own:
    // the exit code below says what happened far better than a broken pipe does.
    runCatching { process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer -> input?.let(writer::write) } }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("${command.firstOrNull().orEmpty()} did not finish within $timeoutSeconds seconds")
    }
    return CommandResult(
        exitCode = process.exitValue(),
        output = runCatching { output.get(READ_AFTER_EXIT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(""),
        error = runCatching { error.get(READ_AFTER_EXIT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(""),
    )
}

/**
 * How long to go on reading once the program has ended.
 *
 * Normally no time at all, since its outputs close with it. Something it started and left running can hold
 * them open, though, and that is no reason to wait for ever.
 */
private const val READ_AFTER_EXIT_SECONDS = 5L
