package app.spiceity.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spiceity.domain.Track
import app.spiceity.playback.PlaybackState
import app.spiceity.playback.PlaybackStatus
import coil.compose.AsyncImage

/**
 * Spiceity on a phone.
 *
 * One screen, on purpose. Search reaches YouTube Music and SoundCloud without any account at all — their
 * own web players answer a search to anybody — so this is the part that is useful the moment the app is
 * installed, and it needs none of the sign-in machinery.
 *
 * The library, the likes and Spotify all need a session, which on Android means a WebView sign-in that is
 * not built yet. Adding a Library tab that could only ever say "sign in first" would be worse than not
 * having the tab.
 */
class MainActivity : ComponentActivity() {

    private lateinit var state: PhoneState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val application = application as SpiceityApplication
        state = PhoneState(application.providers, application.player)

        setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = SpiceityDark) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    SearchScreen(state)
                }
            }
        }
    }

    override fun onDestroy() {
        state.close()
        super.onDestroy()
    }
}

/**
 * The same near-black the desktop uses.
 *
 * Taken from Spiceity's own palette rather than from Material's dynamic colour, so the two look like one
 * application. On an OLED phone the background is also the cheapest possible thing to draw.
 */
private val SpiceityDark = darkColorScheme(
    primary = Color(0xFFB794F6),
    onPrimary = Color(0xFF1A0B2E),
    background = Color(0xFF08070C),
    onBackground = Color(0xFFF3F1F8),
    surface = Color(0xFF12111A),
    onSurface = Color(0xFFF3F1F8),
    surfaceVariant = Color(0xFF1B1926),
    onSurfaceVariant = Color(0xFFA7A2B8),
)

@Composable
private fun SearchScreen(state: PhoneState) {
    val search by state.search.collectAsState()
    val playback by state.playback.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (playback.track != null) PlayerBar(playback, state) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Text(
                "Spiceity",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 10.dp),
            )
            OutlinedTextField(
                search.query,
                state::setQuery,
                placeholder = { Text("Search YouTube Music and SoundCloud") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (search.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))

            search.message?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                )
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                items(search.results, key = { it.queueKey }) { track ->
                    TrackRow(
                        track,
                        isCurrent = playback.track?.queueKey == track.queueKey,
                    ) { state.play(track, search.results) }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, isCurrent: Boolean, play: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = play)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artworkUrl, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // An empty artist line means the service did not say, which is worth showing as such
                // rather than filling in with the provider's name.
                listOfNotNull(
                    track.artistLine.takeIf(String::isNotBlank) ?: "Unknown artist",
                    track.provider.displayName,
                    track.durationMs?.let(::formatDuration),
                ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Artwork(url: String?, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Icon(
                Icons.Default.MusicNote,
                null,
                Modifier.size(size / 2),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

@Composable
private fun PlayerBar(playback: PlaybackState, state: PhoneState) {
    val track = playback.track ?: return
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            // The line that says where in the track we are. Zero-width when the length is unknown, rather
            // than a full bar — a bar drawn to an unknown length reads as a finished track.
            LinearProgressIndicator(
                progress = {
                    val duration = playback.durationMs
                    if (duration > 0) (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(track.artworkUrl, 44.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        playback.errorMessage
                            ?: track.artistLine.takeIf(String::isNotBlank)
                            ?: "Unknown artist",
                        color = if (playback.errorMessage != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(state::skipPrevious) { Icon(Icons.Default.SkipPrevious, "Previous track") }
                IconButton(state::togglePlayback) {
                    when {
                        playback.status == PlaybackStatus.RESOLVING ->
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        playback.isPlaying -> Icon(Icons.Default.Pause, "Pause")
                        else -> Icon(Icons.Default.PlayArrow, "Play")
                    }
                }
                IconButton(state::skipNext) { Icon(Icons.Default.SkipNext, "Next track") }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
