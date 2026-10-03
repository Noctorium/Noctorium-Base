package app.noctorium.settings

import app.noctorium.platform.CommandResult
import app.noctorium.platform.CommandRunner
import app.noctorium.platform.isMacOs
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The operating system's answer to [SecretStore].
 *
 * On Windows only DPAPI ciphertext reaches the disk. DPAPI ties what it encrypts to the signed-in Windows
 * account, so a credentials file copied to another machine — or read by another user on this one — decrypts
 * to nothing. It is reached by running PowerShell rather than through JNA, because this happens a handful of
 * times per session and a process is cheaper to be sure of than a hand-written binding to a security API.
 *
 * On Linux it is the desktop's keyring — GNOME Keyring, KWallet, KeePassXC, whatever answers the Secret
 * Service — through `secret-tool`, for the same reason PowerShell is used on Windows. Nothing reaches the
 * disk from here at all; the keyring keeps it, locked with the session.
 *
 * On a Mac it is the login keychain, through `/usr/bin/security`, as a generic password under the service
 * "Noctorium" with the key as its account. Going through that one program every time is also what keeps
 * macOS from asking permission: an item belongs to the program that made it, and the same program reads it.
 *
 * Where none of these exists — a server with no keyring running, say, which is where a terminal player may
 * well be — [put] refuses, as the interface asks, unless [rememberForSession] is set. Then a secret is held
 * in memory until the program exits and the caller is expected to say so: signed in for now, not for next
 * time, which is still better than a token in a plain file somebody else can read.
 */
class SecureCredentialStore(
    private val credentialPath: Path? = SettingsRepository.defaultSettingsPath()?.resolveSibling("credentials.json"),
    private val rememberForSession: Boolean = false,
    private val secretTool: () -> Path? = { findOnPath("secret-tool") },
    /** The operating system by the name Java gives it. A test passes "Mac OS X" to be a Mac anywhere. */
    private val osName: String = System.getProperty("os.name").orEmpty(),
    /** The keychain's command-line tool, when this machine has one. */
    private val keychainTool: () -> Path? = { Path.of(MAC_SECURITY).takeIf(Files::isRegularFile) },
    /** How that tool is run, which a test replaces with a keychain of its own. */
    private val commands: CommandRunner = CommandRunner.system,
) : SecretStore {
    private val json = Json { prettyPrint = true }
    private val isWindows = osName.startsWith("Windows", ignoreCase = true)
    private val isMac = isMacOs(osName)
    private val forNow = ConcurrentHashMap<String, String>()

    /** Whether a secret put here outlives the program: false only when it would be held in memory. */
    val persistent: Boolean get() = isWindows || keychain() != null || keyring() != null

    @Synchronized
    override fun put(key: String, secret: String) {
        SecretStore.requireValidKey(key)
        require(secret.isNotBlank()) { "Credential cannot be blank" }
        if (isWindows) {
            val encrypted = runPowerShell(ENCRYPT_SCRIPT, secret)
            val values = readEncrypted().toMutableMap().apply { put(key, encrypted) }
            writeEncrypted(values)
            return
        }
        if (isMac) {
            val account = keychainAccount(key)
            val tool = keychain()
            if (tool != null) {
                keepInKeychain(tool, account, secret)
                return
            }
            check(rememberForSession) {
                "The keychain did not answer, so there is nowhere safe to keep this. Sign in to this Mac's " +
                    "desktop session, or set ${SecretStore.environmentNameFor(key)}."
            }
            forNow[key] = secret
            return
        }
        val tool = keyring()
        if (tool != null) {
            run(tool, listOf("store", "--label=Noctorium ($key)") + attributes(key), stdin = secret)
            return
        }
        check(rememberForSession) {
            "There is no keyring to keep this in. Install secret-tool (libsecret) and sign in to a desktop " +
                "session, or set ${SecretStore.environmentNameFor(key)}."
        }
        forNow[key] = secret
    }

    @Synchronized
    override fun get(key: String): String? {
        System.getenv(SecretStore.environmentNameFor(key))?.takeIf(String::isNotBlank)?.let { return it }
        forNow[key]?.let { return it }
        if (isWindows) {
            val encrypted = readEncrypted()[key] ?: return null
            return runCatching { runPowerShell(DECRYPT_SCRIPT, encrypted).trimEnd('\r', '\n') }.getOrNull()
        }
        if (isMac) {
            val tool = keychain() ?: return null
            return runCatching { readFromKeychain(tool, keychainAccount(key)) }.getOrNull()
        }
        val tool = keyring() ?: return null
        return runCatching { run(tool, listOf("lookup") + attributes(key)).trimEnd('\r', '\n') }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)
    }

    @Synchronized
    override fun remove(key: String) {
        forNow.remove(key)
        if (isWindows) {
            val values = readEncrypted().toMutableMap()
            if (values.remove(key) != null) writeEncrypted(values)
            return
        }
        if (isMac) {
            keychain()?.let { tool ->
                runCatching {
                    commands.run(
                        listOf(tool.toString(), "delete-generic-password", "-s", KEYCHAIN_SERVICE, "-a", keychainAccount(key)),
                        null,
                        KEYCHAIN_TIMEOUT_SECONDS,
                    )
                }
            }
            return
        }
        keyring()?.let { tool -> runCatching { run(tool, listOf("clear") + attributes(key)) } }
    }

    /**
     * Writes one secret into the keychain without it ever appearing on a command line.
     *
     * Anything on a command line can be read by every other program on the machine, for as long as the
     * process runs, by simply listing processes. So `security` is started in its interactive mode and the
     * command is written to it instead. The value goes in base64: it then holds nothing a command line could
     * misread -- no space, no quote, no newline -- whatever the token looks like, and the account is a key
     * already held to [KEYCHAIN_ACCOUNT].
     *
     * Interactive mode carries on past a command that failed, and whether its exit code says so is not
     * something to depend on. So the value is read straight back, and only a keychain that returns exactly
     * what was given to it counts as having kept it.
     */
    private fun keepInKeychain(tool: Path, account: String, secret: String) {
        val encoded = Base64.getEncoder().encodeToString(secret.toByteArray(Charsets.UTF_8))
        val reply = commands.run(
            listOf(tool.toString(), "-i"),
            "add-generic-password -U -s $KEYCHAIN_SERVICE -a $account -w $encoded\n",
            KEYCHAIN_TIMEOUT_SECONDS,
        )
        if (runCatching { readFromKeychain(tool, account) }.getOrNull() == secret) return
        // Whatever security said, minus the value itself should it have repeated the line back.
        val said = reply.error.replace(encoded, "…").trim()
        error(said.ifBlank { "The keychain did not keep it" })
    }

    /** The secret kept for [account], or null when there is none or it is not something Noctorium wrote. */
    private fun readFromKeychain(tool: Path, account: String): String? {
        val reply = commands.run(
            listOf(tool.toString(), "find-generic-password", "-s", KEYCHAIN_SERVICE, "-a", account, "-w"),
            null,
            KEYCHAIN_TIMEOUT_SECONDS,
        )
        if (isNotFound(reply)) return null
        check(reply.succeeded) { reply.error.trim().ifBlank { "The keychain refused" } }
        return runCatching { String(Base64.getDecoder().decode(reply.output.trim()), Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)
    }

    /**
     * `/usr/bin/security`, when it is there and the keychain answers it.
     *
     * Asked once per run, the way the Linux keyring is, with a lookup of a key nobody stores: "could not be
     * found" is the keychain answering. Over ssh, or with no login keychain at all, the tool is there and
     * the question goes nowhere, and a secret is better held for the session than refused or lost.
     */
    private val keychainAnswer: Path? by lazy {
        val tool = keychainTool() ?: return@lazy null
        val answered = runCatching {
            val reply = commands.run(
                listOf(tool.toString(), "find-generic-password", "-s", KEYCHAIN_SERVICE, "-a", "noctorium.probe"),
                null,
                KEYCHAIN_PROBE_SECONDS,
            )
            reply.succeeded || isNotFound(reply)
        }.getOrDefault(false)
        tool.takeIf { answered }
    }

    private fun keychain(): Path? = if (isMac) keychainAnswer else null

    /**
     * `security`'s way of saying there is no such item.
     *
     * Its exit code is the low byte of the Security framework's status, and errSecItemNotFound (-25300)
     * comes out as 44; the message is checked as well in case that ever changes. Deliberately not just
     * "could not be found": "A default keychain could not be found" means something else entirely.
     */
    private fun isNotFound(reply: CommandResult): Boolean =
        reply.exitCode == ITEM_NOT_FOUND_EXIT || reply.error.contains("specified item could not be found", ignoreCase = true)

    /**
     * The key as a keychain account name, or a refusal.
     *
     * Stricter than [SecretStore.KEY_PATTERN], which already rules out spaces and quotes: a key may not start
     * with a dash either, so that nothing given as an account can be read as one of `security`'s options.
     * Keys are Noctorium's own identifiers, never somebody's input, so this costs nothing real.
     */
    private fun keychainAccount(key: String): String {
        require(key.matches(KEYCHAIN_ACCOUNT)) { "Invalid credential key" }
        return key
    }

    /**
     * `secret-tool`, when it is installed and something answers it.
     *
     * Installed is not enough: over ssh, or on a machine with no desktop session, the tool is there and the
     * Secret Service it talks to is not, and every call would wait for D-Bus and then fail. One lookup of a
     * key nobody stores says which, and it is asked once per run.
     */
    private val keyringAnswer: Path? by lazy {
        val tool = secretTool() ?: return@lazy null
        val answered = runCatching {
            val process = ProcessBuilder(listOf(tool.toString(), "lookup") + attributes("noctorium.probe"))
                .redirectErrorStream(true)
                .start()
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                // Exit 1 with nothing said is "no such secret", which is the keyring answering.
                val said = process.inputStream.bufferedReader().readText()
                process.exitValue() == 0 || said.isBlank()
            }
        }.getOrDefault(false)
        tool.takeIf { answered }
    }

    private fun keyring(): Path? = if (isWindows || isMac) null else keyringAnswer

    private fun attributes(key: String) = listOf("application", "noctorium", "key", key)

    private fun run(tool: Path, arguments: List<String>, stdin: String? = null): String {
        val process = ProcessBuilder(listOf(tool.toString()) + arguments).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer -> stdin?.let(writer::write) }
        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("The keyring did not answer")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }.trim()
        check(process.exitValue() == 0) { error.ifBlank { "The keyring refused" } }
        return output
    }

    private fun readEncrypted(): Map<String, String> = runCatching {
        val path = credentialPath ?: return@runCatching emptyMap()
        if (!Files.isRegularFile(path)) emptyMap() else json.decodeFromString<Map<String, String>>(Files.readString(path))
    }.getOrDefault(emptyMap())

    private fun writeEncrypted(values: Map<String, String>) {
        val path = credentialPath ?: error("Credential path is unavailable")
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(values))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun runPowerShell(script: String, stdin: String): String {
        val executable = System.getenv("WINDIR")?.let { Path.of(it, "System32", "WindowsPowerShell", "v1.0", "powershell.exe") }
            ?.takeIf(Files::isRegularFile)
            ?: Path.of("powershell.exe")
        val process = ProcessBuilder(
            executable.toString(),
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            script,
        ).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stdin) }
        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Credential encryption timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val error = process.errorStream.bufferedReader().use { it.readText() }.trim()
        check(process.exitValue() == 0 && output.isNotBlank()) { error.ifBlank { "Credential encryption failed" } }
        return output
    }

    internal companion object {
        /** Where every Mac keeps the keychain's command-line tool. */
        const val MAC_SECURITY = "/usr/bin/security"

        /** The service every Noctorium secret is filed under in the keychain; the key is the account. */
        const val KEYCHAIN_SERVICE = "Noctorium"

        /** What a key has to look like to be passed to `security` as an account: see [keychainAccount]. */
        val KEYCHAIN_ACCOUNT = Regex("[a-z0-9][a-z0-9_.-]{0,79}")

        const val ITEM_NOT_FOUND_EXIT = 44
        const val KEYCHAIN_TIMEOUT_SECONDS = 12L
        const val KEYCHAIN_PROBE_SECONDS = 5L

        private fun findOnPath(name: String): Path? = System.getenv("PATH").orEmpty()
            .split(java.io.File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map { Path.of(it, name) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

        private const val ENCRYPT_SCRIPT = "Add-Type -AssemblyName System.Security;\$plain=[Console]::In.ReadToEnd();\$bytes=[Text.Encoding]::UTF8.GetBytes(\$plain);\$protected=[System.Security.Cryptography.ProtectedData]::Protect(\$bytes,\$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);[Convert]::ToBase64String(\$protected)"
        private const val DECRYPT_SCRIPT = "Add-Type -AssemblyName System.Security;\$encrypted=[Console]::In.ReadToEnd();\$bytes=[Convert]::FromBase64String(\$encrypted);\$plain=[System.Security.Cryptography.ProtectedData]::Unprotect(\$bytes,\$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);[Text.Encoding]::UTF8.GetString(\$plain)"
    }
}
