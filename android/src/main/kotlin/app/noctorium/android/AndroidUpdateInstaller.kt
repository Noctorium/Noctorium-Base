package app.noctorium.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.noctorium.update.UpdateChannel
import app.noctorium.update.UpdateInstaller
import app.noctorium.update.Version
import java.nio.file.Path

/**
 * What the phone does with a downloaded APK.
 *
 * It hands it to Android's own package installer and stops there. Nothing here installs anything: the
 * system shows its own screen, names the application, and asks -- and the first time, it also sends the
 * listener to a settings page to allow this application to install others at all. That is three deliberate
 * steps between a download finishing and anything being replaced, and all three belong to Android rather
 * than to Noctorium, which is the right way round.
 *
 * Only for a sideloaded build. Anything installed from a store is updated by that store, and an
 * application that went round the back of one would be removed from it.
 */
class AndroidUpdateInstaller(private val context: Context) : UpdateInstaller {

    override val channel: UpdateChannel = UpdateChannel.ANDROID_APK

    override val currentVersion: Version? = Version.parse(BuildConfig.VERSION_NAME)

    /**
     * Inside the application's own files, not the cache.
     *
     * A FileProvider can only share a path it has been told about, and Android may empty the cache
     * directory at any moment -- including between the download finishing and the installer opening it.
     */
    override fun downloadDirectory(): Path =
        context.filesDir.toPath().resolve("updates")

    override suspend fun install(file: Path): String? = runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file.toFile(),
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            // The installer runs in its own task, and it has to be allowed to read a file that belongs to
            // this application -- without the grant it opens on an error rather than on the APK.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        null
    }.getOrElse { error ->
        "Could not open the installer: ${error.message}. The download is in the app's files."
    }
}
