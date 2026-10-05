package app.noctorium.update

import java.nio.file.Path

/**
 * Whatever this platform does with a downloaded update.
 *
 * Deliberately not "apply the update". No platform here lets a running program replace itself: Windows
 * hands the file to an installer and the application quits so its own files are not locked, Linux hands it
 * to the package manager through a privilege prompt, and Android hands it to the system package installer,
 * which asks the person again before it does anything. In all three the last word belongs to something
 * other than Noctorium, which is the correct arrangement and not a limitation to work around.
 *
 * The terminal player is the nearest thing to an exception, and still not one: its folder is its user's own
 * and nothing else installs into it, so it does put the new copy there itself -- but beside the running one,
 * to be swapped in by a helper once the old copy has quit, never underneath a program that is still using it.
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

    /**
     * What to say once [install] has handed [version] over.
     *
     * The desktop and the phone are about to close, or have the system's own installer on screen, and the
     * default says so. The terminal player is neither: it leaves the new copy waiting beside itself and goes
     * on playing, and says when the new one takes over instead.
     */
    fun installedMessage(version: Version): String = "Installing. Noctorium will close."

    /** The default for anything that cannot install for itself: know nothing, do nothing. */
    companion object {
        fun none(version: Version? = null): UpdateInstaller = object : UpdateInstaller {
            override val channel = UpdateChannel.UNMANAGED
            override val currentVersion = version
            override fun downloadDirectory(): Path = Path.of(System.getProperty("java.io.tmpdir"), "noctorium-update")
            override suspend fun install(file: Path): String =
                "This copy of Noctorium was not installed by an installer, so it cannot update itself."
        }
    }
}

/** What came of fetching an update and handing it to an installer, in a sentence either way. */
sealed interface InstallOutcome {
    val message: String

    /** Handed over; [message] is the installer's own word on what happens next. */
    data class Installed(val version: Version, override val message: String) : InstallOutcome

    /** Nothing was installed, and [message] says why. */
    data class Failed(override val message: String) : InstallOutcome
}

/**
 * Downloads [update], proves it is the file that was published, and hands it to this installer.
 *
 * The one road from "there is a newer version" to "it is installed", taken by the settings screens through
 * [app.noctorium.core.AppState.installUpdate] and by the terminal player, which also updates with no screen
 * at all -- so that the rule that nothing unverified is ever installed lives in one place rather than in each
 * of them. [UpdateDownloader] refuses a release that published no checksum, and a file that does not match.
 */
suspend fun UpdateInstaller.fetchAndInstall(
    update: AvailableUpdate,
    downloader: UpdateDownloader = UpdateDownloader(),
    onProgress: (fraction: Float) -> Unit = {},
): InstallOutcome {
    val file = update.file ?: return InstallOutcome.Failed("This release has nothing for this computer.")
    if (!channel.canInstallItself) return InstallOutcome.Failed("Open the release page to get this one.")
    val target = downloadDirectory().resolve(file.name)
    return when (val fetched = downloader.fetch(file, update.sha256, target, onProgress)) {
        is UpdateDownloader.Outcome.Failed -> InstallOutcome.Failed(fetched.message)
        is UpdateDownloader.Outcome.Ready -> install(fetched.file)
            ?.let { refusal -> InstallOutcome.Failed(refusal) }
            ?: InstallOutcome.Installed(update.version, installedMessage(update.version))
    }
}

/** Everything the settings screen shows about updating, and the one thing that interrupts. */
data class UpdateState(
    val currentVersion: String = "",
    val checking: Boolean = false,
    /** Set once a newer release is found, and cleared when it is installed. */
    val available: AvailableUpdate? = null,
    /**
     * The same release, when it is one worth interrupting somebody about.
     *
     * Apart from [available] because the two answer different questions. The settings card asks "is
     * there a newer Noctorium", and should go on saying yes however many times the offer has been
     * turned down. This asks "should a dialog appear in front of somebody who did not ask", which is
     * true once per version and never again after they say no.
     */
    val prompt: AvailableUpdate? = null,
    /** Between 0 and 1 while a download is running, null when none is. */
    val downloading: Float? = null,
    val message: String? = null,
    /** False when all this can do is point at the release page. */
    val canInstall: Boolean = false,
) {
    val busy: Boolean get() = checking || downloading != null
}
