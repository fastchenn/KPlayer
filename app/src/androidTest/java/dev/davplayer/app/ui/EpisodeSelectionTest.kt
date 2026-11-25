package dev.davplayer.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.davplayer.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EpisodeSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun episodeSwitchPreservesPlayerModeAndHomeTabsWork() {
        compose.onNodeWithText("媒体库").assertIsSelected()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("设置").assertIsSelected()
        compose.onNodeWithText("最近").performClick()
        compose.onNodeWithText("最近").assertIsSelected()
        compose.onNodeWithText("媒体库").performClick()

        compose.onNodeWithText("先逛逛示例媒体库").performScrollTo().performClick()
        fun episode(number: String) = "山间慢行 · 第 $number 集.mkv"
        fun episodeChoice(number: String) = compose.onNode(hasText(episode(number)) and hasClickAction())
        fun waitForTitle(title: String) {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        }
        waitForTitle("剧集")
        compose.onNodeWithText("剧集").performClick()
        waitForTitle(episode("01"))
        compose.onNodeWithText(episode("01")).performClick()

        val picker = compose.onNodeWithContentDescription("选集")
        val enter = compose.onNodeWithContentDescription("全屏播放")
        val subtitles = compose.onNodeWithText("字幕")
        assertTrue(subtitles.getUnclippedBoundsInRoot().right <= picker.getUnclippedBoundsInRoot().left)
        assertTrue(picker.getUnclippedBoundsInRoot().right <= enter.getUnclippedBoundsInRoot().left)
        picker.performClick()
        episodeChoice("01").assertIsSelected()
        episodeChoice("02").performClick()
        waitForTitle(episode("02"))
        enter.performClick()

        compose.onNodeWithContentDescription("旋转屏幕方向").assertDoesNotExist()
        val exitButtons = compose.onAllNodesWithContentDescription("退出全屏")
        compose.waitUntil(10_000) { exitButtons.fetchSemanticsNodes().size == 2 }
        val exit = exitButtons[1]
        assertTrue(subtitles.getUnclippedBoundsInRoot().right < picker.getUnclippedBoundsInRoot().left)
        assertTrue(picker.getUnclippedBoundsInRoot().right <= exit.getUnclippedBoundsInRoot().left)
        picker.performClick()
        episodeChoice("02").assertIsSelected()
        episodeChoice("03").performClick()
        waitForTitle(episode("03"))
        enter.assertDoesNotExist()
        exit.performClick()
        enter.assertIsDisplayed()
    }
}
