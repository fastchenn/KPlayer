package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.PlaybackRequest
import dev.davplayer.playback.api.CachedTimeRange
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackCacheStreamTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private val consumer = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    @Volatile private var content = "0123456789abcdef".toByteArray()
    @Volatile private var etag: String? = "\"version-1\""
    @Volatile private var supportsRanges = true
    @Volatile private var supportsHead = true
    @Volatile private var slowBody = false
    private val downloaded = AtomicLong()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val data = content
                val response = MockResponse().setHeader("Accept-Ranges", "bytes")
                if (slowBody) response.throttleBody(1, 250, TimeUnit.MILLISECONDS)
                etag?.let { response.setHeader("ETag", it) }
                if (request.method == "HEAD") return if (supportsHead) response.setHeader("Content-Length", data.size) else MockResponse().setResponseCode(405)
                val range = request.getHeader("Range")?.let { Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(it) }
                if (supportsRanges && range != null) {
                    val from = range.groupValues[1].toInt()
                    val to = range.groupValues[2].toIntOrNull()?.coerceAtMost(data.lastIndex) ?: data.lastIndex
                    if (from >= data.size) return response.setResponseCode(416).setHeader("Content-Range", "bytes */${data.size}")
                    return response.setResponseCode(206).setHeader("Content-Range", "bytes $from-$to/${data.size}")
                        .setBody(Buffer().write(data, from, to - from + 1))
                }
                return response.setBody(Buffer().write(data))
            }
        }
        server.start()
    }

    @After fun stop() { server.shutdown(); consumer.connectionPool.evictAll() }

    private fun request(
        auth: String = "Basic private",
        enabled: Boolean = true,
        mediaId: String = "video",
        url: String = server.url("/video.mkv").toString(),
        cacheIdentity: String? = null,
        modified: String? = null,
        knownSize: Boolean = false,
    ) = PlaybackRequest(
        mediaId, url, "video.mkv", headers = mapOf("Authorization" to auth), cacheEnabled = enabled,
        cacheIdentity = cacheIdentity, sourceSizeBytes = if (knownSize || modified != null) content.size.toLong() else null, sourceModifiedAt = modified,
    )

    private fun source(
        cache: PlaybackDiskCache,
        request: PlaybackRequest = request(),
    ) = LoopbackMediaSource(request, diskCache = cache, onBytesRead = { downloaded.addAndGet(it.toLong()) })

    private fun awaitCache(cache: PlaybackDiskCache, expected: Long = content.size.toLong()) = runBlocking {
        withTimeout(6_000) { cache.usedBytes.first { it >= expected } }
    }

    private fun read(source: LoopbackMediaSource, range: String? = null): ByteArray {
        val request = Request.Builder().url(source.url).apply { range?.let { header("Range", it) } }.build()
        return consumer.newCall(request).execute().use {
            assertEquals(if (range == null) 200 else 206, it.code)
            it.body!!.bytes()
        }
    }

    @Test fun rereadingCachedRangesAndSuffixesDoesNotDownloadAgain() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { source ->
            assertArrayEquals("2345".toByteArray(), read(source, "bytes=2-5"))
            awaitCache(cache)
            assertEquals(content.size.toLong(), downloaded.get())
            assertEquals(content.size.toLong(), cache.usedBytes.value)
            assertArrayEquals("89abcdef".toByteArray(), read(source, "bytes=8-"))
            assertArrayEquals("def".toByteArray(), read(source, "bytes=-3"))
            assertEquals(content.size.toLong(), downloaded.get())
            assertEquals(2, server.requestCount) // one validation HEAD and one video block
        }
    }

    @Test fun timeRangesRestoreFromDiskAndCachedReadsNeedNoNewRequests() {
        content = ByteArray(PlaybackDiskCache.BLOCK_BYTES * 3) { (it % 127).toByte() }
        val directory = temporary.newFolder()
        val cache = PlaybackDiskCache(directory)
        val request = request(cacheIdentity = "timeline-file", knownSize = true)
        val times = listOf(CachedTimeRange(0, 2000), CachedTimeRange(20_000, 22_000))
        val tail = PlaybackDiskCache.BLOCK_BYTES * 2
        source(cache, request).use { source ->
            read(source, "bytes=0-3")
            read(source, "bytes=$tail-${tail + 3}")
            awaitCache(cache, PlaybackDiskCache.BLOCK_BYTES * 2L)
            assertEquals(times, source.cachedTimeline(times, 60_000))
        }
        val count = server.requestCount
        source(PlaybackDiskCache(directory), request).use { source ->
            assertArrayEquals(content.copyOfRange(0, 4), read(source, "bytes=0-3"))
            assertEquals(times, source.cachedTimeline(emptyList(), 60_000))
            assertArrayEquals(content.copyOfRange(tail, tail + 4), read(source, "bytes=$tail-${tail + 3}"))
        }
        assertEquals(count, server.requestCount)
    }

    @Test fun completeFileCanAdvertiseItsWholeDurationAndClearWithdrawsIt() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { source ->
            read(source)
            awaitCache(cache)
            assertEquals(listOf(CachedTimeRange(0, 60_000)), source.cachedTimeline(emptyList(), 60_000))
            cache.clear()
            assertTrue(source.cachedTimeline(listOf(CachedTimeRange(0, 60_000)), 60_000).isEmpty())
        }
    }

    @Test fun reopenedPlaybackValidatesThenUsesExistingDiskBlocks() {
        val directory = temporary.newFolder()
        val cache = PlaybackDiskCache(directory)
        source(cache).use { assertArrayEquals(content, read(it)); awaitCache(cache) }
        source(PlaybackDiskCache(directory)).use { assertArrayEquals(content, read(it)) }
        assertEquals(3, server.requestCount) // two HEADs, one GET
        assertEquals(content.size.toLong(), downloaded.get())
        assertTrue(directory.listFiles()!!.filter { it.name.endsWith(".blk") }.all { it.name.matches(Regex("[a-f0-9]{64}\\.[0-9]+\\.blk")) })
    }

    @Test fun updatedServerValidatorNeverReplaysTheOldVideo() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { read(it); awaitCache(cache) }
        etag = "\"version-2\""
        content = "fedcba9876543210".toByteArray()
        source(cache).use { assertArrayEquals(content, read(it)) }
        assertEquals(4, server.requestCount)
        assertEquals(32L, downloaded.get())
    }

    @Test fun differentAccountsCannotShareCachedVideoBlocks() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache, request(mediaId = "account-A")).use { read(it); awaitCache(cache) }
        source(cache, request(mediaId = "account-B")).use { read(it) }
        assertEquals(4, server.requestCount)
        assertEquals(32L, downloaded.get())
    }

    @Test fun changedCredentialsForTheSameAccountDoNotBlockLocalReopening() {
        val directory = temporary.newFolder()
        val cache = PlaybackDiskCache(directory)
        source(cache, request(auth = "Basic account-A", cacheIdentity = "same-account-file")).use { read(it); awaitCache(cache) }
        source(PlaybackDiskCache(directory), request(auth = "Basic account-B", cacheIdentity = "same-account-file")).use { assertArrayEquals(content, read(it)) }
        assertEquals(2, server.requestCount)
        assertEquals(16L, downloaded.get())
    }

    @Test fun disabledCacheUsesDirectStreamingAndStillReportsNetworkBytes() {
        val cache = PlaybackDiskCache(temporary.newFolder(), enabled = false)
        source(cache).use {
            repeat(2) { _ -> assertArrayEquals("2345".toByteArray(), read(it, "bytes=2-5")) }
        }
        assertEquals(8L, downloaded.get())
        assertEquals(0L, cache.usedBytes.value)
        assertEquals(2, server.requestCount)
    }

    @Test fun perPlaybackSwitchBypassesAnEnabledSharedCache() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache, request(enabled = false)).use { read(it) }
        assertEquals(0L, cache.usedBytes.value)
        assertEquals(1, server.requestCount)
    }

    @Test fun clearingForcesTheNextPlaybackToDownloadAgainWhileThisSessionReusesItsHeldBlock() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use {
            read(it)
            awaitCache(cache)
            cache.clear()
            assertEquals(0L, cache.usedBytes.value)
            assertArrayEquals(content, read(it))
            assertEquals(content.size.toLong(), downloaded.get())
        }
        source(cache).use {
            assertArrayEquals(content, read(it))
            awaitCache(cache)
        }
        assertEquals(32L, downloaded.get())
    }

    @Test fun seekingAcrossBlockBoundariesCombinesTheCorrectBytes() {
        val block = PlaybackDiskCache.BLOCK_BYTES
        content = ByteArray(block + 10) { (it % 251).toByte() }
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use {
            val range = "bytes=${block - 3}-${block + 4}"
            val expected = content.copyOfRange(block - 3, block + 5)
            assertArrayEquals(expected, read(it, range))
            awaitCache(cache)
            assertArrayEquals(expected, read(it, range))
            assertEquals(content.size.toLong(), downloaded.get())
            assertEquals(content.size.toLong(), cache.usedBytes.value)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun outOfBoundsRangeReturns416WithoutDownloadingABlock() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { source ->
            consumer.newCall(Request.Builder().url(source.url).header("Range", "bytes=100-").build()).execute().use {
                assertEquals(416, it.code)
                assertEquals("bytes */16", it.header("Content-Range"))
            }
        }
        assertEquals(1, server.requestCount)
        assertEquals(0L, downloaded.get())
    }

    @Test fun serversIgnoringRangeAreStoredAndServedAtCorrectOffsets() {
        supportsRanges = false
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { assertArrayEquals("2345".toByteArray(), read(it, "bytes=2-5")) }
        assertEquals(content.size.toLong(), cache.usedBytes.value)
        assertEquals(content.size.toLong(), downloaded.get())
        assertEquals(2, server.requestCount)
    }

    @Test fun missingHttpValidatorsDoNotThrowAwayStoredMediaOnReopen() {
        etag = null
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { read(it); awaitCache(cache); read(it) }
        source(cache).use { read(it) }
        assertEquals(3, server.requestCount)
        assertEquals(16L, downloaded.get())
    }

    @Test fun headUnsupportedServersProbeAndReuseACompleteFirstBlock() {
        supportsHead = false
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { assertArrayEquals(content, read(it)); awaitCache(cache) }
        assertEquals(content.size.toLong() + 1, downloaded.get())
        assertEquals(3, server.requestCount) // failed HEAD, one-byte probe, first block
        source(cache).use { assertArrayEquals(content, read(it)) }
        assertEquals(content.size.toLong() + 2, downloaded.get())
        assertEquals(5, server.requestCount) // reopen only validates, no repeated video block
    }

    @Test fun headUnsupportedLargeVideoCachesActualFileLengthAndReusesSavedPosition() {
        supportsHead = false
        content = ByteArray(5 * 1024 * 1024 + 123) { (it % 251).toByte() }
        val directory = temporary.newFolder()
        val cache = PlaybackDiskCache(directory)
        val offset = 4 * 1024 * 1024
        val range = "bytes=$offset-${offset + 31}"
        source(cache).use { assertArrayEquals(content.copyOfRange(offset, offset + 32), read(it, range)); awaitCache(cache, minOf(PlaybackDiskCache.BLOCK_BYTES.toLong(), content.size.toLong() - offset)) }
        val before = downloaded.get()
        source(PlaybackDiskCache(directory)).use { assertArrayEquals(content.copyOfRange(offset, offset + 32), read(it, range)) }
        assertEquals(before + 1, downloaded.get()) // only a one-byte validation
    }

    @Test fun freshWebDavMetadataReusesCacheWhenCdnOmitsValidatorsAndQueryChanges() {
        etag = null
        val directory = temporary.newFolder()
        fun playback(query: String, modified: String = "2026-10-18T12:00:00Z") = request(
            url = server.url("/video.mkv?token=$query").toString(), cacheIdentity = "account-file", modified = modified)
        source(PlaybackDiskCache(directory), playback("old")).use { assertArrayEquals(content, read(it)) }
        source(PlaybackDiskCache(directory), playback("new")).use { assertArrayEquals(content, read(it)) }
        assertEquals(16L, downloaded.get())
        content = "fedcba9876543210".toByteArray()
        source(PlaybackDiskCache(directory), playback("latest", "2026-10-18T13:00:00Z")).use { assertArrayEquals(content, read(it)) }
        assertEquals(32L, downloaded.get())
    }

    @Test fun identicalPathsAndValidatorsNeverShareBytesBetweenAccounts() {
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache, request(auth = "Basic user-A")).use { read(it) }
        source(cache, request(auth = "Basic user-B")).use { read(it) }
        assertEquals(32L, downloaded.get())
    }

    @Test fun weakEtagIsNeverSentAsIfRangeToAnHttpCompliantServer() {
        etag = "W/\"version-1\""
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("If-Range")?.startsWith("W/") == true)
                    return MockResponse().setBody(Buffer().write(content))
                return original.dispatch(request)
            }
        }
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { read(it); awaitCache(cache) }
        source(cache).use { read(it) }
        assertEquals(16L, downloaded.get())
    }

    @Test fun firstByteArrivesBeforeASlowBlockCompletesAndSizeGrowsWhileStreaming() {
        content = ByteArray(PlaybackDiskCache.BLOCK_BYTES * 2) { (it % 251).toByte() }
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = original.dispatch(request)
                if (request.method == "GET") response.throttleBody(PlaybackDiskCache.BLOCK_BYTES / 8L, 250, TimeUnit.MILLISECONDS)
                return response
            }
        }
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { endpoint ->
            val start = System.nanoTime()
            consumer.newCall(Request.Builder().url(endpoint.url).build()).execute().use { response ->
                val stream = response.body!!.byteStream()
                assertEquals(0, stream.read())
                assertTrue("Must stream before the 1.75s block finishes", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_300)
                awaitCache(cache, PlaybackDiskCache.BLOCK_BYTES.toLong())
                assertTrue(downloaded.get() < content.size)
            }
        }
        assertTrue(cache.usedBytes.value > 0)
    }

    @Test fun diskFailureStillDeliversTheVideo() {
        val cache = PlaybackDiskCache(temporary.newFile())
        source(cache).use { assertArrayEquals(content, read(it)) }
        assertEquals(0L, cache.usedBytes.value)
    }

    @Test fun anEarlyConsumerDisconnectStillFinishesAndPersistsTheBlock() {
        slowBody = true
        val directory = temporary.newFolder()
        val cache = PlaybackDiskCache(directory)
        source(cache).use { source ->
            consumer.newCall(Request.Builder().url(source.url).build()).execute().use {
                assertEquals('0'.code, it.body!!.byteStream().read())
            }
            awaitCache(cache)
        }
        assertEquals(content.size.toLong(), cache.usedBytes.value)
        source(PlaybackDiskCache(directory)).use { assertArrayEquals(content, read(it)) }
        assertEquals(content.size.toLong(), downloaded.get())
    }

    @Test fun aFailedCachedRangeFallsBackToDirectStreamingBeforeWritingAProbe() {
        var videoGets = 0
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET" && ++videoGets == 1) return MockResponse().setResponseCode(500)
                return original.dispatch(request)
            }
        }
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { assertArrayEquals("2345".toByteArray(), read(it, "bytes=2-5")) }
        assertEquals(0L, cache.usedBytes.value)
        source(cache).use { assertArrayEquals("2345".toByteArray(), read(it, "bytes=2-5")) }
    }

    @Test fun weakEtagsRemainUsableForSafeReopening() {
        etag = "W/\"version-1\""
        val directory = temporary.newFolder()
        source(PlaybackDiskCache(directory)).use { assertArrayEquals(content, read(it)) }
        source(PlaybackDiskCache(directory)).use { assertArrayEquals(content, read(it)) }
        assertEquals(16L, downloaded.get())
        assertEquals(3, server.requestCount)
    }

    @Test fun cachedRangeFetchesStillStripCredentialsOnCrossOriginRedirects() {
        MockWebServer().use { destination ->
            destination.dispatcher = server.dispatcher
            destination.start()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(302)
                    .setHeader("Location", destination.url("/signed.mkv?token=temporary"))
            }
            val cache = PlaybackDiskCache(temporary.newFolder())
            source(cache).use { assertArrayEquals("2345".toByteArray(), read(it, "bytes=2-5")); awaitCache(cache) }
            repeat(2) {
                assertEquals("Basic private", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                assertNull(destination.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            }
            assertEquals(content.size.toLong(), downloaded.get())
        }
    }

    @Test fun aChangedValidatorBetweenHeadAndGetFallsBackBeforeSendingCachedHeaders() {
        val original = server.dispatcher
        var changed = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET" && !changed) {
                    changed = true
                    content = "fedcba9876543210".toByteArray()
                    etag = "\"version-2\""
                }
                return original.dispatch(request)
            }
        }
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use {
            assertArrayEquals("dcba".toByteArray(), read(it, "bytes=2-5"))
            assertEquals(0L, cache.usedBytes.value)
            assertArrayEquals("dcba".toByteArray(), read(it, "bytes=2-5"))
            awaitCache(cache)
        }
    }

    @Test fun explicitLocalMediaStorageIsIndependentOfBrowserNoStoreHeaders() {
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = original.dispatch(request).setHeader("Cache-Control", "no-store")
        }
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { assertArrayEquals("2345".toByteArray(), read(it, "bytes=2-5")) }
        assertEquals(content.size.toLong(), cache.usedBytes.value)
    }

    @Test fun cloudDownloadNoStoreDoesNotDisableUserEnabledVideoStorage() {
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = original.dispatch(request)
                if (request.method == "GET") response.setHeader("Cache-Control", "no-store")
                return response
            }
        }
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache).use { assertArrayEquals(content, read(it)) }
        assertEquals(content.size.toLong(), cache.usedBytes.value)
    }

    @Test fun openListRedirectsNoStoreAndChangingCdnEtagsStillPersistAndSeekOffline() {
        content = ByteArray(PlaybackDiskCache.BLOCK_BYTES * 5 + 17) { (it % 251).toByte() }
        val directory = temporary.newFolder()
        val upstream = server.dispatcher
        MockWebServer().use { cdn ->
            cdn.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertNull("OpenList credentials leaked", request.getHeader("Authorization"))
                    if (request.method == "HEAD") return MockResponse().setResponseCode(405)
                    return upstream.dispatch(request).setHeader("ETag", "W/\"temporary-${System.nanoTime()}\"")
                        .setHeader("Cache-Control", "max-age=0, no-cache, no-store, must-revalidate")
                }
            }
            cdn.start()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("Basic private", request.getHeader("Authorization"))
                    return MockResponse().setResponseCode(302).setHeader("Cache-Control", "no-store")
                        .setHeader("Location", cdn.url("/cloud.mkv?sign=${System.nanoTime()}"))
                }
            }
            val playback = request(cacheIdentity = "openlist-account-file", knownSize = true, modified = "2026-10-18T12:00:00Z")
            val cache = PlaybackDiskCache(directory)
            source(cache, playback).use { assertArrayEquals(content, read(it)) }
            assertEquals(content.size.toLong(), cache.usedBytes.value)
            assertNotNull(PlaybackDiskCache(directory).cachedMedia("openlist-account-file"))
            val originCalls = server.requestCount
            val cdnCalls = cdn.requestCount
            // Both services are now unavailable: every seek/reopen must use disk.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
            }
            cdn.dispatcher = server.dispatcher
            source(PlaybackDiskCache(directory), playback).use { endpoint ->
                consumer.newCall(Request.Builder().url(endpoint.url).head().build()).execute().use {
                    assertEquals(200, it.code); assertEquals(content.size.toString(), it.header("Content-Length"))
                }
                for (offset in listOf(content.size - 100, 0, 300_000, 700_000, 100)) {
                    assertArrayEquals(content.copyOfRange(offset, offset + 64), read(endpoint, "bytes=$offset-${offset + 63}"))
                }
            }
            assertEquals(originCalls, server.requestCount)
            assertEquals(cdnCalls, cdn.requestCount)
            assertEquals(content.size.toLong(), downloaded.get())
        }
    }

    @Test fun exitingMidBlockKeepsThePrefixAndReopenOnlyDownloadsMissingBytes() {
        content = ByteArray(PlaybackDiskCache.BLOCK_BYTES * 2) { (it % 251).toByte() }
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = original.dispatch(request)
                .throttleBody(32 * 1024, 300, TimeUnit.MILLISECONDS)
        }
        val directory = temporary.newFolder()
        val playback = request(cacheIdentity = "partial-file", knownSize = true, modified = "version")
        val cache = PlaybackDiskCache(directory)
        source(cache, playback).use { endpoint ->
            consumer.newCall(Request.Builder().url(endpoint.url).build()).execute().use { response ->
                val first = ByteArray(4096)
                response.body!!.source().readFully(first)
                assertArrayEquals(content.copyOfRange(0, 4096), first)
            }
        }
        val retained = cache.usedBytes.value
        assertTrue("Partial data vanished on stop", retained in 4096 until PlaybackDiskCache.BLOCK_BYTES.toLong())
        server.dispatcher = original
        val before = server.requestCount
        val reopened = PlaybackDiskCache(directory)
        source(reopened, playback).use { endpoint ->
            assertArrayEquals(content.copyOfRange(0, 4096), read(endpoint, "bytes=0-4095"))
            assertEquals(before, server.requestCount)
            assertArrayEquals(content.copyOfRange(0, PlaybackDiskCache.BLOCK_BYTES),
                read(endpoint, "bytes=0-${PlaybackDiskCache.BLOCK_BYTES - 1}"))
        }
        assertEquals(PlaybackDiskCache.BLOCK_BYTES.toLong(), downloaded.get())
        assertEquals(PlaybackDiskCache.BLOCK_BYTES.toLong(), reopened.usedBytes.value)
    }

    @Test fun cappedAndChunkedRangeResponsesAreCombinedAndSaved() {
        content = ByteArray(PlaybackDiskCache.BLOCK_BYTES) { (it % 251).toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("GET", request.method)
                val start = request.getHeader("Range")!!.substringAfter("bytes=").substringBefore('-').toInt()
                val end = minOf(start + 32 * 1024 - 1, content.lastIndex)
                return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${content.size}")
                    .setChunkedBody(Buffer().write(content, start, end - start + 1), 4096)
            }
        }
        val cache = PlaybackDiskCache(temporary.newFolder())
        val playback = request(knownSize = true)
        source(cache, playback).use { assertArrayEquals(content, read(it)) }
        val chunks = PlaybackDiskCache.BLOCK_BYTES / (32 * 1024)
        assertEquals(chunks, server.requestCount)
        source(cache, playback).use { assertArrayEquals(content, read(it)) }
        assertEquals(chunks, server.requestCount)
        assertEquals(content.size.toLong(), cache.usedBytes.value)
    }

    @Test fun ignoringRangeAtADistantPositionAlsoStoresTheSkippedBeginning() {
        supportsRanges = false
        content = ByteArray(PlaybackDiskCache.BLOCK_BYTES * 3) { (it % 251).toByte() }
        val cache = PlaybackDiskCache(temporary.newFolder())
        val playback = request(knownSize = true)
        val offset = PlaybackDiskCache.BLOCK_BYTES + 10
        source(cache, playback).use {
            assertArrayEquals(content.copyOfRange(offset, offset + 64), read(it, "bytes=$offset-${offset + 63}"))
            awaitCache(cache, PlaybackDiskCache.BLOCK_BYTES * 2L)
        }
        val before = server.requestCount
        source(cache, playback).use { assertArrayEquals(content.copyOfRange(100, 200), read(it, "bytes=100-199")) }
        assertEquals(before, server.requestCount)
    }

    @Test fun readAheadContinuesToEofAfterThePlayerStopsReading() {
        content = ByteArray(PlaybackDiskCache.BLOCK_BYTES * 6 + 17) { (it % 251).toByte() }
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache, request(knownSize = true)).use { endpoint ->
            consumer.newCall(Request.Builder().url(endpoint.url).header("Range", "bytes=0-").build()).execute().use {
                assertEquals(0, it.body!!.byteStream().read())
                // Equivalent to the demuxer filling its RAM buffer or pausing playback.
            }
            awaitCache(cache)
            assertEquals(content.size.toLong(), downloaded.get())
            assertArrayEquals(content.copyOfRange(content.size - 64, content.size), read(endpoint, "bytes=-64"))
            assertEquals(1, server.requestCount) // one long response across all disk blocks
            runBlocking { withTimeout(2_000) { while (endpoint.downloadProgress()?.reachedEnd != true) kotlinx.coroutines.delay(10) } }
            assertEquals(content.size.toLong(), endpoint.downloadProgress()!!.storedBytes)
        }
    }

    @Test fun aResumePositionDownloadsOnlyThatBlockThroughEof() {
        content = ByteArray(PlaybackDiskCache.BLOCK_BYTES * 5 + 37) { (it % 251).toByte() }
        val offset = PlaybackDiskCache.BLOCK_BYTES * 3
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache, request(knownSize = true)).use { endpoint ->
            consumer.newCall(Request.Builder().url(endpoint.url).header("Range", "bytes=$offset-").build()).execute().use {
                assertEquals(content[offset].toInt() and 255, it.body!!.byteStream().read())
            }
            awaitCache(cache, content.size.toLong() - offset)
            assertEquals(content.size.toLong() - offset, downloaded.get())
            assertEquals(content.size.toLong() - offset, cache.usedBytes.value)
            assertEquals("bytes=$offset-${content.lastIndex}", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Range"))
        }
    }

    @Test fun seekingCancelsTheOldStreamAndContinuesFromTheNewPosition() {
        content = ByteArray(PlaybackDiskCache.BLOCK_BYTES * 8) { (it % 251).toByte() }
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = original.dispatch(request)
                .throttleBody(128 * 1024, 40, TimeUnit.MILLISECONDS)
        }
        val cache = PlaybackDiskCache(temporary.newFolder())
        val offset = PlaybackDiskCache.BLOCK_BYTES * 6
        source(cache, request(knownSize = true)).use { endpoint ->
            consumer.newCall(Request.Builder().url(endpoint.url).header("Range", "bytes=0-").build()).execute().use {
                assertEquals(0, it.body!!.byteStream().read())
            }
            endpoint.prepareSeek()
            consumer.newCall(Request.Builder().url(endpoint.url).header("Range", "bytes=$offset-").build()).execute().use {
                assertEquals(content[offset].toInt() and 255, it.body!!.byteStream().read())
            }
            runBlocking { withTimeout(6_000) {
                while (true) {
                    val files = cache.directory().listFiles().orEmpty().filter { it.name.endsWith(".blk") && it.name.substringBeforeLast('.').endsWith(".7") }
                    if (files.any { it.length() == PlaybackDiskCache.BLOCK_BYTES.toLong() }) break
                    kotlinx.coroutines.delay(20)
                }
            } }
            val firstBlock = cache.directory().listFiles().orEmpty().first { it.name.endsWith(".0.blk") }
            assertTrue("Old position must not keep filling", firstBlock.length() < PlaybackDiskCache.BLOCK_BYTES)
            assertTrue("Do not download the skipped middle", cache.directory().listFiles().orEmpty().none {
                it.name.endsWith(".2.blk") || it.name.endsWith(".3.blk") || it.name.endsWith(".4.blk") || it.name.endsWith(".5.blk")
            })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun readAheadSkipsAnAlreadyCachedSpanWithoutRedownloadingIt() {
        val block = PlaybackDiskCache.BLOCK_BYTES
        content = ByteArray(block * 4 + 17) { (it % 251).toByte() }
        val cache = PlaybackDiskCache(temporary.newFolder())
        val playback = request(knownSize = true)
        source(cache, playback).use { endpoint ->
            read(endpoint, "bytes=${2 * block}-${3 * block - 1}")
            awaitCache(cache, block.toLong())
            consumer.newCall(Request.Builder().url(endpoint.url).header("Range", "bytes=0-").build()).execute().use {
                assertEquals(0, it.body!!.byteStream().read())
            }
            awaitCache(cache)
            assertEquals(content.size.toLong(), downloaded.get())
            assertArrayEquals(content, read(endpoint))
            assertEquals(content.size.toLong(), downloaded.get())
        }
    }

    @Test fun capacityPausesReadAheadWithoutEvictingUnreadBytesAndReadingResumesIt() {
        val block = PlaybackDiskCache.BLOCK_BYTES
        content = ByteArray(block * 10) { (it % 251).toByte() }
        val cache = PlaybackDiskCache(temporary.newFolder(), maxBytes = 6L * block)
        source(cache, request(knownSize = true)).use { endpoint ->
            consumer.newCall(Request.Builder().url(endpoint.url).header("Range", "bytes=0-").build()).execute().use { response ->
                assertEquals(0, response.body!!.byteStream().read())
                Thread.sleep(200)
                assertTrue(cache.usedBytes.value <= 6L * block)
                // Consume the original stream; all bytes remain correct while the bounded window advances.
                val remaining = response.body!!.byteStream().readBytes()
                assertArrayEquals(content.copyOfRange(1, content.size), remaining)
            }
            assertTrue(cache.usedBytes.value <= 6L * block)
        }
    }

    @Test fun interruptedContinuousResponseResumesAtTheSavedPrefix() {
        content = ByteArray(PlaybackDiskCache.BLOCK_BYTES * 3) { (it % 251).toByte() }
        val original = server.dispatcher
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = original.dispatch(request)
                return if (request.method == "GET" && first.getAndSet(false))
                    response.setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                else response
            }
        }
        val cache = PlaybackDiskCache(temporary.newFolder())
        source(cache, request(knownSize = true)).use { endpoint ->
            assertArrayEquals(content, read(endpoint))
            awaitCache(cache)
            assertEquals(content.size.toLong(), downloaded.get())
            assertEquals(2, server.requestCount)
            assertEquals("bytes=0-${content.lastIndex}", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Range"))
            val resumed = server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Range")!!.substringAfter("bytes=").substringBefore('-').toLong()
            assertTrue("Resume must keep the downloaded prefix", resumed > 0)
        }
    }
}
