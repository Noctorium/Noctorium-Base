package app.spiceity.settings

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * The Windows answer to [SecretStore]: only DPAPI ciphertext reaches the disk.
 *
 * DPAPI ties what it encrypts to the signed-in Windows account, so a credentials file copied to another
 * machine — or read by another user on this one — decrypts to nothing. It is reached by running PowerShell
 * rather than through JNA, because this happens a handful of times per session and a process is cheaper to
 * be sure of than a hand-written binding to a security API.
 */
class SecureCredentialStore(
    private val credentialPath: Path? = SettingsRepository.defaultSettingsPath()?.resolveSibling("credentials.json"),
) : SecretStore {
    private val json = Json { prettyPrint = true }
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    @Synchronized
    override fun put(key: String, secret: String) {
        SecretStore.requireValidKey(key)
        require(secret.isNotBlank()) { "Credential cannot be blank" }
        check(isWindows) { "Secure credential storage is not available on this platform yet" }
        val encrypted = runPowerShell(ENCRYPT_SCRIPT, secret)
        val values = readEncrypted().toMutableMap().apply { put(key, encrypted) }
        writeEncrypted(values)
    }

    @Synchronized
    override fun get(key: String): String? {
        System.getenv(SecretStore.environmentNameFor(key))?.takeIf(String::isNotBlank)?.let { return it }
        if (!isWindows) return null
        val encrypted = readEncrypted()[key] ?: return null
        return runCatching { runPowerShell(DECRYPT_SCRIPT, encrypted).trimEnd('\r', '\n') }.getOrNull()
    }

    @Synchronized
    override fun remove(key: String) {
        val values = readEncrypted().toMutableMap()
        if (values.remove(key) != null) writeEncrypted(values)
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
        const val ENCRYPT_SCRIPT = "Add-Type -AssemblyName System.Security;\$plain=[Console]::In.ReadToEnd();\$bytes=[Text.Encoding]::UTF8.GetBytes(\$plain);\$protected=[System.Security.Cryptography.ProtectedData]::Protect(\$bytes,\$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);[Convert]::ToBase64String(\$protected)"
        const val DECRYPT_SCRIPT = "Add-Type -AssemblyName System.Security;\$encrypted=[Console]::In.ReadToEnd();\$bytes=[Convert]::FromBase64String(\$encrypted);\$plain=[System.Security.Cryptography.ProtectedData]::Unprotect(\$bytes,\$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);[Text.Encoding]::UTF8.GetString(\$plain)"
    }
}
