package dev.davplayer.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.davplayer.app.AppViewModel
import dev.davplayer.app.HomeTab
import dev.davplayer.app.MainActivity
import dev.davplayer.app.data.ConnectionStore
import dev.davplayer.app.data.ServerConnection
import dev.davplayer.app.data.WatchHistoryStore
import dev.davplayer.app.data.WatchRecord
import dev.davplayer.playback.api.PlaybackPhase
import dev.davplayer.playback.api.TrackType
import dev.davplayer.playback.mpv.PlaybackDiskCache
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/** Also run these phases separately with am force-stop between them to test process death. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PersistentPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun launch(): MainActivity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun model(activity: MainActivity): AppViewModel {
        var result: AppViewModel? = null
        onMain { result = ViewModelProvider(activity)[AppViewModel::class.java] }
        return result!!
    }

    @Test fun a_primeCacheThroughOpenListAndPersistHistory() {
        context.getSharedPreferences("player_settings", Application.MODE_PRIVATE).edit(commit = true) {
            putBoolean("prefer_hardware", false); putBoolean("playback_cache_enabled", true)
        }
        val fixture = avi()
        OpenListFixture(fixture).use { origin ->
            val server = ServerConnection.create("OpenList cache fixture", origin.root, "user", "pass", "cache-025-fixture")
            val store = ConnectionStore(context)
            store.save(store.load().filterNot { it.id == server.id } + server)
            val activity = launch()
            val player = model(activity)
            try {
                await("Initialization failed") { !player.state.value.initializing && !player.state.value.historyLoading }
                onMain { player.openServer(server) }
                await("PROPFIND failed: ${player.state.value.browser}") { player.state.value.browser?.entries?.any { it.isVideo } == true }
                val entry = player.state.value.browser!!.entries.first { it.isVideo }
                val identity = WatchRecord.create(server, entry)!!.id
                onMain { player.openEntry(entry) }
                await("Native video failed") { player.engine.state.value.phase == PlaybackPhase.PLAYING && player.engine.state.value.positionMs >= 300 }
                val cache = PlaybackDiskCache(context.noBackupFilesDir.resolve("playback"))
                await("Video/subtitle were not stored") {
                    cache.cachedBytes(identity) == fixture.size.toLong() &&
                        cache.cachedBytes("$identity:subtitle:/dav/fixture.sc.srt") == origin.subtitle.size.toLong() &&
                        player.engine.state.value.tracks.any { it.type == TrackType.SUBTITLE && it.external }
                }
                assertFalse("WebDAV credentials reached the CDN", origin.leaked.get())
                assertEquals(1, origin.listings.get())
                assertEquals(0, origin.heads.get()) // Sizes came from the original WebDAV listing.
                assertNull(player.state.value.playbackCacheError)
                await("Persistent cache ranges were not published") { player.engine.state.value.cachedRanges.any { it.startMs == 0L && it.endMs >= 5_000 } }
                onMain { player.back() }
                await("History checkpoint was not saved") {
                    WatchHistoryStore(context.noBackupFilesDir.resolve("watch-history.bin")).load().any {
                        it.serverId == server.id && it.positionMs > 0 && cache.cachedMedia(it.id) != null
                    }
                }
                val record = player.state.value.watchHistory.first { it.serverId == server.id }
                assertEquals(1, cache.cachedMedia(record.id)!!.subtitles.size)
                println("Prime: persisted=${player.state.value.playbackCacheUsedBytes}, listings=${origin.listings.get()}, heads=${origin.heads.get()}")
            } finally { onMain { activity.finish() }; instrumentation.waitForIdleSync() }
        } // Close both OpenList and CDN listeners before the next phase.
    }

    @Test fun b_reopenHistoryWithoutOpenListAfterProcessRestart() {
        val activity = launch()
        val player = model(activity)
        try {
            await("History was not restored") { !player.state.value.initializing && !player.state.value.historyLoading && player.state.value.watchHistory.any { it.serverId == "cache-025-fixture" } }
            val record = player.state.value.watchHistory.first { it.serverId == "cache-025-fixture" }
            val cache = PlaybackDiskCache(context.noBackupFilesDir.resolve("playback"))
            assertNotNull(cache.cachedMedia(record.id))
            onMain { player.setTab(HomeTab.RECENT) }
            val start = System.nanoTime()
            onMain { player.openHistory(record) }
            await("Offline history playback failed: ${player.engine.state.value}", 5) {
                player.engine.state.value.phase == PlaybackPhase.PLAYING && player.engine.state.value.positionMs > 0
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("Cached startup waited $elapsed ms for the unavailable server", elapsed < 3_000)
            await("Cached sidecar was not restored") { player.engine.state.value.tracks.any { it.type == TrackType.SUBTITLE && it.external && it.selected } }
            onMain { player.engine.pause() }
            await("Pause missing") { player.engine.state.value.phase == PlaybackPhase.PAUSED }
            for (position in listOf(4_000L, 1_000L, 5_000L, 2_000L)) {
                onMain { player.engine.seekTo(position) }
                await("Offline seek to $position failed") { abs(player.engine.state.value.positionMs - position) < 300 }
            }
            fun surface(view: View): SurfaceView? {
                if (view is SurfaceView) return view
                if (view is ViewGroup) repeat(view.childCount) { surface(view.getChildAt(it))?.let { found -> return found } }
                return null
            }
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            val result = AtomicInteger(-1)
            onMain { PixelCopy.request(surface(activity.window.decorView)!!, bitmap, { result.set(it); copied.countDown() }, Handler(Looper.getMainLooper())) }
            assertTrue(copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result.get())
            assertTrue("Offline native video is black", android.graphics.Color.red(bitmap.getPixel(32, 32)) > 100)
            bitmap.recycle()
            assertNull(player.state.value.playbackCacheError)
            await("Persistent ranges did not return after reopening") { player.engine.state.value.cachedRanges.any { it.startMs == 0L && it.endMs >= 5_000 } }
            onMain { player.clearPlaybackCache() }
            await("Cleared cache ranges remained visible") { player.state.value.playbackCacheUsedBytes == 0L && player.engine.state.value.cachedRanges.isEmpty() }
            println("Cold offline history startup: $elapsed ms; four seeks, subtitle and GPU pixels passed")
        } finally { onMain { activity.finish() }; instrumentation.waitForIdleSync() }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, seconds: Long = 15, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(25) }
        fail(message)
    }

    private class OpenListFixture(val video: ByteArray) : Closeable {
        val subtitle = "1\n00:00:00,000 --> 00:00:06,000\nCached subtitle\n".toByteArray()
        private val gateway = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val cdn = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val root = "http://127.0.0.1:${gateway.localPort}/dav/"
        val leaked = AtomicBoolean(false)
        val listings = AtomicInteger()
        val heads = AtomicInteger()
        @Volatile private var closed = false
        init { listOf(gateway, cdn).forEach { listener -> thread(isDaemon = true) {
            while (!closed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { socket.use { runCatching { serve(it, listener === gateway) } } }
            }
        } } }
        private fun serve(socket: Socket, isGateway: Boolean) {
            val input = socket.getInputStream().bufferedReader()
            val line = input.readLine() ?: return
            val method = line.substringBefore(' ')
            val path = line.substringAfter(' ').substringBefore(' ').substringBefore('?')
            val headers = mutableMapOf<String, String>()
            while (true) { val header = input.readLine() ?: return; if (header.isEmpty()) break; headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
            val output = socket.getOutputStream()
            if (isGateway) {
                if (headers["authorization"] != "Basic dXNlcjpwYXNz") {
                    output.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
                }
                if (method == "PROPFIND") {
                    listings.incrementAndGet()
                    val entries = listOf("fixture.avi" to video.size, "fixture.sc.srt" to subtitle.size).joinToString("") { (name, size) ->
                        "<d:response><d:href>/dav/$name</d:href><d:propstat><d:prop><d:displayname>$name</d:displayname><d:resourcetype/><d:getcontentlength>$size</d:getcontentlength><d:getlastmodified>Sun, 18 Oct 2026 12:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                    }
                    val xml = "<d:multistatus xmlns:d=\"DAV:\">$entries</d:multistatus>".toByteArray()
                    output.write("HTTP/1.1 207 Multi-Status\r\nContent-Type: application/xml\r\nContent-Length: ${xml.size}\r\nConnection: close\r\n\r\n".toByteArray()); output.write(xml); return
                }
                output.write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${cdn.localPort}$path?sign=${System.nanoTime()}\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            if (headers["authorization"] != null) leaked.set(true)
            if (method == "HEAD") {
                heads.incrementAndGet()
                output.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            val data = if (path.endsWith(".srt")) subtitle else video
            // Deliberately ignore Range, omit Content-Length and vary the CDN ETag.
            output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nCache-Control: no-store, no-cache\r\nETag: W/\"${System.nanoTime()}\"\r\nConnection: close\r\n\r\n".toByteArray())
            var offset = 0
            while (offset < data.size) {
                val count = minOf(32 * 1024, data.size - offset)
                output.write("${count.toString(16)}\r\n".toByteArray()); output.write(data, offset, count); output.write("\r\n".toByteArray()); offset += count
            }
            output.write("0\r\n\r\n".toByteArray())
        }
        override fun close() { closed = true; gateway.close(); cdn.close() }
    }

    private fun avi(): ByteArray {
        val width = 64; val height = 64; val fps = 8; val frames = 48; val frameSize = width * height * 3
        fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putInt) }.array()
        fun chunk(id: String, data: ByteArray) = ByteArrayOutputStream().apply { write(id.toByteArray()); write(ints(data.size)); write(data); if (data.size % 2 != 0) write(0) }.toByteArray()
        fun list(type: String, data: ByteArray) = chunk("LIST", type.toByteArray() + data)
        val main = chunk("avih", ints(1_000_000 / fps, frameSize * fps, 0, 0x10, frames, 0, 1, frameSize, width, height, 0, 0, 0, 0))
        val stream = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("vidsDIB ".toByteArray()); putInt(0); putShort(0); putShort(0); putInt(0); putInt(1); putInt(fps); putInt(0)
            putInt(frames); putInt(frameSize); putInt(-1); putInt(0); putShort(0); putShort(0); putShort(width.toShort()); putShort(height.toShort())
        }.array()
        val format = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(40); putInt(width); putInt(height); putShort(1); putShort(24); putInt(0); putInt(frameSize); repeat(4) { putInt(0) } }.array()
        val header = list("hdrl", main + list("strl", chunk("strh", stream) + chunk("strf", format)))
        val movie = ByteArrayOutputStream(); val index = ByteArrayOutputStream()
        repeat(frames) { frame ->
            val pixels = ByteArray(frameSize); for (i in pixels.indices step 3) { pixels[i] = 20; pixels[i + 1] = (60 + frame % 10 * 10).toByte(); pixels[i + 2] = 210.toByte() }
            movie.write(chunk("00db", pixels)); index.write("00db".toByteArray()); index.write(ints(0x10, 4 + frame * (frameSize + 8), frameSize))
        }
        return chunk("RIFF", "AVI ".toByteArray() + header + list("movi", movie.toByteArray()) + chunk("idx1", index.toByteArray()))
    }
}
