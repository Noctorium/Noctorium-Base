package app.spiceity.update

import java.nio.file.Path

/**
 * Whatever this platform does with a downloaded update.
 *
 * Deliberately not "apply the update". No platform here lets a running program replace itself: Windows
 * hands the file to an installer and the application quits so its own files are not locked, Linux hands it
 * to the package manager through a privilege prompt, and Android hands it to the system package installer,
 * which asks the person again before it does anything. In all three the last word belongs to something
 * other than Spiceity, which is the correct arrangement and not a limitation to work around.
 */
interface UpdateInstaller {

    /** How this copy was installed, which decides what an update is allowed to do to it. */
    val channel: UpdateChannel

    /** What the running application believes it is, or null for a build that was never told. */
    val currentVersion: Version?

    /** Somewhere to put the download. Wiped and reused; nothing here is kept between attempts. */
    fun downloadDirectory(): Path

    /**
     * Hands the verified file over.
     *
     * Returns null when the handover succeeded -- which usually means something else is now on screen and
     * this application is about to close -- and a sentence worth showing otherwise.
     */
    suspend fun install(file: Path): String?

    /** The default for anything that cannot install for itself: know nothing, do nothing. */
    companion object {
        fun none(version: Version? = null): UpdateInstaller = object : UpdateInstaller {
            override val channel = UpdateChannel.UNMANAGED
            override val currentVersion = version
            override fun downloadDirectory(): Path = Path.of(System.getProperty("java.io.tmpdir"), "spiceity-update")
            override suspend fun install(file: Path): String =
                "This copy of Spiceity was not installed by an installer, so it cannot update itself."
        }
    }
}

/** Everything the settings screen shows about updating. */
data class UpdateState(
    val currentVersion: String = "",
    val checking: Boolean = false,
    /** Set once a newer release is found, and cleared when it is dismissed or installed. */
    val available: AvailableUpdate? = null,
    /** Between 0 and 1 while a download is running, null when none is. */
    val downloading: Float? = null,
    val message: String? = null,
    /** False when all this can do is point at the release page. */
    val canInstall: Boolean = false,
) {
    val busy: Boolean get() = checking || downloading != null
}
