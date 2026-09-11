package com.jarvis.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color0x062A25(),
    secondary = AccentDeep,
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceHi,
    onSurfaceVariant = DarkTextDim,
    outline = DarkOutline,
    error = Danger,
)

private val LightScheme = lightColorScheme(
    primary = AccentDeep,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = Accent,
    background = LightBg,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceHi,
    onSurfaceVariant = LightTextDim,
    outline = LightOutline,
    error = Danger,
)

private fun Color0x062A25() = androidx.compose.ui.graphics.Color(0xFF062A25)

@Composable
fun JarvisTheme(themeMode: String = "SYSTEM", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
