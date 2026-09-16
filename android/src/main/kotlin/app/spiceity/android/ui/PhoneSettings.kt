package app.spiceity.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spiceity.core.AppState
import app.spiceity.domain.ProviderType

/**
 * Settings, as much of them as mean anything on a phone.
 *
 * Deliberately shorter than the desktop's. Discord Rich Presence needs its desktop app over a named pipe
 * and there is none here; the window chrome and player-bar position settings describe a window this has
 * not got. What is left is what actually differs from device to device: the accounts, where saved music
 * goes, and what is on the machine.
 */
@Composable
internal fun SettingsScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val likes by state.likes.collectAsState()
    val account by state.account.collectAsState()

    // The status-bar inset the other screens get from ScreenScaffold. Without it the title sits under
    // the clock, which on this phone is exactly where the clock is.
    LazyColumn(
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item { ScreenTitle("Settings", "Accounts, and where things go") }

        settings.message?.let { message ->
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), fontSize = 12.sp)
                        TextButton(state::clearSettingsMessage) { Text("OK") }
                    }
                }
            }
        }

        item {
            Card {
                Heading(Icons.Default.Insights, "Spiceity account")
                Spacer(Modifier.height(6.dp))
                if (account.signedIn) {
                    Text(account.user?.displayName.orEmpty(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "${account.stats.streams} streamed · ${account.stats.uniqueTracks} different",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(state::signOutOfSpiceity) { Text("Sign out") }
                } else {
                    SpiceityAccountForm(state, busy = account.busy)
                }
                account.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }

        item { ServiceCard(ProviderType.YOUTUBE_MUSIC, settings, likes, state) }
        item { ServiceCard(ProviderType.SOUNDCLOUD, settings, likes, state) }
        item { SpotifyCard(settings, state) }
        item { SavingCard(settings, state) }
        item { DiagnosticsCard(settings, state) }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun Heading(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tint: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint ?: MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(9.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun SpiceityAccountForm(state: AppState, busy: Boolean) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Text(
        "Signing in counts what you listen to, on this phone and on the desktop together.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        email,
        { email = it.trim() },
        label = { Text("Email") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        password,
        { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            { state.logInToSpiceity(email, password) },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
        ) { Text("Sign in") }
        OutlinedButton(
            { state.signUpToSpiceity(email, password, email.substringBefore('@')) },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
        ) { Text("Create account") }
    }
}

/**
 * A service account, and the one thing a phone can honestly say about it.
 *
 * Signing in needs a WebView flow that does not exist yet, so this reports what is stored and points at
 * the desktop rather than offering a button that cannot work. Saying "not yet" is better than a control
 * that does nothing.
 */
@Composable
private fun ServiceCard(
    provider: ProviderType,
    settings: app.spiceity.settings.SettingsState,
    likes: app.spiceity.core.LikeState,
    state: AppState,
) {
    val isSoundCloud = provider == ProviderType.SOUNDCLOUD
    val source = if (isSoundCloud) settings.preferences.soundCloudCookies else settings.preferences.youtubeCookies
    val connected = if (isSoundCloud) likes.soundCloudReady else likes.youTubeReady

    Card {
        Heading(if (isSoundCloud) Icons.Default.Cloud else Icons.Default.PlayCircle, provider.displayName)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                connected -> "Signed in. Your playlists and likes are in the library."
                source.isConfigured -> "A session is saved. It has not been checked on this device."
                else -> "Not connected on this phone."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        if (isSoundCloud) {
            Spacer(Modifier.height(10.dp))
            SoundCloudProfileField(settings.preferences.soundCloudUsername, state)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Signing in on the phone is not built yet — it needs a browser inside the app. Until then, " +
                "connect this account on the desktop.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun SoundCloudProfileField(saved: String, state: AppState) {
    var name by remember(saved) { mutableStateOf(saved) }
    OutlinedTextField(
        name,
        { name = it.take(80) },
        label = { Text("Your SoundCloud profile name") },
        placeholder = { Text("your-name") },
        singleLine = true,
        supportingText = { Text("SoundCloud finds your own playlists by profile name.") },
        trailingIcon = {
            if (name != saved) {
                TextButton({ state.setSoundCloudUsername(name) }) { Text("Save") }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SpotifyCard(settings: app.spiceity.settings.SettingsState, state: AppState) {
    val spotify = settings.spotify
    var clientId by remember(settings.preferences.spotifyClientId) {
        mutableStateOf(settings.preferences.spotifyClientId)
    }

    Card {
        Heading(Icons.Default.LibraryMusic, "Spotify library", tint = Color(0xFF1DB954))
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                spotify.connected && spotify.accountName.isNotBlank() -> "Reading ${spotify.accountName}'s library."
                spotify.connected -> "Connected. Your playlists are in the library."
                spotify.configured -> "Client id saved — connect your account."
                else -> "Read your playlists and liked songs. Playback never comes from Spotify."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.spotifyRedirectUri(),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            IconButton(state::copySpotifyRedirectUri, Modifier.size(30.dp)) {
                Icon(Icons.Default.ContentCopy, "Copy the redirect address", Modifier.size(15.dp))
            }
        }
        Text(
            "Add that to your Spotify app's Redirect URIs — the same one the desktop uses.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            clientId,
            { clientId = it.trim().take(64) },
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                { state.setSpotifyClientId(clientId) },
                enabled = clientId != settings.preferences.spotifyClientId,
            ) { Icon(Icons.Default.Save, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Save") }
            Button(
                state::connectSpotify,
                enabled = spotify.configured && !spotify.connecting &&
                    clientId == settings.preferences.spotifyClientId,
            ) { Text(if (spotify.connected) "Reconnect" else "Connect") }
            if (spotify.connected) {
                OutlinedButton(state::disconnectSpotify) { Text("Disconnect") }
            }
        }
        OutlinedButton(state::openSpotifyDashboard, Modifier.padding(top = 8.dp)) {
            Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Spotify dashboard")
        }
        spotify.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SavingCard(settings: app.spiceity.settings.SettingsState, state: AppState) {
    var folder by remember(settings.preferences.exportFolder) { mutableStateOf(settings.preferences.exportFolder) }
    Card {
        Heading(Icons.Default.Save, "Saving music")
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            folder,
            { folder = it },
            label = { Text("Folder") },
            placeholder = { Text(state.exportFolder()?.toString() ?: "Your Music folder") },
            singleLine = true,
            supportingText = {
                Text(
                    if (state.canSaveAsMp3()) {
                        "Saved as MP3."
                    } else {
                        "Saved as it comes — m4a or opus, which this phone plays anyway."
                    },
                )
            },
            trailingIcon = {
                if (folder != settings.preferences.exportFolder) {
                    TextButton({ state.setExportFolder(folder) }) { Text("Save") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiagnosticsCard(settings: app.spiceity.settings.SettingsState, state: AppState) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Heading(Icons.Default.History, "Diagnostics")
            Spacer(Modifier.weight(1f))
            TextButton(state::runDiagnostics, enabled = !settings.diagnosticsRunning) {
                Text(if (settings.diagnosticsRunning) "Checking…" else "Check")
            }
        }
        settings.diagnostics.forEach { result ->
            Spacer(Modifier.height(6.dp))
            Text(result.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(result.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}
