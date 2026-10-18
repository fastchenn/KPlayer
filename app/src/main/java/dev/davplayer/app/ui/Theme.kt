package dev.davplayer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CanvasColor = Color(0xFF101715)
val PanelColor = Color(0xFF1B2521)
val MutedColor = Color(0xFF94A59D)
val MintColor = Color(0xFFA5E6CD)
val LineColor = Color(0xFF2B3832)
val WarmColor = Color(0xFFE8CBA0)

@Composable
fun DavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MintColor, onPrimary = Color(0xFF10372A),
            primaryContainer = Color(0xFF294B3D), onPrimaryContainer = MintColor,
            background = CanvasColor, onBackground = Color(0xFFE9F0EB),
            surface = PanelColor, onSurface = Color(0xFFE9F0EB),
            surfaceVariant = Color(0xFF25322B), onSurfaceVariant = MutedColor,
            outline = Color(0xFF61766A), outlineVariant = LineColor,
            secondary = WarmColor, error = Color(0xFFFFB4AB),
        ),
        content = content,
    )
}
