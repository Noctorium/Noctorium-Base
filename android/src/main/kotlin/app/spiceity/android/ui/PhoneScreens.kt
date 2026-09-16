package app.spiceity.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import app.spiceity.core.ProviderFilter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spiceity.core.AppState
import app.spiceity.domain.HomeSection
import app.spiceity.domain.Playlist
import app.spiceity.domain.Track
import app.spiceity.downloads.DownloadStage
import kotlin.math.roundToInt

/** A page title, sitting under the status bar. Every screen starts with one. */
@Composable
internal fun ScreenTitle(title: String, subtitle: String? = null, action: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        action?.invoke()
    }
}

@Composable
private fun ScreenScaffold(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) { content() }
}

// --- Home ---

@Composable
internal fun HomeScreen(state: AppState) {
    val ui by state.ui.collectAsState()
    val playback by state.playback.collectAsState()

    ScreenScaffold {
        ScreenTitle(greeting(), "What is on, and what you were listening to") {
            IconButton({ state.refreshHome() }) { Icon(Icons.Default.Refresh, "Reload") }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProviderFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = ui.providerFilter == filter,
                            onClick = { state.setFilter(filter) },
                            label = { Text(filter.label(), fontSize = 12.sp) },
                        )
                    }
                }
            }

            ui.errorMessage?.let { message ->
                item {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }

            if (ui.recentTracks.isNotEmpty()) {
                item { TrackCarousel("Jump back in", "Where you left off", ui.recentTracks, state) }
            }

            if (ui.homeLoading && ui.homeSections.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
            }

            items(ui.homeSections.filter { it.matches(ui.providerFilter) }, key = HomeSection::id) { section ->
                TrackCarousel(section.title, section.subtitle, section.tracks, state)
            }

            if (!ui.homeLoading && ui.homeSections.isEmpty() && ui.recentTracks.isEmpty()) {
                item {
                    EmptyNote(
                        "Nothing here yet",
                        "Search for something, or connect an account under Settings to see your own music.",
                    )
                }
            }
        }
    }
}

/**
 * A row of cards that scrolls sideways.
 *
 * The phone's answer to the desktop's shelf. Cards rather than rows because a horizontal strip is how a
 * phone shows "here are some things" without spending the whole screen on six of them.
 */
@Composable
private fun TrackCarousel(title: String, subtitle: String?, tracks: List<Track>, state: AppState) {
    if (tracks.isEmpty()) return
    Column(Modifier.padding(top = 14.dp)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            subtitle?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tracks, key = { it.queueKey }) { track ->
                Column(
                    Modifier
                        .width(132.dp)
                        .clickable { state.play(track, sourceQueue = tracks) },
                ) {
                    Artwork(track.artworkUrl, 132.dp, corner = 10.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        track.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artistLine.ifBlank { track.provider.displayName },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun HomeSection.matches(filter: ProviderFilter): Boolean = when (filter) {
    ProviderFilter.ALL -> true
    ProviderFilter.YOUTUBE_MUSIC -> provider.name.startsWith("YOUTUBE")
    ProviderFilter.SOUNDCLOUD -> provider.name == "SOUNDCLOUD"
}

private fun ProviderFilter.label(): String = when (this) {
    ProviderFilter.ALL -> "All"
    ProviderFilter.YOUTUBE_MUSIC -> "YouTube Music"
    ProviderFilter.SOUNDCLOUD -> "SoundCloud"
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..22 -> "Good evening"
    else -> "Still up"
}

// --- Search ---

@Composable
internal fun SearchScreen(state: AppState) {
    val ui by state.ui.collectAsState()
    val playback by state.playback.collectAsState()

    ScreenScaffold {
        ScreenTitle("Search")
        OutlinedTextField(
            ui.searchQuery,
            state::search,
            placeholder = { Text("YouTube Music and SoundCloud") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (ui.searchLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))

        val results = ui.searchResults.tracks
        when {
            ui.searchQuery.isBlank() -> EmptyNote(
                "Find something",
                "Both services answer a search without an account, so this works straight away.",
            )
            results.isEmpty() && !ui.searchLoading -> EmptyNote(
                "Nothing found",
                "No track matched \"${ui.searchQuery}\".",
            )
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results, key = { it.queueKey }) { track ->
                    TrackRow(
                        track,
                        state,
                        isCurrent = playback.track?.queueKey == track.queueKey,
                    ) { state.play(track, sourceQueue = results) }
                }
            }
        }
    }
}

// --- Library ---

@Composable
internal fun LibraryScreen(state: AppState) {
    val library by state.library.collectAsState()
    val downloads by state.downloadState.collectAsState()
    val playback by state.playback.collectAsState()

    // Opening the library is what asks for it; the state itself decides whether that means a fetch.
    androidx.compose.runtime.LaunchedEffect(Unit) { state.refreshLibrary() }

    val open = library.openPlaylist
    if (open != null) {
        PlaylistScreen(open, library.openPlaylistLoading, library.openPlaylistError, state)
        return
    }

    ScreenScaffold {
        ScreenTitle("Library", "Your playlists, and what is kept on this phone") {
            IconButton({ state.refreshLibrary(force = true) }) { Icon(Icons.Default.Refresh, "Reload") }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (downloads.entries.isNotEmpty() || downloads.active.isNotEmpty()) {
                item { DownloadsCard(downloads, state) }
            }

            library.notice?.let { notice ->
                item {
                    Text(
                        notice,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }

            if (library.localPlaylists.isNotEmpty()) {
                item { SectionHeading("Made in Spiceity") }
                items(library.localPlaylists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        title = playlist.title,
                        detail = "${playlist.trackCount} tracks · made here",
                        artworkUrl = null,
                    ) { state.openLocalPlaylist(playlist) }
                }
            }

            when {
                library.loading && library.playlists.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
                library.playlists.isEmpty() -> item {
                    EmptyNote(
                        "No playlists yet",
                        library.errorMessage
                            ?: "Connect YouTube Music, SoundCloud or Spotify under Settings to see yours here.",
                    )
                }
                else -> {
                    item { SectionHeading("From your accounts") }
                    items(library.playlists, key = { it.playlistKey }) { playlist ->
                        PlaylistRow(
                            title = playlist.title,
                            detail = listOfNotNull(
                                playlist.provider.displayName,
                                playlist.trackCount?.let { "$it tracks" },
                                playlist.ownerName,
                            ).joinToString(" · "),
                            artworkUrl = playlist.artworkUrl,
                        ) { state.openPlaylist(playlist) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun PlaylistRow(title: String, detail: String, artworkUrl: String?, open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artworkUrl, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One playlist, opened. Loads in two passes the way the desktop does, so it appears before it is complete. */
@Composable
private fun PlaylistScreen(playlist: Playlist, loading: Boolean, error: String?, state: AppState) {
    val playback by state.playback.collectAsState()

    ScreenScaffold {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(state::closePlaylist) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the library") }
            Column(Modifier.weight(1f)) {
                Text(playlist.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(playlist.provider.displayName, playlist.ownerName).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            if (playlist.tracks.isNotEmpty()) {
                IconButton({ state.playPlaylist(playlist) }) { Icon(Icons.Default.PlayArrow, "Play this playlist") }
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(20.dp))
        }

        when {
            loading && playlist.tracks.isEmpty() -> Box(
                Modifier.fillMaxWidth().padding(40.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) }

            playlist.tracks.isEmpty() -> EmptyNote("Nothing in here", "This playlist came back empty.")

            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(playlist.tracks, key = { it.queueKey }) { track ->
                    TrackRow(
                        track,
                        state,
                        isCurrent = playback.track?.queueKey == track.queueKey,
                    ) { state.playPlaylist(playlist, startAt = track) }
                }
            }
        }
    }
}

/** What is kept on the phone, and what is arriving. */
@Composable
private fun DownloadsCard(downloads: app.spiceity.downloads.DownloadsState, state: AppState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DownloadDone, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Available offline", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "${downloads.entries.size} tracks" +
                            if (downloads.active.isNotEmpty()) " · ${downloads.active.size} on the way" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                if (downloads.entries.isNotEmpty()) {
                    IconButton({ state.playDownloads() }) { Icon(Icons.Default.PlayArrow, "Play downloads") }
                }
            }
            downloads.active.forEach { job ->
                Spacer(Modifier.height(8.dp))
                Text(job.track.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                if (job.stage == DownloadStage.FAILED) {
                    Text(
                        job.detail ?: "Could not download this one.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { job.progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                }
            }
        }
    }
}

// --- Queue ---

@Composable
internal fun QueueScreen(state: AppState) {
    val queue by state.queue.state.collectAsState()
    val playback by state.playback.collectAsState()

    ScreenScaffold {
        ScreenTitle("Queue", "What is playing, and what follows") {
            if (queue.tracks.isNotEmpty()) {
                IconButton(state::clearQueue) { Icon(Icons.Default.Delete, "Clear the queue") }
            }
        }
        if (queue.tracks.isEmpty()) {
            EmptyNote("Nothing queued", "Play something and it will show up here.")
            return@ScreenScaffold
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(queue.tracks.size) { index ->
                val track = queue.tracks[index]
                TrackRow(
                    track,
                    state,
                    isCurrent = index == queue.currentIndex,
                    trailing = {
                        IconButton({ state.removeQueueItem(index) }) {
                            Icon(Icons.Default.Delete, "Remove from the queue", Modifier.size(18.dp))
                        }
                    },
                ) { state.jumpToQueueItem(index) }
            }
        }
    }
}

// --- The menu every track row carries ---

@Composable
internal fun TrackMenuButton(track: Track, state: AppState) {
    var open by remember { mutableStateOf(false) }
    val likes by state.likes.collectAsState()
    val downloads by state.downloadState.collectAsState()
    val onDisk = state.downloadableTrack(track)
    val job = downloads.jobFor(onDisk)
    val kept = downloads.isDownloaded(onDisk)

    Box {
        IconButton({ open = true }) {
            Icon(Icons.Default.MoreVert, "Track actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                onClick = { state.playNext(track); open = false },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                onClick = { state.addToQueue(track); open = false },
            )
            if (likes.supports(track)) {
                val liked = likes.isLiked(track)
                DropdownMenuItem(
                    text = { Text(if (liked) "Remove from likes" else "Like on ${track.provider.displayName}") },
                    leadingIcon = { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                    onClick = { state.toggleLike(track); open = false },
                )
            }
            when {
                job != null && job.stage != DownloadStage.FAILED -> DropdownMenuItem(
                    text = { Text("Downloading… ${(job.progress * 100).roundToInt()}%") },
                    leadingIcon = { Icon(Icons.Default.Downloading, null) },
                    onClick = { state.cancelDownload(onDisk.queueKey); open = false },
                )
                job != null -> DropdownMenuItem(
                    text = { Text("Download failed — try again") },
                    leadingIcon = { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { state.cancelDownload(onDisk.queueKey); state.downloadTrack(track); open = false },
                )
                kept -> DropdownMenuItem(
                    text = { Text("Remove download") },
                    leadingIcon = { Icon(Icons.Default.DownloadDone, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { state.deleteDownload(onDisk.queueKey); open = false },
                )
                else -> DropdownMenuItem(
                    text = { Text("Download for offline") },
                    leadingIcon = { Icon(Icons.Default.Download, null) },
                    onClick = { state.downloadTrack(track); open = false },
                )
            }
            DropdownMenuItem(
                text = { Text("Save a copy…") },
                leadingIcon = { Icon(Icons.Default.SaveAlt, null) },
                onClick = { state.exportTrack(track); open = false },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Copy link") },
                leadingIcon = { Icon(Icons.Default.Link, null) },
                onClick = { state.copyTrackLink(track); open = false },
            )
        }
    }
}
