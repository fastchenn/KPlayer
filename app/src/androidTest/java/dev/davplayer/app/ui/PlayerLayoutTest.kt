package dev.davplayer.app.ui

import android.content.Intent
import android.graphics.Rect
import android.view.Surface
import android.view.MotionEvent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.platform.app.InstrumentationRegistry
import dev.davplayer.app.MainActivity
import dev.davplayer.app.VideoTarget
import dev.davplayer.app.data.MediaEntry
import dev.davplayer.app.data.ServerConnection
import dev.davplayer.playback.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Tests actual Compose accessibility bounds after fullscreen orientation changes. */
class PlayerLayoutTest {
    @Test fun fullscreenSwipePreviewsOnceAndCannotTurnIntoDoubleSpeed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val engine = TestEngine(PlaybackPhase.PLAYING)
        val target = VideoTarget(ServerConnection.create("NAS", "http://127.0.0.1/dav/", "", ""),
            MediaEntry("http://127.0.0.1/dav/video.mkv", "手势验证.mkv", false))
        var downTime = 0L
        var x = 0f
        var y = 0f
        fun touch(action: Int, nextX: Float = x) {
            x = nextX
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            instrumentation.waitForIdleSync()
        }
        try {
            instrumentation.runOnMainSync { activity.setContent { DavTheme {
                val playback by engine.state.collectAsState()
                PlayerScreen(target, engine, playback, {})
            } } }
            click(node("全屏播放"))
            await("Fullscreen landscape missing") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            var width = 0f
            instrumentation.runOnMainSync { width = activity.window.decorView.width.toFloat(); y = activity.window.decorView.height / 2f }

            touch(MotionEvent.ACTION_DOWN, width / 2)
            Thread.sleep(700)
            val holdHint = bounds(node("2X"))
            assertEquals(2f, engine.state.value.speed)
            touch(MotionEvent.ACTION_MOVE, width * .75f)
            assertTrue(engine.seeks.isEmpty())
            assertEquals(2f, engine.state.value.speed)
            touch(MotionEvent.ACTION_UP)
            assertEquals(1f, engine.state.value.speed)

            touch(MotionEvent.ACTION_DOWN, width / 2)
            touch(MotionEvent.ACTION_MOVE, width * .75f)
            val seekHint = bounds(node("00:01:00/00:02:00"))
            assertEquals(holdHint.centerX(), seekHint.centerX())
            assertEquals(holdHint.top, seekHint.top)
            Thread.sleep(700)
            assertEquals(1f, engine.state.value.speed)
            assertTrue(engine.seeks.isEmpty())
            touch(MotionEvent.ACTION_UP)
            assertEquals(listOf(60_000L), engine.seeks)

            // Cancellation must discard the preview and restore the hint/controls.
            touch(MotionEvent.ACTION_DOWN, width / 2)
            touch(MotionEvent.ACTION_MOVE, width * .25f)
            node("00:00:00/00:02:00")
            touch(MotionEvent.ACTION_CANCEL)
            assertEquals(listOf(60_000L), engine.seeks)
            node("手势验证.mkv")
        } finally {
            touch(MotionEvent.ACTION_CANCEL)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun settingsShowsRealCacheSizeAndRequiresConfirmationToClear() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var cleared = false
        try {
            instrumentation.runOnMainSync { activity.setContent { DavTheme {
                SettingsScreen(dev.davplayer.app.AppState(playbackCacheReady = true, playbackCacheUsedBytes = 2_097_152),
                    {}, {}, {}, {}, { cleared = true }, {})
            } } }
            Thread.sleep(500)
            fun scroll(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isScrollable) return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                repeat(node.childCount) { if (scroll(node.getChild(it))) return true }
                return false
            }
            instrumentation.waitForIdleSync()
            await("Settings scroller missing") {
                instrumentation.uiAutomation.clearCache()
                scroll(instrumentation.uiAutomation.rootInActiveWindow)
            }
            node("2.00 MB")
            click(node("清除缓存"))
            node("将清除 2.00 MB 已缓存的视频数据，再次播放时会重新下载。")
            assertFalse(cleared)
            click(node("清除"))
            await("Clear callback missing") { cleared }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun wideFullscreenAlsoKeepsTransportCentered() {
        shell("wm size 1920x1080")
        try { fullscreenTransportIsCenteredAndCacheSpeedSharesTitleRow() }
        finally { shell("wm size reset") }
    }

    @Test fun fullscreenTransportIsCenteredAndCacheSpeedSharesTitleRow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val engine = TestEngine()
        val title = "布局验证.mkv"
        val target = VideoTarget(ServerConnection.create("NAS", "http://127.0.0.1/dav/", "", ""),
            MediaEntry("http://127.0.0.1/dav/video.mkv", title, false))
        try {
            instrumentation.runOnMainSync {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val playback = engine.state.value
                activity.setContent { DavTheme { PlayerScreen(target, engine, playback, {}) } }
            }
            click(node("全屏播放"))
            await("Fullscreen landscape missing") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            verify(activity, title, "landscape")
            click(node("旋转屏幕方向"))
            await("Fullscreen portrait missing") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            verify(activity, title, "portrait")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun verify(activity: MainActivity, title: String, mode: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(700)
        val play = bounds(node("播放"))
        val back = bounds(node("后退十秒"))
        val forward = bounds(node("前进十秒"))
        var width = 0
        var height = 0
        instrumentation.runOnMainSync { width = activity.window.decorView.width; height = activity.window.decorView.height }
        println("$mode viewport=$width x $height play=$play back=$back forward=$forward")
        assertTrue("$mode play center ${play.centerX()} differs from viewport ${width / 2}", abs(play.centerX() - width / 2) <= 2)
        assertTrue("Transport must stay below progress/options", play.centerY() > height * .75)
        assertTrue(abs(back.centerY() - play.centerY()) <= 2)
        assertTrue(abs(forward.centerY() - play.centerY()) <= 2)
        val speed = bounds(node("缓存速度 1.00 MB/s"))
        val filename = bounds(node(title))
        assertTrue("Cache speed left the title row", abs(speed.centerY() - filename.centerY()) <= 4)
        assertTrue("Cache speed must follow filename", speed.left >= filename.right)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "player-024-$mode.png")
        output.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        println("$mode viewport=$width x $height play=$play filename=$filename speed=$speed")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun node(label: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        try { await("Missing UI node: $label") {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
                repeat(node.childCount) { find(node.getChild(it))?.let { result -> return result } }
                return null
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.clearCache()
            found = find(automation.rootInActiveWindow)
            found != null
        } } catch (error: AssertionError) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            fun dump(node: AccessibilityNodeInfo?, depth: Int = 0) {
                if (node == null) return
                println("${" ".repeat(depth)}${node.className} text=${node.text} desc=${node.contentDescription}")
                repeat(node.childCount) { dump(node.getChild(it), depth + 1) }
            }
            dump(automation.rootInActiveWindow)
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "player-024-failure.png")
            automation.takeScreenshot()?.let { bitmap -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            throw error
        }
        return found!!
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) { if (condition()) return; Thread.sleep(50) }
        fail(message)
    }
    private class TestEngine(phase: PlaybackPhase = PlaybackPhase.PAUSED) : PlayerEngine {
        override val id = EngineId.MPV
        override val availability = EngineAvailability.Available
        override val capabilities = PlayerCapabilities(seek = true, speed = true)
        override val state = MutableStateFlow(PlaybackState(phase = phase, seekable = true,
            positionMs = 30_000, durationMs = 120_000, networkBytesPerSecond = 1_048_576, bufferedUntilMs = 60_000))
        override fun attachSurface(surface: Surface?) {}
        override fun setSurfaceSize(width: Int, height: Int) {}
        override fun open(request: PlaybackRequest) {}
        override fun play() {}
        override fun pause() {}
        override fun stop() {}
        val seeks = mutableListOf<Long>()
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        override fun setSpeed(speed: Float) { state.value = state.value.copy(speed = speed) }
        override fun selectTrack(type: TrackType, trackId: String?) {}
        override fun release() {}
    }
}
