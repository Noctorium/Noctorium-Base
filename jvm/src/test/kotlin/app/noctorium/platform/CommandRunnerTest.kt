package app.noctorium.platform

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real runner, against the one program certain to be on every machine the tests run on: Java itself.
 *
 * What matters about it is what the keychain depends on -- that what is written to a program reaches it
 * whole, that both of its outputs come back however much it says, and that one which never finishes is
 * stopped rather than waited on for ever.
 */
class CommandRunnerTest {
    private val folder: Path = Files.createTempDirectory("noctorium-commands")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private val java: String = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
    ).toString()

    /** A one-file Java program, run from source, which is how a JDK runs a script. */
    private fun program(body: String): String {
        val source = folder.resolve("Program.java")
        Files.writeString(source, "public class Program { public static void main(String[] a) throws Exception { $body } }")
        return source.toString()
    }

    @Test
    fun `what is written reaches the program whole, and both its outputs come back`() {
        // More than a pipe holds in either direction, which is where reading only at the end would hang.
        val input = "abcdefghij".repeat(20_000)
        val echo = program(
            "byte[] all = System.in.readAllBytes();" +
                "System.out.print(new String(all).toUpperCase());" +
                "System.err.print(\"read \" + all.length);" +
                "System.exit(3);",
        )

        val result = CommandRunner.system.run(listOf(java, echo), input, 120)

        assertEquals(3, result.exitCode)
        assertFalse(result.succeeded)
        assertEquals(input.uppercase(), result.output)
        assertEquals("read ${input.length}", result.error)
    }

    @Test
    fun `a program that finishes cleanly has succeeded`() {
        val result = CommandRunner.system.run(listOf(java, "-version"), null, 120)

        assertTrue(result.succeeded)
        assertTrue("version" in result.error, "java -version said: ${result.error}")
    }

    @Test
    fun `a program that does not finish in time is stopped and reported`() {
        val sleeper = program("Thread.sleep(120_000);")

        val failure = assertFailsWith<IllegalStateException> { CommandRunner.system.run(listOf(java, sleeper), null, 3) }

        assertTrue("did not finish" in failure.message.orEmpty())
    }

    @Test
    fun `a program that is not there cannot be started, which is an exception and not a result`() {
        assertFailsWith<IOException> {
            CommandRunner.system.run(listOf(folder.resolve("no-such-program").toString()), null, 5)
        }
    }

    @Test
    fun `macOS is recognised by the name Java gives it, and nothing else is`() {
        assertTrue(isMacOs("Mac OS X"))
        assertTrue(isMacOs("mac os x"))
        assertFalse(isMacOs("Linux"))
        assertFalse(isMacOs("Windows 11"))
        assertFalse(isMacOs(""))
    }
}
