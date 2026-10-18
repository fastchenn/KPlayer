package dev.davplayer.playback.mpv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.test.platform.app.InstrumentationRegistry
import dev.davplayer.playback.api.*
import `is`.xyz.mpv.MPVLib
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class MpvTestActivity : Activity(), SurfaceHolder.Callback {
    lateinit var video: SurfaceView
    private var engine: PlayerEngine? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = SurfaceView(this)
        video.holder.addCallback(this)
        setContentView(video)
    }
    fun bind(engine: PlayerEngine?) {
        this.engine?.attachSurface(null)
        this.engine = engine
        if (video.holder.surface.isValid) {
            engine?.attachSurface(video.holder.surface)
            engine?.setSurfaceSize(video.width, video.height)
        }
    }
    override fun surfaceCreated(holder: SurfaceHolder) { engine?.attachSurface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { engine?.setSurfaceSize(width, height) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { engine?.attachSurface(null) }
}

/** Exercises the application engine, authenticated HTTP bridge, native decoder and GPU surface. */
class MpvVideoSmokeTest {
    @Test fun rendersAuthenticatedVideoAndSupportsPauseSeekSpeedAndReopen() = verifyVideoPlayback(redirect = false)

    @Test fun rendersVideoAfterCrossOriginRedirectWithoutLeakingCredentials() = verifyVideoPlayback(redirect = true)

    @Test fun rendersAuthenticatedSameFolderSrtAndSupportsDisableAndReopen() = verifySubtitlePlayback("srt")

    @Test fun rendersSameFolderAssAfterCrossOriginRedirect() = verifySubtitlePlayback("ass", redirect = true)

    @Test fun rendersSameFolderWebVtt() = verifySubtitlePlayback("vtt")

    @Test fun rendersSameFolderSsa() = verifySubtitlePlayback("ssa")

    @Test fun unavailableSubtitleDoesNotInterruptVideo() = verifyVideoPlayback(redirect = false, missingSubtitle = true)

    @Test fun exposesAndSwitchesAllLanguageTaggedAndVideoExtensionSidecars() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MpvTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MpvTestActivity
        val variants = linkedMapOf("fixture.sc.ass" to "SC 简体字幕", "fixture.tc.ass" to "TC 繁體字幕",
            "fixture.chs.ass" to "CHS 简体字幕", "fixture.cht.ass" to "CHT 繁體字幕", "fixture.avi.ass" to "Full filename 中文字幕")
        var engine: MpvPlayerEngine? = null
        try {
            await("Surface not ready") { activity.video.holder.surface.isValid }
            TestMediaServer(videoFixture(), subtitles = variants.mapKeys { "/${it.key}" }
                .mapValues { subtitleFixture("ass", it.value) }).use { server ->
                val headers = mapOf("Authorization" to "Basic smoke-test")
                val request = PlaybackRequest("multiple-subs", server.url, "fixture.avi", headers, preferHardware = false,
                    externalSubtitles = variants.keys.map { name ->
                        ExternalSubtitle(server.url.substringBeforeLast('/') + "/$name", name, headers)
                    })
                onMain { engine = MpvPlayerEngine(activity); activity.bind(engine); engine!!.open(request) }
                val player = engine!!
                await("All subtitle variants must be available in the track menu") {
                    player.state.value.phase == PlaybackPhase.PLAYING && player.state.value.positionMs > 250 &&
                        player.state.value.tracks.count { it.type == TrackType.SUBTITLE && it.external } == variants.size
                }
                assertTrue(server.authenticated.get())
                onMain { player.pause(); player.seekTo(1_000) }
                val tracks = player.state.value.tracks.filter { it.type == TrackType.SUBTITLE }
                assertEquals(1, tracks.count { it.selected })
                for ((name, text) in variants) {
                    val track = tracks.single { it.label.contains(name) }
                    onMain { player.selectTrack(TrackType.SUBTITLE, track.id) }
                    await("Selecting $name must change the active track and decoded text") {
                        var decoded: String? = null
                        onMain { decoded = MPVLib.getPropertyString("sub-text") }
                        player.state.value.tracks.any { it.id == track.id && it.selected } && decoded?.contains(text) == true
                    }
                    await("Selected subtitle $name must render on the surface") { whitePixels(activity) > 20 }
                }
                onMain { player.selectTrack(TrackType.SUBTITLE, null) }
                await("Disabling subtitles must clear all variants") { whitePixels(activity) < 5 }
                onMain { player.stop(); player.open(request) }
                await("Reopening must restore every variant without duplicates") {
                    val subs = player.state.value.tracks.filter { it.type == TrackType.SUBTITLE }
                    player.state.value.phase == PlaybackPhase.PLAYING && subs.size == variants.size && subs.count { it.selected } == 1
                }
            }
        } finally { onMain { activity.bind(null); engine?.release(); activity.finish() } }
    }

    @Test fun disablingSubtitlesWhileTheyLoadPreservesTheUsersChoice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MpvTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MpvTestActivity
        val gate = CountDownLatch(1)
        var engine: MpvPlayerEngine? = null
        try {
            await("Surface not ready") { activity.video.holder.surface.isValid }
            TestMediaServer(videoFixture(), subtitles = mapOf("/fixture.srt" to
                "1\n00:00:00,000 --> 00:00:05,500\nDelayed subtitle\n".toByteArray()), subtitleGate = gate).use { server ->
                val headers = mapOf("Authorization" to "Basic smoke-test")
                onMain {
                    engine = MpvPlayerEngine(activity)
                    activity.bind(engine)
                    engine!!.open(PlaybackRequest("delayed", server.url, "fixture.avi", headers, preferHardware = false,
                        externalSubtitles = listOf(ExternalSubtitle(server.url.replace(".avi", ".srt"), "fixture.srt", headers))))
                }
                await("Subtitle download did not start") { server.subtitleRequests.get() > 0 }
                val player = engine!!
                // This command must execute while remote subtitle probing is still blocked.
                onMain { player.selectTrack(TrackType.SUBTITLE, null) }
                gate.countDown()
                await("Delayed subtitle did not load") { player.state.value.tracks.any { it.external && it.type == TrackType.SUBTITLE } }
                assertTrue(player.state.value.tracks.none { it.type == TrackType.SUBTITLE && it.selected })
                await("Video stopped while loading subtitles") { player.state.value.phase == PlaybackPhase.PLAYING && player.state.value.positionMs > 500 }
            }
        } finally { gate.countDown(); onMain { activity.bind(null); engine?.release(); activity.finish() } }
    }

    private fun subtitleFixture(extension: String, text: String): ByteArray = when (extension) {
            "ass", "ssa" -> """
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 640
                PlayResY: 640
                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Default,sans-serif,36,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,20,1
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:00.00,0:00:05.50,Default,,0,0,0,,$text
            """.trimIndent()
            "vtt" -> "WEBVTT\n\n00:00.000 --> 00:05.500\n$text\n"
            else -> "1\n00:00:00,000 --> 00:00:05,500\n$text\n"
        }.toByteArray(Charsets.UTF_8)

    private fun verifySubtitlePlayback(extension: String, redirect: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MpvTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MpvTestActivity
        var engine: MpvPlayerEngine? = null
        val text = "KPlayer 中文字幕"
        val subtitle = subtitleFixture(extension, text)
        try {
            await("Surface not ready") { activity.video.holder.surface.isValid }
            TestMediaServer(videoFixture(), requireCredentials = !redirect,
                subtitles = mapOf("/fixture.$extension" to subtitle)).use { server ->
                val gateway = if (redirect) TestMediaServer(ByteArray(0), redirectTo = server.url + "?sign=subtitle-test") else null
                try {
                    val source = gateway ?: server
                    val headers = mapOf("Authorization" to "Basic smoke-test")
                    val request = PlaybackRequest("sub-fixture", source.url, "fixture.avi", headers, preferHardware = false,
                        externalSubtitles = listOf(ExternalSubtitle(source.url.substringBeforeLast('.') + ".$extension", "fixture.$extension", headers)))
                    onMain { engine = MpvPlayerEngine(activity); activity.bind(engine); engine!!.open(request) }
                    val player = engine!!
                    await("External subtitle was not automatically selected: ${player.state.value}") {
                        player.state.value.phase == PlaybackPhase.PLAYING && player.state.value.positionMs > 250 &&
                            player.state.value.tracks.any { it.type == TrackType.SUBTITLE && it.external && it.selected }
                    }
                    assertTrue(source.authenticated.get())
                    assertFalse("Subtitle credentials leaked on redirect", server.credentialsLeaked.get())
                    assertTrue("Subtitle endpoint was never fetched", server.subtitleRequests.get() > 0)
                    onMain { player.pause(); player.seekTo(1_000) }
                    await("Subtitle text was not decoded") {
                        var decoded: String? = null
                        onMain { decoded = MPVLib.getPropertyString("sub-text") }
                        decoded?.contains(text) == true
                    }
                    await("Subtitle glyphs were not rendered on the GPU surface") { whitePixels(activity) > 20 }
                    val tracks = player.state.value.tracks.filter { it.type == TrackType.SUBTITLE }
                    assertEquals(1, tracks.size)
                    assertTrue(tracks.single().label.contains("fixture.$extension"))
                    onMain { player.selectTrack(TrackType.SUBTITLE, null) }
                    await("Subtitle disable did not clear the surface") { whitePixels(activity) < 5 }
                    onMain { player.selectTrack(TrackType.SUBTITLE, tracks.single().id) }
                    await("Subtitle re-enable did not render") { whitePixels(activity) > 20 }
                    onMain { player.stop(); player.open(request) }
                    await("Subtitle did not reload after reopening") {
                        player.state.value.phase == PlaybackPhase.PLAYING &&
                            player.state.value.tracks.count { it.type == TrackType.SUBTITLE && it.external } == 1 &&
                            player.state.value.tracks.any { it.type == TrackType.SUBTITLE && it.selected }
                    }
                    // Switching to a video without sidecars must clear the previous subtitle.
                    onMain { player.open(PlaybackRequest("plain", source.url, "fixture.avi", headers, preferHardware = false)) }
                    await("Video without subtitles failed") { player.state.value.phase == PlaybackPhase.PLAYING }
                    assertTrue(player.state.value.tracks.none { it.type == TrackType.SUBTITLE })
                } finally { gateway?.close() }
            }
        } finally { onMain { activity.bind(null); engine?.release(); activity.finish() } }
    }

    private fun whitePixels(activity: MpvTestActivity): Int {
        val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        val result = AtomicInteger(-1)
        onMain { PixelCopy.request(activity.video, bitmap, { result.set(it); copied.countDown() }, Handler(Looper.getMainLooper())) }
        assertTrue(copied.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result.get())
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return pixels.count { android.graphics.Color.red(it) > 200 && android.graphics.Color.green(it) > 200 && android.graphics.Color.blue(it) > 200 }
    }

    private fun verifyVideoPlayback(redirect: Boolean, missingSubtitle: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MpvTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MpvTestActivity
        var engine: MpvPlayerEngine? = null
        try {
            await("Surface not ready") { activity.video.holder.surface.isValid }
            TestMediaServer(videoFixture(), requireCredentials = !redirect).use { server ->
                val gateway = if (redirect) TestMediaServer(ByteArray(0), redirectTo = server.url + "?sign=test-token") else null
                try {
                val source = gateway ?: server
                val headers = mapOf("Authorization" to "Basic smoke-test")
                val request = PlaybackRequest("fixture", source.url, "fixture.avi", headers, preferHardware = false,
                    externalSubtitles = if (missingSubtitle) listOf(ExternalSubtitle(source.url.replace(".avi", ".srt"), "fixture.srt", headers)) else emptyList())
                onMain {
                    engine = MpvPlayerEngine(activity)
                    assertTrue(engine!!.availability is EngineAvailability.Available)
                    activity.bind(engine)
                    engine!!.open(request)
                }
                val player = engine!!
                await("Video did not start: ${player.state.value}") { player.state.value.phase == PlaybackPhase.PLAYING && player.state.value.positionMs >= 250 }
                assertTrue(player.state.value.durationMs!! >= 5_000)
                assertTrue(source.authenticated.get())
                assertFalse("WebDAV credentials reached the media server", server.credentialsLeaked.get())
                assertTrue(server.ranges.get() > 0)

                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val copied = CountDownLatch(1)
                val copyResult = AtomicInteger(-1)
                onMain { PixelCopy.request(activity.video, bitmap, { result -> copyResult.set(result); copied.countDown() }, Handler(Looper.getMainLooper())) }
                assertTrue(copied.await(5, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, copyResult.get())
                val pixel = bitmap.getPixel(32, 32)
                bitmap.recycle()
                assertTrue("Native GPU surface is black", android.graphics.Color.red(pixel) > 100)

                onMain { player.pause() }
                await("Pause missing") { player.state.value.phase == PlaybackPhase.PAUSED }
                Thread.sleep(200)
                val pausedAt = player.state.value.positionMs
                Thread.sleep(300)
                assertTrue("Paused playback kept advancing", abs(player.state.value.positionMs - pausedAt) < 200)
                assertTrue(player.state.value.seekable)
                onMain { player.seekTo(3_000) }
                await("Seek did not reach target") { abs(player.state.value.positionMs - 3_000) < 300 }
                onMain { player.setSpeed(1.5f) }
                await("Speed did not change") { player.state.value.speed == 1.5f }
                onMain { player.play() }
                await("Resume missing") { player.state.value.phase == PlaybackPhase.PLAYING && player.state.value.positionMs > 3_250 }
                onMain { player.stop(); player.open(request) }
                await("Reopen failed") { player.state.value.phase == PlaybackPhase.PLAYING && player.state.value.positionMs in 1..2_000 }
                if (missingSubtitle) assertTrue(player.state.value.tracks.none { it.type == TrackType.SUBTITLE })
                } finally { gateway?.close() }
            }
        } finally {
            onMain { activity.bind(null); engine?.release(); activity.finish() }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(50) }
        fail(message)
    }

    private fun onMain(block: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { try { block() } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw AssertionError("Main thread operation failed", it) }
    }

    /** A six-second uncompressed RGB AVI generated locally; no external media/test service. */
    private fun videoFixture(): ByteArray {
        val width = 64
        val height = 64
        val fps = 8
        val frames = 48
        val frameSize = width * height * 3
        fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putInt) }.array()
        fun chunk(id: String, data: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(id.toByteArray()); write(ints(data.size)); write(data); if (data.size % 2 != 0) write(0)
        }.toByteArray()
        fun list(type: String, data: ByteArray) = chunk("LIST", type.toByteArray() + data)
        val main = chunk("avih", ints(1_000_000 / fps, frameSize * fps, 0, 0x10, frames, 0, 1, frameSize, width, height, 0, 0, 0, 0))
        val stream = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("vidsDIB ".toByteArray()); putInt(0); putShort(0); putShort(0); putInt(0)
            putInt(1); putInt(fps); putInt(0); putInt(frames); putInt(frameSize); putInt(-1); putInt(0)
            putShort(0); putShort(0); putShort(width.toShort()); putShort(height.toShort())
        }.array()
        val format = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(40); putInt(width); putInt(height); putShort(1); putShort(24); putInt(0); putInt(frameSize)
            repeat(4) { putInt(0) }
        }.array()
        val header = list("hdrl", main + list("strl", chunk("strh", stream) + chunk("strf", format)))
        val movie = ByteArrayOutputStream()
        val index = ByteArrayOutputStream()
        repeat(frames) { frame ->
            val pixels = ByteArray(frameSize)
            for (i in pixels.indices step 3) { pixels[i] = 20; pixels[i + 1] = (60 + frame % 10 * 10).toByte(); pixels[i + 2] = 210.toByte() }
            movie.write(chunk("00db", pixels))
            index.write("00db".toByteArray()); index.write(ints(0x10, 4 + frame * (frameSize + 8), frameSize))
        }
        return chunk("RIFF", "AVI ".toByteArray() + header + list("movi", movie.toByteArray()) + chunk("idx1", index.toByteArray()))
    }

    private class TestMediaServer(
        private val data: ByteArray,
        private val requireCredentials: Boolean = true,
        private val redirectTo: String? = null,
        private val subtitles: Map<String, ByteArray> = emptyMap(),
        private val subtitleGate: CountDownLatch? = null,
    ) : Closeable {
        private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/fixture.avi"
        val authenticated = AtomicBoolean(false)
        val credentialsLeaked = AtomicBoolean(false)
        val ranges = AtomicInteger()
        val subtitleRequests = AtomicInteger()
        @Volatile private var closed = false
        init {
            thread(isDaemon = true) {
                while (!closed) {
                    val socket = try { listener.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) { socket.use { runCatching { serve(it) } } }
                }
            }
        }
        private fun serve(socket: Socket) {
            val input = socket.getInputStream().bufferedReader()
            val head = input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val output = socket.getOutputStream()
            if (requireCredentials && headers["authorization"] != "Basic smoke-test") {
                output.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            if (!requireCredentials && listOf("authorization", "cookie", "x-api-key").any { headers[it] != null }) {
                credentialsLeaked.set(true)
                output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            if (requireCredentials) authenticated.set(true)
            if (redirectTo != null) {
                val path = head.substringAfter(' ').substringBefore(' ').substringBefore('?')
                val target = if (path == "/fixture.avi") redirectTo else redirectTo.replace("/fixture.avi", path)
                output.write("HTTP/1.1 302 Found\r\nLocation: $target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            val path = head.substringAfter(' ').substringBefore(' ').substringBefore('?')
            val subtitle = subtitles[path]
            if (subtitle != null) {
                subtitleRequests.incrementAndGet()
                subtitleGate?.await(10, TimeUnit.SECONDS)
            }
            val data = subtitle ?: if (path == "/fixture.avi") data else {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            val range = headers["range"]?.removePrefix("bytes=")
            val start = range?.substringBefore('-')?.toIntOrNull() ?: 0
            val end = (range?.substringAfter('-')?.toIntOrNull() ?: data.lastIndex).coerceAtMost(data.lastIndex)
            if (range != null) ranges.incrementAndGet()
            if (start !in data.indices || end < start) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            val status = if (range != null) 206 else 200
            val rangeHeader = if (range != null) "Content-Range: bytes $start-$end/${data.size}\r\n" else ""
            val contentType = if (subtitle == null) "video/x-msvideo" else "application/octet-stream"
            output.write("HTTP/1.1 $status OK\r\nAccept-Ranges: bytes\r\nContent-Type: $contentType\r\nContent-Length: ${end - start + 1}\r\n${rangeHeader}Connection: close\r\n\r\n".toByteArray())
            if (!head.startsWith("HEAD ")) output.write(data, start, end - start + 1)
        }
        override fun close() { closed = true; listener.close() }
    }
}
