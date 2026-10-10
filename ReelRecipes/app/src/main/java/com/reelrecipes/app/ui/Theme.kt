package com.reelrecipes.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Same palette as the web app: basil green, saffron, herb-tinted paper.
private val Light = lightColorScheme(
    primary = Color(0xFF2E6A4E), onPrimary = Color.White,
    secondary = Color(0xFFE3A72F), onSecondary = Color(0xFF2A1E05),
    background = Color(0xFFF1F4EE), onBackground = Color(0xFF1D2820),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1D2820),
    surfaceVariant = Color(0xFFE3EADF), onSurfaceVariant = Color(0xFF5D6B61),
    outline = Color(0xFFD9E0D6), error = Color(0xFFB3402C),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF6CC198), onPrimary = Color(0xFF0E1A13),
    secondary = Color(0xFFEDB547), onSecondary = Color(0xFF241904),
    background = Color(0xFF141A16), onBackground = Color(0xFFE7EDE6),
    surface = Color(0xFF1C241F), onSurface = Color(0xFFE7EDE6),
    surfaceVariant = Color(0xFF26302A), onSurfaceVariant = Color(0xFF9AA89E),
    outline = Color(0xFF2D3830), error = Color(0xFFE3826F),
)

val Display = FontFamily.Serif
val Mono = FontFamily.Monospace

@Composable
fun ReelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
