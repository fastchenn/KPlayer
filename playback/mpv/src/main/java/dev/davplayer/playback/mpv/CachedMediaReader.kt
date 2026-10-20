package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.PlaybackRequest
import dev.davplayer.playback.api.CachedTimeRange
import dev.davplayer.playback.api.CacheDownloadProgress
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import okhttp3.Call

/** Cancels only the old read-ahead, including a request still waiting for headers. */
internal class MediaFetchScope {
    @Volatile var cancelled = false
        private set
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    fun attach(call: Call) { calls.add(call); if (cancelled) call.cancel() }
    fun detach(call: Call) { calls.remove(call) }
    fun cancel() { cancelled = true; calls.forEach(Call::cancel) }
}

internal interface MediaFetcher {
    fun <T> useResponse(method: String, headers: Map<String, String>, scope: MediaFetchScope? = null, read: (Response) -> T): T
}

/** User-enabled local media storage, independent of HTTP proxy/browser cache policy. */
internal class CachedMediaReader(
    private val request: PlaybackRequest,
    private val cache: PlaybackDiskCache,
    private val fetcher: MediaFetcher,
    private val onBytesRead: (Int) -> Unit,
    private val isClosed: () -> Boolean,
) : AutoCloseable {
    private data class Metadata(val media: CachedMediaInfo, val etag: String? = null, val modified: String? = null)
    private class Download(val bytes: ByteArray, var available: Int) {
        val monitor = Object()
        val scope = MediaFetchScope()
        var complete = false
        var error: IOException? = null
    }
    private val namespace = PlaybackDiskCache.namespace(request)
    private val generation = cache.generation()
    private val downloads = ConcurrentHashMap<Long, Download>()
    private val held = object : LinkedHashMap<Long, ByteArray>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 2
    }
    private val downloader = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "KPlayer-cache-block").apply { isDaemon = true }
    }
    private val readAheadWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KPlayer-read-ahead").apply { isDaemon = true }
    }
    private val readAheadLock = Object()
    private class ReadAhead(val epoch: Long, val position: Long, val scope: MediaFetchScope = MediaFetchScope()) {
        val owned = ConcurrentHashMap<Long, Download>()
        @Volatile var finished = false
        @Volatile var reachedEnd = false
        var failure: IOException? = null
    }
    @Volatile private var readAhead: ReadAhead? = null
    @Volatile private var seekEpoch = 0L
    @Volatile private var consumedPosition = 0L
    @Volatile private var closed = false
    @Volatile private var metadata: Metadata? = null
    private var probed = false
    private val deliveredLock = Any()
    private var delivered = emptyList<CachedByteRange>()
    @Volatile private var timelineEligible = true

    @Volatile private var playbackEpoch = 0L
    private val playedCoverage = PlayedTimeCoverage()
    private var containerIndex: MatroskaCacheIndex? = null
    private var containerIndexKey: String? = null

    /** Capture with the native sample, before an IO worker or a seek can change its meaning. */
    fun timelineSnapshot(): CacheReadSnapshot = synchronized(deliveredLock) {
        CacheReadSnapshot(seekEpoch, playbackEpoch, delivered, timelineEligible)
    }

    fun nativeSeek() { playbackEpoch++ }

    fun cachedTimeline(
        observed: List<CachedTimeRange>, durationMs: Long?,
        snapshot: CacheReadSnapshot = timelineSnapshot(), sample: PlaybackTimelineSample? = null,
    ): List<CachedTimeRange> {
        val meta = metadata ?: return emptyList()
        val media = meta.media
        // Read dependencies before inspecting disk. The opposite order lost observations
        // whenever downloading/serving advanced during the disk snapshot.
        val available = cache.byteRanges(media.key)
        if (durationMs != null && durationMs > 0 && available.any { it.start == 0L && it.end >= media.size }) {
            return cache.timeline(media.key, listOf(CachedTimeRange(0, durationMs)),
                listOf(CachedByteRange(0, media.size)), generation)
        }
        val current = snapshot.seekEpoch == seekEpoch
        val played = playedCoverage.observe(snapshot.playbackEpoch, sample.takeIf { current && snapshot.eligible })
        if (containerIndexKey != media.key) {
            containerIndexKey = media.key
            containerIndex = if (request.title.substringAfterLast('.').lowercase() in setOf("mkv", "webm"))
                MatroskaCacheIndex(media.size, { position, count -> cache.readRange(media.key, position, count) },
                    { position, count -> requestIndexBytes(meta, position, count) }) else null
        }
        val indexed = containerIndex?.proofs(available, durationMs, sample?.originMs ?: 0).orEmpty()
        containerIndex?.let { cache.retainMetadata(media.key, it.metadataRanges()) }
        return cache.timeline(media.key, if (current && snapshot.eligible) observed + played else emptyList(),
            if (current && snapshot.eligible) snapshot.bytes else emptyList(), generation, indexed)
    }

    private fun requestIndexBytes(meta: Metadata, position: Long, count: Int) {
        if (stopped() || !cache.enabled || cache.generation() != generation) return
        val first = position / PlaybackDiskCache.BLOCK_BYTES
        val last = (position + count - 1) / PlaybackDiskCache.BLOCK_BYTES
        for (index in first..last) {
            val length = minOf(PlaybackDiskCache.BLOCK_BYTES.toLong(), meta.media.size - index * PlaybackDiskCache.BLOCK_BYTES).toInt()
            if (cache.prefixLength(meta.media.key, index, length) >= length) continue
            val prefix = cache.readPrefix(meta.media.key, index, length) ?: ByteArray(0)
            download(meta, index, length, prefix)
        }
    }

    fun downloadProgress(): CacheDownloadProgress? {
        val media = metadata?.media ?: return null
        if (!cache.enabled || generation != cache.generation()) return null
        val available = cache.byteRanges(media.key)
        val run = readAhead
        val start = (run?.position ?: 0L) / PlaybackDiskCache.BLOCK_BYTES * PlaybackDiskCache.BLOCK_BYTES
        val reachedEnd = run != null && !run.scope.cancelled && run.epoch == seekEpoch && run.reachedEnd &&
            available.any { it.start <= start && it.end >= media.size }
        return CacheDownloadProgress(available.sumOf { it.end - it.start }, reachedEnd)
    }

    fun bypassed() { timelineEligible = false }

    /** The next open-ended libmpv request supplies the exact container byte offset. */
    fun prepareSeek() {
        synchronized(readAheadLock) {
            seekEpoch++
            playbackEpoch++
            readAhead?.let { it.scope.cancel(); finishAhead(it) }
            for ((index, block) in downloads) {
                block.scope.cancel()
                synchronized(block.monitor) { block.error = IOException("Seek superseded"); block.complete = true; block.monitor.notifyAll() }
                downloads.remove(index, block)
            }
            readAheadLock.notifyAll()
        }
    }

    private fun delivered(position: Long, count: Int) {
        if (count <= 0) return
        synchronized(deliveredLock) {
            delivered = mergeByteRanges(delivered + CachedByteRange(position, position + count))
            if (delivered.size > 256) { delivered = emptyList(); timelineEligible = false }
        }
    }

    fun serve(method: String, incoming: Map<String, String>, output: OutputStream): Boolean {
        if (method !in setOf("GET", "HEAD") || !cache.enabled || incoming.keys.any {
                it in setOf("if-none-match", "if-modified-since")
            }) return false
        val meta = probe() ?: return false
        val size = meta.media.size
        val range = parseRange(if (method == "HEAD") null else incoming["range"], size) ?: return false
        if (range.first >= size || range.last < range.first) {
            output.write("HTTP/1.1 416 Response\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return true
        }
        val partial = method == "GET" && incoming["range"] != null
        val headers = buildString {
            append("HTTP/1.1 ${if (partial) 206 else 200} Response\r\nConnection: close\r\nCache-Control: no-store\r\nAccept-Ranges: bytes\r\n")
            append("Content-Length: ${range.last - range.first + 1}\r\n")
            if (partial) append("Content-Range: bytes ${range.first}-${range.last}/$size\r\n")
            meta.media.type?.takeIf { it.all { c -> c.code in 32..126 } }?.let { append("Content-Type: $it\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        if (method == "HEAD") { output.write(headers); return true }
        val epoch = seekEpoch
        // Bounded header/index probes must not redirect the background download.
        val sequential = incoming["range"] == null || incoming["range"]?.matches(Regex("bytes=[0-9]+-")) == true
        val primary = if (sequential) synchronized(readAheadLock) {
            startReadAhead(meta, range.first, epoch)
            readAhead
        } else null
        var headersSent = false
        var position = range.first
        try {
            while (position <= range.last && !stopped() && epoch == seekEpoch) {
                val index = position / PlaybackDiskCache.BLOCK_BYTES
                val blockStart = index * PlaybackDiskCache.BLOCK_BYTES
                var from = (position - blockStart).toInt()
                val length = minOf(PlaybackDiskCache.BLOCK_BYTES.toLong(), size - blockStart).toInt()
                val to = minOf(length.toLong(), range.last - blockStart + 1).toInt()
                val prefix = cache.readPrefix(meta.media.key, index, length) ?: synchronized(held) { held[index] } ?: ByteArray(0)
                if (prefix.size >= to) {
                    if (!headersSent) { output.write(headers); headersSent = true }
                    delivered(position, to - from)
                    output.write(prefix, from, to - from)
                } else {
                    val download = download(meta, index, length, prefix)
                    while (from < to && !stopped() && epoch == seekEpoch) {
                        val available = synchronized(download.monitor) {
                            while (download.available <= from && !download.complete && !stopped() && epoch == seekEpoch) download.monitor.wait(250)
                            if (download.available <= from) download.error?.let { throw it }
                            minOf(download.available, to)
                        }
                        if (available <= from) throw IOException("Incomplete media block")
                        if (!headersSent) { output.write(headers); headersSent = true }
                        delivered(blockStart + from, available - from)
                        output.write(download.bytes, from, available - from)
                        from = available
                    }
                }
                position = blockStart + to
                if (sequential) synchronized(readAheadLock) {
                    if (epoch == seekEpoch && readAhead === primary) {
                        consumedPosition = maxOf(consumedPosition, position)
                        readAheadLock.notifyAll()
                    }
                }
            }
        } catch (error: SourceChanged) {
            synchronized(this) { metadata = null; probed = false }
            if (!headersSent) return false
            throw error
        } catch (_: IOException) {
            if (!headersSent && !stopped()) return false
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return true
    }

    @Synchronized private fun probe(): Metadata? {
        if (probed) return metadata
        probed = true
        // WebDAV already supplied file size/version. Never make cached playback wait
        // for a fresh CDN link, HEAD, ETag, or an online server.
        request.sourceSizeBytes?.takeIf { it > 0 }?.let { size ->
            return describe(size, request.sourceModifiedAt, null).also { metadata = it }
        }
        request.cacheIdentity?.let { identity -> cache.cachedMedia(identity)?.let { stored ->
            return Metadata(stored).also { metadata = it }
        } }
        metadata = try {
            fetcher.useResponse("HEAD", emptyMap()) { response ->
                if (response.isSuccessful) describeResponse(response, response.header("Content-Length")?.toLongOrNull()) else null
            }
        } catch (_: IOException) { null }
        if (metadata != null) return metadata
        metadata = try {
            fetcher.useResponse("GET", mapOf("range" to "bytes=0-0")) { response ->
                val range = response.header("Content-Range")?.let(CONTENT_RANGE::matchEntire)
                val size = if (response.code == 206 && range != null && range.groupValues[1] == "0" && range.groupValues[2] == "0")
                    range.groupValues[3].toLongOrNull() else if (response.code == 200) response.body?.contentLength() else null
                val result = describeResponse(response, size)
                response.body?.byteStream()?.use { if (it.read() >= 0) onBytesRead(1) }
                result
            }
        } catch (_: IOException) { null }
        if (metadata == null && !stopped()) cache.reportIssue("服务器未提供有效的视频大小，暂时无法建立播放缓存")
        return metadata
    }

    private fun describeResponse(response: Response, size: Long?): Metadata? {
        if (size == null || size <= 0) return null
        val etag = response.header("ETag")?.takeIf { it.isNotBlank() }
        val modified = response.header("Last-Modified")?.takeIf { it.isNotBlank() }
        val remoteVersion = request.cacheIdentity == null
        return describe(size, request.sourceModifiedAt ?: if (remoteVersion) etag ?: modified else null, response.header("Content-Type"))
            .copy(etag = if (remoteVersion) etag else null, modified = if (remoteVersion) modified else null)
    }

    private fun describe(size: Long, version: String?, type: String?): Metadata {
        val key = PlaybackDiskCache.key("blocks-v3-${PlaybackDiskCache.BLOCK_BYTES}\u0000$namespace\u0000$size\u0000${version.orEmpty()}")
        val previous = cache.readMedia(namespace)?.takeIf { it.key == key }
        val original = request.url.toHttpUrl()
        val subtitles = request.externalSubtitles.take(64).mapNotNull { subtitle ->
            val url = runCatching { subtitle.url.toHttpUrl() }.getOrNull() ?: return@mapNotNull null
            if (url.scheme != original.scheme || url.host != original.host || url.port != original.port ||
                url.encodedPath.substringBeforeLast('/') != original.encodedPath.substringBeforeLast('/') ||
                url.encodedPath.length > 8192 || subtitle.title.length > 2048) return@mapNotNull null
            CachedSubtitle(url.encodedPath, subtitle.title, subtitle.sizeBytes, subtitle.modifiedAt)
        }.ifEmpty { previous?.subtitles.orEmpty() }
        val info = CachedMediaInfo(key, size, version, type ?: previous?.type, subtitles)
        cache.remember(namespace, info, generation)
        return Metadata(info)
    }

    /** One independent upstream stream, with only two live blocks in memory. */
    private fun startReadAhead(meta: Metadata, position: Long, epoch: Long) {
        synchronized(readAheadLock) {
            if (epoch != seekEpoch || stopped()) return
            val previous = readAhead
            if (previous != null && previous.epoch == epoch && previous.position == position && !previous.finished && !previous.scope.cancelled) return
            previous?.let { it.scope.cancel(); finishAhead(it) }
            consumedPosition = position
            val run = ReadAhead(epoch, position)
            readAhead = run
            // Reserve before serving so playback joins this download instead of duplicating it.
            reserve(run, meta, position / PlaybackDiskCache.BLOCK_BYTES)
            try { readAheadWorker.execute { fillAhead(run, meta, position) } }
            catch (_: RejectedExecutionException) { finishAhead(run) }
        }
    }

    private fun aheadStopped(run: ReadAhead) = stopped() || run.scope.cancelled || run.epoch != seekEpoch ||
        !cache.enabled || cache.generation() != generation

    private fun reserve(run: ReadAhead, meta: Metadata, index: Long): Download? {
        val start = index * PlaybackDiskCache.BLOCK_BYTES
        if (start >= meta.media.size || aheadStopped(run)) return null
        downloads[index]?.let { return it }
        val length = minOf(PlaybackDiskCache.BLOCK_BYTES.toLong(), meta.media.size - start).toInt()
        if (cache.prefixLength(meta.media.key, index, length) == length) return null
        val prefix = cache.readPrefix(meta.media.key, index, length) ?: ByteArray(0)
        return downloads.computeIfAbsent(index) {
            Download(ByteArray(length).also { prefix.copyInto(it) }, prefix.size).also { run.owned[index] = it }
        }
    }

    private fun fillAhead(run: ReadAhead, meta: Metadata, position: Long) {
        var cursor = position / PlaybackDiskCache.BLOCK_BYTES * PlaybackDiskCache.BLOCK_BYTES
        var failures = 0
        try {
            while (cursor < meta.media.size && !aheadStopped(run)) {
                val index = cursor / PlaybackDiskCache.BLOCK_BYTES
                val block = reserve(run, meta, index)
                if (block == null) { cursor = minOf((index + 1) * PlaybackDiskCache.BLOCK_BYTES, meta.media.size); continue }
                if (run.owned[index] !== block) {
                    // A small demuxer probe is already downloading this block.
                    synchronized(block.monitor) {
                        while (!block.complete && !aheadStopped(run)) block.monitor.wait(250)
                        block.error?.let { throw it }
                    }
                    cursor = minOf((index + 1) * PlaybackDiskCache.BLOCK_BYTES, meta.media.size)
                    continue
                }
                val start = index * PlaybackDiskCache.BLOCK_BYTES + block.available
                // Skip existing cache spans with a new request, rather than download them again.
                val nextCached = cache.byteRanges(meta.media.key).firstOrNull { it.start > start }?.start
                val end = minOf(meta.media.size, nextCached ?: meta.media.size) - 1
                val headers = mutableMapOf("range" to "bytes=$start-$end")
                (meta.etag?.takeUnless { it.startsWith("W/", true) } ?: meta.modified)?.let { headers["if-range"] = it }
                try {
                    fetcher.useResponse("GET", headers, run.scope) { response ->
                        val body = response.body ?: throw IOException("Empty media body")
                        var absolute = start
                        val limit = when (response.code) {
                            206 -> {
                                val range = response.header("Content-Range")?.let(CONTENT_RANGE::matchEntire) ?: throw SourceChanged()
                                val last = range.groupValues[2].toLongOrNull() ?: throw SourceChanged()
                                if (range.groupValues[1].toLongOrNull() != start || range.groupValues[3].toLongOrNull() != meta.media.size ||
                                    last < start || last >= meta.media.size) throw SourceChanged()
                                minOf(end, last)
                            }
                            200 -> {
                                if (body.contentLength() >= 0 && body.contentLength() != meta.media.size) throw SourceChanged()
                                absolute = 0
                                end
                            }
                            else -> throw IOException("Media HTTP ${response.code}")
                        }
                        if ((meta.etag != null && response.header("ETag")?.let { it != meta.etag } == true) ||
                            (meta.modified != null && response.header("Last-Modified")?.let { it != meta.modified } == true)) throw SourceChanged()
                        body.byteStream().use { input ->
                            val skipped = ByteArray(128 * 1024)
                            while (absolute < start && !aheadStopped(run)) {
                                val count = input.read(skipped, 0, minOf(skipped.size.toLong(), start - absolute).toInt())
                                if (count < 0) throw IOException("Incomplete media body")
                                onBytesRead(count)
                                capture(meta.media.key, absolute, skipped, count)
                                absolute += count
                            }
                            while (absolute <= limit && !aheadStopped(run)) {
                                // Never evict unread ahead data to make space for still later data.
                                synchronized(readAheadLock) {
                                    while (absolute - consumedPosition >= cache.readAheadBudget() && !aheadStopped(run))
                                        readAheadLock.wait(250)
                                }
                                if (aheadStopped(run)) break
                                val currentIndex = absolute / PlaybackDiskCache.BLOCK_BYTES
                                val current = reserve(run, meta, currentIndex)
                                if (current == null || run.owned[currentIndex] !== current) break
                                val offset = (absolute % PlaybackDiskCache.BLOCK_BYTES).toInt()
                                val count = input.read(current.bytes, offset,
                                    minOf(128 * 1024L, current.bytes.size.toLong() - offset, limit - absolute + 1).toInt())
                                if (count < 0) throw IOException("Incomplete media body")
                                onBytesRead(count)
                                if (aheadStopped(run)) break
                                val stored = cache.append(meta.media.key, currentIndex, offset, current.bytes, offset, count, generation)
                                absolute += count
                                cursor = absolute
                                val complete = offset + count == current.bytes.size
                                // Reserve before publishing the final bytes, so the foreground cannot race it.
                                if (complete) reserve(run, meta, currentIndex + 1)
                                synchronized(current.monitor) { current.available = offset + count; current.monitor.notifyAll() }
                                if (complete) {
                                    synchronized(held) { held[currentIndex] = current.bytes }
                                    synchronized(current.monitor) { current.complete = true; current.monitor.notifyAll() }
                                    downloads.remove(currentIndex, current)
                                    run.owned.remove(currentIndex, current)
                                }
                                if (!stored) throw StorageFailure()
                                failures = 0
                            }
                        }
                        cursor = absolute
                    }
                } catch (error: StorageFailure) { throw error }
                catch (error: SourceChanged) { throw error }
                catch (error: IOException) {
                    if (aheadStopped(run) || ++failures >= 3) throw error
                    // Resume a broken response at the durable prefix, using a fresh redirect.
                    synchronized(readAheadLock) { if (!aheadStopped(run)) readAheadLock.wait(500L * failures) }
                }
            }
            run.reachedEnd = cursor >= meta.media.size && !aheadStopped(run)
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (error: IOException) {
            run.failure = error
            if (!aheadStopped(run)) cache.reportIssue(if (error is SourceChanged)
                "视频信息已变化，请从媒体库重新打开后缓存" else "持续缓存暂时中断，播放时会重试缺失数据")
        } finally { finishAhead(run) }
    }

    private fun finishAhead(run: ReadAhead) {
        run.finished = true
        for ((index, block) in run.owned) {
            synchronized(block.monitor) {
                block.error = run.failure ?: IOException("Read-ahead stopped")
                block.complete = true
                block.monitor.notifyAll()
            }
            downloads.remove(index, block)
        }
        run.owned.clear()
    }

    private fun download(meta: Metadata, index: Long, length: Int, prefix: ByteArray): Download = downloads.computeIfAbsent(index) {
        Download(ByteArray(length).also { prefix.copyInto(it) }, prefix.size).also { queued ->
            try { downloader.execute {
                try {
                    var offset = prefix.size
                    val blockStart = index * PlaybackDiskCache.BLOCK_BYTES
                    while (offset < length && !stopped() && !queued.scope.cancelled) {
                        val start = blockStart + offset
                        val end = blockStart + length - 1
                        val headers = mutableMapOf("range" to "bytes=$start-$end")
                        (meta.etag?.takeUnless { it.startsWith("W/", true) } ?: meta.modified)?.let { headers["if-range"] = it }
                        fetcher.useResponse("GET", headers, queued.scope) { response ->
                            val body = response.body ?: throw IOException("Empty media body")
                            var absolute = start
                            val limit = when (response.code) {
                                206 -> {
                                    val range = response.header("Content-Range")?.let(CONTENT_RANGE::matchEntire) ?: throw SourceChanged()
                                    val responseEnd = range.groupValues[2].toLongOrNull() ?: throw SourceChanged()
                                    if (range.groupValues[1].toLongOrNull() != start || range.groupValues[3].toLongOrNull() != meta.media.size ||
                                        responseEnd < start || responseEnd >= meta.media.size) throw SourceChanged()
                                    minOf(end, responseEnd)
                                }
                                200 -> {
                                    if (body.contentLength() >= 0 && body.contentLength() != meta.media.size) throw SourceChanged()
                                    absolute = 0
                                    end
                                }
                                else -> throw IOException("Media HTTP ${response.code}")
                            }
                            if ((meta.etag != null && response.header("ETag")?.let { it != meta.etag } == true) ||
                                (meta.modified != null && response.header("Last-Modified")?.let { it != meta.modified } == true)) throw SourceChanged()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(32 * 1024)
                                // Some cloud drives ignore Range and return the whole file.
                                // Retain the skipped prefix too, rather than disabling storage.
                                while (absolute < start && !stopped() && !queued.scope.cancelled) {
                                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), start - absolute).toInt())
                                    if (count < 0) throw IOException("Incomplete media body")
                                    onBytesRead(count)
                                    capture(meta.media.key, absolute, buffer, count)
                                    absolute += count
                                }
                                while (absolute <= limit && !stopped() && !queued.scope.cancelled) {
                                    val count = input.read(queued.bytes, offset, minOf(32 * 1024L, limit - absolute + 1).toInt())
                                    if (count < 0) throw IOException("Incomplete media block")
                                    onBytesRead(count)
                                    if (queued.scope.cancelled) throw IOException("Seek superseded")
                                    cache.append(meta.media.key, index, offset, queued.bytes, offset, count, generation)
                                    offset += count
                                    absolute += count
                                    synchronized(queued.monitor) { queued.available = offset; queued.monitor.notifyAll() }
                                }
                            }
                        }
                    }
                    if (offset != length) throw IOException("Stream closed")
                    synchronized(held) { held[index] = queued.bytes }
                } catch (error: IOException) {
                    if (!stopped() && !queued.scope.cancelled) cache.reportIssue(if (error is SourceChanged) "视频信息已变化，请从媒体库重新打开后缓存" else "缓存下载中断，请检查服务器与视频直链")
                    synchronized(queued.monitor) { queued.error = error }
                } finally {
                    synchronized(queued.monitor) { queued.complete = true; queued.monitor.notifyAll() }
                    downloads.remove(index, queued)
                }
            } } catch (_: RejectedExecutionException) {
                synchronized(queued.monitor) { queued.error = IOException("Stream closed"); queued.complete = true; queued.monitor.notifyAll() }
            }
        }
    }

    private fun capture(key: String, start: Long, buffer: ByteArray, count: Int) {
        var offset = 0
        var position = start
        while (offset < count) {
            val index = position / PlaybackDiskCache.BLOCK_BYTES
            val inBlock = (position % PlaybackDiskCache.BLOCK_BYTES).toInt()
            val amount = minOf(count - offset, PlaybackDiskCache.BLOCK_BYTES - inBlock)
            cache.append(key, index, inBlock, buffer, offset, amount, generation)
            offset += amount
            position += amount
        }
    }

    private fun stopped() = closed || isClosed()
    override fun close() {
        closed = true
        downloads.values.forEach { it.scope.cancel() }
        synchronized(readAheadLock) { readAhead?.scope?.cancel(); readAheadLock.notifyAll() }
        readAheadWorker.shutdownNow()
        downloader.shutdownNow()
    }
    private class StorageFailure : IOException("Cannot persist read-ahead")
    private class SourceChanged : IOException("Media source changed")

    companion object {
        private val CONTENT_RANGE = Regex("bytes\\s+([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
        private fun parseRange(header: String?, size: Long): LongRange? {
            if (header == null) return 0L..size - 1
            val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(header) ?: return null
            val first = match.groupValues[1]
            val last = match.groupValues[2]
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return (size - suffix).coerceAtLeast(0)..size - 1
            }
            val start = first.toLongOrNull() ?: return null
            val end = if (last.isEmpty()) size - 1 else (last.toLongOrNull() ?: return null).coerceAtMost(size - 1)
            return start..end
        }
    }
}
