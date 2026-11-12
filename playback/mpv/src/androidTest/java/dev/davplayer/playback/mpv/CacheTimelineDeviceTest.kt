package dev.davplayer.playback.mpv

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.davplayer.playback.api.*
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Real three-hour containers, native decoder, background disk cache and offline seeks. */
class CacheTimelineDeviceTest {
    @Test fun threeHourMp4CompletesWhilePausedAndSeeksOffline() = verify("mp4")
    @Test fun threeHourMatroskaCompletesWhilePausedAndSeeksOffline() = verify("mkv")

    private fun verify(extension: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val content = instrumentation.context.assets.open("timeline-3h.$extension").use { it.readBytes() }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MpvTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MpvTestActivity
        val directory = File(instrumentation.targetContext.cacheDir, "long-cache-$extension-${System.nanoTime()}")
        var engine: MpvPlayerEngine? = null
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(50) }
            throw AssertionError("$message: ${engine?.state?.value}")
        }
        try {
            await("Surface unavailable") { activity.video.holder.surface.isValid }
            MediaServer(content).use { origin ->
                val request = PlaybackRequest("long-device-$extension", origin.url, "three-hours.$extension", preferHardware = false,
                    cacheIdentity = "long-device-$extension", sourceSizeBytes = content.size.toLong())
                onMain {
                    engine = MpvPlayerEngine(activity, PlaybackDiskCache(directory, maxBytes = 64L * 1024 * 1024))
                    activity.bind(engine)
                    engine!!.open(request)
                }
                await("Native decoder did not report three-hour duration") {
                    (engine!!.state.value.durationMs?.let { it in 10_799_000L..10_801_000L } == true) && engine!!.state.value.positionKnown
                }
                onMain { engine!!.pause() }
                await("Paused background download did not publish full disk coverage") {
                    val state = engine!!.state.value
                    state.cacheProgress?.reachedEnd == true && state.cachedRanges == listOf(CachedTimeRange(0, state.durationMs!!))
                }
                val requests = origin.requests.get()
                origin.close()
                onMain { engine!!.seekTo(7_200_000) }
                await("Offline two-hour seek failed") { engine!!.state.value.positionMs >= 7_199_000 && engine!!.state.value.phase != PlaybackPhase.ERROR }
                assertEquals(requests, origin.requests.get())
                assertEquals(listOf(CachedTimeRange(0, engine!!.state.value.durationMs!!)), engine!!.state.value.cachedRanges)
                onMain { engine!!.release(); activity.bind(null) }
                onMain {
                    engine = MpvPlayerEngine(activity, PlaybackDiskCache(directory, maxBytes = 64L * 1024 * 1024))
                    activity.bind(engine)
                    engine!!.open(PlaybackRequest(request.mediaId, request.url, request.title, startPositionMs = 7_200_000,
                        preferHardware = false, cacheIdentity = request.cacheIdentity, sourceSizeBytes = content.size.toLong()))
                }
                await("Offline reopening lost playback or white bar") {
                    val state = engine!!.state.value
                    state.positionMs >= 7_199_000 && state.cacheProgress?.reachedEnd == true &&
                        state.cachedRanges == listOf(CachedTimeRange(0, state.durationMs ?: 1))
                }
                assertEquals(requests, origin.requests.get())
            }
        } finally { onMain { engine?.release(); activity.bind(null); activity.finish() } }
    }

    private class MediaServer(private val bytes: ByteArray) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        private val workers = Executors.newFixedThreadPool(8)
        val requests = AtomicInteger()
        val url = "http://127.0.0.1:${socket.localPort}/video"
        private val listener = thread(isDaemon = true, name = "long-video-fixture") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                clients.add(client)
                workers.execute {
                    try { client.use { connection ->
                        connection.soTimeout = 5_000
                        val input = connection.getInputStream().bufferedReader()
                        val method = input.readLine().orEmpty().substringBefore(' ')
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.readLine().orEmpty()
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        requests.incrementAndGet()
                        val range = headers["range"]?.let { Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(it) }
                        val start = range?.groupValues?.get(1)?.toInt() ?: 0
                        val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                        val partial = range != null
                        val response = buildString {
                            append("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n")
                            append("Content-Length: ${end - start + 1}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
                            if (partial) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
                            append("\r\n")
                        }
                        connection.getOutputStream().apply {
                            write(response.toByteArray(Charsets.US_ASCII))
                            if (method != "HEAD") write(bytes, start, end - start + 1)
                            flush()
                        }
                    } } catch (_: java.io.IOException) { /* A seek cancels its previous request. */ }
                    finally { clients.remove(client) }
                }
            }
        }
        override fun close() {
            socket.close()
            clients.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            listener.join(2_000)
        }
    }
}
