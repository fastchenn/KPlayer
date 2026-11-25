package dev.davplayer.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.davplayer.app.ui.DavPlayerApp
import dev.davplayer.app.ui.DavTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialLightTheme = model.state.value.useLightTheme
        val theme = model.state.map { it.useLightTheme }.distinctUntilChanged()
        val transparentBars = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = transparentBars, navigationBarStyle = transparentBars)
        updateSystemBarIcons(initialLightTheme)
        setContent {
            val lightTheme by theme.collectAsStateWithLifecycle(initialValue = initialLightTheme)
            LaunchedEffect(lightTheme) { updateSystemBarIcons(lightTheme) }
            DavTheme(lightTheme = lightTheme) { DavPlayerApp(model) }
        }
    }

    private fun updateSystemBarIcons(lightTheme: Boolean) {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.isAppearanceLightStatusBars = lightTheme
        bars.isAppearanceLightNavigationBars = lightTheme
    }
}
