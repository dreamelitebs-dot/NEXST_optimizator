package ru.booster.network.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Cyberpunk / gamer palette ───────────────────────────────────────────────
val NeonCyan = Color(0xFF00E5FF)
val NeonMagenta = Color(0xFFFF007A)
val NeonPurple = Color(0xFF9D4EDD)
val NeonGreen = Color(0xFF00FF9C)
val CyberBlack = Color(0xFF07090F)
val PanelDark = Color(0xFF0D1220)
val PanelElevated = Color(0xFF131A2B)
val GridLine = Color(0xFF1C2740)
val TextPrimary = Color(0xFFE6F1FF)
val TextSecondary = Color(0xFF8CA3C7)
val DangerRed = Color(0xFFFF3B5C)
val WarnAmber = Color(0xFFFFB300)

private val CyberColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = CyberBlack,
    primaryContainer = PanelElevated,
    onPrimaryContainer = NeonCyan,
    secondary = NeonMagenta,
    onSecondary = Color.White,
    secondaryContainer = PanelDark,
    onSecondaryContainer = NeonMagenta,
    tertiary = NeonPurple,
    background = CyberBlack,
    onBackground = TextPrimary,
    surface = PanelDark,
    onSurface = TextPrimary,
    surfaceVariant = PanelElevated,
    onSurfaceVariant = TextSecondary,
    outline = GridLine,
    error = DangerRed,
    onError = Color.White
)

private val CyberTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        letterSpacing = 2.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        letterSpacing = 1.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 1.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = 0.5.sp
    ),
    bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp
    )
)

private val CyberShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun NetworkBoosterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CyberColorScheme,
        typography = CyberTypography,
        shapes = CyberShapes,
        content = content
    )
}

/** Ping color coding used across cards & HUD widgets. */
fun pingColor(pingMs: Int): Color = when {
    pingMs < 0 -> DangerRed          // lost
    pingMs <= 40 -> NeonGreen
    pingMs <= 90 -> NeonCyan
    pingMs <= 150 -> WarnAmber
    else -> DangerRed
}
