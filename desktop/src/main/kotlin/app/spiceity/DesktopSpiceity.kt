package app.spiceity

import app.spiceity.connect.DeviceKind
import app.spiceity.core.AppState
import app.spiceity.discord.DiscordPresenceManager
import app.spiceity.downloads.AudioConverter
import app.spiceity.downloads.DownloadManager
import app.spiceity.platform.DesktopBridge
import app.spiceity.playback.AccountProbe
import app.spiceity.playback.MpvPlaybackEngine
import app.spiceity.playback.YtDlpService
import app.spiceity.settings.SecureCredentialStore

/**
 * The desktop's answers to everything `core` asks for.
 *
 * `AppState` has no platform defaults, on purpose: a default would have been silently wrong on one of the
 * two platforms, and a constructor that insists on being told which backend, which secret store and which
 * bridge it has is a cheap way never to wonder. This is where the desktop says so, once.
 */
fun desktopAppState(): AppState {
    val backend = YtDlpService()
    val downloads = DownloadManager(backend, converter = AudioConverter())
    return AppState(
        ytDlp = backend,
        credentials = SecureCredentialStore(),
        system = DesktopBridge(),
        downloads = downloads,
        // Handed the download library to consult, so a track kept on the disk plays from there and asks
        // the network for nothing.
        playbackEngine = MpvPlaybackEngine(backend, downloadedFile = downloads::localFile),
        accountProbe = AccountProbe(),
        discordPresence = DiscordPresenceManager(),
        // What this machine calls itself, which is what shows up in the list on the phone. The computer
        // name is what somebody already recognises; the hostname is a fallback for when Windows has not
        // set one in the environment.
        deviceName = {
            System.getenv("COMPUTERNAME")?.takeIf(String::isNotBlank)
                ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
                ?: "This computer"
        },
        deviceKind = DeviceKind.DESKTOP,
    )
}
