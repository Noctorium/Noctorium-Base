package app.noctorium.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noctorium.core.AppState
import app.noctorium.settings.SettingsState

/**
 * Updating, on the phone.
 *
 * Noctorium is sideloaded, so nothing else is going to update it. The APK is fetched, checked against the
 * checksum the release published, and handed to Android's own package installer, which shows its own
 * screen and asks again. Nothing is replaced without that second answer.
 */
@Composable
internal fun UpdateCard(settings: SettingsState, state: AppState) {
    val updates by state.updates.collectAsState()
    val available = updates.available
    val file = available?.file

    SettingsCardShell {
        CardHeading(Icons.Default.SystemUpdateAlt, "Updates")
        Text(
            when {
                available != null -> "Noctorium ${available.version} is out. You have ${updates.currentVersion}."
                updates.currentVersion.isBlank() -> "This build does not say which version it is."
                else -> "You have ${updates.currentVersion}, which is the newest."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )

        if (available != null && file != null && available.sha256 != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${file.name} · ${megabytes(file.bytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }

        updates.downloading?.let { fraction ->
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
            Text(
                "Downloading… ${(fraction * 100).toInt()}%",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (available != null && file != null && available.sha256 != null) {
                Button({ state.installUpdate() }, enabled = !updates.busy) {
                    Text(if (updates.downloading != null) "Downloading…" else "Update", fontSize = 13.sp)
                }
            }
            if (available != null) {
                TextButton({ state.openReleasePage() }) { Text("What's new", fontSize = 13.sp) }
            }
            TextButton({ state.checkForUpdates() }, enabled = !updates.busy) {
                Text(if (updates.checking) "Checking…" else "Check now", fontSize = 13.sp)
            }
        }

        updates.message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Look for updates when Noctorium starts", fontSize = 13.sp)
                Text(
                    "One request to GitHub. Nothing downloads without asking.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Switch(settings.preferences.updates.checkOnLaunch, state::setUpdateCheckOnLaunch)
        }

        if (available != null) {
            Text(
                "Android will ask before it installs anything, and the first time it will ask you to " +
                    "allow Noctorium to install apps at all.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

private fun megabytes(bytes: Long): String =
    if (bytes <= 0) "size unknown" else "%.0f MB".format(bytes / 1_048_576.0)
