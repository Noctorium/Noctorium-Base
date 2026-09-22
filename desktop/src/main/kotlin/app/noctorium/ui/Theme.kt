package app.noctorium.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val AmoledBlack = Color(0xFF000000)
val NoctoriumPanel = Color(0xFF07050A)
val NoctoriumPurple = Color(0xFFB47CFF)
val NoctoriumPurpleStrong = Color(0xFF8B5CF6)
val NoctoriumPurpleDark = Color(0xFF2A1248)
val NoctoriumLavender = Color(0xFFD8B4FE)

val NoctoriumColors = darkColorScheme(
    primary = NoctoriumPurple,
    onPrimary = Color(0xFF16002D),
    primaryContainer = NoctoriumPurpleDark,
    onPrimaryContainer = NoctoriumLavender,
    secondary = NoctoriumLavender,
    onSecondary = Color(0xFF1D0635),
    secondaryContainer = Color(0xFF241638),
    background = AmoledBlack,
    surface = AmoledBlack,
    surfaceVariant = Color(0xFF15101C),
    onSurface = Color(0xFFF8F4FF),
    onSurfaceVariant = Color(0xFFCFC5DA),
    outline = Color(0xFF594B69),
    error = Color(0xFFFF6B81),
    errorContainer = Color(0xFF3D0713),
)

/** Blends toward [other]; used to derive a whole scheme from one accent colour. */
private fun Color.mix(other: Color, ratio: Float): Color = Color(
    red = red + (other.red - red) * ratio,
    green = green + (other.green - green) * ratio,
    blue = blue + (other.blue - blue) * ratio,
)

/**
 * Builds the palette from a single accent and a background. Containers and outlines are derived rather than
 * hand-picked so any accent — including one sampled from the cover art — produces a coherent scheme.
 */
fun noctoriumColorScheme(accent: Color, background: Color) = darkColorScheme(
    primary = accent,
    onPrimary = accent.mix(Color.Black, .84f),
    primaryContainer = accent.mix(Color.Black, .74f),
    onPrimaryContainer = accent.mix(Color.White, .58f),
    secondary = accent.mix(Color.White, .38f),
    onSecondary = accent.mix(Color.Black, .86f),
    secondaryContainer = accent.mix(background, .82f),
    background = background,
    surface = background,
    surfaceVariant = background.mix(accent, .12f),
    onSurface = Color(0xFFF8F4FF),
    onSurfaceVariant = Color(0xFFCFC5DA),
    outline = accent.mix(Color(0xFF6B6478), .55f),
    error = Color(0xFFFF6B81),
    errorContainer = Color(0xFF3D0713),
)

/** Slightly lifted off black, for people who find pure AMOLED too stark. */
val NoctoriumDarkBackground = Color(0xFF0D0B12)
