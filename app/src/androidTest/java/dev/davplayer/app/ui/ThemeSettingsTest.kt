package dev.davplayer.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.davplayer.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThemeSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun lightThemeAppliesImmediatelyAndSurvivesRelaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val key = "use_light_theme"
        val previous = preferences.getBoolean(key, false).takeIf { preferences.contains(key) }
        preferences.edit(commit = true) { remove(key) }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                compose.onNodeWithText("设置").performClick()
                val toggle = compose.onNodeWithText("浅色主题")
                toggle.assertIsOff()
                assertSystemBars(activity, lightBackground = false)
                toggle.performClick().assertIsOn()
                assertSystemBars(activity, lightBackground = true)
            }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                compose.onNodeWithText("设置").performClick()
                val toggle = compose.onNodeWithText("浅色主题")
                toggle.assertIsOn()
                assertSystemBars(activity, lightBackground = true)
                toggle.performClick().assertIsOff()
                assertSystemBars(activity, lightBackground = false)
            }
        } finally {
            preferences.edit(commit = true) {
                if (previous == null) remove(key) else putBoolean(key, previous)
            }
        }
    }

    private fun assertSystemBars(activity: ActivityScenario<MainActivity>, lightBackground: Boolean) {
        activity.onActivity {
            val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
            assertEquals(lightBackground, bars.isAppearanceLightStatusBars)
            assertEquals(lightBackground, bars.isAppearanceLightNavigationBars)
        }
    }
}
