package app.spiceity.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import app.spiceity.platform.SystemBridge
import java.nio.file.Path

/**
 * The phone's answers to the few things only a platform can do.
 *
 * Every one of them is an intent or a system service, and none exists on a desktop — which is the whole
 * reason [SystemBridge] is an interface. The application context is held rather than an activity's: these
 * are called from background work that outlives whatever screen started it.
 */
class AndroidBridge(private val context: Context) : SystemBridge {

    override fun openUrl(url: String) {
        require(url.startsWith("https://")) { "Only secure links can be opened" }
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Spiceity", text))
    }

    /**
     * Offers the file to whatever the listener wants to do with it.
     *
     * A phone has no file manager to reveal something in, and no expectation of one — what somebody wants
     * after saving a track is to send it somewhere or open it in a player. A share sheet is that. It is
     * attempted rather than assured: a file outside the provider's declared paths cannot be shared, and
     * failing to offer a sheet is not worth crashing over.
     */
    override fun revealFile(path: Path) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", path.toFile())
            val share = Intent(Intent.ACTION_SEND)
                .setType("audio/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(Intent.createChooser(share, "Send this track").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * The shared Music folder, which is where every other app on the phone looks for music.
     *
     * Not the app's own directory: a file saved there would vanish with the app and be invisible to
     * anything else, which defeats the point of saving one.
     */
    override fun defaultExportFolder(): Path? = runCatching {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            ?.apply { mkdirs() }
            ?.toPath()
    }.getOrNull()
}
