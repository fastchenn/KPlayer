package dev.davplayer.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.davplayer.app.MainActivity
import dev.davplayer.app.VideoTarget
import dev.davplayer.app.data.MediaEntry
import dev.davplayer.app.data.ServerConnection
import dev.davplayer.playback.api.PlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FullscreenLockTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fullscreenKeepsOriginalWidthAndLockBlocksOtherControls() {
        val engine = PlayerLayoutTest.TestEngine()
        val target = VideoTarget(
            ServerConnection.create("NAS", "http://127.0.0.1/dav/", "", ""),
            MediaEntry("http://127.0.0.1/dav/video.mkv", "锁屏验证.mkv", false),
        )
        compose.runOnIdle {
            compose.activity.setContent {
                DavTheme {
                    val playback by engine.state.collectAsState()
                    PlayerScreen(target, engine, playback, {}, {}, {})
                }
            }
        }

        val enter = compose.onNodeWithContentDescription("全屏播放")
        enter.performClick()
        val exitButtons = compose.onAllNodesWithContentDescription("退出全屏")
        compose.waitUntil(10_000) {
            val viewport = compose.onRoot().getUnclippedBoundsInRoot()
            viewport.right - viewport.left > viewport.bottom - viewport.top && exitButtons.fetchSemanticsNodes().size == 2
        }
        val progress = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        val lock = compose.onNodeWithContentDescription("锁定播放控件")
        val unlock = compose.onNodeWithContentDescription("解锁播放控件")
        val episodes = compose.onNodeWithContentDescription("选集")
        val viewport = compose.onRoot().getUnclippedBoundsInRoot()
        val lockBounds = lock.getUnclippedBoundsInRoot()
        val elapsed = compose.onNodeWithText("00:30").getUnclippedBoundsInRoot()
        val duration = compose.onNodeWithText("02:00").getUnclippedBoundsInRoot()
        assertEquals(20f, (elapsed.left - viewport.left).value, 1f)
        assertEquals(20f, (viewport.right - duration.right).value, 1f)
        assertEquals(20f, (viewport.right - exitButtons[1].getUnclippedBoundsInRoot().right).value, 1f)
        assertTrue(lockBounds.right <= episodes.getUnclippedBoundsInRoot().left)

        lock.performClick()
        unlock.assertDoesNotExist()
        compose.runOnIdle { engine.state.value = engine.state.value.copy(phase = PlaybackPhase.PLAYING) }
        compose.onRoot().performTouchInput {
            swipe(center, center + Offset(width / 4f, 0f))
            longClick(center)
        }
        compose.runOnIdle {
            assertTrue(engine.seeks.isEmpty())
            assertTrue(engine.speedChanges.isEmpty())
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        enter.assertDoesNotExist()
        compose.onRoot().performTouchInput { click(center) }
        unlock.assertIsDisplayed()
        assertEquals(lockBounds, unlock.getUnclippedBoundsInRoot())
        lock.assertDoesNotExist()
        episodes.assertDoesNotExist()
        progress.assertDoesNotExist()
        compose.onNodeWithContentDescription("暂停").assertDoesNotExist()
        exitButtons.assertCountEquals(0)
        unlock.performClick()
        lock.assertIsDisplayed()
        progress.assertIsDisplayed()
        episodes.assertIsDisplayed()
    }
}
