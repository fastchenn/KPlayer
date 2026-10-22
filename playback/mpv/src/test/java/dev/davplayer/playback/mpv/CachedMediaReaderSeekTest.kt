package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.CachedTimeRange
import dev.davplayer.playback.api.PlaybackRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedMediaReaderSeekTest {
    @get:Rule val temporary = TemporaryFolder()
    private class HeadersGate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
    private val nextHeaders = AtomicReference<HeadersGate?>()
    private val block = PlaybackDiskCache.BLOCK_BYTES
    private val size = 8L * block
    private val fetcher = object : MediaFetcher {
        override fun <T> useResponse(method: String, headers: Map<String, String>, scope: MediaFetchScope?, read: (Response) -> T): T {
            nextHeaders.getAndSet(null)?.let { gate ->
                gate.started.countDown()
                check(gate.release.await(5, TimeUnit.SECONDS))
                if (scope?.cancelled == true) throw IOException("Cancelled before headers")
            }
            if (method == "HEAD") return Response.Builder()
                .request(Request.Builder().url("https://example.test/video").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Length", size.toString()).body(ByteArray(0).toResponseBody()).build().use(read)
            val range = headers.getValue("range").removePrefix("bytes=").split('-')
            val start = range[0].toLong()
            val end = range[1].toLong()
            val response = Response.Builder().request(Request.Builder().url("https://example.test/video").build())
                .protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                .header("Content-Range", "bytes $start-$end/$size")
                .body(ByteArray((end - start + 1).toInt()).toResponseBody()).build()
            return response.use(read)
        }
    }
    private fun reader(cache: PlaybackDiskCache, knownSize: Boolean = true) = CachedMediaReader(
        PlaybackRequest("video", "https://example.test/video.mp4", "video.mp4", sourceSizeBytes = size.takeIf { knownSize }),
        cache, fetcher, {}, { false },
    )

    @Test fun repeatedSeeksBeforeHeadersDoNotFallBackOrDisableNewWhiteBars() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        val worker = Executors.newSingleThreadExecutor()
        val ranges = mutableListOf<CachedTimeRange>()
        try { reader(cache).use { reader ->
            repeat(4) { attempt ->
                val gate = HeadersGate()
                nextHeaders.set(gate)
                val oldPosition = attempt * 2L * block
                val pending = worker.submit<Boolean> {
                    reader.serve("GET", mapOf("range" to "bytes=$oldPosition-${oldPosition + 3}"), ByteArrayOutputStream())
                }
                try {
                    assertTrue("Old request must be waiting for headers", gate.started.await(5, TimeUnit.SECONDS))
                    reader.prepareSeek()
                    val handled = pending.get(5, TimeUnit.SECONDS)
                    // LoopbackMediaSource treats false as an uncached fallback and disables timeline reporting.
                    if (!handled) reader.bypassed()
                    assertTrue("A superseded request must not fall back to an uncached stream", handled)
                    assertTrue(reader.timelineSnapshot().eligible)
                } finally { gate.release.countDown() }
                val position = oldPosition + block
                assertTrue(reader.serve("GET", mapOf("range" to "bytes=$position-${position + 3}"), ByteArrayOutputStream()))
                ranges.add(CachedTimeRange(attempt * 20_000L + 10_000, attempt * 20_000L + 12_000))
                assertEquals("New cached ranges must keep appearing after every seek", ranges,
                    reader.cachedTimeline(ranges, 160_000))
            }
        } } finally { worker.shutdownNow() }
    }

    @Test fun replacingAReadAheadWithinOneSeekDoesNotFallBack() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        val worker = Executors.newFixedThreadPool(2)
        val gate = HeadersGate()
        try { reader(cache).use { reader ->
            nextHeaders.set(gate)
            val old = worker.submit<Boolean> {
                reader.serve("GET", mapOf("range" to "bytes=0-"), ByteArrayOutputStream())
            }
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))
            val replacement = worker.submit<Boolean> {
                reader.serve("GET", mapOf("range" to "bytes=${7L * block}-"), ByteArrayOutputStream())
            }
            try {
                assertTrue("Superseding an internal byte request must not trigger an uncached fallback", old.get(5, TimeUnit.SECONDS))
            } finally { gate.release.countDown() }
            assertTrue(replacement.get(5, TimeUnit.SECONDS))
            assertTrue(reader.timelineSnapshot().eligible)
            val ranges = listOf(CachedTimeRange(140_000, 160_000))
            assertEquals(ranges, reader.cachedTimeline(ranges, 160_000))
        } } finally { gate.release.countDown(); worker.shutdownNow() }
    }

    @Test fun seekDuringMetadataProbeCannotStartAnOldPositionInTheNewEpoch() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        val worker = Executors.newSingleThreadExecutor()
        val gate = HeadersGate()
        try { reader(cache, knownSize = false).use { reader ->
            nextHeaders.set(gate)
            val output = ByteArrayOutputStream()
            val old = worker.submit<Boolean> { reader.serve("GET", mapOf("range" to "bytes=0-3"), output) }
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))
            reader.prepareSeek()
            gate.release.countDown()
            assertTrue(old.get(5, TimeUnit.SECONDS))
            assertEquals("The old probe must not start a media response after seek", 0, output.size())
            assertTrue(reader.timelineSnapshot().bytes.isEmpty())
            assertEquals(0L, cache.usedBytes.value)
        } } finally { gate.release.countDown(); worker.shutdownNow() }
    }

    @Test fun nativeSeekRejectsATimelineSampleQueuedBeforeTheSeek() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        reader(cache).use { reader ->
            reader.serve("GET", mapOf("range" to "bytes=0-3"), ByteArrayOutputStream())
            val stale = reader.timelineSnapshot()
            reader.nativeSeek()
            val ranges = listOf(CachedTimeRange(0, 2000))
            assertTrue(reader.cachedTimeline(ranges, 160_000, stale).isEmpty())
            assertEquals(ranges, reader.cachedTimeline(ranges, 160_000))
        }
    }
}
