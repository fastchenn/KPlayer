package dev.davplayer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val LocalLightTheme = staticCompositionLocalOf { false }

val CanvasColor: Color @Composable get() = MaterialTheme.colorScheme.background
val PanelColor: Color @Composable get() = MaterialTheme.colorScheme.surface
val MutedColor: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val MintColor: Color @Composable get() = MaterialTheme.colorScheme.primary
val LineColor: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val WarmColor: Color @Composable get() = MaterialTheme.colorScheme.secondary

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5E6CD), onPrimary = Color(0xFF10372A),
    primaryContainer = Color(0xFF294B3D), onPrimaryContainer = Color(0xFFA5E6CD),
    background = Color(0xFF101715), onBackground = Color(0xFFE9F0EB),
    surface = Color(0xFF1B2521), onSurface = Color(0xFFE9F0EB),
    surfaceVariant = Color(0xFF25322B), onSurfaceVariant = Color(0xFF94A59D),
    outline = Color(0xFF61766A), outlineVariant = Color(0xFF2B3832),
    secondary = Color(0xFFE8CBA0), tertiary = Color(0xFFBFC4F5), error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF22664F), onPrimary = Color(0xFFF7FBF4),
    primaryContainer = Color(0xFFCFE7D8), onPrimaryContainer = Color(0xFF184735),
    secondary = Color(0xFF78521D), onSecondary = Color(0xFFFFF8ED),
    secondaryContainer = Color(0xFFDCE8D9), onSecondaryContainer = Color(0xFF253E31),
    tertiary = Color(0xFF4F5689),
    background = Color(0xFFF2EFE7), onBackground = Color(0xFF222D26),
    surface = Color(0xFFFAF7EF), onSurface = Color(0xFF222D26),
    surfaceVariant = Color(0xFFE4E8DE), onSurfaceVariant = Color(0xFF506057),
    surfaceContainerLowest = Color(0xFFFAF7EF), surfaceContainerLow = Color(0xFFF5F2EA),
    surfaceContainer = Color(0xFFEFEEE5), surfaceContainerHigh = Color(0xFFEAECE2),
    surfaceContainerHighest = Color(0xFFE4E8DE),
    outline = Color(0xFF65756C), outlineVariant = Color(0xFFCED5C9),
    inverseSurface = Color(0xFF29372F), inverseOnSurface = Color(0xFFF4F4EA),
    inversePrimary = Color(0xFFA5E6CD),
)

@Composable
fun DavTheme(lightTheme: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLightTheme provides lightTheme) {
        MaterialTheme(colorScheme = if (lightTheme) LightColors else DarkColors, content = content)
    }
}
