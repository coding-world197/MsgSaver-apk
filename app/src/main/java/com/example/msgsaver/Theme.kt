package com.example.msgsaver

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B7A66), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE5), onPrimaryContainer = Color(0xFF00382D),
    secondary = Color(0xFF4A635C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEFE5), onSecondaryContainer = Color(0xFF00382D),
    background = Color(0xFFF4F8F6), onBackground = Color(0xFF161D1B),
    surface = Color.White, onSurface = Color(0xFF161D1B),
    surfaceVariant = Color(0xFFDCE5E0), onSurfaceVariant = Color(0xFF404945),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F5F2),
    surfaceContainer = Color(0xFFEAF0ED), surfaceContainerHigh = Color(0xFFE4EAE7),
    surfaceContainerHighest = Color(0xFFDEE4E1),
    outline = Color(0xFF707975),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD8B4), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005143), onPrimaryContainer = Color(0xFFCDEFE5),
    secondary = Color(0xFFB1CCC3), onSecondary = Color(0xFF1C352E),
    secondaryContainer = Color(0xFF005143), onSecondaryContainer = Color(0xFFCDEFE5),
    background = Color(0xFF0E1513), onBackground = Color(0xFFDDE4E0),
    surface = Color(0xFF151D1B), onSurface = Color(0xFFDDE4E0),
    surfaceVariant = Color(0xFF3F4945), onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceContainerLowest = Color(0xFF090F0E), surfaceContainerLow = Color(0xFF161D1B),
    surfaceContainer = Color(0xFF1A211F), surfaceContainerHigh = Color(0xFF242B29),
    surfaceContainerHighest = Color(0xFF2F3634),
    outline = Color(0xFF89938E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF5A1A17), onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun ChatRewindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
