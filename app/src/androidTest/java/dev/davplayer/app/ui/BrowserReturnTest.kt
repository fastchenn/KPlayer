package dev.davplayer.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.davplayer.app.AppViewModel
import dev.davplayer.app.MainActivity
import dev.davplayer.app.data.ServerConnection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.io.File
import kotlin.math.floor
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Exercises the real app, including ViewModel requests and Compose disposal/restoration. */
class BrowserReturnTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private val list get() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))

    @Test fun folderAndVideoReturnRestoreExactViewportAndControlsWithoutReloading() {
        DirectoryFixture().use { origin ->
            open(origin)
            val rootId = model.state.value.browser!!.pageId
            compose.onNodeWithContentDescription("搜索当前目录").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Folder")
            hideKeyboard()
            compose.onNodeWithContentDescription("改为名称降序").performClick()
            scrollTo(30)
            val folder = "Folder 068"
            val folderTop = top(folder)
            compose.onNodeWithText(folder).performClick()
            loaded("Folder068/")
            // Each newly entered directory starts with its own default controls and position.
            compose.onNodeWithText("Video 000.mp4").assertIsDisplayed()
            compose.onNodeWithContentDescription("改为名称降序").assertExists()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            val childId = model.state.value.browser!!.pageId
            assertNotEquals(rootId, childId)
            compose.onNodeWithText("视频").performClick()
            compose.onNodeWithContentDescription("搜索当前目录").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Video")
            hideKeyboard()
            scrollTo(20)
            val video = "Video 021.mp4"
            val videoTop = top(video)
            compose.onNodeWithText(video).performClick()
            compose.waitUntil(10_000) { model.state.value.video != null }
            compose.onNodeWithContentDescription("返回目录").performClick()
            loaded("Folder068/")
            assertEquals(childId, model.state.value.browser!!.pageId)
            assertEquals(videoTop, top(video), 1f)
            compose.onNode(hasSetTextAction()).assertTextEquals("Video")
            compose.onNode(hasText("视频") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
            assertEquals(2, origin.requests.get())

            compose.onNodeWithContentDescription("返回上一级").performClick()
            loaded("dav/")
            assertEquals(rootId, model.state.value.browser!!.pageId)
            assertEquals(folderTop, top(folder), 1f)
            compose.onNode(hasSetTextAction()).assertTextEquals("Folder")
            compose.onNodeWithContentDescription("改为名称升序").assertExists()
            assertEquals("Returning must not issue PROPFIND", 2, origin.requests.get())

            compose.onNodeWithContentDescription("刷新目录").performClick()
            compose.waitUntil(10_000) { origin.requests.get() == 3 && model.state.value.browser?.loading == false }
            compose.waitForIdle()
            assertEquals(rootId, model.state.value.browser!!.pageId)
            assertEquals(folderTop, top(folder), 1f)
            compose.onNode(hasSetTextAction()).assertTextEquals("Folder")

            // A new browsing session must not reuse an old account's page or search state.
            compose.runOnIdle { model.leaveBrowser() }
            open(origin)
            assertNotEquals(rootId, model.state.value.browser!!.pageId)
            compose.onNodeWithText("Folder 000").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithContentDescription("改为名称降序").assertExists()
        }
    }

    @Test fun breadcrumbReturnAndOfflineParentRestoreKeepTheOriginalRow() {
        DirectoryFixture().use { origin ->
            open(origin)
            scrollTo(40)
            val folder = "Folder 041"
            val originalTop = top(folder)
            compose.onNodeWithText(folder).performClick()
            loaded("Folder041/")
            val root = origin.root
            origin.close()
            compose.runOnIdle { model.openBreadcrumb(root) }
            loaded("dav/")
            assertEquals(originalTop, top(folder), 1f)
            assertEquals(2, origin.requests.get())
        }
    }

    @Test fun partialRowsStayBelowHeaderAndReturnKeepsPosition() = verifyViewport(1f)

    @Test fun largeTextAndSearchHeaderStillClipPartiallyScrolledRows() = verifyViewport(1.6f)

    private data class HeaderPixels(val width: Int, val height: Int, val pixels: IntArray)

    private fun verifyViewport(fontScale: Float) {
        compose.runOnIdle {
            val player = model
            val density = compose.activity.resources.displayMetrics.density
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    DavTheme { DavPlayerApp(player) }
                }
            }
        }
        DirectoryFixture(longNames = true).use { origin ->
            open(origin)
            compose.mainClock.advanceTimeBy(1_000)
            val header = captureHeader("font-$fontScale-before")
            list.performScrollToIndex(20)
            list.performTouchInput { swipe(center, Offset(center.x, center.y - 90f), 1_000) }
            compose.waitForIdle()
            val row = "Folder 021 中文长名称用于验证文件夹标题换行与列表绘制边界，返回页面后保持原位置"
            val rowTop = top(row)
            assertHeaderUnchanged(header, "font-$fontScale-scrolled")
            compose.runOnIdle { model.openEntry(model.state.value.browser!!.entries[21]) }
            loaded("Folder021/")
            compose.runOnIdle { model.back() }
            loaded("dav/")
            assertEquals(rowTop, top(row), 1f)
            assertHeaderUnchanged(header, "font-$fontScale-returned")

            compose.onNodeWithContentDescription("搜索当前目录").performClick()
            compose.mainClock.advanceTimeBy(1_000)
            val expandedHeader = captureHeader("font-$fontScale-search")
            list.performScrollToIndex(40)
            list.performTouchInput { swipe(center, Offset(center.x, center.y - 90f), 1_000) }
            compose.waitForIdle()
            assertHeaderUnchanged(expandedHeader, "font-$fontScale-search-scrolled")
        }
    }

    private fun assertHeaderUnchanged(expected: HeaderPixels, name: String) {
        val actual = captureHeader(name)
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertArrayEquals("Rows painted outside the viewport: $name", expected.pixels, actual.pixels)
    }

    private fun captureHeader(name: String): HeaderPixels {
        compose.waitForIdle()
        val top = compose.onNodeWithTag("browser-content-viewport").fetchSemanticsNode().boundsInRoot.top
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val height = floor(top).toInt().coerceIn(1, bitmap.height)
        val pixels = IntArray(bitmap.width * height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, height)
        val folder = File(compose.activity.getExternalFilesDir(null), "browser-layout").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return HeaderPixels(bitmap.width, height, pixels)
    }

    private fun open(origin: DirectoryFixture) {
        compose.waitUntil(10_000) { !model.state.value.initializing && !model.state.value.historyLoading }
        compose.runOnIdle { model.openServer(ServerConnection.create("Browser fixture", origin.root, "", "")) }
        loaded("dav/")
    }

    private fun loaded(suffix: String) {
        compose.waitUntil(10_000) {
            model.state.value.browser?.let { !it.loading && it.error == null && it.url.endsWith(suffix) } == true
        }
        compose.waitForIdle()
    }

    private fun hideKeyboard() {
        compose.runOnIdle {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        compose.waitForIdle()
    }

    private fun scrollTo(index: Int) {
        list.performScrollToIndex(index)
        // Include a pixel offset, not just restoration to the first row of a page.
        list.performTouchInput { swipe(center, Offset(center.x, center.y - 32f), 1_000) }
        compose.waitForIdle()
    }

    private fun top(label: String): Float = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top

    private class DirectoryFixture(private val longNames: Boolean = false) : Closeable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val root = "http://127.0.0.1:${socket.localPort}/dav/"
        val requests = AtomicInteger()
        private val worker = thread(isDaemon = true, name = "browser-directory-fixture") {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: Exception) { break }
                connection.use { client ->
                    client.soTimeout = 5_000
                    val input = client.getInputStream().bufferedReader()
                    val request = input.readLine().orEmpty().split(' ')
                    while (!input.readLine().isNullOrEmpty()) { }
                    val method = request.firstOrNull()
                    val path = request.getOrNull(1).orEmpty()
                    val body = if (method == "PROPFIND") {
                        requests.incrementAndGet()
                        buildString {
                            append("<d:multistatus xmlns:d=\"DAV:\">")
                            repeat(100) { index ->
                                val number = index.toString().padStart(3, '0')
                                val directory = path == "/dav/"
                                val name = if (directory) "Folder $number" + if (longNames) " 中文长名称用于验证文件夹标题换行与列表绘制边界，返回页面后保持原位置" else ""
                                    else "Video $number.mp4"
                                val href = path + if (directory) "Folder$number/" else "Video$number.mp4"
                                append("<d:response><d:href>$href</d:href><d:propstat><d:prop>")
                                append("<d:displayname>$name</d:displayname><d:resourcetype>")
                                if (directory) append("<d:collection/>")
                                append("</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                            }
                            append("</d:multistatus>")
                        }
                    } else ""
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val status = if (method == "PROPFIND") "207 Multi-Status" else "404 Not Found"
                    runCatching {
                        client.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }
        override fun close() { socket.close(); worker.join(6_000) }
    }
}
