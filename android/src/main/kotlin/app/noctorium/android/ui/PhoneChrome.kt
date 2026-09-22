package app.noctorium.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.settings.BadgePolicy
import app.noctorium.settings.CardSize

/**
 * How wide a card is, translated for a phone.
 *
 * The desktop's numbers — 140, 172, 208 — are sized against a window a metre from your face. On a screen
 * held at arm's length the same widths leave one and a half cards visible, so the choice is kept and the
 * scale is not.
 */
internal fun CardSize.phoneWidth(): Dp = when (this) {
    CardSize.COMPACT -> 112.dp
    CardSize.COMFORTABLE -> 134.dp
    CardSize.LARGE -> 160.dp
}

/**
 * Whether a row should name the services in it.
 *
 * "Only when mixed" is the interesting one and the default: a shelf of nothing but YouTube Music says
 * nothing useful by stamping YTM on all six, whereas a shelf with one SoundCloud track among them very
 * much does.
 */
internal fun BadgePolicy.showsFor(tracks: List<Track>): Boolean = when (this) {
    BadgePolicy.ALWAYS -> true
    BadgePolicy.NEVER -> false
    BadgePolicy.AUTO -> tracks.map { it.provider }.distinct().size > 1
}

/** The small mark in the corner of a card saying where a track came from. */
@Composable
internal fun ProviderBadge(track: Track, modifier: Modifier = Modifier) {
    Surface(
        color = track.provider.badgeColour().copy(alpha = .92f),
        shape = RoundedCornerShape(5.dp),
        modifier = modifier,
    ) {
        Text(
            track.provider.badgeLabel(),
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** The same colours the desktop badges use, so a card reads identically on either screen. */
private fun ProviderType.badgeColour(): Color = when (this) {
    ProviderType.YOUTUBE_MUSIC -> Color(0xFF8B5CF6)
    ProviderType.YOUTUBE_VIDEO -> Color(0xFFCC0000)
    ProviderType.SOUNDCLOUD -> Color(0xFFFF7300)
    ProviderType.SPOTIFY -> Color(0xFF1DB954)
    ProviderType.LOCAL -> Color(0xFF5A5A6E)
}

private fun ProviderType.badgeLabel(): String = when (this) {
    ProviderType.YOUTUBE_MUSIC -> "YTM"
    ProviderType.YOUTUBE_VIDEO -> "YT"
    ProviderType.SOUNDCLOUD -> "SC"
    ProviderType.SPOTIFY -> "SP"
    ProviderType.LOCAL -> "FILE"
}
