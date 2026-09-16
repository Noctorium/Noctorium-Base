package app.spiceity.platform

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a desktop can do that a phone cannot, and the other way round.
 *
 * All four answers here are awt or a Windows command, and none of them exists on Android — which is the
 * entire reason [SystemBridge] is an interface rather than a utility object.
 */
class DesktopBridge : SystemBridge {

    override fun openUrl(url: String) {
        require(url.startsWith("https://")) { "Only secure links can be opened" }
        check(Desktop.isDesktopSupported()) { "Opening links is not supported on this system" }
        Desktop.getDesktop().browse(URI(url))
    }

    override fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    /**
     * Selects the file in Explorer rather than merely opening the folder it sits in.
     *
     * A folder with four hundred things in it, opened at the top, has not shown anybody the file they just
     * saved. Explorer is asked directly because awt has no way to express "and highlight this one"; where
     * that is not available — another platform, or Explorer missing — opening the folder is the fallback.
     */
    override fun revealFile(path: Path) {
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val opened = isWindows && runCatching {
            ProcessBuilder("explorer.exe", "/select,${path.toAbsolutePath()}").start()
        }.isSuccess
        if (opened) return
        runCatching {
            val folder = path.parent ?: return
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(folder.toFile())
        }
    }

    /**
     * The desktop, which is where somebody looks for a file they have just saved.
     *
     * Falls back to the home directory on a system that has no Desktop folder, and to null when even that
     * cannot be found — the caller treats null as "ask them to choose one".
     */
    override fun defaultExportFolder(): Path? {
        val home = System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of) ?: return null
        return home.resolve("Desktop").takeIf(Files::isDirectory) ?: home.takeIf(Files::isDirectory)
    }
}
