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
import androidx.compose.ui.graphics.toPixelMap
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

/** Colours sampled from a cover, used to tint the Now Playing backdrop the way the artwork does. */
data class ArtworkPalette(val primary: Color, val secondary: Color)

private object PaletteCache {
    val palettes = ConcurrentHashMap<String, ArtworkPalette>()
}

private val DefaultPalette = ArtworkPalette(Color(0xFF6E4BD8), Color(0xFF2A1B45))

/**
 * Pulls the two colours that carry a cover: the most common vivid hue, and a deeper companion for the far end
 * of the gradient. Washed-out and near-black pixels are ignored, since they describe the background of the
 * artwork rather than its character.
 */
@Composable
fun rememberArtworkPalette(url: String?, provider: ProviderType): ArtworkPalette {
    val palette by produceState(
        initialValue = url?.let { PaletteCache.palettes[it] } ?: DefaultPalette,
        key1 = url,
    ) {
        val key = url ?: return@produceState
        PaletteCache.palettes[key]?.let { value = it; return@produceState }
        val image = ArtworkCache.images[key] ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { extractPalette(image) }.getOrDefault(DefaultPalette)
        }.also { PaletteCache.palettes[key] = it }
    }
    return palette
}

private fun extractPalette(image: ImageBitmap): ArtworkPalette {
    val pixels = image.toPixelMap()
    val step = maxOf(1, minOf(image.width, image.height) / 48)
    // Twelve hue buckets is enough to separate "pink cover" from "teal cover" without chasing gradients.
    val buckets = Array(12) { floatArrayOf(0f, 0f, 0f, 0f) }
    var y = 0
    while (y < image.height) {
        var x = 0
        while (x < image.width) {
            val colour = pixels[x, y]
            val max = maxOf(colour.red, colour.green, colour.blue)
            val min = minOf(colour.red, colour.green, colour.blue)
            val saturation = if (max <= 0f) 0f else (max - min) / max
            if (max > 0.15f && max < 0.97f && saturation > 0.22f) {
                val bucket = buckets[(hueOf(colour.red, colour.green, colour.blue) / 30f).toInt().coerceIn(0, 11)]
                bucket[0] += colour.red
                bucket[1] += colour.green
                bucket[2] += colour.blue
                bucket[3] += 1f
            }
            x += step
        }
        y += step
    }
    val ranked = buckets.filter { it[3] > 0f }.sortedByDescending { it[3] }
    if (ranked.isEmpty()) return DefaultPalette
    val primary = ranked.first().let { Color(it[0] / it[3], it[1] / it[3], it[2] / it[3]) }
    val secondary = ranked.getOrNull(1)?.let { Color(it[0] / it[3], it[1] / it[3], it[2] / it[3]) }
        ?: Color(primary.red * .45f, primary.green * .45f, primary.blue * .45f)
    return ArtworkPalette(primary, secondary)
}

private fun hueOf(red: Float, green: Float, blue: Float): Float {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    if (delta <= 0f) return 0f
    val hue = when (max) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }
    return if (hue < 0f) hue + 360f else hue
}
