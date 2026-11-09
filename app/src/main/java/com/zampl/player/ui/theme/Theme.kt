package com.zampl.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF4F8CFF)
val StarColor = Color(0xFFFFC53D)
val FlagColor = Color(0xFFFF5A5F)
val SubText = Color(0xFF9AA1AD)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFF0F1115),
    surface = Color(0xFF0F1115),
    surfaceContainer = Color(0xFF1A1D23),
    surfaceContainerHigh = Color(0xFF1F232A),
    surfaceContainerHighest = Color(0xFF242830),
    surfaceVariant = Color(0xFF242830),
    onSurfaceVariant = SubText,
    secondaryContainer = Color(0xFF2A3550),
)

@Composable
fun ZamplTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
