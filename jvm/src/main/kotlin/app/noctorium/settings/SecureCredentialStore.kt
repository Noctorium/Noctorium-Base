package app.noctorium.settings

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
 * Where neither exists — a server with no keyring running, say, which is where a terminal player may well
 * be — [put] refuses, as the interface asks, unless [rememberForSession] is set. Then a secret is held in
 * memory until the program exits and the caller is expected to say so: signed in for now, not for next time,
 * which is still better than a token in a plain file somebody else can read.
 */
class SecureCredentialStore(
    private val credentialPath: Path? = SettingsRepository.defaultSettingsPath()?.resolveSibling("credentials.json"),
    private val rememberForSession: Boolean = false,
    private val secretTool: () -> Path? = { findOnPath("secret-tool") },
) : SecretStore {
    private val json = Json { prettyPrint = true }
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val forNow = ConcurrentHashMap<String, String>()

    /** Whether a secret put here outlives the program: false only when it would be held in memory. */
    val persistent: Boolean get() = isWindows || keyring() != null

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
        keyring()?.let { tool -> runCatching { run(tool, listOf("clear") + attributes(key)) } }
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

    private fun keyring(): Path? = if (isWindows) null else keyringAnswer

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

    private companion object {
        fun findOnPath(name: String): Path? = System.getenv("PATH").orEmpty()
            .split(java.io.File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map { Path.of(it, name) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

        const val ENCRYPT_SCRIPT = "Add-Type -AssemblyName System.Security;\$plain=[Console]::In.ReadToEnd();\$bytes=[Text.Encoding]::UTF8.GetBytes(\$plain);\$protected=[System.Security.Cryptography.ProtectedData]::Protect(\$bytes,\$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);[Convert]::ToBase64String(\$protected)"
        const val DECRYPT_SCRIPT = "Add-Type -AssemblyName System.Security;\$encrypted=[Console]::In.ReadToEnd();\$bytes=[Convert]::FromBase64String(\$encrypted);\$plain=[System.Security.Cryptography.ProtectedData]::Unprotect(\$bytes,\$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);[Text.Encoding]::UTF8.GetString(\$plain)"
    }
}
