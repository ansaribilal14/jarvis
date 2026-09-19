package com.jarvis.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// =============================================================================
// v2.0 Material 3 Expressive theme: expanded tonal roles, dynamic color on
// Android 12+ (with the JARVIS brand as fallback), and the big-shape expressive
// geometry (28-32dp large radii) that defines Android 16+ system UI.
// =============================================================================

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00201B),
    primaryContainer = Color(0xFF0E3B33),
    onPrimaryContainer = AquaHi,
    secondary = AccentDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF123A36),
    onSecondaryContainer = Color(0xFFB5EBE2),
    tertiary = Plasma,
    onTertiary = Color(0xFF241A54),
    tertiaryContainer = Color(0xFF3A2F73),
    onTertiaryContainer = Color(0xFFE2DEFF),
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceHi,
    onSurfaceVariant = DarkTextDim,
    surfaceContainer = Color(0xFF121922),
    surfaceContainerHigh = DarkSurfaceHi,
    surfaceContainerHighest = Color(0xFF1C2530),
    outline = DarkOutline,
    outlineVariant = Color(0xFF1A232E),
    error = Danger,
    errorContainer = Color(0xFF3A1113),
    onErrorContainer = Color(0xFFF6B4AE),
)

private val LightScheme = lightColorScheme(
    primary = AccentDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F2EA),
    onPrimaryContainer = Color(0xFF00332C),
    secondary = Color(0xFF0B6B60),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2F1EA),
    onSecondaryContainer = Color(0xFF003730),
    tertiary = PlasmaDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5DEFF),
    onTertiaryContainer = Color(0xFF24005B),
    background = LightBg,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceHi,
    onSurfaceVariant = LightTextDim,
    surfaceContainer = Color(0xFFF0F4F3),
    surfaceContainerHigh = Color(0xFFE9EEEE),
    surfaceContainerHighest = Color(0xFFE2E8E8),
    outline = LightOutline,
    outlineVariant = Color(0xFFE3E9E7),
    error = Danger,
    errorContainer = Color(0xFFFCE0DD),
    onErrorContainer = Color(0xFF410002),
)

/** Expressive geometry: large radii everywhere (the M3 "big shapes" look). */
val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun JarvisTheme(themeMode: String = "SYSTEM", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemInDarkTheme()
    }
    // Material You dynamic color on Android 12+ keeps JARVIS native to the
    // system palette; brand scheme everywhere else (and when the user forces a mode).
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && themeMode == "SYSTEM"
    val scheme = if (dynamic) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) DarkScheme else LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = JarvisShapes,
        content = content,
    )
}
