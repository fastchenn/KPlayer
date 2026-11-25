package dev.davplayer.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.davplayer.app.MainActivity
import org.junit.Rule
import org.junit.Test

class EpisodeSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun canSelectAnotherEpisodeInBothModesAndStaysFullscreen() {
        fun episode(number: String) = "山间慢行 · 第 $number 集.mkv"
        fun waitForText(text: String) {
            compose.waitUntil(8_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }

        compose.onNodeWithText("先逛逛示例媒体库").performClick()
        waitForText("剧集")
        compose.onNodeWithText("剧集").performClick()
        waitForText(episode("01"))
        compose.onNodeWithText(episode("01")).performClick()

        compose.onNodeWithContentDescription("选集").performClick()
        compose.onNode(hasText(episode("01")) and isSelected()).assertIsDisplayed()
        compose.onNodeWithText(episode("02")).performClick()
        compose.onNodeWithText(episode("02")).assertIsDisplayed()
        compose.onNodeWithContentDescription("全屏播放").performClick()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithContentDescription("退出全屏").fetchSemanticsNodes().size == 2
        }
        compose.onNodeWithContentDescription("旋转屏幕方向").assertDoesNotExist()

        compose.onNodeWithContentDescription("选集").performClick()
        compose.onNode(hasText(episode("02")) and isSelected()).assertIsDisplayed()
        compose.onNodeWithText(episode("03")).performClick()
        compose.onNodeWithText(episode("03")).assertIsDisplayed()
        compose.onNodeWithContentDescription("全屏播放").assertDoesNotExist()
        compose.onNodeWithContentDescription("选集").assertIsDisplayed()
    }
}
