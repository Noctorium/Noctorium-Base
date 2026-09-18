package app.spiceity.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * What a desktop Spiceity can do about a new version of itself.
 *
 * Nothing here replaces the running application. Windows hands the file to the installer that built it and
 * quits so its own files stop being locked; Linux hands the package to the package manager through a
 * graphical privilege prompt, because /opt belongs to the package manager and a program that writes there
 * behind its back leaves a machine whose package database is a lie.
 *
 * The important case is neither: a copy running from an unzipped folder, or straight out of Gradle, was
 * never installed by anything. Running an installer over it would leave the old folder exactly where it is
 * and put a second copy somewhere else, so those are told and sent to the release page instead.
 */
class DesktopUpdateInstaller(
    override val currentVersion: Version? = readVersion(),
) : UpdateInstaller {

    override val channel: UpdateChannel by lazy { detectChannel() }

    override fun downloadDirectory(): Path =
        Path.of(System.getProperty("java.io.tmpdir"), "spiceity-update")

    override suspend fun install(file: Path): String? = when (channel) {
        UpdateChannel.WINDOWS_INSTALLER -> runWindowsInstaller(file)
        UpdateChannel.DEBIAN_PACKAGE -> runLinuxPackage(file, listOf("dpkg", "--install", file.toString()))
        UpdateChannel.FEDORA_PACKAGE -> runLinuxPackage(file, listOf("rpm", "--upgrade", "--force", file.toString()))
        else -> "This copy of Spiceity was not installed by an installer, so it cannot update itself."
    }

    /**
     * Starts the installer and gets out of its way.
     *
     * The installer cannot replace files this process is holding open, so Spiceity quits rather than
     * waiting to be told to. It is started visibly rather than silently: the same window somebody would
     * see if they had downloaded it themselves, which is also their last chance to say no.
     */
    private fun runWindowsInstaller(file: Path): String? = runCatching {
        ProcessBuilder(file.toString()).start()
        // Long enough for the installer to be on screen before this window disappears, so it does not
        // look as though Spiceity closed for no reason.
        Thread.sleep(1_200)
        exitProcess(0)
        @Suppress("UNREACHABLE_CODE") null
    }.getOrElse { "Could not start the installer: ${it.message}" }

    /**
     * Asks the package manager, through whatever this desktop uses to ask for a password.
     *
     * pkexec is the graphical front for a privileged command on every desktop that ships polkit, which is
     * all of the ones these packages target. Without it there is no way to install a package that does not
     * involve a terminal, so the file is left where it is and the command is handed over instead -- which
     * is more use than a failure nobody can act on.
     */
    private fun runLinuxPackage(file: Path, command: List<String>): String? {
        if (which("pkexec") == null) {
            return "Downloaded to $file. Install it with: sudo ${command.joinToString(" ")}"
        }
        return runCatching {
            val process = ProcessBuilder(listOf("pkexec") + command).start()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return "The installer did not finish. The package is at $file."
            }
            when (process.exitValue()) {
                0 -> {
                    exitProcess(0)
                    @Suppress("UNREACHABLE_CODE") null
                }
                // polkit answers 126 when the prompt is dismissed, which is a decision rather than a fault.
                126, 127 -> "Update cancelled. The package is at $file."
                else -> "The package manager refused it. The package is at $file."
            }
        }.getOrElse { "Could not run the package manager: ${it.message}. The package is at $file." }
    }

    /**
     * How this copy got here.
     *
     * `jpackage.app-path` is set by the launcher jpackage builds, so its absence means this is not a
     * packaged build at all -- a Gradle run, or a jar. Its presence is not enough on its own, because the
     * same launcher goes into the app image that gets zipped up; what that means per platform is decided
     * below.
     */
    private fun detectChannel(): UpdateChannel {
        val launcher = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }
            ?: return UpdateChannel.UNMANAGED

        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (os.startsWith("windows")) return windowsChannel(launcher)
        if (!os.startsWith("linux")) return UpdateChannel.UNMANAGED

        // Which package manager put it there, asked of the package manager rather than guessed from the
        // distribution's name: plenty of machines have both, and only one of them owns this file.
        if (owns(listOf("dpkg", "--search", launcher))) return UpdateChannel.DEBIAN_PACKAGE
        if (owns(listOf("rpm", "--query", "--file", launcher))) return UpdateChannel.FEDORA_PACKAGE
        return UpdateChannel.UNMANAGED
    }

    /**
     * On Windows, where it is sitting decides whether it was installed.
     *
     * jpackage sets jpackage.app-path for its own launcher, and it builds the same launcher for an
     * installed application and for the app image that gets zipped up -- so the property says the
     * application was built by jpackage, not that anything installed it. Somebody running the unzipped
     * folder would otherwise be handed an installer, which would put a second copy in Program Files and
     * leave the folder they are actually running exactly as it was.
     *
     * So the location is what is checked, and an unfamiliar one is treated as unmanaged. That is the safe
     * way round: the worst case is being told to update by hand, where the other way is two copies.
     */
    private fun windowsChannel(launcher: String): UpdateChannel {
        val installedUnder = listOfNotNull(
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)"),
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs" },
        )
        val where = launcher.lowercase()
        return if (installedUnder.any { where.startsWith(it.lowercase()) }) {
            UpdateChannel.WINDOWS_INSTALLER
        } else {
            UpdateChannel.UNMANAGED
        }
    }

    private fun owns(command: List<String>): Boolean = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        process.exitValue() == 0
    }.getOrDefault(false)

    private fun which(tool: String): Path? =
        System.getenv("PATH")?.split(java.io.File.pathSeparator)
            ?.map { Path.of(it, tool) }
            ?.firstOrNull { Files.isExecutable(it) }

    companion object {
        /**
         * The version written in at build time.
         *
         * A jar manifest would do for a jar, and jpackage does not build one -- it builds a runtime image,
         * and the manifest is not what ends up next to the launcher. So the build writes a properties file
         * into the resources instead, from the same value the installers are named after.
         */
        fun readVersion(): Version? = runCatching {
            DesktopUpdateInstaller::class.java.getResourceAsStream("/spiceity-version.properties")?.use { stream ->
                val properties = java.util.Properties().apply { load(stream) }
                Version.parse(properties.getProperty("version"))
            }
        }.getOrNull()
    }
}
