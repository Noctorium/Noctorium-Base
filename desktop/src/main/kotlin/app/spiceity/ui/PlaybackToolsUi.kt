package app.spiceity.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spiceity.playback.hostPlatform
import app.spiceity.playback.linuxInstallHint
import app.spiceity.playback.PlaybackTool
import app.spiceity.playback.PlaybackToolInstaller
import app.spiceity.playback.PlaybackToolsState
import app.spiceity.playback.ToolOrigin
import kotlinx.coroutines.launch

/**
 * The programs Spiceity plays music with, and how they got here.
 *
 * This panel exists because an error message promised it did. "mpv is missing. Install it in Spiceity
 * Settings" was what a fresh install said the first time somebody pressed play, and Settings had no such
 * page -- so the one instruction Spiceity gave led nowhere. Mostly nobody should ever see this: what it
 * offers happens by itself at startup. It is here for when that failed, and to be honest about what is
 * running on the machine.
 */
@Composable
internal fun PlaybackToolsPanel() {
    val tools by PlaybackToolInstaller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val windows = hostPlatform().isWindows

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SettingsPanelCard {
            Text(
                if (tools.ready) "Spiceity has what it needs" else "Something is missing",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Spiceity uses two separate programs to play music. They are kept in Spiceity's own " +
                    "folder and are fetched automatically — this page is for when that did not work.",
                Modifier.widthIn(max = 560.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(16.dp))
            PlaybackTool.entries.forEach { tool ->
                ToolRow(tool, tools, windows) { scope.launch { PlaybackToolInstaller.install(tool) } }
                Spacer(Modifier.height(8.dp))
            }

            tools.progress?.let { fraction ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth().widthIn(max = 560.dp))
                Text(
                    "Downloading ${tools.installing?.displayName.orEmpty()}… ${(fraction * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            // Progress goes to null between the download finishing and the archive being unpacked, which
            // for mpv is a hundred and twenty megabytes and long enough to look like nothing is happening.
            if (tools.installing != null && tools.progress == null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Unpacking ${tools.installing?.displayName.orEmpty()}…", fontSize = 11.sp)
                }
            }

            tools.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, Modifier.widthIn(max = 560.dp), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton({ PlaybackToolInstaller.refresh() }, enabled = !tools.busy) { Text("Check again") }
                PlaybackToolInstaller.binDirectory()?.let { bin ->
                    OutlinedButton({ openFolder(bin.toString()) }) { Text("Open folder") }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SettingsPanelCard {
            Text("Where these come from", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            AccountFact("yt-dlp is downloaded from its own releases and checked against the checksum published with it.")
            if (windows) {
                AccountFact("mpv is downloaded from the Windows builds mpv.org points at, and unpacked with the tar that ships in Windows.")
            } else {
                AccountFact("mpv comes from your distribution's package manager, which is the only sensible place for it on Linux.")
            }
            AccountFact("A copy you installed yourself is found and left alone — Spiceity never replaces it.")
            AccountFact("yt-dlp is refreshed every couple of weeks, because YouTube changes and an old one stops working.")
        }
    }
}

@Composable
private fun ToolRow(
    tool: PlaybackTool,
    tools: PlaybackToolsState,
    windows: Boolean,
    install: () -> Unit,
) {
    val status = tools.status(tool)
    val missing = status == null || status.origin == ToolOrigin.MISSING
    // Linux has no portable mpv or FFmpeg to fetch, so offering a button that can only ever print a
    // package-manager line would be worse than printing the line.
    val installable = !missing || windows || tool == PlaybackTool.YT_DLP
    val colour = when {
        !missing -> MaterialTheme.colorScheme.primary
        tool.required -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f), shape = RoundedCornerShape(11.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    tools.installing == tool -> Icons.Default.Downloading
                    missing -> Icons.Default.ErrorOutline
                    else -> Icons.Default.CheckCircle
                },
                null,
                tint = colour,
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tool.displayName, fontWeight = FontWeight.SemiBold)
                    if (!tool.required) {
                        Spacer(Modifier.width(6.dp))
                        Text("optional", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
                Text(
                    describe(tool, tools, windows),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            if (missing && installable) {
                Spacer(Modifier.width(10.dp))
                Button(install, enabled = !tools.busy) { Text("Install") }
            }
        }
    }
}

private fun describe(tool: PlaybackTool, tools: PlaybackToolsState, windows: Boolean): String {
    val status = tools.status(tool) ?: return "${tool.purpose} — not checked yet"
    return when (status.origin) {
        // Saying which copy is being used matters: somebody with their own mpv on PATH and a Spiceity
        // one in its folder should be able to see, without guessing, which of the two is playing.
        ToolOrigin.MANAGED -> "${tool.purpose} — installed by Spiceity"
        ToolOrigin.SYSTEM -> "${tool.purpose} — already on this machine at ${status.path}"
        ToolOrigin.MISSING -> when {
            windows || tool == PlaybackTool.YT_DLP -> "${tool.purpose} — not installed"
            else -> "${tool.purpose} — not installed. " + linuxInstallHint(tool)
        }
    }
}

/**
 * The strip across the top of the application while this is being sorted out.
 *
 * Only ever shown when something is actually happening or actually wrong. A banner that is always there
 * is one nobody reads.
 */
@Composable
internal fun PlaybackToolsBanner(openSettings: () -> Unit) {
    val tools by PlaybackToolInstaller.state.collectAsState()
    if (tools.installing == null && tools.ready) return
    // Nothing has looked yet: saying "missing" here would be a guess, and it resolves in milliseconds.
    if (tools.tools.isEmpty()) return

    val installing = tools.installing
    val colour = if (installing != null) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.errorContainer
    }

    Surface(color = colour, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (installing != null) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(11.dp))
                    Text(
                        "Setting up ${installing.displayName}, so Spiceity can play music. " +
                            "You can keep using everything else.",
                        fontSize = 12.sp,
                    )
                } else {
                    Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(11.dp))
                    Text(
                        "Spiceity cannot play anything until " +
                            tools.missingRequired.joinToString(" and ") { it.displayName } + " is installed.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Button(openSettings) { Text("Fix this") }
                }
            }
            tools.progress?.let { fraction ->
                LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
            }
            if (installing != null && tools.progress == null) {
                Box(Modifier.fillMaxWidth()) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }
}


private fun openFolder(path: String) {
    runCatching { java.awt.Desktop.getDesktop().open(java.io.File(path)) }
}
