package app.noctorium.settings

import app.noctorium.platform.CommandResult
import app.noctorium.platform.CommandRunner
import app.noctorium.platform.isMacOs
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Secrets on a Mac, kept in the login keychain through `/usr/bin/security`.
 *
 * Everything but the last test runs anywhere: `security` is played by a keychain kept in a map, which
 * answers the way the real one does -- exit code 44 and "could not be found" for a missing item -- and
 * which records every command and everything written to it. So what is checked is exactly what would be
 * run on a Mac, and above all that the secret itself never appears in a command line, where any other
 * program on the machine could read it by listing processes.
 */
class KeychainCredentialStoreTest {
    private val security: Path = Path.of("/usr/bin/security")

    /** A keychain in a map, run the way `security` runs. */
    private class FakeKeychain(
        /** False plays a keychain that never answers: no login session, say, or ssh. */
        val answers: Boolean = true,
        /** False plays one that accepts a command and then does not keep what it was given. */
        val keeps: Boolean = true,
    ) : CommandRunner {
        /** Account to what was given as the password, exactly as given. */
        val items = mutableMapOf<String, String>()
        val commands = mutableListOf<List<String>>()
        val inputs = mutableListOf<String>()

        override fun run(command: List<String>, input: String?, timeoutSeconds: Long): CommandResult {
            commands += command
            input?.let(inputs::add)
            if (!answers) error("${command.first()} did not finish within $timeoutSeconds seconds")
            val arguments = command.drop(1)
            if (arguments == listOf("-i")) {
                input.orEmpty().lineSequence().filter(String::isNotBlank).forEach { line -> execute(line.split(" ")) }
                return CommandResult(0, "security> ".repeat(2), "")
            }
            return execute(arguments)
        }

        private fun execute(arguments: List<String>): CommandResult {
            fun valueOf(option: String): String? = arguments.indexOf(option).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }
            check(valueOf("-s") == "Noctorium") { "not filed under Noctorium: $arguments" }
            val account = valueOf("-a") ?: error("no account: $arguments")
            return when (arguments.first()) {
                "add-generic-password" -> {
                    check("-U" in arguments) { "an existing item would not be updated: $arguments" }
                    if (keeps) items[account] = valueOf("-w") ?: error("no password: $arguments")
                    CommandResult(0, "", "")
                }
                "find-generic-password" -> items[account]
                    ?.let { CommandResult(0, if ("-w" in arguments) "$it\n" else "keychain: \"login.keychain-db\"\n", "") }
                    ?: NOT_FOUND
                "delete-generic-password" -> if (items.remove(account) != null) CommandResult(0, "", "") else NOT_FOUND
                else -> CommandResult(2, "", "security: unknown command ${arguments.first()}")
            }
        }

        companion object {
            val NOT_FOUND = CommandResult(
                44,
                "",
                "security: SecKeychainSearchCopyNext: The specified item could not be found in the keychain.\n",
            )
        }
    }

    private fun store(
        keychain: CommandRunner,
        rememberForSession: Boolean = false,
        hasSecurity: Boolean = true,
    ) = SecureCredentialStore(
        credentialPath = null,
        rememberForSession = rememberForSession,
        secretTool = { error("a Mac never asks secret-tool") },
        osName = "Mac OS X",
        keychainTool = { security.takeIf { hasSecurity } },
        commands = keychain,
    )

    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `a secret is written to security's standard input, and never onto a command line`() {
        val keychain = FakeKeychain()
        val secret = "refresh-token-6f1c"

        store(keychain).put("spotify.refresh_token", secret)

        assertTrue(listOf(security.toString(), "-i") in keychain.commands, "security was not run interactively")
        assertEquals(
            "add-generic-password -U -s Noctorium -a spotify.refresh_token -w ${base64(secret)}\n",
            keychain.inputs.single(),
        )
        keychain.commands.flatten().forEach { argument ->
            assertFalse(secret in argument || base64(secret) in argument, "the secret was on a command line: $argument")
        }
    }

    @Test
    fun `it is read back with find-generic-password and removed with delete-generic-password`() {
        val keychain = FakeKeychain()
        val store = store(keychain)
        store.put("lastfm.session", "session-key")
        keychain.commands.clear()

        assertEquals("session-key", store.get("lastfm.session"))
        assertEquals(
            listOf(security.toString(), "find-generic-password", "-s", "Noctorium", "-a", "lastfm.session", "-w"),
            keychain.commands.single(),
        )

        store.remove("lastfm.session")
        assertTrue(
            listOf(security.toString(), "delete-generic-password", "-s", "Noctorium", "-a", "lastfm.session") in keychain.commands,
        )
        assertNull(store.get("lastfm.session"))
    }

    /** Base64 is what makes any token safe to put on a line that something else splits into words. */
    @Test
    fun `whatever a secret contains, it comes back exactly, and is kept as base64`() {
        val keychain = FakeKeychain()
        val store = store(keychain)
        val awkward = listOf(
            "with spaces and \"double\" and 'single' quotes",
            "-w -a looks like options",
            "line one\nline two",
            "ünïcødé ✓ 🎵",
            "back\\slash \$HOME `tick` ; rm -rf",
        )

        awkward.forEachIndexed { index, secret ->
            store.put("test.awkward$index", secret)
            assertEquals(secret, store.get("test.awkward$index"))
            assertEquals(base64(secret), keychain.items["test.awkward$index"])
        }
    }

    @Test
    fun `an item that is not there is simply absent`() {
        assertNull(store(FakeKeychain()).get("soundcloud.oauth_token"))
    }

    @Test
    fun `something in the keychain that Noctorium did not write is not taken for a secret`() {
        val keychain = FakeKeychain().apply { items["noctorium.session_token"] = "not base64 at all!" }

        assertNull(store(keychain).get("noctorium.session_token"))
    }

    @Test
    fun `a key that could be read as one of security's options is refused before anything runs`() {
        val keychain = FakeKeychain()
        val store = store(keychain)
        // The probe first, so that what is counted below is only what the keys themselves cause.
        assertTrue(store.persistent)

        val before = keychain.commands.size
        listOf("-w", "-a", "-ui", ".hidden", "_private", "has space", "Upper.case", "semi;colon", "new\nline", "").forEach { key ->
            assertFailsWith<IllegalArgumentException>("the key \"$key\" was accepted") { store.put(key, "value") }
            assertNull(store.get(key))
            store.remove(key)
        }
        assertEquals(before, keychain.commands.size, "a refused key still reached security: ${keychain.commands.drop(before)}")
    }

    @Test
    fun `the keychain is asked once whether it answers, without reading any password`() {
        val keychain = FakeKeychain()
        val store = store(keychain)

        assertTrue(store.persistent)
        assertTrue(store.persistent)

        assertEquals(
            listOf(listOf(security.toString(), "find-generic-password", "-s", "Noctorium", "-a", "noctorium.probe")),
            keychain.commands,
        )
    }

    @Test
    fun `without security at all, a Mac behaves as Linux does with no keyring`() {
        val keychain = FakeKeychain()
        val refusing = store(keychain, hasSecurity = false)

        assertFalse(refusing.persistent)
        assertFailsWith<IllegalStateException> { refusing.put("test.token", "value") }
        assertNull(refusing.get("test.token"))

        val forNow = store(keychain, rememberForSession = true, hasSecurity = false)
        forNow.put("test.token", "value")
        assertEquals("value", forNow.get("test.token"))
        forNow.remove("test.token")
        assertNull(forNow.get("test.token"))
        assertTrue(keychain.commands.isEmpty(), "security was run although there is none: ${keychain.commands}")
    }

    @Test
    fun `a keychain that does not answer is not persistent, and the session fallback takes over`() {
        val silent = FakeKeychain(answers = false)

        val refusing = store(silent)
        assertFalse(refusing.persistent)
        assertFailsWith<IllegalStateException> { refusing.put("test.token", "value") }

        val forNow = store(FakeKeychain(answers = false), rememberForSession = true)
        forNow.put("test.token", "value")
        assertEquals("value", forNow.get("test.token"))
        assertFalse(forNow.persistent)
    }

    @Test
    fun `a keychain that takes the command but does not keep the value is a failure, said without the value`() {
        val keychain = FakeKeychain(keeps = false)
        val secret = "would-be-lost"

        val failure = assertFailsWith<IllegalStateException> { store(keychain).put("listenbrainz.token", secret) }

        val message = failure.message.orEmpty()
        assertFalse(secret in message || base64(secret) in message, "the failure repeated the secret: $message")
    }

    @Test
    fun `Linux never asks the keychain, even with security somewhere on the machine`() {
        val keychain = FakeKeychain()
        val linux = SecureCredentialStore(
            credentialPath = null,
            secretTool = { null },
            osName = "Linux",
            keychainTool = { security },
            commands = keychain,
        )

        assertFalse(linux.persistent)
        assertFailsWith<IllegalStateException> { linux.put("test.token", "value") }
        assertTrue(keychain.commands.isEmpty())
    }

    /**
     * The real login keychain, on a real Mac.
     *
     * Skipped anywhere else. On a Mac it writes one item under a key made up for the run and removes it
     * again, which is the only way to know that `security -i` reads its command the way it is written here.
     */
    @Test
    fun `on a real Mac a secret goes into the login keychain and comes back out`() {
        if (!isMacOs() || !Files.isRegularFile(security)) return
        val store = SecureCredentialStore(credentialPath = null)
        val key = "test.keychain-${Random.nextInt(1_000_000)}"
        val secret = "real \"keychain\" check ${Random.nextLong()}"
        try {
            assertTrue(store.persistent, "the login keychain did not answer")
            store.put(key, secret)
            assertEquals(secret, store.get(key))
        } finally {
            store.remove(key)
        }
        assertNull(store.get(key))
    }
}
