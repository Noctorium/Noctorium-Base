package app.spice.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.spice.domain.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

private object ArtworkCache {
    val images = ConcurrentHashMap<String, ImageBitmap>()
}

@Composable
fun RemoteArtwork(
    url: String?,
    provider: ProviderType,
    modifier: Modifier = Modifier,
) {
    val artwork by produceState<ImageBitmap?>(initialValue = url?.let(ArtworkCache.images::get), key1 = url) {
        if (value == null && !url.isNullOrBlank()) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URI(url).toURL().openConnection().apply {
                        connectTimeout = 5_000
                        readTimeout = 8_000
                        setRequestProperty("User-Agent", "Spice/0.1")
                    }
                    connection.getInputStream().use { stream ->
                        SkiaImage.makeFromEncoded(stream.readBytes()).toComposeImageBitmap()
                    }.also { ArtworkCache.images[url] = it }
                }.getOrNull()
            }
        }
    }

    Box(
        modifier.background(
            Brush.linearGradient(
                if (provider == ProviderType.YOUTUBE_MUSIC || provider == ProviderType.YOUTUBE_VIDEO) {
                    listOf(Color(0xFF1A002E), Color(0xFF5B21B6))
                } else {
                    listOf(Color(0xFF10001F), Color(0xFF9333EA))
                },
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        artwork?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } ?: Icon(
            Icons.Default.GraphicEq,
            contentDescription = null,
            tint = Color.White.copy(alpha = .72f),
            modifier = Modifier.fillMaxSize(.32f),
        )
    }
}
