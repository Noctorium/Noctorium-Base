package app.spice.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spice.core.*
import app.spice.domain.*
import app.spice.lyrics.LyricLine
import app.spice.lyrics.LyricsProviderOutcome
import app.spice.lyrics.LyricsProviderStatus
import app.spice.playback.QueueState
import app.spice.playback.PlaybackState
import app.spice.playback.PlaybackStatus
import app.spice.playback.RepeatMode
import app.spice.auth.EmbeddedBrowserSession
import app.spice.auth.isSoundCloudSignedIn
import app.spice.auth.writeCookieFile
import app.spice.playlists.LocalPlaylist
import app.spice.playlists.PlaylistShareLink
import app.spice.settings.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SpiceApp(appState: AppState = remember { AppState() }) {
    val ui by appState.ui.collectAsState()
    val queue by appState.queue.state.collectAsState()
    val playback by appState.playback.collectAsState()

    DisposableEffect(appState) {
        onDispose { appState.close() }
    }

    MaterialTheme(colorScheme = SpiceColors) {
        Surface(Modifier.fillMaxSize()) {
            Row {
                NavigationRail(ui.destination, appState::navigate)
                Column(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f)) {
                        when (ui.destination) {
                            Destination.HOME -> HomeScreen(ui, appState)
                            Destination.SEARCH -> SearchScreen(ui, appState)
                            Destination.LIBRARY -> LibraryScreen(appState)
                            Destination.NOW_PLAYING -> NowPlayingScreen(queue, playback, appState)
                            Destination.QUEUE -> QueueScreen(queue, appState)
                            Destination.SETTINGS -> SettingsScreen(appState)
                        }
                    }
                    PlayerBar(queue, playback, appState)
                }
            }
        }
    }
}

@Composable
private fun NavigationRail(selected: Destination, navigate: (Destination) -> Unit) {
    Column(
        Modifier.width(192.dp).fillMaxHeight().background(AmoledBlack).padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(SpiceLavender, SpicePurpleStrong))),
                contentAlignment = Alignment.Center,
            ) { Text("S", color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp) }
            Spacer(Modifier.width(10.dp))
            Text("Spice", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(28.dp))
        NavItem("Home", Icons.Default.Home, selected == Destination.HOME) { navigate(Destination.HOME) }
        NavItem("Search", Icons.Default.Search, selected == Destination.SEARCH) { navigate(Destination.SEARCH) }
        NavItem("Library", Icons.Default.LibraryMusic, selected == Destination.LIBRARY) { navigate(Destination.LIBRARY) }
        Spacer(Modifier.weight(1f))
        NavItem("Settings", Icons.Default.Settings, selected == Destination.SETTINGS) { navigate(Destination.SETTINGS) }
        Spacer(Modifier.height(8.dp))
        Text("YouTube  •  YT Music  •  SoundCloud", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f), fontSize = 10.sp)
    }
}

@Composable
private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, action: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = action,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, tint = color)
            Spacer(Modifier.width(12.dp))
            Text(label, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun HomeScreen(ui: AppUiState, state: AppState) {
    val filtered = ui.homeSections.filter { section ->
        ui.providerFilter == ProviderFilter.ALL || section.provider.name == ui.providerFilter.name
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentPadding = PaddingValues(bottom = 36.dp)) {
        item {
            Spacer(Modifier.height(28.dp))
            Text("Good evening", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("Everything you love, together.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            FilterChips(ui.providerFilter, state::setFilter)
            Spacer(Modifier.height(18.dp))
        }
        ui.errorMessage?.let { message ->
            item { PlaybackError(message) }
        }
        if (ui.homeLoading) {
            items(2) { LoadingSection() }
        } else {
            items(filtered, key = { it.id }) { section ->
                HomeSectionView(section, state)
            }
        }
    }
}

@Composable
private fun FilterChips(selected: ProviderFilter, select: (ProviderFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderFilter.entries.forEach { filter ->
            val active = selected == filter
            Surface(
                onClick = { select(filter) },
                shape = RoundedCornerShape(10.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = .45f) else MaterialTheme.colorScheme.outline.copy(alpha = .55f)),
            ) {
                Text(
                    when (filter) {
                        ProviderFilter.ALL -> "All"
                        ProviderFilter.YOUTUBE_MUSIC -> "YouTube Music"
                        ProviderFilter.SOUNDCLOUD -> "SoundCloud"
                    },
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HomeSectionView(section: HomeSection, state: AppState) {
    Column(Modifier.padding(vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                Text(section.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                section.subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f), fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(section.tracks, key = { it.queueKey }) { track -> TrackCard(track, section.tracks, state) }
        }
    }
}

@Composable
private fun TrackCard(track: Track, sourceQueue: List<Track>, state: AppState) {
    Surface(
        onClick = { state.play(track, PlaybackOrigin.HOME, sourceQueue) },
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.width(160.dp),
    ) {
        Column {
            Box(
                Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)),
            ) {
                RemoteArtwork(track.artworkUrl, track.provider, Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.TopStart).padding(9.dp)) { ProviderBadge(track.provider, compact = true) }
                Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) { TrackMenu(track, state) }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(track.artistLine, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun LibraryScreen(state: AppState) {
    val library by state.library.collectAsState()

    LaunchedEffect(Unit) { state.refreshLibrary() }

    var newPlaylistOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }

    if (newPlaylistOpen) {
        PlaylistNameDialog("New playlist", "", "Create") { title ->
            newPlaylistOpen = false
            title?.let { state.createPlaylist(it) }
        }
    }
    if (importOpen) {
        ImportLinkDialog { link ->
            importOpen = false
            link?.let { state.importSharedPlaylist(it) }
        }
    }

    library.openLocalPlaylist?.let { playlist ->
        LocalPlaylistDetail(playlist, library.notice, state)
        return
    }
    library.openPlaylist?.let { playlist ->
        PlaylistDetail(playlist, library, state)
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp)) {
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your library", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Playlists you made here, and playlists from the accounts you connected.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            Button({ newPlaylistOpen = true }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(7.dp)); Text("New playlist")
            }
            Spacer(Modifier.width(9.dp))
            OutlinedButton({ importOpen = true }) {
                Icon(Icons.Default.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Paste link")
            }
            IconButton({ state.refreshLibrary(force = true) }, enabled = !library.loading) {
                Icon(Icons.Default.Refresh, "Reload playlists")
            }
        }
        Spacer(Modifier.height(10.dp))
        library.notice?.let { LibraryNotice(it, state::clearLibraryNotice) }
        Spacer(Modifier.height(6.dp))
        if (library.localPlaylists.isNotEmpty()) {
            LazyColumn(
                Modifier.heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(library.localPlaylists, key = { it.id }) { playlist ->
                    LocalPlaylistRow(playlist, state) { state.openLocalPlaylist(playlist) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "From your accounts",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(9.dp))
        }
        when {
            library.loading && library.playlists.isEmpty() -> LoadingSection()
            library.needsSoundCloudUsername && library.playlists.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { SoundCloudNamePrompt(state) }
            library.errorMessage != null && library.playlists.isEmpty() -> LibraryProblem(library.errorMessage!!, state)
            library.playlists.isEmpty() -> EmptyScreen("No playlists yet", "Playlists you own on a connected service appear here.")
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                library.errorMessage?.let { message ->
                    item { LibraryProblemBanner(message) }
                }
                if (library.needsSoundCloudUsername) {
                    item { SoundCloudNamePrompt(state) }
                }
                items(library.playlists, key = { it.playlistKey }) { playlist ->
                    PlaylistRow(playlist) { state.openPlaylist(playlist) }
                }
            }
        }
    }
}

@Composable
private fun LibraryNotice(message: String, dismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), shape = RoundedCornerShape(11.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(9.dp))
            SelectionContainer(Modifier.weight(1f)) { Text(message, fontSize = 12.sp) }
            IconButton(dismiss, Modifier.size(26.dp)) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(15.dp)) }
        }
    }
}

@Composable
private fun LocalPlaylistRow(playlist: LocalPlaylist, state: AppState, open: () -> Unit) {
    Surface(onClick = open, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f), shape = RoundedCornerShape(13.dp)) {
        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(listOf(SpiceLavender, SpicePurpleStrong))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(24.dp), tint = Color.White) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"} • made in Spice",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            IconButton({ state.copyPlaylistShareLink(playlist) }) {
                Icon(Icons.Default.Share, "Copy share link", Modifier.size(18.dp))
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LocalPlaylistDetail(playlist: LocalPlaylist, notice: String?, state: AppState) {
    var renameOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (renameOpen) {
        PlaylistNameDialog("Rename playlist", playlist.title, "Rename") { title ->
            renameOpen = false
            title?.let { state.renamePlaylist(playlist.id, it) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${playlist.title}\"?") },
            text = { Text("The playlist is removed from Spice. The tracks themselves are untouched.") },
            confirmButton = {
                Button({ confirmDelete = false; state.deletePlaylist(playlist.id) }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton({ confirmDelete = false }) { Text("Keep") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(state::closeLocalPlaylist) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to library") }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.title, fontSize = 27.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"} • made in Spice",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            if (playlist.tracks.isNotEmpty()) {
                Button({ state.playLocalPlaylist(playlist) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(7.dp)); Text("Play all")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton({ state.copyPlaylistShareLink(playlist) }) {
                Icon(Icons.Default.Share, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Copy share link")
            }
            OutlinedButton({ state.copyPlaylistAsText(playlist) }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Copy as text")
            }
            OutlinedButton({ renameOpen = true }) { Text("Rename") }
            OutlinedButton({ confirmDelete = true }) { Text("Delete") }
        }
        Spacer(Modifier.height(10.dp))
        notice?.let { LibraryNotice(it, state::clearLibraryNotice) }
        Spacer(Modifier.height(8.dp))
        Text(
            "A share link carries the whole playlist inside it, so anyone with Spice can paste it and get the same tracks.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (playlist.tracks.isEmpty()) {
            EmptyScreen("Nothing here yet", "Use the ⋮ menu on any track to add it to this playlist.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                items(playlist.tracks, key = { it.queueKey }) { track ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { TrackRow(track, playlist.tracks, state) }
                        IconButton({ state.removeTrackFromPlaylist(playlist.id, track.queueKey) }) {
                            Icon(Icons.Default.RemoveCircleOutline, "Remove from playlist", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    finish: (String?) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { finish(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name,
                { name = it.take(120) },
                label = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button({ finish(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { OutlinedButton({ finish(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun ImportLinkDialog(finish: (String?) -> Unit) {
    var link by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { finish(null) },
        title = { Text("Paste a shared playlist") },
        text = {
            Column {
                Text(
                    "Paste a spice://playlist link someone sent you. The tracks travel inside the link, so nothing needs to be online.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    link,
                    { link = it },
                    label = { Text("Share link") },
                    placeholder = { Text(PlaylistShareLink.PREFIX + "…") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
            }
        },
        confirmButton = { Button({ finish(link) }, enabled = link.isNotBlank()) { Text("Import") } },
        dismissButton = { OutlinedButton({ finish(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun SoundCloudNamePrompt(state: AppState) {
    var username by remember { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.widthIn(max = 620.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Text("One thing needed for SoundCloud", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(7.dp))
            Text(
                "SoundCloud finds your playlists by profile name, and the browser session does not reveal it. " +
                    "Open your SoundCloud profile and copy the last part of the address — soundcloud.com/<name>.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    username,
                    { username = it.take(80) },
                    label = { Text("Profile name or profile link") },
                    placeholder = { Text("your-name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Button({ state.setSoundCloudUsername(username) }, enabled = username.isNotBlank()) {
                    Icon(Icons.Default.Search, null); Spacer(Modifier.width(7.dp)); Text("Load playlists")
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(
                "Your own sets are public, so this works whether or not the browser session is connected.",
                color = MaterialTheme.colorScheme.tertiary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, open: () -> Unit) {
    Surface(onClick = open, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f), shape = RoundedCornerShape(13.dp)) {
        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                if (playlist.artworkUrl != null) {
                    RemoteArtwork(playlist.artworkUrl, playlist.provider, Modifier.fillMaxSize())
                } else {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, Modifier.size(24.dp)) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        playlist.ownerName,
                        playlist.trackCount?.let { "$it tracks" },
                    ).joinToString(" • ").ifBlank { playlist.provider.displayName },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProviderBadge(playlist.provider, compact = true)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlaylistDetail(playlist: Playlist, library: LibraryState, state: AppState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(state::closePlaylist) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to library") }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.title, fontSize = 27.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        playlist.provider.displayName,
                        playlist.ownerName,
                        playlist.tracks.size.takeIf { it > 0 }?.let { "$it tracks" },
                    ).joinToString(" • "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            if (library.openPlaylistEnriching) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(7.dp))
                    Text("Loading covers…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            if (playlist.tracks.isNotEmpty()) {
                Button({ state.playPlaylist(playlist) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(7.dp)); Text("Play all")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            library.openPlaylistLoading -> LoadingSection()
            library.openPlaylistError != null -> LibraryProblem(library.openPlaylistError!!, state)
            playlist.tracks.isEmpty() -> EmptyScreen("Nothing to play", "This playlist came back empty.")
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                items(playlist.tracks, key = { it.queueKey }) { track ->
                    TrackRow(track, playlist.tracks, state)
                }
            }
        }
    }
}

@Composable
private fun LibraryProblem(message: String, state: AppState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CloudOff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(12.dp))
            Text("Playlists could not be loaded", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            SelectionContainer {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Spacer(Modifier.height(15.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button({ state.navigate(Destination.SETTINGS) }) {
                    Icon(Icons.Default.Settings, null); Spacer(Modifier.width(7.dp)); Text("Open settings")
                }
                OutlinedButton({ state.refreshLibrary(force = true) }) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun LibraryProblemBanner(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f), shape = RoundedCornerShape(11.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WarningAmber, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(9.dp))
            Text(message, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SearchScreen(ui: AppUiState, state: AppState) {
    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text("Search", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = ui.searchQuery,
            onValueChange = state::search,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Artists, songs, albums and playlists") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().widthIn(max = 860.dp),
        )
        Spacer(Modifier.height(12.dp))
        SearchModePicker(ui.searchMode, state::setSearchMode)
        Spacer(Modifier.height(18.dp))
        when {
            ui.searchLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.searchQuery.isBlank() -> EmptyScreen(
                "Search ${ui.searchMode.displayName}",
                if (ui.searchMode == SearchMode.HYBRID)
                    "YouTube Music, YouTube videos, and SoundCloud results appear together."
                else "Only ${ui.searchMode.displayName} results will appear.",
            )
            ui.errorMessage != null -> PlaybackError(ui.errorMessage)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(ui.searchResults.tracks, key = { it.queueKey }) { track ->
                    TrackRow(track, ui.searchResults.tracks, state)
                }
            }
        }
    }
}

@Composable
private fun SearchModePicker(selected: SearchMode, select: (SearchMode) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SearchMode.entries, key = { it.name }) { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { select(mode) },
                label = { Text(mode.displayName) },
                leadingIcon = if (mode == SearchMode.HYBRID) {
                    { Icon(Icons.Default.Hub, null, Modifier.size(16.dp)) }
                } else null,
                shape = RoundedCornerShape(10.dp),
            )
        }
    }
}

@Composable
private fun TrackRow(track: Track, sourceQueue: List<Track>, state: AppState) {
    Surface(
        onClick = { state.play(track, PlaybackOrigin.SEARCH, sourceQueue) },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .36f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteArtwork(track.artworkUrl, track.provider, Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.SemiBold)
                Text(track.artistLine, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            ProviderBadge(track.provider)
            TrackMenu(track, state)
        }
    }
}

/**
 * Writes the like to the provider account, not just to Spice, so the heart reflects what SoundCloud holds.
 */
@Composable
private fun LikeButton(track: Track, state: AppState, size: Dp = 36.dp) {
    val likes by state.likes.collectAsState()
    val liked = likes.isLiked(track)
    val busy = likes.isBusy(track)
    val supported = track.provider == ProviderType.SOUNDCLOUD
    IconButton({ state.toggleLike(track) }, Modifier.size(size), enabled = supported && !busy) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            liked -> Icon(Icons.Default.Favorite, "Remove from your SoundCloud likes", tint = MaterialTheme.colorScheme.primary)
            else -> Icon(
                Icons.Default.FavoriteBorder,
                if (supported) "Like on SoundCloud" else "Liking is only available for SoundCloud",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (supported) 1f else .4f),
            )
        }
    }
}

@Composable
private fun TrackMenu(track: Track, state: AppState) {
    var expanded by remember { mutableStateOf(false) }
    var addToPlaylistOpen by remember { mutableStateOf(false) }
    val library by state.library.collectAsState()
    val likes by state.likes.collectAsState()
    val likedNow = likes.isLiked(track)
    Box {
        IconButton({ expanded = true }, Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, "Track actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                onClick = { state.playNext(track); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                onClick = { state.addToQueue(track); expanded = false },
            )
            if (track.provider == ProviderType.SOUNDCLOUD) {
                DropdownMenuItem(
                    text = { Text(if (likedNow) "Remove from SoundCloud likes" else "Like on SoundCloud") },
                    leadingIcon = {
                        Icon(if (likedNow) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null)
                    },
                    onClick = { state.toggleLike(track); expanded = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Copy link") },
                leadingIcon = { Icon(Icons.Default.Link, null) },
                onClick = { state.copyTrackLink(track); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                trailingIcon = { Icon(Icons.Default.ChevronRight, null, Modifier.size(17.dp)) },
                onClick = { expanded = false; addToPlaylistOpen = true },
            )
        }
        if (addToPlaylistOpen) {
            AddToPlaylistDialog(track, library.localPlaylists, state) { addToPlaylistOpen = false }
        }
    }
}

@Composable
private fun AddToPlaylistDialog(
    track: Track,
    playlists: List<LocalPlaylist>,
    state: AppState,
    dismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(playlists.isEmpty()) }
    if (creating) {
        PlaylistNameDialog("New playlist for ${track.title}", "", "Create and add") { title ->
            dismiss()
            title?.let { state.createPlaylist(it, firstTrack = track) }
        }
        return
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    Surface(
                        onClick = { dismiss(); state.addTrackToPlaylist(playlist.id, track) },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${playlist.trackCount} tracks",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp,
                                )
                            }
                            if (playlist.tracks.any { it.queueKey == track.queueKey }) {
                                Icon(Icons.Default.Check, "Already added", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button({ creating = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("New playlist") } },
        dismissButton = { OutlinedButton(dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProviderBadge(provider: ProviderType, compact: Boolean = false) {
    val background = when (provider) {
        ProviderType.YOUTUBE_MUSIC -> Color(0xFF8B2AB8)
        ProviderType.YOUTUBE_VIDEO -> Color(0xFFB32C35)
        ProviderType.SOUNDCLOUD -> Color(0xFFC45A16)
        ProviderType.LOCAL -> Color(0xFF4F46E5)
    }
    val label = when (provider) {
        ProviderType.YOUTUBE_MUSIC -> if (compact) "YTM" else "YT MUSIC"
        ProviderType.YOUTUBE_VIDEO -> if (compact) "YT" else "YOUTUBE"
        ProviderType.SOUNDCLOUD -> if (compact) "SC" else "SOUNDCLOUD"
        ProviderType.LOCAL -> if (compact) "LOCAL" else "LOCAL"
    }
    Text(
        label,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(background.copy(alpha = .9f)).padding(horizontal = if (compact) 6.dp else 7.dp, vertical = 4.dp),
    )
}

@Composable
private fun PlayerBar(queue: QueueState, playback: PlaybackState, state: AppState) {
    val current = queue.current
    Surface(
        shadowElevation = 0.dp,
        color = SpicePanel,
        modifier = Modifier.fillMaxWidth().height(122.dp).clickable(
            enabled = current != null,
            onClickLabel = "Open now playing",
            onClick = { state.navigate(Destination.NOW_PLAYING) },
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 760.dp
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = .35f))
                PlaybackProgressBar(
                    playback = playback,
                    onSeek = state::seekTo,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 18.dp),
                )
                Row(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = if (compact) 10.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (!compact) {
                            if (current != null) {
                                RemoteArtwork(current.artworkUrl, current.provider, Modifier.size(52.dp).clip(RoundedCornerShape(11.dp)))
                            } else {
                                Box(
                                    Modifier.size(52.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Default.MusicNote, null) }
                            }
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                current?.title ?: "Nothing playing",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                playback.errorMessage ?: current?.artistLine ?: "Choose a track to start",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (playback.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        current?.let { LikeButton(it, state) }
                    }

                    Row(
                        Modifier.width(if (compact) 236.dp else 270.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(state::toggleShuffle, Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Shuffle,
                                "Shuffle",
                                tint = if (queue.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalIconButton(state::previous, Modifier.size(40.dp)) {
                            Icon(Icons.Default.SkipPrevious, "Previous track")
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            state::togglePlayback,
                            Modifier.size(50.dp),
                            enabled = playback.status != PlaybackStatus.RESOLVING && current != null,
                        ) {
                            if (playback.status == PlaybackStatus.RESOLVING) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (playback.isPlaying) "Pause" else "Play",
                                    Modifier.size(30.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton(state::next, Modifier.size(40.dp)) {
                            Icon(Icons.Default.SkipNext, "Next track")
                        }
                        IconButton(state::cycleRepeat, Modifier.size(36.dp)) {
                            Icon(
                                if (queue.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                when (queue.repeatMode) {
                                    RepeatMode.OFF -> "Repeat off"
                                    RepeatMode.ALL -> "Repeat all"
                                    RepeatMode.ONE -> "Repeat one"
                                },
                                tint = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!compact) {
                            IconButton(state::toggleMute, Modifier.size(36.dp)) {
                                Icon(
                                    if (playback.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    if (playback.isMuted) "Unmute" else "Mute",
                                    tint = if (playback.isMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Slider(
                                value = volumeToSliderPosition(playback.volume, playback.volumeBoostEnabled),
                                onValueChange = { state.setVolume(sliderPositionToVolume(it, playback.volumeBoostEnabled)) },
                                modifier = Modifier.width(92.dp),
                                valueRange = 0f..1f,
                            )
                            Text(
                                "${(playback.volume * 100).roundToInt()}%",
                                color = if (playback.volume > 1f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(34.dp),
                            )
                            FilterChip(
                                selected = playback.volumeBoostEnabled,
                                onClick = state::toggleVolumeBoost,
                                label = { Text("BOOST ×10", fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                                leadingIcon = { Icon(Icons.Default.Bolt, null, Modifier.size(14.dp)) },
                                modifier = Modifier.height(30.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        BadgedBox(
                            badge = {
                                if (queue.tracks.isNotEmpty()) Badge { Text(queue.tracks.size.toString()) }
                            },
                        ) {
                            IconButton({ state.navigate(Destination.QUEUE) }) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Open queue")
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun volumeToSliderPosition(volume: Float, boosted: Boolean): Float {
    if (!boosted) return volume.coerceIn(0f, 1f)
    val safeVolume = volume.coerceIn(0f, 10f)
    return if (safeVolume <= 1f) {
        safeVolume * .5f
    } else {
        .5f + ((safeVolume - 1f) / 9f) * .5f
    }
}

internal fun sliderPositionToVolume(position: Float, boosted: Boolean): Float {
    val safePosition = position.coerceIn(0f, 1f)
    if (!boosted) return safePosition
    return if (safePosition <= .5f) {
        safePosition * 2f
    } else {
        1f + ((safePosition - .5f) / .5f) * 9f
    }
}

private enum class NowPlayingTab(val label: String) {
    UP_NEXT("Up next"),
    LYRICS("Lyrics"),
    RELATED("Related"),
}

@Composable
private fun NowPlayingScreen(queue: QueueState, playback: PlaybackState, state: AppState) {
    val current = queue.current
    var selectedTab by remember { mutableStateOf(NowPlayingTab.UP_NEXT) }

    if (current == null) {
        EmptyScreen("Nothing playing", "Choose a track, then click the player to open this view.")
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                NowPlayingHero(current, playback, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                NowPlayingPanel(
                    queue = queue,
                    selectedTab = selectedTab,
                    selectTab = { selectedTab = it },
                    state = state,
                    modifier = Modifier.width(410.dp).fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                NowPlayingHero(current, playback, Modifier.fillMaxWidth().height(330.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                NowPlayingPanel(
                    queue = queue,
                    selectedTab = selectedTab,
                    selectTab = { selectedTab = it },
                    state = state,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NowPlayingHero(track: Track, playback: PlaybackState, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.background(
            Brush.radialGradient(
                listOf(MaterialTheme.colorScheme.primary.copy(alpha = .14f), AmoledBlack),
            ),
        ),
    ) {
        val artworkSize = minOf(370.dp, maxWidth * .72f, maxHeight * .62f)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("NOW PLAYING", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                ProviderBadge(track.provider)
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 18.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                RemoteArtwork(track.artworkUrl, track.provider, Modifier.size(artworkSize))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                track.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                track.artistLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (playback.status == PlaybackStatus.RESOLVING) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.widthIn(max = 280.dp).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun NowPlayingPanel(
    queue: QueueState,
    selectedTab: NowPlayingTab,
    selectTab: (NowPlayingTab) -> Unit,
    state: AppState,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = SpicePanel.copy(alpha = .72f)) {
        Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NowPlayingTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    Surface(
                        onClick = { selectTab(tab) },
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            tab.label,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .22f))
            when (selectedTab) {
                NowPlayingTab.UP_NEXT -> UpNextPanel(queue, state)
                NowPlayingTab.LYRICS -> queue.current?.let { LyricsPanel(it, state) }
                NowPlayingTab.RELATED -> PanelPlaceholder(
                    icon = Icons.Default.AutoAwesome,
                    title = "Related music",
                    message = "More music based on the current track will appear here.",
                )
            }
        }
    }
}

@Composable
private fun LyricsPanel(track: Track, state: AppState) {
    val lyrics by state.lyrics.collectAsState()
    val playback by state.playback.collectAsState()
    val selectedOutcome = lyrics.outcomes.firstOrNull { it.provider == lyrics.selectedProvider }
    val selectedResult = selectedOutcome?.result
    val sourceListState = rememberLazyListState()
    val sourceScrollScope = rememberCoroutineScope()

    LaunchedEffect(track.queueKey, track.title, track.artistLine, track.durationMs) {
        state.loadLyrics(track)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sources", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton({ state.loadLyrics(track, forceRefresh = true) }, Modifier.size(34.dp)) {
                Icon(Icons.Default.Refresh, "Refresh lyrics", Modifier.size(18.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    sourceScrollScope.launch {
                        sourceListState.animateScrollToItem((sourceListState.firstVisibleItemIndex - 1).coerceAtLeast(0))
                    }
                },
                enabled = sourceListState.canScrollBackward,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.ChevronLeft, "Previous lyric sources", Modifier.size(18.dp))
            }
            Box(Modifier.weight(1f).height(53.dp)) {
                LazyRow(
                    state = sourceListState,
                    modifier = Modifier.fillMaxSize().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    items(lyrics.outcomes, key = { it.provider.name }) { outcome ->
                        FilterChip(
                            selected = lyrics.selectedProvider == outcome.provider,
                            onClick = { state.selectLyricsProvider(outcome.provider) },
                            label = { Text(outcome.provider.displayName, fontSize = 10.sp) },
                            leadingIcon = { LyricsProviderStatusIcon(outcome) },
                            shape = RoundedCornerShape(9.dp),
                        )
                    }
                }
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(sourceListState),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp),
                )
            }
            IconButton(
                onClick = {
                    sourceScrollScope.launch {
                        val lastVisible = sourceListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        sourceListState.animateScrollToItem((lastVisible + 1).coerceAtMost(lyrics.outcomes.lastIndex.coerceAtLeast(0)))
                    }
                },
                enabled = sourceListState.canScrollForward,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.ChevronRight, "More lyric sources", Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))

        when {
            lyrics.loading && selectedResult == null -> {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Checking ${lyrics.outcomes.size} lyric sources…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            selectedResult != null && selectedResult.lines.isNotEmpty() -> {
                LyricsContent(selectedOutcome, playback.positionMs, state)
            }
            selectedResult != null && selectedResult.sourceUrl != null -> {
                Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(14.dp))
                    Text(selectedResult.provider.displayName, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        selectedResult.message ?: "Open this provider to view the lyrics.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    FilledTonalButton({ state.openExternalUrl(selectedResult.sourceUrl) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open ${selectedResult.provider.displayName}")
                    }
                }
            }
            selectedOutcome != null -> LyricsProviderUnavailable(selectedOutcome) {
                state.loadLyrics(track, forceRefresh = true)
            }
            else -> LyricsNotFound(lyrics.outcomes, lyrics.errorMessage)
        }
    }
}

@Composable
private fun LyricsProviderUnavailable(outcome: LyricsProviderOutcome, refresh: () -> Unit) {
    val (title, message) = when (outcome.status) {
        LyricsProviderStatus.NOT_FOUND -> "No match on ${outcome.provider.displayName}" to
            "This source does not currently have lyrics for this version of the track."
        LyricsProviderStatus.NEEDS_KEY -> "${outcome.provider.displayName} needs access" to
            "This optional source requires its own API key before Spice can search it."
        LyricsProviderStatus.ERROR -> "${outcome.provider.displayName} is unavailable" to
            "The source did not respond correctly. Refresh to try it again."
        LyricsProviderStatus.SEARCHING -> "Checking ${outcome.provider.displayName}" to
            "Waiting for this source to respond…"
        else -> "Lyrics unavailable" to (outcome.detail ?: "This source did not return lyrics.")
    }
    Column(
        Modifier.fillMaxSize().padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (outcome.status == LyricsProviderStatus.NEEDS_KEY) Icons.Default.Key else Icons.Default.Lyrics,
            null,
            Modifier.size(40.dp),
            tint = if (outcome.status == LyricsProviderStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (outcome.status == LyricsProviderStatus.ERROR) {
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(refresh) {
                Icon(Icons.Default.Refresh, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("Try again")
            }
        }
    }
}

@Composable
private fun LyricsProviderStatusIcon(outcome: LyricsProviderOutcome) {
    val (icon, tint) = when (outcome.status) {
        LyricsProviderStatus.SEARCHING -> Icons.Default.HourglassTop to MaterialTheme.colorScheme.onSurfaceVariant
        LyricsProviderStatus.FOUND -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        LyricsProviderStatus.LINK_ONLY -> Icons.AutoMirrored.Filled.OpenInNew to MaterialTheme.colorScheme.primary
        LyricsProviderStatus.NOT_FOUND -> Icons.Default.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
        LyricsProviderStatus.NEEDS_KEY -> Icons.Default.Key to MaterialTheme.colorScheme.tertiary
        LyricsProviderStatus.ERROR -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(icon, outcome.detail, Modifier.size(15.dp), tint = tint)
}

@Composable
private fun LyricsContent(outcome: LyricsProviderOutcome, positionMs: Long, state: AppState) {
    val result = outcome.result ?: return
    val listState = rememberLazyListState()
    val activeIndex = remember(result.lines, positionMs) {
        if (!result.synced) -1 else result.lines.indexOfLast { (it.startTimeMs ?: Long.MAX_VALUE) <= positionMs }
    }

    LaunchedEffect(activeIndex, result.provider) {
        if (activeIndex >= 0) listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(result.provider.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    if (result.synced) "Synced lyrics" else "Plain lyrics",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
            result.sourceUrl?.let { url ->
                TextButton({ state.openExternalUrl(url) }) {
                    Text("Source", fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(14.dp))
                }
            }
        }
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (result.synced) 13.dp else 8.dp),
            ) {
                itemsIndexed(result.lines) { index, line ->
                    LyricLineText(line, active = index == activeIndex, synced = result.synced)
                }
                result.attribution?.let { attribution ->
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text(attribution, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f), fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricLineText(line: LyricLine, active: Boolean, synced: Boolean) {
    Text(
        line.text,
        color = when {
            active -> MaterialTheme.colorScheme.primary
            synced -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        fontSize = if (active) 18.sp else if (synced) 15.sp else 14.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        lineHeight = if (active) 23.sp else 20.sp,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    )
}

@Composable
private fun LyricsNotFound(outcomes: List<LyricsProviderOutcome>, errorMessage: String?) {
    val needsKeys = outcomes.filter { it.status == LyricsProviderStatus.NEEDS_KEY }
    Column(
        Modifier.fillMaxSize().padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lyrics, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("No lyrics found", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            errorMessage ?: "None of the configured sources matched this track.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (needsKeys.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Optional keys unlock: ${needsKeys.joinToString { it.provider.displayName }}",
                color = MaterialTheme.colorScheme.tertiary,
                fontSize = 10.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun UpNextPanel(queue: QueueState, state: AppState) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Playing from", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text(
                    queue.context?.originType?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Queue",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text("${queue.currentIndex + 1} / ${queue.tracks.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 18.dp),
        ) {
            itemsIndexed(queue.tracks, key = { index, track -> "expanded:${track.queueKey}:$index" }) { index, track ->
                val playing = index == queue.currentIndex
                Surface(
                    onClick = { state.jumpToQueueItem(index) },
                    color = if (playing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            if (playing) {
                                Icon(Icons.Default.GraphicEq, "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                            } else {
                                RemoteArtwork(track.artworkUrl, track.provider, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                            )
                            Text(
                                track.artistLine,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        track.durationMs?.let {
                            Text(formatPlaybackTime(it), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelPlaceholder(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun QueueScreen(queue: QueueState, state: AppState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Queue", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (queue.tracks.isEmpty()) "Nothing queued" else "${queue.tracks.size} tracks • ${queue.currentIndex + 1} playing",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            if (queue.tracks.isNotEmpty()) {
                TextButton(state::clearQueue) { Icon(Icons.Default.ClearAll, null); Spacer(Modifier.width(6.dp)); Text("Clear") }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = state::toggleShuffle,
                label = { Text(if (queue.shuffleEnabled) "Shuffle on" else "Shuffle") },
                leadingIcon = { Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (queue.shuffleEnabled) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    labelColor = if (queue.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconContentColor = if (queue.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            AssistChip(
                onClick = state::cycleRepeat,
                label = {
                    Text(
                        when (queue.repeatMode) {
                            RepeatMode.OFF -> "Repeat"
                            RepeatMode.ALL -> "Repeat all"
                            RepeatMode.ONE -> "Repeat one"
                        },
                    )
                },
                leadingIcon = {
                    Icon(if (queue.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null, Modifier.size(18.dp))
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    labelColor = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconContentColor = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
        Spacer(Modifier.height(18.dp))
        if (queue.tracks.isEmpty()) {
            EmptyScreen("Your queue is empty", "Play a section or add tracks from the ⋮ menu.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                itemsIndexed(queue.tracks, key = { index, track -> "${track.queueKey}:$index" }) { index, track ->
                    QueueTrackRow(track, index, queue, state)
                }
            }
        }
    }
}

@Composable
private fun QueueTrackRow(track: Track, index: Int, queue: QueueState, state: AppState) {
    val playing = index == queue.currentIndex
    Surface(
        onClick = { state.jumpToQueueItem(index) },
        color = if (playing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .58f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .28f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (playing) Icon(Icons.Default.GraphicEq, "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                else Text((index + 1).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            RemoteArtwork(track.artworkUrl, track.provider, Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal)
                Text(track.artistLine, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            ProviderBadge(track.provider, compact = true)
            IconButton({ state.moveQueueItem(index, index - 1) }, enabled = index > 0, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "Move up", Modifier.size(19.dp))
            }
            IconButton({ state.moveQueueItem(index, index + 1) }, enabled = index < queue.tracks.lastIndex, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, "Move down", Modifier.size(19.dp))
            }
            IconButton({ state.removeQueueItem(index) }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PlaybackProgressBar(
    playback: PlaybackState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMs = playback.durationMs.coerceAtLeast(0)
    val canSeek = playback.track != null && durationMs > 0 && playback.status != PlaybackStatus.RESOLVING
    var dragging by remember { mutableStateOf(false) }
    var draggedPosition by remember { mutableFloatStateOf(0f) }
    val displayedPosition = if (dragging) draggedPosition else playback.positionMs.toFloat()
    val maximum = durationMs.coerceAtLeast(1).toFloat()

    Row(modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatPlaybackTime(displayedPosition.toLong()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.width(44.dp),
        )
        Slider(
            value = displayedPosition.coerceIn(0f, maximum),
            onValueChange = {
                dragging = true
                draggedPosition = it
            },
            onValueChangeFinished = {
                val target = draggedPosition.toLong()
                dragging = false
                onSeek(target)
            },
            enabled = canSeek,
            valueRange = 0f..maximum,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatPlaybackTime(durationMs),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.width(48.dp),
        )
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun PlaybackError(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

private enum class SettingsPage { PROFILE, YOUTUBE, SOUNDCLOUD, SCROBBLING, LYRICS, DISCORD, DIAGNOSTICS }

@Composable
private fun SettingsScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    if (page != null) {
        SettingsDetailHeader(pageTitle(page!!), { page = null }) {
            when (page) {
                SettingsPage.PROFILE -> ProfileSettingsPanel(settings.preferences, state)
                SettingsPage.YOUTUBE -> GoogleAccountPanel(settings, state)
                SettingsPage.SOUNDCLOUD -> AccountConnectionPanel(
                    ProviderType.SOUNDCLOUD,
                    settings.preferences.soundCloudCookies,
                    settings.soundCloudAccount,
                    state,
                    settings.preferences.soundCloudUsername,
                )
                SettingsPage.SCROBBLING -> ScrobblingSettingsPanel(settings, state)
                SettingsPage.LYRICS -> LyricsSettingsPanel()
                SettingsPage.DISCORD -> DiscordSettingsPanel(settings.preferences, state)
                SettingsPage.DIAGNOSTICS -> DiagnosticsPanel(settings, state)
                null -> Unit
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 30.dp),
        contentPadding = PaddingValues(top = 26.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("Accounts, services and how Spice behaves.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
        }
        settings.message?.let { message ->
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f), shape = RoundedCornerShape(11.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp)); Text(message, Modifier.weight(1f), fontSize = 12.sp)
                        IconButton(state::clearSettingsMessage, Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(16.dp)) }
                    }
                }
            }
        }
        item {
            SettingsCard(
                settings.preferences.profileName,
                "Your local Spice profile",
                Icons.Default.AccountCircle,
                { page = SettingsPage.PROFILE },
            )
        }
        item {
            SettingsCard(
                "YouTube Music",
                accountSummary(settings.preferences.youtubeCookies, settings.youtubeAccount),
                Icons.Default.PlayCircle,
                { page = SettingsPage.YOUTUBE },
                settings.youtubeAccount.status == AccountConnectionStatus.CONNECTED,
            )
        }
        item {
            SettingsCard(
                "SoundCloud",
                accountSummary(settings.preferences.soundCloudCookies, settings.soundCloudAccount),
                Icons.Default.Cloud,
                { page = SettingsPage.SOUNDCLOUD },
                settings.soundCloudAccount.status == AccountConnectionStatus.CONNECTED,
            )
        }
        item {
            val connected = listOf(settings.scrobbling.lastFm, settings.scrobbling.listenBrainz)
                .filter { it.status == ScrobbleConnectionStatus.CONNECTED }
                .mapNotNull { it.username }
            SettingsCard(
                "Scrobbling",
                if (connected.isEmpty()) "Connect Last.fm or ListenBrainz" else "Connected: ${connected.joinToString()}",
                Icons.Default.History,
                { page = SettingsPage.SCROBBLING },
                connected.isNotEmpty(),
            )
        }
        item { SettingsCard("Lyrics providers", "LRCLIB, Better Lyrics, Genius and 5 more", Icons.Default.Lyrics, { page = SettingsPage.LYRICS }) }
        item {
            SettingsCard(
                "Discord Rich Presence",
                if (settings.preferences.discordPresenceEnabled) "Enabled" else "Disabled",
                Icons.Default.SportsEsports,
                { page = SettingsPage.DISCORD },
                settings.preferences.discordPresenceEnabled,
            )
        }
        item { SettingsCard("Diagnostics", "Check yt-dlp, mpv, FFmpeg and storage", Icons.Default.MonitorHeart, { page = SettingsPage.DIAGNOSTICS }) }
    }
}

private fun pageTitle(page: SettingsPage) = when (page) {
    SettingsPage.PROFILE -> "Your Spice profile"
    SettingsPage.YOUTUBE -> "YouTube Music account"
    SettingsPage.SOUNDCLOUD -> "SoundCloud account"
    SettingsPage.SCROBBLING -> "Scrobbling"
    SettingsPage.LYRICS -> "Lyrics providers"
    SettingsPage.DISCORD -> "Discord Rich Presence"
    SettingsPage.DIAGNOSTICS -> "Diagnostics"
}

@Composable
private fun SettingsDetailHeader(title: String, back: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to settings") }
            Spacer(Modifier.width(6.dp)); Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun ProfileSettingsPanel(preferences: SpicePreferences, state: AppState) {
    var name by remember(preferences.profileName) { mutableStateOf(preferences.profileName) }
    SettingsPanelCard {
        Icon(Icons.Default.AccountCircle, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text("Local profile", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("This name stays on this computer and identifies your Spice setup.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Button({ state.setProfileName(name) }) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(7.dp)); Text("Save profile") }
    }
}

/**
 * Hosts SoundCloud's own sign-in page in embedded Chromium. SoundCloud has no OAuth to build against — app
 * registration has been closed for years — so signing in on their real page inside Spice is the closest thing
 * to a first-party login, and the session it produces serves both playback and liking.
 */
@Composable
private fun SoundCloudSignInWindow(state: AppState, close: () -> Unit) {
    var status by remember { mutableStateOf("Starting the embedded browser…") }
    var component by remember { mutableStateOf<java.awt.Component?>(null) }
    var currentUrl by remember { mutableStateOf("") }
    var finishing by remember { mutableStateOf(false) }
    val session = remember { state.dataDirectory()?.let { EmbeddedBrowserSession(it.resolve("chromium")) } }

    DisposableEffect(session) { onDispose { session?.dispose() } }

    LaunchedEffect(session) {
        if (session == null) {
            status = "Spice has nowhere to store the browser on this system."
            return@LaunchedEffect
        }
        runCatching {
            session.start(
                url = "https://soundcloud.com/signin",
                onProgress = { progress -> status = "Preparing Chromium — $progress" },
                onPageLoaded = { url -> currentUrl = url },
            )
        }.onSuccess { ui ->
            component = ui
            status = "Sign in to SoundCloud below. Spice picks the session up on its own."
        }.onFailure { error ->
            status = "Could not start the embedded browser: ${error.message?.take(180)}"
        }
    }

    // Cookies are read from a coroutine rather than inside a Chromium callback, which keeps the browser's own
    // threads free and avoids waiting on it from inside its own event.
    LaunchedEffect(component) {
        val live = session ?: return@LaunchedEffect
        if (component == null) return@LaunchedEffect
        while (true) {
            delay(2_500)
            val cookies = runCatching { live.harvestCookies("https://soundcloud.com") }.getOrDefault(emptyList())
            if (isSoundCloudSignedIn(cookies)) {
                finishing = true
                status = "Signed in — saving the session…"
                val destination = state.dataDirectory()?.resolve("soundcloud.cookies") ?: return@LaunchedEffect
                val saved = runCatching { writeCookieFile(cookies, destination) }.getOrNull()
                if (saved == null) {
                    status = "Signed in, but the session could not be written to disk."
                    finishing = false
                } else {
                    state.completeSoundCloudSignIn(
                        saved.toString(),
                        cookies.firstOrNull { it.name == "oauth_token" }?.value,
                    )
                    close()
                }
                return@LaunchedEffect
            }
        }
    }

    DialogWindow(
        onCloseRequest = close,
        state = rememberDialogState(width = 980.dp, height = 760.dp),
        title = "Sign in to SoundCloud",
    ) {
        Column(Modifier.fillMaxSize().background(SpicePanel)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (finishing || component == null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(status, fontSize = 12.sp)
                    if (currentUrl.isNotBlank()) {
                        Text(currentUrl, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(close) { Text("Cancel") }
            }
            HorizontalDivider()
            component?.let { ui ->
                SwingPanel(background = Color.Black, factory = { ui }, modifier = Modifier.fillMaxSize())
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp)) {
                    Text("Getting Chromium ready", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The first sign-in downloads the embedded browser, which is a large one-time download. " +
                            "Later sign-ins open straight away.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoogleAccountPanel(settings: SettingsState, state: AppState) {
    val google = settings.google
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var showClientFields by remember(google.configured) { mutableStateOf(!google.configured) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsPanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (google.signedIn) "Signed in with Google" else "Sign in with Google",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Spice opens Google's own sign-in page in your browser. Google refuses sign-in inside " +
                            "apps, so this is the only route it permits — your password never touches Spice.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(state::signInWithGoogle, enabled = google.configured && !google.busy) {
                    if (google.busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.OpenInBrowser, null)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        when {
                            google.busy -> "Waiting for Google…"
                            google.signedIn -> "Sign in again"
                            else -> "Sign in with Google"
                        },
                    )
                }
                if (google.signedIn) OutlinedButton(state::signOutGoogle) { Text("Sign out") }
            }
            google.message?.let { message ->
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .6f), shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) { Text(message, fontSize = 11.sp) }
                        IconButton(state::clearGoogleMessage, Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "Dismiss", Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        SettingsPanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (google.configured) Icons.Default.CheckCircle else Icons.Default.Warning,
                    null,
                    Modifier.size(18.dp),
                    tint = if (google.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    if (google.configured) "OAuth client saved" else "One-time setup: your own OAuth client",
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (google.configured) {
                    TextButton({ showClientFields = !showClientFields }) {
                        Text(if (showClientFields) "Hide" else "Replace")
                    }
                }
            }
            if (showClientFields) {
                Spacer(Modifier.height(9.dp))
                Text(
                    "Spice ships no Google client of its own, so you create one once — it is free and takes a couple " +
                        "of minutes:",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(7.dp))
                AccountFact("In Google Cloud Console, create a project and enable the YouTube Data API v3.")
                AccountFact("Create an OAuth client of type Desktop app, then copy its id and secret here.")
                AccountFact("Add yourself as a test user on the OAuth consent screen so it will let you in.")
                Spacer(Modifier.height(11.dp))
                OutlinedTextField(
                    clientId,
                    { clientId = it.trim() },
                    label = { Text("OAuth client id") },
                    placeholder = { Text("…apps.googleusercontent.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(
                    clientSecret,
                    { clientSecret = it.trim() },
                    label = { Text("OAuth client secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(11.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(
                        {
                            val id = clientId
                            val secret = clientSecret
                            clientId = ""
                            clientSecret = ""
                            state.saveGoogleClient(id, secret)
                        },
                        enabled = clientId.isNotBlank() && clientSecret.isNotBlank(),
                    ) { Icon(Icons.Default.Lock, null); Spacer(Modifier.width(7.dp)); Text("Save securely") }
                    TextButton({ state.openExternalUrl("https://console.cloud.google.com/apis/credentials") }) {
                        Text("Open Google Cloud Console")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Stored encrypted for your Windows account. SPICE_GOOGLE_CLIENT_ID and " +
                        "SPICE_GOOGLE_CLIENT_SECRET override these if you prefer environment variables.",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 11.sp,
                )
            }
        }

        SettingsPanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp)); Text("What signing in covers", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(9.dp))
            AccountFact("Your playlists and liked videos, read and written through the official YouTube Data API.")
            AccountFact("Liking a YouTube track in Spice rates the video on your account, exactly as the site would.")
            AccountFact(
                "Playback stays unauthenticated: yt-dlp cannot stream with an OAuth token, so public tracks play " +
                    "normally while age-restricted or private ones will not.",
            )
            AccountFact("No cookies are read or stored for YouTube any more.")
        }
    }
}

@Composable
private fun AccountConnectionPanel(
    provider: ProviderType,
    source: CookieSource,
    connection: AccountConnectionState,
    state: AppState,
    savedSoundCloudUsername: String = "",
) {
    var soundCloudUsername by remember(savedSoundCloudUsername) { mutableStateOf(savedSoundCloudUsername) }
    var useCookieFile by remember(source) { mutableStateOf(source.usesCookieFile) }
    var selectedBrowser by remember(source) { mutableStateOf(source.browser ?: BrowserSession.FIREFOX) }
    var profile by remember(source) { mutableStateOf(source.profile) }
    var container by remember(source) { mutableStateOf(source.container) }
    var cookieFile by remember(source) { mutableStateOf(source.cookieFile) }
    var showAdvanced by remember(source) { mutableStateOf(source.profile.isNotBlank() || source.container.isNotBlank()) }
    var browserMenuOpen by remember { mutableStateOf(false) }

    val checking = connection.status == AccountConnectionStatus.CHECKING
    val draft = if (useCookieFile) {
        CookieSource.ofFile(cookieFile)
    } else {
        CookieSource.ofBrowser(selectedBrowser, profile, container)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsPanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (provider == ProviderType.SOUNDCLOUD) Icons.Default.Cloud else Icons.Default.PlayCircle,
                    null,
                    Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (source.isConfigured) "Signed in through ${source.describe()}" else "Public mode",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Spice reuses an account you are already signed into. Only the location of the cookies is saved — never the cookies themselves.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            AccountStatusRow(connection)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ state.connectAccount(provider, draft) }, enabled = !checking && draft.isConfigured) {
                    if (checking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Link, null)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(if (checking) "Checking…" else if (source.isConfigured) "Save and check" else "Connect and check")
                }
                if (source.isConfigured) {
                    OutlinedButton({ state.verifyAccount(provider) }, enabled = !checking) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Check again")
                    }
                    OutlinedButton({ state.disconnectAccount(provider) }, enabled = !checking) { Text("Disconnect") }
                }
            }
        }

        if (provider == ProviderType.SOUNDCLOUD) {
            val likes by state.likes.collectAsState()
            var signInOpen by remember { mutableStateOf(false) }
            if (signInOpen) SoundCloudSignInWindow(state) { signInOpen = false }

            SettingsPanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cloud, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Text("Sign in to SoundCloud", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "Signs in on SoundCloud's own page inside Spice. SoundCloud issues no API credentials to new " +
                        "apps, so this is how a first-party login is done — one sign-in covers playback, your " +
                        "playlists and liking, with no browser cookie fiddling.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(13.dp))
                Button({ signInOpen = true }) {
                    Icon(Icons.Default.Login, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (source.usesCookieFile) "Sign in again" else "Sign in to SoundCloud")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "The first sign-in downloads embedded Chromium once. Your password goes to SoundCloud's page, never to Spice.",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 11.sp,
                )
            }

            SettingsPanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (likes.soundCloudReady) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null,
                        Modifier.size(20.dp),
                        tint = if (likes.soundCloudReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("Liking tracks on SoundCloud", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    if (likes.soundCloudReady) {
                        "Connected. Hearts in Spice are written to your SoundCloud likes."
                    } else {
                        "Reading needs only cookies, but liking writes to your account, so Spice needs the session " +
                            "token from the browser you connected. It is stored encrypted for your Windows account " +
                            "and nothing else from the cookie jar is kept."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(13.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(state::connectSoundCloudLiking) {
                        Icon(Icons.Default.Lock, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (likes.soundCloudReady) "Refresh token" else "Connect liking")
                    }
                    if (likes.soundCloudReady) {
                        OutlinedButton(state::disconnectSoundCloudLiking) { Text("Disconnect") }
                    }
                }
                likes.message?.let { message ->
                    Spacer(Modifier.height(11.dp))
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .6f), shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(Modifier.weight(1f)) { Text(message, fontSize = 11.sp) }
                            IconButton(state::clearLikeMessage, Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, "Dismiss", Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            SettingsPanelCard {
                Text("Your SoundCloud profile name", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "SoundCloud finds your own playlists by profile name, and cookies do not reveal it. Copy the last part of your profile link — soundcloud.com/<name>.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(13.dp))
                OutlinedTextField(
                    soundCloudUsername,
                    { soundCloudUsername = it.take(80) },
                    label = { Text("Profile name") },
                    placeholder = { Text("your-name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(11.dp))
                Button(
                    { state.setSoundCloudUsername(soundCloudUsername) },
                    enabled = soundCloudUsername.isNotBlank(),
                ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(7.dp)); Text("Save profile name") }
            }
        }

        SettingsPanelCard {
            Text("Where cookies come from", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FilterChip(!useCookieFile, { useCookieFile = false }, { Text("Browser session") })
                FilterChip(useCookieFile, { useCookieFile = true }, { Text("cookies.txt file") })
            }
            Spacer(Modifier.height(16.dp))
            if (useCookieFile) {
                OutlinedTextField(
                    cookieFile,
                    { cookieFile = it.trim() },
                    label = { Text("Path to cookies.txt") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton({ pickCookieFile()?.let { cookieFile = it } }) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Browse…")
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Export the file with a cookies.txt browser extension while you are signed in. This is the option that works when a Chrome-based browser refuses to hand over its cookies.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            } else {
                Box {
                    OutlinedButton({ browserMenuOpen = true }, Modifier.widthIn(min = 280.dp)) {
                        Icon(Icons.Default.Language, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(selectedBrowser.displayName + if (selectedBrowser == BrowserSession.FIREFOX) " · recommended" else "")
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(browserMenuOpen, { browserMenuOpen = false }) {
                        BrowserSession.entries.forEach { browser ->
                            DropdownMenuItem(
                                text = {
                                    Text(browser.displayName + if (browser == BrowserSession.FIREFOX) " · recommended" else "")
                                },
                                leadingIcon = { if (browser == selectedBrowser) Icon(Icons.Default.Check, null) },
                                onClick = { selectedBrowser = browser; browserMenuOpen = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (showAdvanced) {
                    OutlinedTextField(
                        profile,
                        { profile = it.take(120) },
                        label = { Text("Browser profile (optional)") },
                        placeholder = { Text(if (selectedBrowser.chromium) "Profile 2" else "default-release") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!selectedBrowser.chromium) {
                        Spacer(Modifier.height(9.dp))
                        OutlinedTextField(
                            container,
                            { container = it.take(120) },
                            label = { Text("Firefox container (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Leave the profile empty to use the browser's default. Name it when the signed-in account lives in a second profile.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                } else {
                    TextButton({ showAdvanced = true }) { Text("Choose a specific profile or container") }
                }
            }
        }

        SettingsPanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp)); Text("What to expect", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(9.dp))
            AccountFact("Firefox is the reliable choice — yt-dlp reads its cookie database directly.")
            AccountFact(
                "Chrome, Edge, Brave, Opera and Vivaldi lock their cookie database while running, and on Windows " +
                    "Chrome 127 and later encrypt it in a way yt-dlp cannot read. Close the browser and try, then " +
                    "fall back to a cookies.txt file.",
            )
            AccountFact(
                if (provider == ProviderType.SOUNDCLOUD) {
                    "SoundCloud has no cheap private page to load, so Spice confirms the cookies are read and accepted rather than naming your account."
                } else {
                    "Connecting loads your subscriptions feed, which only a signed-in account can see — that is how Spice knows the session is real."
                },
            )
            AccountFact("Spice never asks for your password, and disconnecting removes the session immediately.")
        }
    }
}

@Composable
private fun AccountStatusRow(connection: AccountConnectionState) {
    val (icon, tint) = when (connection.status) {
        AccountConnectionStatus.CONNECTED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        AccountConnectionStatus.CHECKING -> Icons.Default.Sync to MaterialTheme.colorScheme.onSurfaceVariant
        AccountConnectionStatus.WARNING -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
        AccountConnectionStatus.ERROR -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        AccountConnectionStatus.DISCONNECTED -> Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .55f), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(13.dp)) {
            Icon(icon, null, Modifier.size(19.dp), tint = tint)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    connection.detail ?: "Not connected — searches and playback use public access only.",
                    fontSize = 12.sp,
                )
                connection.hint?.let {
                    Spacer(Modifier.height(5.dp))
                    SelectionContainer { Text(it, color = MaterialTheme.colorScheme.tertiary, fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
private fun AccountFact(text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

private fun accountSummary(source: CookieSource, connection: AccountConnectionState): String = when {
    !source.isConfigured -> "Public mode — connect a browser session"
    connection.status == AccountConnectionStatus.CHECKING -> "Checking ${source.describe()}…"
    connection.status == AccountConnectionStatus.ERROR -> "${source.describe()} — needs attention"
    connection.status == AccountConnectionStatus.WARNING -> "${source.describe()} — not confirmed"
    else -> "Using ${source.describe()}"
}

private fun pickCookieFile(): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose your exported cookies.txt", java.awt.FileDialog.LOAD)
    dialog.file = "*.txt"
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return java.io.File(directory, name).absolutePath
}

@Composable
private fun ScrobblingSettingsPanel(settings: SettingsState, state: AppState) {
    var listenBrainzToken by remember { mutableStateOf("") }
    var lastFmApiKey by remember { mutableStateOf("") }
    var lastFmSharedSecret by remember { mutableStateOf("") }
    var useCustomLastFmApplication by remember { mutableStateOf(false) }
    val scrobbling = settings.scrobbling
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (scrobbling.lastEvent != null || scrobbling.scrobblesThisSession > 0) {
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp)); Column {
                            Text(
                                buildString {
                                    append("${scrobbling.scrobblesThisSession} scrobbled this session")
                                    if (scrobbling.pendingScrobbles > 0) append(" • ${scrobbling.pendingScrobbles} queued")
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                            scrobbling.lastEvent?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        item {
            SettingsPanelCard {
                ScrobbleServiceHeader("Last.fm", scrobbling.lastFm)
                Spacer(Modifier.height(8.dp))
                Text("Spice opens Last.fm in your browser for approval and finishes sign-in by itself once you allow it. Your Last.fm password is never entered into Spice.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(15.dp))
                when (scrobbling.lastFm.status) {
                    ScrobbleConnectionStatus.CONNECTED -> OutlinedButton(state::disconnectLastFm) { Icon(Icons.Default.LinkOff, null); Spacer(Modifier.width(7.dp)); Text("Disconnect") }
                    ScrobbleConnectionStatus.AWAITING_APPROVAL -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Waiting for your approval in the browser…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Button(state::finishLastFmLogin) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(7.dp)); Text("I approved Spice") }
                            OutlinedButton(state::beginLastFmLogin) { Text("Reopen browser") }
                        }
                    }
                    ScrobbleConnectionStatus.CONNECTING -> Button({}, enabled = false) { CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Connecting…") }
                    else -> {
                        Button(state::beginLastFmLogin, enabled = scrobbling.lastFmConfigured) {
                            Icon(Icons.Default.OpenInBrowser, null); Spacer(Modifier.width(7.dp)); Text("Connect Last.fm")
                        }
                        Spacer(Modifier.height(8.dp))
                        if (!useCustomLastFmApplication) {
                            TextButton({ useCustomLastFmApplication = true }) { Text("Use my own Last.fm application keys") }
                        } else {
                            OutlinedTextField(
                                lastFmApiKey,
                                { lastFmApiKey = it.trim().take(32) },
                                label = { Text("Last.fm API key") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(9.dp))
                            OutlinedTextField(
                                lastFmSharedSecret,
                                { lastFmSharedSecret = it.trim().take(32) },
                                label = { Text("Last.fm shared secret") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Button(
                                    onClick = {
                                        val apiKey = lastFmApiKey
                                        val secret = lastFmSharedSecret
                                        lastFmApiKey = ""
                                        lastFmSharedSecret = ""
                                        useCustomLastFmApplication = false
                                        state.configureLastFmApplication(apiKey, secret)
                                    },
                                    enabled = lastFmApiKey.length == 32 && lastFmSharedSecret.length == 32,
                                ) { Icon(Icons.Default.Lock, null); Spacer(Modifier.width(7.dp)); Text("Save securely") }
                                TextButton({ state.openExternalUrl("https://www.last.fm/api/account/create") }) { Text("Create credentials") }
                                TextButton({ lastFmApiKey = ""; lastFmSharedSecret = ""; useCustomLastFmApplication = false }) { Text("Cancel") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Optional — Spice already ships with application credentials. These only replace which application Last.fm sees; account approval still happens in your browser.", color = MaterialTheme.colorScheme.tertiary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            SettingsPanelCard {
                ScrobbleServiceHeader("ListenBrainz", scrobbling.listenBrainz)
                Spacer(Modifier.height(8.dp))
                Text("Paste the user token from your ListenBrainz settings. It is encrypted for your Windows account before being saved.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                if (scrobbling.listenBrainz.status == ScrobbleConnectionStatus.CONNECTED) {
                    OutlinedButton(state::disconnectListenBrainz) { Icon(Icons.Default.LinkOff, null); Spacer(Modifier.width(7.dp)); Text("Disconnect") }
                } else {
                    OutlinedTextField(
                        listenBrainzToken,
                        { listenBrainzToken = it.take(200) },
                        label = { Text("ListenBrainz user token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(11.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(
                            onClick = { val token = listenBrainzToken; listenBrainzToken = ""; state.connectListenBrainz(token) },
                            enabled = listenBrainzToken.isNotBlank() && scrobbling.listenBrainz.status != ScrobbleConnectionStatus.CONNECTING,
                        ) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(7.dp)); Text("Validate and connect") }
                        TextButton({ state.openExternalUrl("https://listenbrainz.org/settings/") }) { Text("Get token") }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp)); Text("Tracks count after half their duration or 4 minutes, whichever comes first. Tracks 30 seconds or shorter are ignored.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ScrobbleServiceHeader(name: String, service: ScrobbleServiceState) {
    val color = when (service.status) {
        ScrobbleConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        ScrobbleConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
        ScrobbleConnectionStatus.AWAITING_APPROVAL -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when (service.status) {
                ScrobbleConnectionStatus.CONNECTED -> Icons.Default.CheckCircle
                ScrobbleConnectionStatus.ERROR -> Icons.Default.ErrorOutline
                ScrobbleConnectionStatus.AWAITING_APPROVAL -> Icons.Default.OpenInBrowser
                else -> Icons.Default.Radio
            },
            null,
            tint = color,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                service.username ?: service.message ?: service.status.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase),
                color = color,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun LyricsSettingsPanel() {
    val providers = listOf(
        "LRCLIB" to true,
        "Better Lyrics" to true,
        "Karalyr" to true,
        "SyncLRC" to true,
        "lyrics.ovh" to true,
        "Musixmatch" to !System.getenv("SPICE_MUSIXMATCH_API_KEY").isNullOrBlank(),
        "Happi" to !System.getenv("SPICE_HAPPI_API_KEY").isNullOrBlank(),
        "Genius" to true,
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Core providers work without an account. Optional commercial sources show whether their API key is available.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp); Spacer(Modifier.height(8.dp)) }
        items(providers, key = { it.first }) { (name, ready) ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f), shape = RoundedCornerShape(11.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ready) Icons.Default.CheckCircle else Icons.Default.Key, null, tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(11.dp)); Text(name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text(if (ready) "Ready" else "API key needed", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun DiscordSettingsPanel(preferences: SpicePreferences, state: AppState) {
    SettingsPanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SportsEsports, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text("Show listening activity", fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("Share the current track when Discord integration is available.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            Switch(preferences.discordPresenceEnabled, state::setDiscordPresence)
        }
        Spacer(Modifier.height(15.dp))
        Text("The preference is saved now. The Discord IPC client still needs to be added before activity is published.", color = MaterialTheme.colorScheme.tertiary, fontSize = 11.sp)
    }
}

@Composable
private fun DiagnosticsPanel(settings: SettingsState, state: AppState) {
    Column {
        Button(state::runDiagnostics, enabled = !settings.diagnosticsRunning) {
            if (settings.diagnosticsRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.MonitorHeart, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text(if (settings.diagnosticsRunning) "Checking…" else "Run diagnostics")
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(settings.diagnostics, key = { it.name }) { result ->
                val color = when (result.level) { DiagnosticLevel.PASS -> MaterialTheme.colorScheme.primary; DiagnosticLevel.WARNING -> MaterialTheme.colorScheme.tertiary; DiagnosticLevel.FAIL -> MaterialTheme.colorScheme.error }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f), shape = RoundedCornerShape(11.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (result.level == DiagnosticLevel.PASS) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, tint = color)
                        Spacer(Modifier.width(11.dp)); Column { Text(result.name, fontWeight = FontWeight.SemiBold); Text(result.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanelCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f), shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 720.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), content = content)
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: () -> Unit,
    active: Boolean = false,
) {
    Surface(onClick = action, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            if (active) { Icon(Icons.Default.CheckCircle, "Active", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(9.dp)) }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun EmptyScreen(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingSection() {
    Column(Modifier.padding(vertical = 18.dp)) {
        Box(Modifier.width(190.dp).height(24.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            repeat(5) { Box(Modifier.size(140.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f))) }
        }
    }
}
