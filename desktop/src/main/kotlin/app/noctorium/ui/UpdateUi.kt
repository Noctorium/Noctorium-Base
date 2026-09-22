package app.noctorium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noctorium.core.AppState

/**
 * Updating, from the desktop.
 *
 * Deliberately not a thing that happens on its own. Noctorium looks once at launch and says so if there is
 * something newer; replacing the application underneath somebody who is listening to music is not an
 * improvement, and a download of a quarter of a gigabyte is not something to start without being asked.
 */
@Composable
internal fun UpdatePanel(state: AppState) {
    val updates by state.updates.collectAsState()
    val settings by state.settings.collectAsState()
    val available = updates.available
    // Bound here because a property from another module cannot be smart cast, and the two branches below
    // both need to know it is really there.
    val file = available?.file

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SettingsPanelCard {
            Text(
                if (available != null) "Noctorium ${available.version} is out" else "Noctorium is up to date",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (updates.currentVersion.isBlank()) {
                    "This build does not say which version it is, so it cannot tell."
                } else {
                    "You have ${updates.currentVersion}."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            if (available != null) {
                Spacer(Modifier.height(12.dp))
                when {
                    // Nothing this copy can install: an unzipped folder, or a release with no file for
                    // this platform. Saying so beats a button that cannot work.
                    !updates.canInstall || file == null -> Text(
                        "This copy was not installed by an installer, so it cannot replace itself. " +
                            "The release page has the download.",
                        Modifier.widthIn(max = 520.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    available.sha256 == null -> Text(
                        "This release did not publish a checksum, so Noctorium will not run its installer. " +
                            "The release page has the download.",
                        Modifier.widthIn(max = 520.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    else -> Text(
                        "${file?.name} · ${megabytes(file?.bytes ?: 0)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            updates.downloading?.let { fraction ->
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth().widthIn(max = 520.dp))
                Text(
                    "Downloading… ${(fraction * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                if (available != null && updates.canInstall && file != null && available.sha256 != null) {
                    Button({ state.installUpdate() }, enabled = !updates.busy) {
                        Text(if (updates.downloading != null) "Downloading…" else "Download and install")
                    }
                }
                if (available != null) {
                    OutlinedButton({ state.openReleasePage() }) { Text("What's new") }
                }
                OutlinedButton({ state.checkForUpdates() }, enabled = !updates.busy) {
                    Text(if (updates.checking) "Checking…" else "Check now")
                }
            }

            updates.message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, Modifier.widthIn(max = 520.dp), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(settings.preferences.updates.checkOnLaunch, state::setUpdateCheckOnLaunch)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Column {
                    Text("Look for updates when Noctorium starts", fontSize = 13.sp)
                    Text(
                        "One request to GitHub, and nothing is downloaded without asking.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        if (available != null && updates.canInstall) {
            Spacer(Modifier.height(14.dp))
            SettingsPanelCard {
                Text("What happens", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                AccountFact("The download is checked against the checksum published with the release.")
                AccountFact("The installer opens and asks, the same as if you had downloaded it yourself.")
                AccountFact("Noctorium closes so the installer can replace its files.")
                AccountFact("Your settings, sign-ins and downloads are kept: they live elsewhere.")
            }
        }
    }
}

private fun megabytes(bytes: Long): String =
    if (bytes <= 0) "size unknown" else "${(bytes / 1_048_576.0).let { "%.0f".format(it) }} MB"
