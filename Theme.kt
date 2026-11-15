package com.eliosnotes.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF526C8A),
    onPrimary = Color.White,
    secondary = Color(0xFF667D98),
    background = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF1F4)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB6C9E0),
    secondary = Color(0xFFB5C7DC),
    background = Color(0xFF17191C),
    surface = Color(0xFF22262A),
    surfaceVariant = Color(0xFF30363C)
)

@Composable
fun EliosTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
