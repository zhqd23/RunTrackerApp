package com.run.tracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = GreenContainer,
    onPrimaryContainer = OnGreenContainer,
    secondary = BlueAccent,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = OrangeAccent,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    error = RedAccent,
    background = SurfaceLight,
    surface = androidx.compose.ui.graphics.Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = GreenLight,
    onPrimary = OnGreenContainer,
    primaryContainer = GreenDark,
    onPrimaryContainer = GreenContainer,
    secondary = BlueAccent,
    tertiary = OrangeAccent,
    background = SurfaceDark,
    surface = SurfaceDark
)

@Composable
fun RunTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
