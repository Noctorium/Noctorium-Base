package app.spiceity.platform

import java.nio.file.Path

/**
 * The handful of things Spiceity asks the machine it is running on to do.
 *
 * Small on purpose. Everything else the application does it does itself; these four are the places where
 * there is no portable answer — opening a page, putting something on the clipboard, showing a file to
 * somebody, and saying where files a listener keeps should go. A desktop answers with awt and Explorer, a
 * phone with intents and its own directories, and neither vocabulary means anything to the other.
 */
interface SystemBridge {
    /**
     * Opens a page in whatever browser the listener uses.
     *
     * Implementations reject anything that is not https. Every address Spiceity opens is one it built from
     * a known service, and a scheme check is the cheap guard against that ever stopping being true.
     */
    fun openUrl(url: String)

    fun copyToClipboard(text: String)

    /**
     * Shows a saved file to the listener, in whatever way the platform shows things.
     *
     * The desktop selects it in a file manager. A phone has no file manager to speak of and no expectation
     * of one, so the default does nothing rather than pretending — a saved file there is found in the
     * downloads folder like everything else.
     */
    fun revealFile(path: Path) {}

    /**
     * Where a file the listener keeps should be written, when they have not chosen somewhere.
     *
     * The desktop answers with the desktop, because that is where somebody looks for something they just
     * saved. A phone answers with its shared music directory.
     */
    fun defaultExportFolder(): Path?
}
