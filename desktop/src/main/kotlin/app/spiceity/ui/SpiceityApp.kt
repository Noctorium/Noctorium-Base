package app.spiceity.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spiceity.core.*
import app.spiceity.desktopAppState
import app.spiceity.discord.DiscordPreview
import app.spiceity.discord.DiscordPresenceSettings
import app.spiceity.discord.PausedBehaviour
import app.spiceity.discord.PresenceActivityKind
import app.spiceity.discord.PresenceArtwork
import app.spiceity.discord.PresenceButton
import app.spiceity.discord.PresenceTimestamps
import app.spiceity.downloads.DownloadStage
import app.spiceity.domain.*
import app.spiceity.lyrics.LyricLine
import app.spiceity.lyrics.LyricsProviderOutcome
import app.spiceity.lyrics.LyricsProviderStatus
import app.spiceity.playback.QueueState
import app.spiceity.playback.PlaybackState
import app.spiceity.playback.PlaybackStatus
import app.spiceity.playback.RepeatMode
import app.spiceity.auth.EmbeddedBrowserSession
import app.spiceity.auth.SOUNDCLOUD_SESSION_URLS
import app.spiceity.auth.SOUNDCLOUD_SIGN_IN
import app.spiceity.auth.YOUTUBE_SESSION_URLS
import app.spiceity.auth.SOUNDCLOUD_OWN_LIKES
import app.spiceity.auth.YOUTUBE_MUSIC_HOME
import app.spiceity.auth.YOUTUBE_SIGN_IN
import app.spiceity.auth.isYouTubeSignedIn
import app.spiceity.auth.isSoundCloudSignedIn
import app.spiceity.auth.permalinkFromBrowserUrl
import app.spiceity.auth.adoptCookieFile
import app.spiceity.auth.writeCookieFile
import app.spiceity.playlists.LocalPlaylist
import app.spiceity.playlists.PlaylistShareLink
import app.spiceity.settings.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SpiceityApp(appState: AppState = remember { desktopAppState() }, window: java.awt.Window? = null) {
    val ui by appState.ui.collectAsState()
    val queue by appState.queue.state.collectAsState()
    val playback by appState.playback.collectAsState()
    val preferences = appState.settings.collectAsState().value.preferences

    DisposableEffect(appState) {
        onDispose { appState.close() }
    }

    // "Match the artwork" reuses the palette already sampled for the now playing backdrop, so the whole
    // interface drifts with whatever is on.
    val artworkPalette = rememberArtworkPalette(queue.current?.artworkUrl, queue.current?.provider ?: ProviderType.LOCAL)
    val accentTarget = preferences.accent.argb?.let { Color(it) } ?: artworkPalette.primary
    val accent by animateColorAsState(accentTarget, tween(600), label = "accent")
    val background = when (preferences.backgroundDepth) {
        BackgroundDepth.AMOLED -> AmoledBlack
        BackgroundDepth.DARK -> SpiceityDarkBackground
    }

    // The title bar is Windows', not ours, so it has to be told the colour separately — and told again
    // whenever the chosen depth changes, or a switch to pure black would leave a grey strip above it.
    LaunchedEffect(window, background) {
        WindowChrome.applyDarkTitleBar(
            window,
            backgroundArgb = background.toArgb(),
            foregroundArgb = Color.White.copy(alpha = .88f).toArgb(),
        )
    }

    MaterialTheme(colorScheme = spiceityColorScheme(accent, background)) {
        Surface(Modifier.fillMaxSize()) {
            Row {
                NavigationRail(ui.destination, appState::navigate)
                Column(Modifier.weight(1f)) {
                    val playerAtTop = preferences.playerBarPosition == PlayerBarPosition.TOP
                    if (playerAtTop) PlayerBar(queue, playback, appState)
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
                    if (!playerAtTop) PlayerBar(queue, playback, appState)
                }
            }
        }
    }
}

@Composable
private fun NavigationRail(selected: Destination, navigate: (Destination) -> Unit) {
    Column(
        Modifier.width(176.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(SpiceityLavender, SpiceityPurpleStrong))),
                contentAlignment = Alignment.Center,
            ) { Text("S", color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp) }
            Spacer(Modifier.width(10.dp))
            Text("Spiceity", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(26.dp))
        NavItem("Home", Icons.Default.Home, selected == Destination.HOME) { navigate(Destination.HOME) }
        NavItem("Search", Icons.Default.Search, selected == Destination.SEARCH) { navigate(Destination.SEARCH) }
        NavItem("Library", Icons.Default.LibraryMusic, selected == Destination.LIBRARY) { navigate(Destination.LIBRARY) }
        Spacer(Modifier.height(18.dp))
        Text(
            "PLAYING",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        // Now Playing and Queue were reachable only by clicking the player bar; they are destinations, so they
        // belong in the rail alongside everything else.
        NavItem("Now playing", Icons.Default.GraphicEq, selected == Destination.NOW_PLAYING) { navigate(Destination.NOW_PLAYING) }
        NavItem("Queue", Icons.AutoMirrored.Filled.QueueMusic, selected == Destination.QUEUE) { navigate(Destination.QUEUE) }
        Spacer(Modifier.weight(1f))
        NavItem("Settings", Icons.Default.Settings, selected == Destination.SETTINGS) { navigate(Destination.SETTINGS) }
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
    val recent = ui.recentTracks.filter { track ->
        ui.providerFilter == ProviderFilter.ALL || track.provider.name == ui.providerFilter.name
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentPadding = PaddingValues(bottom = 36.dp)) {
        item {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(greeting(), fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilterChips(ui.providerFilter, state::setFilter)
            }
            Spacer(Modifier.height(20.dp))
        }
        ui.errorMessage?.let { message ->
            item { PlaybackError(message) }
        }
        if (recent.isNotEmpty()) {
            item {
                TrackRowSection("Jump back in", "Where you left off", recent, state)
            }
        }
        if (ui.homeLoading) {
            items(2) { LoadingSection() }
        } else {
            items(filtered, key = { it.id }) { section ->
                TrackRowSection(section.title, section.subtitle, section.tracks, state)
            }
        }
    }
}

/** Greeting keyed to the actual clock rather than a fixed string. */
private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..22 -> "Good evening"
    else -> "Still up"
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
private fun TrackRowSection(title: String, subtitle: String?, tracks: List<Track>, state: AppState) {
    // A section already names its service, so a per-card badge would be the same word a third time. The badge
    // only earns its place when a row actually mixes services.
    val preferences = state.settings.collectAsState().value.preferences
    val mixedProviders = when (preferences.badgePolicy) {
        BadgePolicy.ALWAYS -> true
        BadgePolicy.NEVER -> false
        BadgePolicy.AUTO -> tracks.map { it.provider }.distinct().size > 1
    }
    Column(Modifier.padding(vertical = 14.dp)) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(tracks, key = { it.queueKey }) { track ->
                TrackCard(track, tracks, state, showBadge = mixedProviders)
            }
        }
    }
}

@Composable
private fun TrackCard(track: Track, sourceQueue: List<Track>, state: AppState, showBadge: Boolean = false) {
    val preferences = state.settings.collectAsState().value.preferences
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    // Whether controls hide until pointed at is a preference; some people would rather always see them.
    val hovered = isHovered || preferences.hoverControls == HoverControls.ALWAYS
    val cardWidth = preferences.cardSize.widthDp.dp
    Surface(
        onClick = { state.play(track, PlaybackOrigin.HOME, sourceQueue) },
        color = Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        interactionSource = interaction,
        modifier = Modifier.width(cardWidth).hoverable(interaction),
    ) {
        Column {
            Box(Modifier.size(cardWidth).clip(RoundedCornerShape(14.dp))) {
                RemoteArtwork(track.artworkUrl, track.provider, Modifier.fillMaxSize())
                if (showBadge) {
                    Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { ProviderBadge(track.provider, compact = true) }
                }
                if (hovered) {
                    if (isHovered) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)))
                    Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) { TrackMenu(track, state) }
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(10.dp).size(38.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onPrimary) }
                }
            }
            Spacer(Modifier.height(9.dp))
            // Two lines, because one-line titles were cutting off mid-word.
            Text(
                track.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
            // yt-dlp leaves the uploader blank in flat listings, which used to render as the service name under
            // every single card. Better to show nothing than to repeat the row heading.
            track.displayArtist()?.let { artist ->
                Text(
                    artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** The artist worth showing, or null when all we have is a stand-in for the service. */
private fun Track.displayArtist(): String? = artistLine
    .takeIf { line ->
        line.isNotBlank() &&
            !line.equals(provider.displayName, ignoreCase = true) &&
            !line.equals("YouTube", ignoreCase = true) &&
            !line.equals("YouTube Music", ignoreCase = true) &&
            !line.equals("SoundCloud", ignoreCase = true)
    }

@Composable
private fun LibraryScreen(state: AppState) {
    val library by state.library.collectAsState()

    LaunchedEffect(Unit) { state.refreshLibrary() }

    var newPlaylistOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }

    if (newPlaylistOpen) {
        val likes by state.likes.collectAsState()
        NewPlaylistDialog(
            title = "New playlist",
            confirmLabel = "Create",
            soundCloudReady = likes.soundCloudReady,
            youTubeReady = likes.youTubeReady,
        ) { name, destination ->
            newPlaylistOpen = false
            if (name != null) {
                when (destination) {
                    PlaylistDestination.SPICE -> state.createPlaylist(name)
                    PlaylistDestination.SOUNDCLOUD -> state.createSoundCloudPlaylist(name)
                    PlaylistDestination.YOUTUBE -> state.createYouTubePlaylist(name)
                }
            }
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
        val downloads by state.downloadState.collectAsState()
        downloads.message?.let { LibraryNotice(it, state::clearDownloadMessage) }
        if (downloads.entries.isNotEmpty() || downloads.active.isNotEmpty()) {
            OfflineDownloadsCard(downloads, state)
            Spacer(Modifier.height(14.dp))
        }
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
                    .background(Brush.linearGradient(listOf(SpiceityLavender, SpiceityPurpleStrong))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(24.dp), tint = Color.White) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"} • made in Spiceity",
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
            text = { Text("The playlist is removed from Spiceity. The tracks themselves are untouched.") },
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
                    "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"} • made in Spiceity",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            if (playlist.tracks.isNotEmpty()) {
                Button({ state.playLocalPlaylist(playlist) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(7.dp)); Text("Play all")
                }
                Spacer(Modifier.width(9.dp))
                val downloads by state.downloadState.collectAsState()
                val missing = playlist.tracks.count { !downloads.isDownloaded(it) }
                OutlinedButton({ state.downloadAll(playlist.tracks) }, enabled = missing > 0) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    // Naming how many are left makes clear that a second press is not needed, and that a
                    // playlist already kept is finished rather than the button being broken.
                    Text(if (missing == 0) "All downloaded" else "Download $missing")
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
            if (playlist.tracks.any { it.provider == ProviderType.SOUNDCLOUD }) {
                OutlinedButton({ state.publishPlaylistToSoundCloud(playlist) }) {
                    Icon(Icons.Default.CloudUpload, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Save to SoundCloud")
                }
            }
            if (playlist.tracks.any { it.provider != ProviderType.SOUNDCLOUD }) {
                OutlinedButton({ state.publishPlaylistToYouTube(playlist) }) {
                    Icon(Icons.Default.CloudUpload, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Save to YouTube Music")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        notice?.let { LibraryNotice(it, state::clearLibraryNotice) }
        Spacer(Modifier.height(8.dp))
        Text(
            "A share link carries the whole playlist inside it, so anyone with Spiceity can paste it and get the same tracks.",
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

/** Where a new playlist should live. Spiceity always works; the services need a signed-in account. */
private enum class PlaylistDestination(val label: String) {
    SPICE("In Spiceity"),
    SOUNDCLOUD("On SoundCloud"),
    YOUTUBE("On YouTube Music"),
}

/**
 * Names a new playlist and chooses where it is created.
 *
 * A playlist made here can live only in Spiceity or go straight onto a connected account, so the choice sits
 * beside the name rather than being decided for the listener.
 */
@Composable
private fun NewPlaylistDialog(
    title: String,
    confirmLabel: String,
    soundCloudReady: Boolean,
    youTubeReady: Boolean,
    finish: (String?, PlaylistDestination) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf(PlaylistDestination.SPICE) }

    AlertDialog(
        onDismissRequest = { finish(null, destination) },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    name,
                    { name = it.take(120) },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Create it", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    PlaylistDestination.entries.forEach { option ->
                        val available = when (option) {
                            PlaylistDestination.SPICE -> true
                            PlaylistDestination.SOUNDCLOUD -> soundCloudReady
                            PlaylistDestination.YOUTUBE -> youTubeReady
                        }
                        val selected = destination == option
                        Surface(
                            onClick = { destination = option },
                            enabled = available,
                            shape = RoundedCornerShape(11.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f)
                            else Color.White.copy(alpha = .04f),
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .5f)
                                else Color.White.copy(alpha = .08f),
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected, { destination = option }, enabled = available)
                                Spacer(Modifier.width(4.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        option.label,
                                        fontSize = 13.sp,
                                        color = if (available) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f),
                                    )
                                    val note = when {
                                        option == PlaylistDestination.SPICE -> "Stays on this computer, any service."
                                        available && option == PlaylistDestination.SOUNDCLOUD ->
                                            "Made on your account, private until you change it."
                                        available -> "Made on your YouTube account."
                                        else -> "Sign in under Settings to use this."
                                    }
                                    Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button({ finish(name, destination) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { OutlinedButton({ finish(null, destination) }) { Text("Cancel") } },
    )
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
                    "Paste a spiceity://playlist link someone sent you. The tracks travel inside the link, so nothing needs to be online.",
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
            VisibilityBadge(playlist.isPublic, compact = true)
            Spacer(Modifier.width(7.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(
                            playlist.provider.displayName,
                            playlist.ownerName,
                            playlist.tracks.size.takeIf { it > 0 }?.let { count ->
                                "$count ${if (count == 1) "track" else "tracks"}"
                            },
                        ).joinToString(" • "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Artwork arrives in the background; a bare spinner says so without a running commentary.
                    if (library.openPlaylistEnriching) {
                        Spacer(Modifier.width(9.dp))
                        CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                    }
                }
            }
            if (playlist.tracks.isNotEmpty()) {
                Button({ state.playPlaylist(playlist) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(7.dp)); Text("Play all")
                }
                Spacer(Modifier.width(9.dp))
                val downloads by state.downloadState.collectAsState()
                val missing = playlist.tracks.count { !downloads.isDownloaded(it) }
                OutlinedButton({ state.downloadAll(playlist.tracks) }, enabled = missing > 0) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    // Naming how many are left makes clear that a second press is not needed, and that a
                    // playlist already kept is finished rather than the button being broken.
                    Text(if (missing == 0) "All downloaded" else "Download $missing")
                }
            }
        }
        // Account actions sit on their own line: in the title row they crowded out the name entirely.
        if (playlist.editableOnService()) {
            Spacer(Modifier.height(12.dp))
            ServicePlaylistActions(playlist, state)
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

/** Says who can see a playlist. Absent when the service has not told us, rather than guessing "public". */
@Composable
private fun VisibilityBadge(isPublic: Boolean?, compact: Boolean = false) {
    if (isPublic == null) return
    val label = if (isPublic) "Public" else "Private"
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isPublic) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)
        else Color.White.copy(alpha = .08f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .1f)),
    ) {
        Row(
            Modifier.padding(horizontal = if (compact) 8.dp else 11.dp, vertical = if (compact) 3.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isPublic) Icons.Default.Public else Icons.Default.Lock,
                null,
                Modifier.size(if (compact) 11.dp else 14.dp),
                tint = if (isPublic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                fontSize = if (compact) 10.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (isPublic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A playlist Spiceity can change on the service it came from.
 *
 * SoundCloud numbers its playlists, while YouTube ids start with PL or VL; the likes listing on either service
 * is a view rather than a playlist, so it is excluded.
 */
fun Playlist.editableOnService(): Boolean = when (provider) {
    ProviderType.SOUNDCLOUD -> id.all(Char::isDigit)
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> id.startsWith("PL") || id.startsWith("VL")
    ProviderType.SPOTIFY, ProviderType.LOCAL -> false
}

/** Rename, delete and privacy for a playlist that lives on a service account rather than only in Spiceity. */
@Composable
private fun ServicePlaylistActions(playlist: Playlist, state: AppState) {
    val onSoundCloud = playlist.provider == ProviderType.SOUNDCLOUD
    SoundCloudPlaylistActions(playlist, state, onSoundCloud)
}

@Composable
private fun SoundCloudPlaylistActions(playlist: Playlist, state: AppState, onSoundCloud: Boolean = true) {
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (renaming) {
        PlaylistNameDialog("Rename on ${playlist.provider.displayName}", playlist.title, "Rename") { title ->
            renaming = false
            title?.let {
                if (onSoundCloud) state.renameSoundCloudPlaylist(playlist.id, it)
                else state.renameYouTubePlaylist(playlist.id, it)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${playlist.title}\" from ${playlist.provider.displayName}?") },
            text = {
                Text(
                    "This removes the playlist from your ${playlist.provider.displayName} account, not just from " +
                        "Spiceity. The tracks themselves are untouched.",
                )
            },
            confirmButton = {
                Button({
                    confirmDelete = false
                    if (onSoundCloud) state.deleteSoundCloudPlaylist(playlist.id) else state.deleteYouTubePlaylist(playlist.id)
                }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton({ confirmDelete = false }) { Text("Keep") } },
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        VisibilityBadge(playlist.isPublic)
        // Only offered when SoundCloud has actually told us the current state, so the button cannot claim to
        // flip something whose value is unknown.
        playlist.isPublic?.let { isPublic ->
            OutlinedButton({
                if (onSoundCloud) state.setSoundCloudPlaylistVisibility(playlist.id, !isPublic)
                else state.setYouTubePlaylistVisibility(playlist.id, !isPublic)
            }) {
                Icon(
                    if (isPublic) Icons.Default.Lock else Icons.Default.Public,
                    null,
                    Modifier.size(16.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(if (isPublic) "Make private" else "Make public")
            }
        }
        OutlinedButton({ renaming = true }) {
            Icon(Icons.Default.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text("Rename")
        }
        OutlinedButton({ confirmDelete = true }) { Text("Delete") }
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
            ui.errorMessage != null -> PlaybackError(ui.errorMessage.orEmpty())
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
            DownloadButton(track, state, size = 34.dp)
            TrackMenu(track, state)
        }
    }
}

/**
 * Writes the like to the provider account, not just to Spiceity, so the heart reflects what the service holds.
 *
 * Which services can be written to is [LikeState.supports]'s decision, not this button's: naming SoundCloud
 * here is what left the heart permanently dead on YouTube Music long after YouTube liking worked.
 */
@Composable
private fun LikeButton(track: Track, state: AppState, size: Dp = 36.dp) {
    val likes by state.likes.collectAsState()
    val liked = likes.isLiked(track)
    val busy = likes.isBusy(track)
    val supported = likes.supports(track)
    val service = track.provider.displayName
    IconButton({ state.toggleLike(track) }, Modifier.size(size), enabled = supported && !busy) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            liked -> Icon(
                Icons.Default.Favorite,
                "Remove from your $service likes",
                tint = MaterialTheme.colorScheme.primary,
            )
            else -> Icon(
                Icons.Default.FavoriteBorder,
                if (supported) "Like on $service" else "Sign in to $service to like tracks",
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
            if (likes.supports(track)) {
                val service = track.provider.displayName
                DropdownMenuItem(
                    text = { Text(if (likedNow) "Remove from $service likes" else "Like on $service") },
                    leadingIcon = {
                        Icon(if (likedNow) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null)
                    },
                    onClick = { state.toggleLike(track); expanded = false },
                )
            }
            val downloads by state.downloadState.collectAsState()
            // A Spotify track has no audio of its own, so what is on the disk is filed under the recording
            // matched to it. Asking about the Spotify entry would report it as never downloaded.
            val onDisk = state.downloadableTrack(track)
            val job = downloads.jobFor(onDisk)
            when {
                // In progress, and tapping it stops it. A download nobody can call off is worse than none.
                job != null && job.stage != DownloadStage.FAILED -> DropdownMenuItem(
                    text = { Text("Downloading… ${(job.progress * 100).roundToInt()}%") },
                    leadingIcon = { Icon(Icons.Default.Downloading, null) },
                    trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) },
                    onClick = { state.cancelDownload(onDisk.queueKey); expanded = false },
                )
                job != null -> DropdownMenuItem(
                    text = { Text("Download failed — try again") },
                    leadingIcon = { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { state.cancelDownload(onDisk.queueKey); state.downloadTrack(track); expanded = false },
                )
                downloads.isDownloaded(onDisk) -> DropdownMenuItem(
                    text = { Text("Remove download") },
                    leadingIcon = { Icon(Icons.Default.DownloadDone, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { state.deleteDownload(onDisk.queueKey); expanded = false },
                )
                else -> DropdownMenuItem(
                    text = { Text("Download for offline") },
                    leadingIcon = { Icon(Icons.Default.Download, null) },
                    onClick = { state.downloadTrack(track); expanded = false },
                )
            }
            DropdownMenuItem(
                text = { Text(if (state.canSaveAsMp3()) "Save as MP3…" else "Save a copy…") },
                leadingIcon = { Icon(Icons.Default.SaveAlt, null) },
                // Named for where it lands, because the difference between this and the item above it is
                // exactly that one is Spiceity's copy and this one is the listener's.
                trailingIcon = {
                    Text(
                        state.exportFolder()?.fileName?.toString() ?: "no folder",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                },
                onClick = { state.exportTrack(track); expanded = false },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Copy link") },
                leadingIcon = { Icon(Icons.Default.Link, null) },
                onClick = { state.copyTrackLink(track); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
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
        val likes by state.likes.collectAsState()
        NewPlaylistDialog(
            title = "New playlist for ${track.title}",
            confirmLabel = "Create and add",
            // A SoundCloud playlist can only hold SoundCloud tracks, so the option is offered only when the
            // track being added could actually go in one.
            soundCloudReady = likes.soundCloudReady && track.provider == ProviderType.SOUNDCLOUD,
            youTubeReady = likes.youTubeReady && track.provider != ProviderType.SOUNDCLOUD,
        ) { name, destination ->
            dismiss()
            if (name != null) {
                when (destination) {
                    PlaylistDestination.SOUNDCLOUD -> state.createSoundCloudPlaylist(name, listOf(track))
                    PlaylistDestination.YOUTUBE -> state.createYouTubePlaylist(name, listOf(track))
                    else -> state.createPlaylist(name, firstTrack = track)
                }
            }
        }
        return
    }
    val library by state.library.collectAsState()
    // Playlists on the account itself, which Spiceity can write to. The likes page is a listing, not a playlist,
    // so it is excluded by requiring a numeric id.
    val servicePlaylists = library.playlists.filter { it.editableOnService() && it.provider.acceptsTrack(track) }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (servicePlaylists.isNotEmpty()) {
                    item {
                        Text(
                            "ON ${track.provider.displayName.uppercase()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(servicePlaylists, key = { "svc:${it.id}" }) { playlist ->
                        Surface(
                            onClick = {
                                dismiss()
                                if (playlist.provider == ProviderType.SOUNDCLOUD) {
                                    state.addTrackToSoundCloudPlaylist(playlist.id, track)
                                } else {
                                    state.addTrackToYouTubePlaylist(playlist.id, track)
                                }
                            },
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Cloud, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        playlist.trackCount?.let { "$it tracks • on your account" } ?: "on your account",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "IN SPICE",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
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
        ProviderType.SPOTIFY -> Color(0xFF1DB954)
        ProviderType.LOCAL -> Color(0xFF4F46E5)
    }
    val label = when (provider) {
        ProviderType.YOUTUBE_MUSIC -> if (compact) "YTM" else "YT MUSIC"
        ProviderType.YOUTUBE_VIDEO -> if (compact) "YT" else "YOUTUBE"
        ProviderType.SOUNDCLOUD -> if (compact) "SC" else "SOUNDCLOUD"
        ProviderType.SPOTIFY -> if (compact) "SP" else "SPOTIFY"
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

/** Volume, boost and mute in one popover, so the bar carries a single icon instead of four controls. */
@Composable
private fun VolumeControl(playback: PlaybackState, state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, Modifier.size(36.dp)) {
            Icon(
                if (playback.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                "Volume",
                tint = when {
                    playback.isMuted -> MaterialTheme.colorScheme.primary
                    playback.volume > 1f -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DropdownMenu(open, { open = false }) {
            Column(Modifier.width(248.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        "${(playback.volume * 100).roundToInt()}%",
                        color = if (playback.volume > 1f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Slider(
                    value = volumeToSliderPosition(playback.volume, playback.volumeBoostEnabled),
                    onValueChange = { state.setVolume(sliderPositionToVolume(it, playback.volumeBoostEnabled)) },
                    valueRange = 0f..1f,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = playback.isMuted,
                        onClick = state::toggleMute,
                        label = { Text(if (playback.isMuted) "Muted" else "Mute", fontSize = 11.sp) },
                        modifier = Modifier.height(30.dp),
                    )
                    FilterChip(
                        selected = playback.volumeBoostEnabled,
                        onClick = state::toggleVolumeBoost,
                        label = { Text("Boost ×10", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Bolt, null, Modifier.size(14.dp)) },
                        modifier = Modifier.height(30.dp),
                    )
                }
            }
        }
    }
}

/**
 * One row, left to right: transport, the track, the seek bar, then the tools. Denser than the stacked layout
 * and it gives the rest of the window back about thirty pixels.
 */
@Composable
private fun InlinePlayerBar(queue: QueueState, playback: PlaybackState, state: AppState) {
    val preferences = state.settings.collectAsState().value.preferences
    val current = queue.current
    var addToPlaylist by remember { mutableStateOf(false) }
    val library by state.library.collectAsState()

    if (addToPlaylist && current != null) {
        AddToPlaylistDialog(current, library.localPlaylists, state) { addToPlaylist = false }
    }

    // The rule separates the bar from what it sits against, so it belongs on whichever edge faces the
    // content: below the bar when the bar is at the top of the window, above it when it is at the foot.
    val atTop = preferences.playerBarPosition == PlayerBarPosition.TOP
    val rule = MaterialTheme.colorScheme.primary.copy(alpha = .22f)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().height(74.dp)) {
        Column {
            if (!atTop) HorizontalDivider(color = rule)
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(state::toggleShuffle, Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Shuffle,
                        "Shuffle",
                        Modifier.size(18.dp),
                        tint = if (queue.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(state::previous, Modifier.size(34.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous track", Modifier.size(20.dp))
                }
                // The one filled control in the bar, so the eye lands on it first.
                FilledIconButton(
                    state::togglePlayback,
                    Modifier.size(42.dp),
                    enabled = playback.status != PlaybackStatus.RESOLVING && current != null,
                ) {
                    if (playback.status == PlaybackStatus.RESOLVING) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (playback.isPlaying) "Pause" else "Play",
                            Modifier.size(24.dp),
                        )
                    }
                }
                IconButton(state::next, Modifier.size(34.dp)) {
                    Icon(Icons.Default.SkipNext, "Next track", Modifier.size(20.dp))
                }
                IconButton(state::cycleRepeat, Modifier.size(34.dp)) {
                    Icon(
                        if (queue.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat",
                        Modifier.size(18.dp),
                        tint = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                current?.let { track ->
                    LikeButton(track, state, size = 34.dp)
                    DownloadButton(track, state, size = 34.dp)
                    IconButton({ addToPlaylist = true }, Modifier.size(34.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            "Add to playlist",
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))
                Row(
                    Modifier.width(230.dp).clickable(enabled = current != null) { state.navigate(Destination.NOW_PLAYING) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (current != null) {
                        RemoteArtwork(current.artworkUrl, current.provider, Modifier.size(40.dp).clip(RoundedCornerShape(7.dp)))
                    } else {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp)) }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            current?.title ?: "Nothing playing",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        )
                        Text(
                            playback.errorMessage ?: current?.artistLine ?: "Choose a track to start",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (playback.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))
                PlaybackProgressBar(
                    playback = playback,
                    onSeek = state::seekTo,
                    modifier = Modifier.weight(1f),
                    style = preferences.progressBarStyle,
                    timeDisplay = preferences.timeDisplay,
                )
                Spacer(Modifier.width(12.dp))

                BadgedBox(badge = { if (queue.tracks.isNotEmpty()) Badge { Text(queue.tracks.size.toString()) } }) {
                    ConnectButton(state)
                    IconButton({ state.navigate(Destination.QUEUE) }, Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", Modifier.size(19.dp))
                    }
                }
                IconButton({ state.navigate(Destination.NOW_PLAYING) }, Modifier.size(34.dp)) {
                    Icon(Icons.Default.Lyrics, "Lyrics", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                VolumeControl(playback, state)
            }
            if (atTop) HorizontalDivider(color = rule)
        }
    }
}

@Composable
private fun PlayerBar(queue: QueueState, playback: PlaybackState, state: AppState) {
    if (state.settings.collectAsState().value.preferences.playerBarStyle == PlayerBarStyle.INLINE) {
        InlinePlayerBar(queue, playback, state)
        return
    }
    val current = queue.current
    val playerPreferences = state.settings.collectAsState().value.preferences
    val progressStyle = playerPreferences.progressBarStyle
    val timeDisplay = playerPreferences.timeDisplay
    val stackedAtTop = playerPreferences.playerBarPosition == PlayerBarPosition.TOP
    val stackedRule = MaterialTheme.colorScheme.primary.copy(alpha = .35f)
    Surface(
        shadowElevation = 0.dp,
        color = SpiceityPanel,
        modifier = Modifier.fillMaxWidth().height(100.dp).clickable(
            enabled = current != null,
            onClickLabel = "Open now playing",
            onClick = { state.navigate(Destination.NOW_PLAYING) },
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 760.dp
            Column {
                // As above: the rule marks the edge the content is on.
                if (!stackedAtTop) HorizontalDivider(color = stackedRule)
                PlaybackProgressBar(
                    playback = playback,
                    onSeek = state::seekTo,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 18.dp),
                    style = progressStyle,
                    timeDisplay = timeDisplay,
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
                        current?.let { DownloadButton(it, state) }
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
                        // Volume, its readout and the boost toggle used to sit permanently in the bar. They now
                        // live behind the speaker icon, which is the only one of the four you reach for often.
                        if (!compact) {
                            VolumeControl(playback, state)
                            Spacer(Modifier.width(4.dp))
                        }
                        ConnectButton(state)
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
                if (stackedAtTop) HorizontalDivider(color = stackedRule)
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

    // The backdrop takes its colour from the cover, so the room changes with the record.
    val ambientEnabled = state.settings.collectAsState().value.preferences.ambientBackdrop
    val palette = rememberArtworkPalette(current.artworkUrl, current.provider)
    val ambient by animateColorAsState(
        if (ambientEnabled) palette.primary else MaterialTheme.colorScheme.background,
        tween(700),
        label = "ambient",
    )
    val ambientDeep by animateColorAsState(
        if (ambientEnabled) palette.secondary else MaterialTheme.colorScheme.background,
        tween(700),
        label = "ambientDeep",
    )

    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(
                    ambient.copy(alpha = .42f),
                    ambientDeep.copy(alpha = .30f),
                    AmoledBlack,
                ),
            ),
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    NowPlayingHero(current, playback, state, Modifier.weight(1f).fillMaxHeight())
                    GlassPanel(Modifier.width(430.dp).fillMaxHeight()) {
                        NowPlayingPanel(
                            queue = queue,
                            selectedTab = selectedTab,
                            selectTab = { selectedTab = it },
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    NowPlayingHero(current, playback, state, Modifier.fillMaxWidth().height(360.dp))
                    GlassPanel(Modifier.fillMaxWidth().weight(1f)) {
                        NowPlayingPanel(
                            queue = queue,
                            selectedTab = selectedTab,
                            selectTab = { selectedTab = it },
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/** Translucent rounded container that lets the ambient backdrop show through. */
@Composable
private fun GlassPanel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = .055f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .08f)),
        content = { Box(Modifier.fillMaxSize(), content = content) },
    )
}

@Composable
private fun NowPlayingHero(
    track: Track,
    playback: PlaybackState,
    state: AppState,
    modifier: Modifier = Modifier,
) {
    val playerPreferences = state.settings.collectAsState().value.preferences
    val progressStyle = playerPreferences.progressBarStyle
    val timeDisplay = playerPreferences.timeDisplay
    BoxWithConstraints(modifier) {
        val artworkSize = minOf(430.dp, maxWidth * .82f, maxHeight * .58f)
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
            // Title and artist lead, above the cover, the way a record sleeve is captioned.
            Text(
                track.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 31.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                track.artistLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = .62f),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TransportControls(playback, state)
                Spacer(Modifier.width(20.dp))
                PlaybackProgressBar(playback, state::seekTo, Modifier.weight(1f), progressStyle, timeDisplay)
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Surface(shape = RoundedCornerShape(18.dp), shadowElevation = 22.dp, color = Color.Transparent) {
                    RemoteArtwork(
                        track.artworkUrl,
                        track.provider,
                        Modifier.size(artworkSize).clip(RoundedCornerShape(18.dp)),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            HeroFooter(track, playback, state)
        }
    }
}

/** Large borderless transport, weighted so play is unmistakably the primary action. */
@Composable
private fun TransportControls(playback: PlaybackState, state: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(state::previous, Modifier.size(44.dp)) {
            Icon(Icons.Default.SkipPrevious, "Previous track", Modifier.size(28.dp), tint = Color.White.copy(alpha = .82f))
        }
        IconButton(
            state::togglePlayback,
            Modifier.size(56.dp),
            enabled = playback.status != PlaybackStatus.RESOLVING,
        ) {
            if (playback.status == PlaybackStatus.RESOLVING) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Icon(
                    if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playback.isPlaying) "Pause" else "Play",
                    Modifier.size(42.dp),
                    tint = Color.White,
                )
            }
        }
        IconButton(state::next, Modifier.size(44.dp)) {
            Icon(Icons.Default.SkipNext, "Next track", Modifier.size(28.dp), tint = Color.White.copy(alpha = .82f))
        }
    }
}

/** The line being sung right now, flanked by the actions that belong to this track. */
@Composable
private fun HeroFooter(track: Track, playback: PlaybackState, state: AppState) {
    val lyrics by state.lyrics.collectAsState()
    val activeLine = lyrics.outcomes
        .firstOrNull { it.provider == lyrics.selectedProvider }
        ?.result
        ?.takeIf { it.synced }
        ?.lines
        ?.lastOrNull { line -> (line.startTimeMs ?: Long.MAX_VALUE) <= playback.positionMs }
        ?.text
        ?.takeIf { it.isNotBlank() }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LikeButton(track, state, size = 34.dp)
        DownloadButton(track, state, size = 34.dp)
        IconButton({ state.copyTrackLink(track) }, Modifier.size(34.dp)) {
            Icon(Icons.Default.Link, "Copy link", Modifier.size(17.dp), tint = Color.White.copy(alpha = .6f))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                activeLine ?: track.album?.title ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = if (activeLine != null) .92f else .45f),
                fontSize = 14.sp,
                fontWeight = if (activeLine != null) FontWeight.Medium else FontWeight.Normal,
            )
        }
        ProviderBadge(track.provider, compact = true)
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
    // Transparent so the ambient backdrop reads through the glass panel behind this.
    Surface(modifier, color = Color.Transparent) {
        Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
            // Rounded chips rather than a segmented bar, matching the pill language of the rest of the panel.
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                NowPlayingTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    Surface(
                        onClick = { selectTab(tab) },
                        color = if (selected) Color.White.copy(alpha = .16f) else Color.Transparent,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = if (selected) .18f else .09f)),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            tab.label,
                            color = if (selected) Color.White else Color.White.copy(alpha = .6f),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
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

        // Read into a local before the branch below tests it. A public property from another module is
        // never smart-cast — nothing there stops it becoming a computed one that answers differently on
        // each read — so testing the property and then using it would leave it nullable.
        val providerPage = selectedResult?.sourceUrl

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
            selectedResult != null && providerPage != null -> {
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
                    FilledTonalButton({ state.openExternalUrl(providerPage) }) {
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
            "This optional source requires its own API key before Spiceity can search it."
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    queue.context?.originType?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Queue",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Text(
                    "${queue.currentIndex + 1} of ${queue.tracks.size}",
                    color = Color.White.copy(alpha = .5f),
                    fontSize = 11.sp,
                )
            }
            // Pills rather than icon buttons, so the two destructive-ish actions read clearly.
            QueueActionPill("Shuffle", Icons.Default.Shuffle, queue.shuffleEnabled, state::toggleShuffle)
            QueueActionPill("Clear", Icons.Default.Close, false, state::clearQueue)
        }
        HorizontalDivider(color = Color.White.copy(alpha = .07f))
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 18.dp),
        ) {
            itemsIndexed(queue.tracks, key = { index, track -> "expanded:${track.queueKey}:$index" }) { index, track ->
                QueueRow(track, index, index == queue.currentIndex, state)
            }
        }
    }
}

/** Small pill action for the queue header. */
@Composable
private fun QueueActionPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    action: () -> Unit,
) {
    Surface(
        onClick = action,
        shape = RoundedCornerShape(20.dp),
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .28f) else Color.White.copy(alpha = .08f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (active) .22f else .1f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                null,
                Modifier.size(14.dp),
                tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .75f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .82f),
            )
        }
    }
}

/**
 * One line of the queue. Play and remove sit on the row itself, appearing on hover the way the reference does,
 * so the list stays quiet until you reach for it.
 */
@Composable
private fun QueueRow(track: Track, index: Int, playing: Boolean, state: AppState) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val hovered = isHovered ||
        state.settings.collectAsState().value.preferences.hoverControls == HoverControls.ALWAYS
    Surface(
        onClick = { state.jumpToQueueItem(index) },
        color = when {
            playing -> Color.White.copy(alpha = .11f)
            hovered -> Color.White.copy(alpha = .06f)
            else -> Color.Transparent
        },
        shape = RoundedCornerShape(12.dp),
        interactionSource = interaction,
        modifier = Modifier.hoverable(interaction),
    ) {
        Row(Modifier.fillMaxWidth().padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                RemoteArtwork(track.artworkUrl, track.provider, Modifier.fillMaxSize())
                if (playing) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.GraphicEq, "Playing", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                    color = if (playing) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .92f),
                )
                Text(
                    track.artistLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = .5f),
                    fontSize = 11.sp,
                )
            }
            if (hovered) {
                IconButton({ state.jumpToQueueItem(index) }, Modifier.size(30.dp)) {
                    Icon(Icons.Default.PlayArrow, "Play now", Modifier.size(18.dp), tint = Color.White.copy(alpha = .85f))
                }
                IconButton({ state.removeQueueItem(index) }, Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, "Remove from queue", Modifier.size(16.dp), tint = Color.White.copy(alpha = .6f))
                }
            } else {
                track.durationMs?.let {
                    Text(formatPlaybackTime(it), color = Color.White.copy(alpha = .45f), fontSize = 11.sp)
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
    style: ProgressBarStyle = ProgressBarStyle.MINIMAL,
    timeDisplay: TimeDisplay = TimeDisplay.TOTAL,
) {
    val durationMs = playback.durationMs.coerceAtLeast(0)
    // Whether the track's length is known yet. Standing in 1 ms for the unknown made every ratio below round
    // to a full bar, so a track of unknown length showed as finished the moment it started playing.
    val hasDuration = durationMs > 0
    val canSeek = playback.track != null && hasDuration && playback.status != PlaybackStatus.RESOLVING
    var dragging by remember { mutableStateOf(false) }
    var draggedPosition by remember { mutableFloatStateOf(0f) }
    val displayedPosition = if (dragging) draggedPosition else playback.positionMs.toFloat()
    val maximum = durationMs.coerceAtLeast(1).toFloat()
    val fraction = playbackFraction(displayedPosition, durationMs)
    // The right-hand figure is either the length of the track or what is left of it. An unknown length is
    // said to be unknown rather than reported as zero.
    val trailing = when {
        !hasDuration -> UNKNOWN_PLAYBACK_TIME
        timeDisplay == TimeDisplay.REMAINING ->
            "-" + formatPlaybackTime((maximum - displayedPosition).toLong().coerceAtLeast(0))
        else -> formatPlaybackTime(durationMs)
    }

    if (style == ProgressBarStyle.MINIMAL) {
        MinimalProgressBar(
            positionMs = displayedPosition,
            maximumMs = maximum,
            fraction = fraction,
            trailingLabel = trailing,
            canSeek = canSeek,
            onScrub = { fraction ->
                dragging = true
                draggedPosition = (fraction * maximum).coerceIn(0f, maximum)
            },
            onScrubFinished = {
                val target = draggedPosition.toLong()
                dragging = false
                onSeek(target)
            },
            modifier = modifier,
        )
        return
    }

    Row(modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatPlaybackTime(displayedPosition.toLong()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.width(44.dp),
        )
        Slider(
            value = if (hasDuration) displayedPosition.coerceIn(0f, maximum) else 0f,
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
            trailing,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.width(48.dp),
        )
    }
}

/**
 * A hairline seek bar: times at each end, a thin track, and a small dot for the handle. Drawn directly rather
 * than built from a Material slider, because the point of it is the absence of furniture.
 */
@Composable
private fun MinimalProgressBar(
    positionMs: Float,
    maximumMs: Float,
    /** How much of the track has played, already resolved by the caller so an unknown length reads as empty. */
    fraction: Float,
    trailingLabel: String,
    canSeek: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val trackColour = Color.White.copy(alpha = .22f)
    val filledColour = MaterialTheme.colorScheme.primary

    Row(modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatPlaybackTime(positionMs.toLong()),
            color = Color.White.copy(alpha = .68f),
            fontSize = 11.sp,
            modifier = Modifier.width(42.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(24.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(canSeek, widthPx) {
                    if (!canSeek) return@pointerInput
                    detectTapGestures { offset ->
                        onScrub(offset.x / widthPx)
                        onScrubFinished()
                    }
                }
                .pointerInput(canSeek, widthPx) {
                    if (!canSeek) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> onScrub(offset.x / widthPx) },
                        onDragEnd = { onScrubFinished() },
                        onDragCancel = { onScrubFinished() },
                        onHorizontalDrag = { change, _ ->
                            onScrub(change.position.x / widthPx)
                            change.consume()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                val centreY = size.height / 2f
                val thickness = 3.dp.toPx()
                drawLine(
                    color = trackColour,
                    start = Offset(0f, centreY),
                    end = Offset(size.width, centreY),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round,
                )
                val head = size.width * fraction
                if (head > 0f) {
                    drawLine(
                        color = filledColour,
                        start = Offset(0f, centreY),
                        end = Offset(head, centreY),
                        strokeWidth = thickness,
                        cap = StrokeCap.Round,
                    )
                }
                if (canSeek) drawCircle(color = filledColour, radius = 6.dp.toPx(), center = Offset(head, centreY))
            }
        }
        Text(
            trailingLabel,
            color = Color.White.copy(alpha = .68f),
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(42.dp),
        )
    }
}

/** Stands in for a length that is not known yet, so an unknown track does not claim to be zero seconds long. */
private const val UNKNOWN_PLAYBACK_TIME = "--:--"

/**
 * How much of the seek bar is filled.
 *
 * A track whose length is not known has nothing to measure a position against. Standing in 1 ms for the
 * unknown made every position round to a full bar, so a YouTube Music track — whose listing carries no
 * duration at all — showed as finished the moment it started playing.
 */
internal fun playbackFraction(positionMs: Float, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs / durationMs.toFloat()).coerceIn(0f, 1f)

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

private enum class SettingsPage {
    ACCOUNT, PROFILE, CUSTOMIZATION, YOUTUBE, SOUNDCLOUD, SPOTIFY, SCROBBLING, LYRICS, DISCORD, DIAGNOSTICS
}

@Composable
private fun SettingsScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    if (page != null) {
        SettingsDetailHeader(pageTitle(page!!), { page = null }) {
            when (page) {
                SettingsPage.ACCOUNT -> SpiceityAccountPanel(state)
                SettingsPage.PROFILE -> ProfileSettingsPanel(settings.preferences, state)
                SettingsPage.CUSTOMIZATION -> CustomizationPanel(settings.preferences, state)
                SettingsPage.YOUTUBE -> YouTubeAccountPanel(settings, state)
                SettingsPage.SOUNDCLOUD -> AccountConnectionPanel(
                    ProviderType.SOUNDCLOUD,
                    settings.preferences.soundCloudCookies,
                    settings.soundCloudAccount,
                    state,
                    settings.preferences.soundCloudUsername,
                )
                SettingsPage.SPOTIFY -> SpotifySettingsPanel(settings, state)
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
            Text("Accounts, services and how Spiceity behaves.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
            val account by state.account.collectAsState()
            SettingsCard(
                account.user?.displayName ?: "Spiceity account",
                if (account.signedIn) {
                    "${account.stats.streams} streamed · ${account.stats.uniqueTracks} different · " +
                        "${formatHours(account.stats.hours)} listened"
                } else {
                    "Sign in to count what you listen to"
                },
                Icons.Default.Insights,
                { page = SettingsPage.ACCOUNT },
            )
        }
        item {
            SettingsCard(
                settings.preferences.profileName,
                "Your local Spiceity profile",
                Icons.Default.AccountCircle,
                { page = SettingsPage.PROFILE },
            )
        }
        item {
            SettingsCard(
                "Customization",
                "Seek bar style and how the player looks",
                Icons.Default.Tune,
                { page = SettingsPage.CUSTOMIZATION },
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
            SettingsCard(
                "Spotify library",
                spotifySummary(settings.spotify),
                Icons.Default.LibraryMusic,
                { page = SettingsPage.SPOTIFY },
                settings.spotify.connected,
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
            // Read from the live setting. This once read the field that only exists to carry a choice over
            // from an older build, which is never written and so always said Disabled however it was set.
            val discord = settings.preferences.discord
            SettingsCard(
                "Discord Rich Presence",
                if (discord.enabled) "Enabled · ${discord.timestamps.displayName}" else "Disabled",
                Icons.Default.SportsEsports,
                { page = SettingsPage.DISCORD },
                discord.enabled,
            )
        }
        item { SettingsCard("Diagnostics", "Check yt-dlp, mpv, FFmpeg and storage", Icons.Default.MonitorHeart, { page = SettingsPage.DIAGNOSTICS }) }
    }
}

private fun pageTitle(page: SettingsPage) = when (page) {
    SettingsPage.ACCOUNT -> "Spiceity account"
    SettingsPage.PROFILE -> "Your Spiceity profile"
    SettingsPage.CUSTOMIZATION -> "Customization"
    SettingsPage.YOUTUBE -> "YouTube Music account"
    SettingsPage.SOUNDCLOUD -> "SoundCloud account"
    SettingsPage.SPOTIFY -> "Spotify library"
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

/**
 * Choices about how Spiceity looks. Each option previews itself with a real, working control rather than a
 * screenshot, so the choice is made by looking at the thing itself.
 */
@Composable
private fun CustomizationPanel(preferences: SpiceityPreferences, state: AppState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsPanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Text("Seek bar", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Used in the player bar and on the now playing screen. Both can be dragged and clicked to seek.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(15.dp))
            ChoiceRow(
                "Player bar layout",
                PlayerBarStyle.entries,
                preferences.playerBarStyle,
                { it.displayName },
                state::setPlayerBarStyle,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                preferences.playerBarStyle.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(15.dp))
            ChoiceRow(
                "Player bar position",
                PlayerBarPosition.entries,
                preferences.playerBarPosition,
                { it.displayName },
                state::setPlayerBarPosition,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                preferences.playerBarPosition.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(18.dp))
            SaveMusicSetting(preferences, state)
            Spacer(Modifier.height(18.dp))
            Text("Seek bar style", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(9.dp))
            ProgressBarStyle.entries.forEach { option ->
                ProgressStyleOption(
                    option = option,
                    selected = preferences.progressBarStyle == option,
                    choose = { state.setProgressBarStyle(option) },
                )
                Spacer(Modifier.height(10.dp))
            }
            ChoiceRow("Right-hand figure", TimeDisplay.entries, preferences.timeDisplay, { it.displayName }, state::setTimeDisplay)
        }

        SettingsPanelCard {
            CardHeading(Icons.Default.Palette, "Colour")
            Spacer(Modifier.height(12.dp))
            Text("Accent", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(9.dp))
            // Swatches rather than names: the colour is the label.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentPreset.entries.forEach { option ->
                    AccentSwatch(option, preferences.accent == option) { state.setAccent(option) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                preferences.accent.let {
                    if (it == AccentPreset.ARTWORK) "The interface follows the cover of whatever is playing."
                    else it.displayName
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(16.dp))
            ChoiceRow(
                "Background",
                BackgroundDepth.entries,
                preferences.backgroundDepth,
                { it.displayName },
                state::setBackgroundDepth,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                preferences.backgroundDepth.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(16.dp))
            ToggleRow(
                "Ambient backdrop on now playing",
                "Tints the screen with colours sampled from the cover.",
                preferences.ambientBackdrop,
                state::setAmbientBackdrop,
            )
        }

        SettingsPanelCard {
            CardHeading(Icons.Default.GridView, "Browsing")
            Spacer(Modifier.height(12.dp))
            ChoiceRow("Card size", CardSize.entries, preferences.cardSize, { it.displayName }, state::setCardSize)
            Spacer(Modifier.height(16.dp))
            ChoiceRow(
                "Service badges",
                BadgePolicy.entries,
                preferences.badgePolicy,
                { it.displayName },
                state::setBadgePolicy,
            )
            Spacer(Modifier.height(6.dp))
            Text(preferences.badgePolicy.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Spacer(Modifier.height(16.dp))
            ChoiceRow(
                "Play and menu buttons",
                HoverControls.entries,
                preferences.hoverControls,
                { it.displayName },
                state::setHoverControls,
            )
            Spacer(Modifier.height(6.dp))
            Text(preferences.hoverControls.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }

        SettingsPanelCard {
            CardHeading(Icons.Default.Start, "Startup")
            Spacer(Modifier.height(12.dp))
            ChoiceRow("Open Spiceity on", StartPage.entries, preferences.startPage, { it.displayName }, state::setStartPage)
        }
    }
}

@Composable
private fun CardHeading(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(9.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** A labelled row of pills. Generic so every choice in this panel looks and behaves identically. */
@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    choose: (T) -> Unit,
) {
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val active = option == selected
                Surface(
                    onClick = { choose(option) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = .05f),
                    border = BorderStroke(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = .55f) else Color.White.copy(alpha = .1f),
                    ),
                ) {
                    Text(
                        name(option),
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Switch(checked, change)
    }
}

@Composable
private fun AccentSwatch(option: AccentPreset, selected: Boolean, choose: () -> Unit) {
    val colour = option.argb?.let { Color(it) }
    Surface(
        onClick = choose,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(
            2.dp,
            if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .16f),
        ),
        modifier = Modifier.size(40.dp),
    ) {
        Box(Modifier.padding(5.dp), contentAlignment = Alignment.Center) {
            if (colour != null) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(colour))
            } else {
                // The artwork option shows a spectrum rather than a single colour, since it has none of its own.
                Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(
                        Brush.sweepGradient(
                            listOf(
                                Color(0xFFFF6EC7), Color(0xFFFF9757), Color(0xFF5FE3B0),
                                Color(0xFF5AB2FF), Color(0xFFB47CFF), Color(0xFFFF6EC7),
                            ),
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun ProgressStyleOption(option: ProgressBarStyle, selected: Boolean, choose: () -> Unit) {
    // The preview is a live bar frozen at a plausible position, so each option shows exactly what it will be.
    val preview = remember {
        PlaybackState(
            track = null,
            status = PlaybackStatus.PAUSED,
            positionMs = 156_000,
            durationMs = 258_000,
        )
    }
    Surface(
        onClick = choose,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .4f) else Color.White.copy(alpha = .04f),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .5f) else Color.White.copy(alpha = .08f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = choose)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(option.displayName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(option.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(color = AmoledBlack.copy(alpha = .55f), shape = RoundedCornerShape(10.dp)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    PlaybackProgressBar(preview, {}, Modifier.fillMaxWidth(), option)
                }
            }
        }
    }
}

@Composable
private fun ProfileSettingsPanel(preferences: SpiceityPreferences, state: AppState) {
    var name by remember(preferences.profileName) { mutableStateOf(preferences.profileName) }
    SettingsPanelCard {
        Icon(Icons.Default.AccountCircle, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text("Local profile", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("This name stays on this computer and identifies your Spiceity setup.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Button({ state.setProfileName(name) }) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(7.dp)); Text("Save profile") }
    }
}

/**
 * Hosts SoundCloud's own sign-in page in embedded Chromium. SoundCloud has no OAuth to build against — app
 * registration has been closed for years — so signing in on their real page inside Spiceity is the closest thing
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
            status = "Spiceity has nowhere to store the browser on this system."
            return@LaunchedEffect
        }
        runCatching {
            session.start(
                url = SOUNDCLOUD_SIGN_IN,
                onProgress = { progress -> status = "Preparing Chromium — $progress" },
                onPageLoaded = { url -> currentUrl = url },
            )
        }.onSuccess { ui ->
            component = ui
            // As with Google: the store outlives the application, so a dead session still in it is enough
            // for SoundCloud to skip the form and return to the signed-in page, leaving the same dead
            // cookies to be harvested again. Clearing turns the button back into a login.
            if (session.clearCookies(SOUNDCLOUD_SESSION_URLS)) {
                session.navigate(SOUNDCLOUD_SIGN_IN)
            }
            status = "Sign in to SoundCloud below. Spiceity picks the session up on its own."
        }.onFailure { error ->
            status = "Could not start the embedded browser: ${error.message?.take(180)}"
        }
    }

    // Cookies are read from a coroutine rather than inside a Chromium callback, which keeps the browser's own
    // threads free and avoids waiting on it from inside its own event.
    LaunchedEffect(component) {
        val live = session ?: return@LaunchedEffect
        if (component == null) return@LaunchedEffect
        // A token already found to be dead. Kept so the same one is not tested repeatedly: each test is a
        // real request, and a stale store returns the same value every couple of seconds.
        var rejected: String? = null
        while (true) {
            delay(2_500)
            val cookies = runCatching { live.harvestCookies("https://soundcloud.com") }.getOrDefault(emptyList())
            if (!isSoundCloudSignedIn(cookies)) continue

            val token = cookies.firstOrNull { it.name == "oauth_token" }?.value
            if (token != null && token == rejected) continue

            finishing = true
            status = "Checking the session with SoundCloud…"
            val destination = state.dataDirectory()?.resolve("soundcloud.cookies") ?: return@LaunchedEffect
            // Beside the real file, not over it: see the YouTube window for why.
            val candidate = runCatching {
                writeCookieFile(cookies, destination.resolveSibling("soundcloud.cookies.checking"))
            }.getOrNull()
            if (candidate == null) {
                status = "Signed in, but the session could not be written to disk."
                finishing = false
                continue
            }

            // Present is not the same as accepted; one real request is what tells them apart.
            if (!state.sessionIsAccepted(ProviderType.SOUNDCLOUD, candidate)) {
                runCatching { java.nio.file.Files.deleteIfExists(candidate) }
                rejected = token
                finishing = false
                status = "That session has expired. Clearing it so SoundCloud asks you to sign in again…"
                live.clearCookies(SOUNDCLOUD_SESSION_URLS)
                live.navigate(SOUNDCLOUD_SIGN_IN)
                continue
            }
            val saved = runCatching { adoptCookieFile(candidate, destination) }.getOrNull()
            if (saved == null) {
                status = "Signed in, but the session could not be saved."
                finishing = false
                continue
            }

            status = "Signed in — finding your profile…"
            // SoundCloud answers its own /you/ routes by moving to the real profile, so a short detour
            // through the signed-in browser names the account with no request of Spiceity's own.
            live.navigate(SOUNDCLOUD_OWN_LIKES)
            var permalink: String? = null
            repeat(8) {
                delay(1_000)
                permalink = permalinkFromBrowserUrl(live.currentUrl())
                if (permalink != null) return@repeat
            }
            state.completeSoundCloudSignIn(saved.toString(), token, permalink)
            close()
            return@LaunchedEffect
        }

    }

    DialogWindow(
        onCloseRequest = close,
        state = rememberDialogState(width = 980.dp, height = 760.dp),
        title = "Sign in to SoundCloud",
    ) {
        Column(Modifier.fillMaxSize().background(SpiceityPanel)) {
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
                OutlinedButton({
                    session?.clearCookies(SOUNDCLOUD_SESSION_URLS)
                    session?.navigate(SOUNDCLOUD_SIGN_IN)
                    status = "Starting over. Sign in to SoundCloud below."
                }) { Text("Start over") }
                Spacer(Modifier.width(8.dp))
                Button(close) { Text("Done") }
            }
            HorizontalDivider()
            component?.let { ui ->
                SwingPanel(background = Color.Black, factory = { ui }, modifier = Modifier.fillMaxSize())
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp)) {
                    Text("Getting the browser ready", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Chromium ships with Spiceity, so nothing is being downloaded — it is unpacked once on this " +
                            "computer and every sign-in after this opens straight away.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * Signs in to YouTube Music on Google's own page inside Spiceity.
 *
 * Sign-in starts at the accounts page and finishes on the music site, because the cookies that authorise the
 * API belong to that domain rather than to the accounts one.
 */
@Composable
private fun YouTubeSignInWindow(state: AppState, close: () -> Unit) {
    var status by remember { mutableStateOf("Starting the embedded browser…") }
    var component by remember { mutableStateOf<java.awt.Component?>(null) }
    var currentUrl by remember { mutableStateOf("") }
    var finishing by remember { mutableStateOf(false) }
    val session = remember { state.dataDirectory()?.let { EmbeddedBrowserSession(it.resolve("chromium")) } }

    DisposableEffect(session) { onDispose { session?.dispose() } }

    LaunchedEffect(session) {
        if (session == null) {
            status = "Spiceity has nowhere to store the browser on this system."
            return@LaunchedEffect
        }
        runCatching {
            session.start(
                url = YOUTUBE_SIGN_IN,
                onProgress = { progress -> status = "Preparing Chromium — $progress" },
                onPageLoaded = { url -> currentUrl = url },
            )
        }.onSuccess { ui ->
            component = ui
            // The store is on disk and outlives the application, so pressing sign-in while a dead session
            // is still in it sends Google a session it recognises: it skips the form, returns to the
            // signed-in page, and the same dead cookies get harvested again. Clearing turns the button
            // back into a login. Done after the browser exists, because there is no cookie manager before.
            if (session.clearCookies(YOUTUBE_SESSION_URLS)) {
                session.navigate(YOUTUBE_SIGN_IN)
            }
            status = "Sign in with Google below. Spiceity picks the session up on its own."
        }.onFailure { error ->
            status = "Could not start the embedded browser: ${error.message?.take(180)}"
        }
    }

    LaunchedEffect(component) {
        val live = session ?: return@LaunchedEffect
        if (component == null) return@LaunchedEffect
        var visitedMusic = false
        // The signing cookie of a session already found to be dead. Kept so the same one is not tested
        // over and over: each test is a real request, and a stale store hands back the same value every
        // couple of seconds. Once a person actually signs in the value changes, and that is the signal.
        var rejected: String? = null
        while (true) {
            delay(2_500)
            val cookies = runCatching { live.harvestCookies(YOUTUBE_MUSIC_HOME) }.getOrDefault(emptyList())
            if (!isYouTubeSignedIn(cookies)) {
                // The signing cookie is set for the music site once it has been visited, so go there after
                // the accounts page has done its part.
                if (!visitedMusic && live.currentUrl()?.contains("accounts.google.com") == false) {
                    visitedMusic = true
                    live.navigate(YOUTUBE_MUSIC_HOME)
                }
                continue
            }
            val signature = cookies.firstOrNull { it.name == "SAPISID" || it.name == "__Secure-3PAPISID" }?.value
            if (signature != null && signature == rejected) continue

            finishing = true
            status = "Checking the session with YouTube…"
            val destination = state.dataDirectory()?.resolve("youtube.cookies") ?: return@LaunchedEffect
            // Written beside the real file and only moved over it once the provider has accepted it. The
            // check needs a file to point at, and writing that file over the working session first would
            // destroy a good one every time an old cookie turned out to be dead.
            val candidate = runCatching {
                writeCookieFile(cookies, destination.resolveSibling("youtube.cookies.checking"))
            }.getOrNull()
            if (candidate == null) {
                status = "Signed in, but the session could not be written to disk."
                finishing = false
                continue
            }

            // A cookie being present is not a session. One real request tells the two apart, and without
            // it a dead session is saved, reported as success, and refused by everything afterwards.
            if (state.sessionIsAccepted(ProviderType.YOUTUBE_MUSIC, candidate)) {
                status = "Signed in — saving the session…"
                val saved = runCatching { adoptCookieFile(candidate, destination) }.getOrNull()
                if (saved == null) {
                    status = "Signed in, but the session could not be saved."
                    finishing = false
                    continue
                }
                state.completeYouTubeSignIn(saved.toString())
                close()
                return@LaunchedEffect
            }
            runCatching { java.nio.file.Files.deleteIfExists(candidate) }


            // Present but not accepted: the store is holding an old session, and Google will keep skipping
            // the login form while it is there. Clearing it is what turns this window back into a login.
            rejected = signature
            finishing = false
            status = "That session has expired. Clearing it so Google asks you to sign in again…"
            live.clearCookies(YOUTUBE_SESSION_URLS)
            live.navigate(YOUTUBE_SIGN_IN)
            visitedMusic = false
        }
    }


    DialogWindow(
        onCloseRequest = close,
        state = rememberDialogState(width = 1000.dp, height = 780.dp),
        title = "Sign in to YouTube Music",
    ) {
        Column(Modifier.fillMaxSize().background(SpiceityPanel)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (finishing || component == null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(status, fontSize = 12.sp)
                    if (currentUrl.isNotBlank()) {
                        Text(
                            currentUrl,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Signing out at Google's end as well, for when it has decided you are still signed in
                // and there is no form to type into.
                OutlinedButton({
                    session?.clearCookies(YOUTUBE_SESSION_URLS)
                    session?.navigate(YOUTUBE_SIGN_IN)
                    status = "Starting over. Sign in with Google below."
                }) { Text("Start over") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton({ session?.navigate(YOUTUBE_MUSIC_HOME) }) { Text("Go to YouTube Music") }
                Spacer(Modifier.width(8.dp))
                // Named for what it does rather than Cancel, which after a successful sign-in reads as
                // though it would undo one.
                Button(close) { Text("Done") }
            }
            HorizontalDivider()
            component?.let { ui ->
                SwingPanel(background = Color.Black, factory = { ui }, modifier = Modifier.fillMaxSize())
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Chromium ships with Spiceity and is unpacked once on this computer.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** YouTube Music through a signed-in browser session, the same arrangement SoundCloud uses. */
@Composable
private fun YouTubeAccountPanel(settings: SettingsState, state: AppState) {
    val likes by state.likes.collectAsState()
    val source = settings.preferences.youtubeCookies
    var signInOpen by remember { mutableStateOf(false) }
    if (signInOpen) YouTubeSignInWindow(state) { signInOpen = false }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsPanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (likes.youTubeReady) "Signed in to YouTube Music" else "Sign in to YouTube Music",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Signs in on Google's own page inside Spiceity. Your playlists and likes then work through " +
                            "the same interface the YouTube Music site uses, with no Google Cloud project involved.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            AccountStatusRow(settings.youtubeAccount)
            Spacer(Modifier.height(15.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ signInOpen = true }) {
                    Icon(Icons.Default.Login, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (source.isConfigured) "Sign in again" else "Sign in to YouTube Music")
                }
                if (source.isConfigured) {
                    OutlinedButton({ state.disconnectAccount(ProviderType.YOUTUBE_MUSIC) }) { Text("Disconnect") }
                }
            }
            likes.message?.let { message ->
                Spacer(Modifier.height(12.dp))
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

        if (likes.youTubeReady) {
            SettingsPanelCard {
                CardHeading(Icons.Default.SwitchAccount, "Channel")
                Spacer(Modifier.height(7.dp))
                Text(
                    "One Google account can own several YouTube channels, and the default one is not always the " +
                        "right one. Whichever is chosen here is the account Spiceity acts as.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(11.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        settings.preferences.youtubeChannelName.ifBlank { "Default channel" },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(11.dp))
                OutlinedButton(state::loadYouTubeChannels) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (likes.youTubeChannels.isEmpty()) "Find my channels" else "Refresh channels")
                }
                if (likes.youTubeChannels.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        likes.youTubeChannels.forEach { channel ->
                            val selected = channel.pageId == settings.preferences.youtubePageId
                            Surface(
                                onClick = { state.setYouTubeChannel(channel) },
                                shape = RoundedCornerShape(11.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f)
                                else Color.White.copy(alpha = .04f),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .5f)
                                    else Color.White.copy(alpha = .08f),
                                ),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected, { state.setYouTubeChannel(channel) })
                                    Spacer(Modifier.width(4.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(channel.name, fontSize = 13.sp)
                                        if (channel.isDefault) {
                                            Text(
                                                "The account's default channel",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 10.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SettingsPanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp)); Text("What signing in covers", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(9.dp))
            AccountFact("Your playlists and liked songs, read and written the way the YouTube Music site does.")
            AccountFact("Liking a YouTube track in Spiceity marks it liked on your account.")
            AccountFact("Your library and playlists come from this session; playback itself resolves without it.")
            AccountFact("Your password goes to Google's page, never to Spiceity, and only the session is kept.")
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
                        "Spiceity reuses an account you are already signed into. Only the location of the cookies is saved — never the cookies themselves.",
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
                    "Signs in on SoundCloud's own page inside Spiceity. SoundCloud issues no API credentials to new " +
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
                    "Chromium is bundled with Spiceity, so the first sign-in unpacks it rather than downloading it. " +
                        "Your password goes to SoundCloud's page, never to Spiceity.",
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
                        "Connected. Hearts in Spiceity are written to your SoundCloud likes."
                    } else {
                        "Reading needs only cookies, but liking writes to your account, so Spiceity needs the session " +
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
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(
                        { state.setSoundCloudUsername(soundCloudUsername) },
                        enabled = soundCloudUsername.isNotBlank(),
                    ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(7.dp)); Text("Save profile name") }
                    OutlinedButton({ state.detectSoundCloudProfile() }) {
                        Icon(Icons.Default.Search, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Detect from my session")
                    }
                }
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
                    "SoundCloud has no cheap private page to load, so Spiceity confirms the cookies are read and accepted rather than naming your account."
                } else {
                    "Connecting loads your subscriptions feed, which only a signed-in account can see — that is how Spiceity knows the session is real."
                },
            )
            AccountFact("Spiceity never asks for your password, and disconnecting removes the session immediately.")
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

private fun spotifySummary(spotify: SpotifyConnectionState): String = when {
    !spotify.configured -> "Read your playlists and liked songs"
    spotify.connecting -> "Waiting for Spotify…"
    spotify.connected && spotify.accountName.isNotBlank() -> "Reading ${spotify.accountName}'s library"
    spotify.connected -> "Your Spotify library is connected"
    else -> "Client id saved — connect your account"
}

/**
 * Spotify, set up as a library.
 *
 * The panel says plainly what this is and is not, because it would otherwise be reasonable to expect that
 * connecting Spotify means playing from Spotify. It does not, and it cannot: the Web API serves no audio,
 * and only Spotify's own player is allowed to decode it. What connecting gives is the collection.
 *
 * The setup asks for a client id, which is a step the other services do not have. There is no way around
 * it -- Spotify answers nothing without one, and its own web-player token endpoint is closed to requests
 * from outside the site.
 */
@Composable
private fun SpotifySettingsPanel(settings: SettingsState, state: AppState) {
    val spotify = settings.spotify
    var clientId by remember(settings.preferences.spotifyClientId) {
        mutableStateOf(settings.preferences.spotifyClientId)
    }
    val redirect = remember { state.spotifyRedirectUri() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsPanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LibraryMusic,
                    null,
                    Modifier.size(40.dp),
                    tint = Color(0xFF1DB954),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        when {
                            spotify.connected && spotify.accountName.isNotBlank() -> spotify.accountName
                            spotify.connected -> "Spotify connected"
                            else -> "Spotify not connected"
                        },
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Your playlists and liked songs, read into Spiceity",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            AccountFact("Your Spotify playlists and Liked Songs appear in your library, in Spotify's order.")
            AccountFact("Playback does not come from Spotify. Each song is matched on YouTube Music and played from there.")
            AccountFact("Nothing is ever written to Spotify — not a like, not a playlist, not a listen.")
            AccountFact("A song Spotify has that cannot be found elsewhere is reported rather than swapped for another.")
        }

        SettingsPanelCard {
            Text("One-time setup", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Spotify answers nothing without a client id, and only issues them per application — so this " +
                    "one is yours rather than Spiceity's. It takes a minute and never expires.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(13.dp))
            SetupStep(1, "Open Spotify's developer dashboard and create an app. Any name will do.")
            SetupStep(2, "Add this exact address to the app's Redirect URIs, and tick the Web API:")
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = .7f),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.padding(start = 26.dp, top = 4.dp, bottom = 8.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionContainer { Text(redirect, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                    Spacer(Modifier.width(8.dp))
                    IconButton(state::copySpotifyRedirectUri, Modifier.size(26.dp)) {
                        Icon(Icons.Default.ContentCopy, "Copy the redirect address", Modifier.size(15.dp))
                    }
                }
            }
            SetupStep(3, "Copy the app's Client ID from its settings and paste it below.")
            Spacer(Modifier.height(11.dp))
            OutlinedButton(state::openSpotifyDashboard) {
                Icon(Icons.Default.OpenInNew, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("Open Spotify's dashboard")
            }
        }

        SettingsPanelCard {
            Text("Your Spotify client id", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                clientId,
                { clientId = it.trim().take(64) },
                label = { Text("Client ID") },
                placeholder = { Text("32 characters from the dashboard") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "This is an identifier and not a password, so it is kept in your settings file. The sign-in " +
                    "itself is stored encrypted for your Windows account.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(
                    { state.setSpotifyClientId(clientId) },
                    enabled = clientId != settings.preferences.spotifyClientId,
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Save client id")
                }
                Button(
                    state::connectSpotify,
                    enabled = spotify.configured && !spotify.connecting &&
                        clientId == settings.preferences.spotifyClientId,
                ) {
                    Icon(Icons.Default.Link, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (spotify.connected) "Reconnect Spotify" else "Connect Spotify")
                }
                if (spotify.connected) {
                    OutlinedButton(state::disconnectSpotify) { Text("Disconnect") }
                }
            }
            spotify.message?.let { message ->
                Spacer(Modifier.height(11.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .6f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) { Text(message, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

/** A numbered instruction, for the one part of Spiceity that asks somebody to go and do something else. */
@Composable
private fun SetupStep(number: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(
            "$number.",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(18.dp),
        )
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
                Text("Spiceity opens Last.fm in your browser for approval and finishes sign-in by itself once you allow it. Your Last.fm password is never entered into Spiceity.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
                            Button(state::finishLastFmLogin) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(7.dp)); Text("I approved Spiceity") }
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
                            Text("Optional — Spiceity already ships with application credentials. These only replace which application Last.fm sees; account approval still happens in your browser.", color = MaterialTheme.colorScheme.tertiary, fontSize = 11.sp)
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
        "Musixmatch" to !System.getenv("SPICEITY_MUSIXMATCH_API_KEY").isNullOrBlank(),
        "Happi" to !System.getenv("SPICEITY_HAPPI_API_KEY").isNullOrBlank(),
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
private fun DiscordSettingsPanel(preferences: SpiceityPreferences, state: AppState) {
    val discord = preferences.discord
    val status by state.discordStatus.collectAsState()
    var applicationId by remember(discord.applicationId) { mutableStateOf(discord.applicationId) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsPanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SportsEsports, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("Show listening activity", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Publishes the current track to your Discord profile over the local client. Nothing leaves this computer except what you see below.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                Switch(discord.enabled, state::setDiscordPresence)
            }
            Spacer(Modifier.height(14.dp))
            DiscordCardPreview(status.preview, discord)
            status.lastMessage?.let { message ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (status.connected) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        null,
                        Modifier.size(15.dp),
                        tint = if (status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SettingsPanelCard {
            CardHeading(Icons.Default.Badge, "Discord application")
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(7.dp))
                Text(
                    if (discord.usesOwnApplication) {
                        "Using your application · ${discord.applicationId}"
                    } else {
                        "Using the application Spiceity ships with · ${discord.resolvedApplicationId()}"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Discord shows this application's name above the card. Spiceity already has one, so nothing is needed " +
                    "here — set an id only to appear as a different application, or to upload your own named " +
                    "images under its Rich Presence assets.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                applicationId,
                { applicationId = it.filter(Char::isDigit).take(25) },
                label = { Text("Application ID (optional)") },
                placeholder = { Text("Leave empty to use Spiceity's own") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(
                    { state.updateDiscord { it.copy(applicationId = applicationId) } },
                    enabled = applicationId != discord.applicationId,
                ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(7.dp)); Text("Save id") }
                OutlinedButton(state::testDiscordConnection) { Text("Test connection") }
                if (discord.usesOwnApplication) {
                    TextButton({ applicationId = ""; state.updateDiscord { it.copy(applicationId = "") } }) {
                        Text("Use Spiceity's")
                    }
                }
                TextButton({ state.openExternalUrl("https://discord.com/developers/applications") }) {
                    Text("Developer portal")
                }
            }
        }

        SettingsPanelCard {
            CardHeading(Icons.Default.TextFields, "What the card says")
            Spacer(Modifier.height(7.dp))
            Text(
                "Each line is a template. Use {title}, {artist}, {album}, {provider}, {duration} and {position}; " +
                    "anything with no value disappears cleanly.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(13.dp))
            TemplateField("First line", discord.detailsTemplate) { value ->
                state.updateDiscord { it.copy(detailsTemplate = value) }
            }
            Spacer(Modifier.height(9.dp))
            TemplateField("Second line", discord.stateTemplate) { value ->
                state.updateDiscord { it.copy(stateTemplate = value) }
            }
            Spacer(Modifier.height(9.dp))
            TemplateField("Cover tooltip", discord.largeTextTemplate) { value ->
                state.updateDiscord { it.copy(largeTextTemplate = value) }
            }
            Spacer(Modifier.height(9.dp))
            TemplateField("Small icon tooltip", discord.smallTextTemplate) { value ->
                state.updateDiscord { it.copy(smallTextTemplate = value) }
            }
            Spacer(Modifier.height(16.dp))
            ChoiceRow(
                "Verb",
                PresenceActivityKind.entries,
                discord.activityKind,
                { it.displayName },
                { kind -> state.updateDiscord { it.copy(activityKind = kind) } },
            )
        }

        SettingsPanelCard {
            CardHeading(Icons.Default.Image, "Images and clock")
            Spacer(Modifier.height(12.dp))
            ChoiceRow(
                "Large image",
                PresenceArtwork.entries,
                discord.artwork,
                { it.displayName },
                { artwork -> state.updateDiscord { it.copy(artwork = artwork) } },
            )
            if (discord.artwork == PresenceArtwork.ASSET) {
                Spacer(Modifier.height(10.dp))
                TemplateField("Asset name", discord.artworkAssetKey) { value ->
                    state.updateDiscord { it.copy(artworkAssetKey = value) }
                }
            }
            Spacer(Modifier.height(10.dp))
            TemplateField("Small icon asset (optional)", discord.smallImageAssetKey) { value ->
                state.updateDiscord { it.copy(smallImageAssetKey = value) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Leave the small icon empty to use the service name — youtube_music, youtube or soundcloud — as the asset name.",
                color = MaterialTheme.colorScheme.tertiary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(16.dp))
            ChoiceRow(
                "Clock",
                PresenceTimestamps.entries,
                discord.timestamps,
                { it.displayName },
                { stamps -> state.updateDiscord { it.copy(timestamps = stamps) } },
            )
            Spacer(Modifier.height(6.dp))
            Text(discord.timestamps.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }

        SettingsPanelCard {
            CardHeading(Icons.Default.SmartButton, "Buttons")
            Spacer(Modifier.height(7.dp))
            Text(
                "Discord allows two. A button needs both a label and a link, and {url} is the track's page.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(13.dp))
            PresenceButtonFields("First button", discord.firstButton) { button ->
                state.updateDiscord { it.copy(firstButton = button) }
            }
            Spacer(Modifier.height(14.dp))
            PresenceButtonFields("Second button", discord.secondButton) { button ->
                state.updateDiscord { it.copy(secondButton = button) }
            }
        }

        SettingsPanelCard {
            CardHeading(Icons.Default.Security, "While paused, and privacy")
            Spacer(Modifier.height(12.dp))
            ChoiceRow(
                "When playback pauses",
                PausedBehaviour.entries,
                discord.paused,
                { it.displayName },
                { behaviour -> state.updateDiscord { it.copy(paused = behaviour) } },
            )
            Spacer(Modifier.height(6.dp))
            Text(discord.paused.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            if (discord.paused == PausedBehaviour.SHOW_PAUSED) {
                Spacer(Modifier.height(10.dp))
                TemplateField("Paused suffix", discord.pausedSuffix) { value ->
                    state.updateDiscord { it.copy(pausedSuffix = value) }
                }
            }
            Spacer(Modifier.height(16.dp))
            ToggleRow(
                "Hide what I am listening to",
                "Replaces every line with one fixed message and drops the cover and buttons.",
                discord.hideTrackDetails,
            ) { hidden -> state.updateDiscord { it.copy(hideTrackDetails = hidden) } }
            if (discord.hideTrackDetails) {
                Spacer(Modifier.height(10.dp))
                TemplateField("Shown instead", discord.privateDetails) { value ->
                    state.updateDiscord { it.copy(privateDetails = value) }
                }
            }
        }
    }
}

/** Mirrors Discord's card so the templates can be judged without alt-tabbing. */
@Composable
private fun DiscordCardPreview(preview: DiscordPreview?, settings: DiscordPresenceSettings) {
    Surface(color = Color(0xFF1E1F22), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                settings.activityKind.displayName.uppercase(),
                color = Color.White.copy(alpha = .55f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .08f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.MusicNote, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = .5f)) }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        preview?.details?.takeIf { it.isNotBlank() } ?: "Nothing playing yet",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    preview?.state?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Color.White.copy(alpha = .75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (settings.timestamps != PresenceTimestamps.NONE) {
                        Text(
                            if (settings.timestamps == PresenceTimestamps.ELAPSED) "0:42 elapsed" else "2:31 left",
                            color = Color.White.copy(alpha = .55f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            preview?.buttons?.takeIf { it.isNotEmpty() }?.let { buttons ->
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    buttons.forEach { label ->
                        Surface(color = Color.White.copy(alpha = .1f), shape = RoundedCornerShape(6.dp)) {
                            Text(
                                label,
                                color = Color.White.copy(alpha = .9f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateField(label: String, value: String, change: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        text,
        {
            text = it.take(128)
            change(text)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PresenceButtonFields(label: String, button: PresenceButton, change: (PresenceButton) -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f)) {
                TemplateField("Label", button.label) { change(button.copy(label = it)) }
            }
            Box(Modifier.weight(1.4f)) {
                TemplateField("Link", button.url) { change(button.copy(url = it)) }
            }
        }
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

/**
 * Whether a playlist on this service can hold the given track: services do not take each other's music.
 *
 * A Spotify track is refused everywhere, in both directions. Its id means nothing to YouTube, and Spiceity
 * never writes to Spotify at all — so neither service could be asked to store it. A Spiceity playlist takes
 * it happily, which is where a mixed collection belongs.
 */
private fun ProviderType.acceptsTrack(track: Track): Boolean = when {
    track.provider == ProviderType.SPOTIFY -> false
    this == ProviderType.SOUNDCLOUD -> track.provider == ProviderType.SOUNDCLOUD
    this == ProviderType.YOUTUBE_MUSIC || this == ProviderType.YOUTUBE_VIDEO ->
        track.provider != ProviderType.SOUNDCLOUD
    else -> false
}

/** Hours as something readable: a fresh account should not be told it has listened for "0.0" hours. */
private fun formatHours(hours: Double): String = when {
    hours <= 0.0 -> "no time yet"
    hours < 1.0 -> "${(hours * 60).roundToInt()} min"
    hours < 10.0 -> "${String.format(java.util.Locale.US, "%.1f", hours)} h"
    else -> "${hours.roundToInt()} h"
}

/**
 * Signing in to the Spiceity account, and what it has counted.
 *
 * The same account as the website. Only the address and a session token are held here; the password is
 * sent once and never written down.
 */
@Composable
private fun SpiceityAccountPanel(state: AppState) {
    val account by state.account.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (account.signedIn) {
            val user = account.user!!
            SettingsPanelCard {
                Text(user.displayName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(user.email, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ListeningStat("${account.stats.streams}", "Streamed", Modifier.weight(1f))
                    ListeningStat("${account.stats.uniqueTracks}", "Different", Modifier.weight(1f))
                    ListeningStat(formatHours(account.stats.hours), "Listened", Modifier.weight(1f))
                    ListeningStat("${account.stats.artists}", "Artists", Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(state::refreshListeningStats) { Text("Refresh") }
                    OutlinedButton(state::signOutOfSpiceity) { Text("Sign out") }
                }
                Spacer(Modifier.height(12.dp))
                AccountFact("A song counts once you have heard 30 seconds of it, or half of it.")
                AccountFact("The same figures are on the website, under your account.")
            }
            Spacer(Modifier.height(14.dp))
            SettingsPanelCard { ConnectSettingsRows(state) }
        } else {
            SettingsPanelCard {
                Text(
                    if (creating) "Create a Spiceity account" else "Sign in to Spiceity",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Counts how many songs you stream, how many different ones, and for how long.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                if (creating) {
                    OutlinedTextField(
                        displayName,
                        { displayName = it },
                        label = { Text("Display name") },
                        placeholder = { Text("Optional") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(9.dp))
                }
                OutlinedTextField(
                    email,
                    { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    // The password is never shown, and never leaves this field except in the one request.
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = if (creating) {
                        { Text("At least 10 characters.") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        {
                            if (creating) state.signUpToSpiceity(email, password, displayName)
                            else state.logInToSpiceity(email, password)
                            password = ""
                        },
                        enabled = !account.busy && email.isNotBlank() && password.isNotBlank(),
                    ) {
                        if (account.busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (creating) "Create account" else "Sign in")
                        }
                    }
                    TextButton({ creating = !creating; password = "" }) {
                        Text(if (creating) "I already have one" else "Create an account")
                    }
                }
            }
        }

        account.message?.let { message ->
            Spacer(Modifier.height(10.dp))
            SettingsPanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), fontSize = 12.sp)
                    IconButton(state::clearSpiceityMessage, Modifier.size(26.dp)) {
                        Icon(Icons.Default.Close, "Dismiss", Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}
