package dev.davplayer.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal fun PlayerWindowMode(fullscreen: Boolean, portrait: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val original = remember(activity) { activity?.let(::PlayerWindowState) }
    DisposableEffect(original) {
        onDispose { original?.restore() }
    }
    LaunchedEffect(original, fullscreen, portrait) {
        if (fullscreen) original?.enterFullscreen(portrait) else original?.restore()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private class PlayerWindowState(private val activity: Activity) {
    private val orientation = activity.requestedOrientation
    private val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    private val barsBehavior = controller.systemBarsBehavior
    private val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
    private val statusBarVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
    private val navigationBarVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true

    fun enterFullscreen(portrait: Boolean) {
        activity.requestedOrientation = if (portrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    fun restore() {
        activity.requestedOrientation = orientation
        controller.systemBarsBehavior = barsBehavior
        if (statusBarVisible) controller.show(WindowInsetsCompat.Type.statusBars())
        else controller.hide(WindowInsetsCompat.Type.statusBars())
        if (navigationBarVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
        else controller.hide(WindowInsetsCompat.Type.navigationBars())
    }
}
