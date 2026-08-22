package app.spice.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val AmoledBlack = Color(0xFF000000)
val SpicePanel = Color(0xFF07050A)
val SpicePurple = Color(0xFFB47CFF)
val SpicePurpleStrong = Color(0xFF8B5CF6)
val SpicePurpleDark = Color(0xFF2A1248)
val SpiceLavender = Color(0xFFD8B4FE)

val SpiceColors = darkColorScheme(
    primary = SpicePurple,
    onPrimary = Color(0xFF16002D),
    primaryContainer = SpicePurpleDark,
    onPrimaryContainer = SpiceLavender,
    secondary = SpiceLavender,
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
