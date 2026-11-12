package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.CachedTimeRange
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor

/** ISO BMFF sample tables, including VBR, B frames, edit lists and 64-bit file offsets. */
internal class Mp4CacheIndex(
    private val fileSize: Long,
    private val readAt: (Long, Int) -> ByteArray?,
    private val onMissing: (Long, Int) -> Unit = { _, _ -> },
) : ContainerCacheIndex {
    private class Missing : RuntimeException()
    private class Invalid : RuntimeException()
    private data class Atom(val type: String, val data: Int, val end: Int)
    private data class Span(val startMs: Double, val endMs: Double, val bytes: CachedByteRange)
    private data class Edit(val startMs: Double, val endMs: Double, val mediaMs: Double)
    private data class Index(val tracks: List<List<Span>>, val metadata: List<CachedByteRange>)
    private val discovered = mutableListOf<CachedByteRange>()
    private var index: Index? = null
    override val ready: Boolean get() = index != null
    private var unsupported = false

    override fun metadataRanges(): List<CachedByteRange> = index?.metadata ?: mergeByteRanges(discovered)

    override fun proofs(available: List<CachedByteRange>, durationMs: Long?, originMs: Long): List<CachedTimeProof> {
        if (unsupported) return emptyList()
        val current = index ?: try { load().also { index = it } }
        catch (_: Missing) { return emptyList() }
        catch (_: Invalid) { unsupported = true; return emptyList() }
        catch (_: IndexOutOfBoundsException) { unsupported = true; return emptyList() }
        val missing = current.metadata.filterNot { coversBytes(available, it.start, it.end) }
        if (missing.isNotEmpty()) {
            missing.forEach { request(it.start, it.end - it.start) }
            return emptyList()
        }
        fun coverage(track: List<Span>): List<CachedTimeProof> {
            val result = mutableListOf<CachedTimeProof>()
            var startMs = Double.NaN
            var endMs = Double.NaN
            var bytes = emptyList<CachedByteRange>()
            fun flush() {
                if (startMs.isNaN()) return
                val start = ceil((startMs - originMs).coerceAtLeast(0.0)).toLong()
                val end = floor((endMs - originMs).coerceAtLeast(0.0)).toLong().let { durationMs?.let { d -> minOf(it, d) } ?: it }
                if (end > start) result.add(CachedTimeProof(CachedTimeRange(start, end), bytes))
            }
            for (span in track) {
                if (!coversBytes(available, span.bytes.start, span.bytes.end)) continue
                // Join adjacent container timestamps BEFORE inward millisecond rounding.
                // Rounding every audio chunk separately creates a 1 ms hole at each boundary.
                if (!startMs.isNaN() && span.startMs <= endMs + 0.000001) {
                    endMs = maxOf(endMs, span.endMs)
                    val first = minOf(bytes.minOf { it.start }, span.bytes.start)
                    val last = maxOf(bytes.maxOf { it.end }, span.bytes.end)
                    bytes = if (coversBytes(available, first, last)) listOf(CachedByteRange(first, last))
                        else mergeByteRanges(bytes + span.bytes)
                } else {
                    flush()
                    startMs = span.startMs; endMs = span.endMs; bytes = listOf(span.bytes)
                }
            }
            flush()
            return result
        }
        var result = coverage(current.tracks.first())
        for (track in current.tracks.drop(1)) {
            val other = coverage(track)
            val intersection = mutableListOf<CachedTimeProof>()
            var a = 0; var b = 0
            while (a < result.size && b < other.size) {
                val x = result[a]; val y = other[b]
                val start = maxOf(x.time.startMs, y.time.startMs)
                val end = minOf(x.time.endMs, y.time.endMs)
                if (end > start) intersection.add(CachedTimeProof(CachedTimeRange(start, end), mergeByteRanges(x.bytes + y.bytes)))
                if (x.time.endMs <= y.time.endMs) a++ else b++
            }
            result = intersection
        }
        return result.map { it.copy(bytes = mergeByteRanges(current.metadata + it.bytes)) }
    }

    private fun request(position: Long, length: Long) {
        var offset = position
        while (offset < position + length) {
            val count = minOf(8_388_608L, position + length - offset).toInt()
            onMissing(offset, count)
            offset += count
        }
    }

    private fun load(): Index {
        discovered.clear()
        fun read(position: Long, length: Int): ByteArray {
            if (position < 0 || length <= 0 || position > fileSize - length) throw Invalid()
            discovered.add(CachedByteRange(position, position + length))
            return readAt(position, length)?.takeIf { it.size == length } ?: run { onMissing(position, length); throw Missing() }
        }
        var cursor = 0L
        var moov: ByteArray? = null
        var count = 0
        while (cursor < fileSize && count++ < 4096) {
            val header = ByteBuffer.wrap(read(cursor, 8)).order(ByteOrder.BIG_ENDIAN)
            var size = uint(header, 0)
            val type = fourcc(header, 4)
            var headerSize = 8
            if (size == 1L) { size = ByteBuffer.wrap(read(cursor + 8, 8)).long; headerSize = 16 }
            if (size == 0L) size = fileSize - cursor
            if (size < headerSize || size > fileSize - cursor) throw Invalid()
            if (type == "moof") throw Invalid()
            if (type == "ftyp") read(cursor, minOf(size, 65_536L).toInt())
            if (type == "moov") {
                if (size - headerSize !in 1..67_108_864L) throw Invalid()
                val length = (size - headerSize).toInt()
                val data = ByteArray(length)
                var missing = false
                var offset = 0
                while (offset < length) {
                    val n = minOf(8_388_608, length - offset)
                    try { read(cursor + headerSize + offset, n).copyInto(data, offset) }
                    catch (_: Missing) { missing = true }
                    offset += n
                }
                if (missing) throw Missing()
                moov = data
                break
            }
            cursor += size
        }
        val bytes = moov ?: throw Invalid()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val children = atoms(buffer, 0, bytes.size)
        if (children.any { it.type == "mvex" }) throw Invalid() // Fragmented MP4 uses a different index.
        val mvhd = children.firstOrNull { it.type == "mvhd" } ?: throw Invalid()
        val movieScale = timescale(buffer, mvhd)
        val tracks = children.filter { it.type == "trak" }.mapNotNull { track(buffer, it, movieScale) }
        if (tracks.isEmpty()) throw Invalid()
        return Index(tracks, mergeByteRanges(discovered))
    }

    private fun track(b: ByteBuffer, trak: Atom, movieScale: Long): List<Span>? {
        val top = atoms(b, trak.data, trak.end)
        val mdia = top.firstOrNull { it.type == "mdia" } ?: throw Invalid()
        val media = atoms(b, mdia.data, mdia.end)
        val hdlr = media.firstOrNull { it.type == "hdlr" } ?: throw Invalid()
        need(hdlr, 12)
        val kind = fourcc(b, hdlr.data + 8)
        if (kind !in setOf("vide", "soun")) return null
        val mdhd = media.firstOrNull { it.type == "mdhd" } ?: throw Invalid()
        val scale = timescale(b, mdhd)
        val minf = media.firstOrNull { it.type == "minf" } ?: throw Invalid()
        val minfChildren = atoms(b, minf.data, minf.end)
        // External media references cannot be proved by bytes in this file.
        minfChildren.firstOrNull { it.type == "dinf" }?.let { dinf ->
            atoms(b, dinf.data, dinf.end).firstOrNull { it.type == "dref" }?.let { dref ->
                need(dref, 8)
                val refs = atoms(b, dref.data + 8, dref.end)
                if (refs.isEmpty() || refs.any { need(it, 4); uint(b, it.data) and 1L == 0L }) throw Invalid()
            }
        }
        val stbl = minfChildren.firstOrNull { it.type == "stbl" } ?: throw Invalid()
        val tables = atoms(b, stbl.data, stbl.end)
        fun table(type: String) = tables.firstOrNull { it.type == type } ?: throw Invalid()
        val stts = table("stts")
        val stsc = table("stsc")
        val offsets = tables.firstOrNull { it.type in setOf("stco", "co64") } ?: throw Invalid()
        val stsz = tables.firstOrNull { it.type == "stsz" } ?: throw Invalid()
        need(stsz, 12)
        val fixedSize = uint(b, stsz.data + 4)
        val samples = uint(b, stsz.data + 8).checkedCount(10_000_000)
        if (samples == 0) return null
        if (fixedSize == 0L && stsz.end - stsz.data - 12L < samples * 4L) throw Invalid()
        val chunkCount = count(b, offsets, if (offsets.type == "co64") 8 else 4)
        val chunkRules = count(b, stsc, 12)
        if (chunkRules == 0 || uint(b, stsc.data + 8) != 1L) throw Invalid()
        var previousChunk = 0L
        for (i in 0 until chunkRules) {
            val at = stsc.data + 8 + i * 12
            val chunk = uint(b, at)
            if (chunk <= previousChunk || chunk > chunkCount || uint(b, at + 4) == 0L || uint(b, at + 8) != 1L) throw Invalid()
            previousChunk = chunk
        }
        val sync = tables.firstOrNull { it.type == "stss" }?.let { atom ->
            val n = count(b, atom, 4)
            IntArray(n) { i -> uint(b, atom.data + 8 + i * 4).checkedCount(samples) - 1 }.also {
                if (it.isEmpty() || it.first() < 0 || (1 until it.size).any { n -> it[n] <= it[n - 1] }) throw Invalid()
            }
        }
        if (kind == "vide" && sync != null && sync.first() != 0) throw Invalid()
        val durations = Runs(b, stts, signed = false)
        val compositions = tables.firstOrNull { it.type == "ctts" }?.let { Runs(b, it, signed = b.get(it.data).toInt() == 1) }
        val edits = top.firstOrNull { it.type == "edts" }?.let { edts ->
            atoms(b, edts.data, edts.end).firstOrNull { it.type == "elst" }?.let { edits(b, it, movieScale, scale) }
        }
        val groups = mutableListOf<Span>()
        var sample = 0
        var rule = 0
        var syncCursor = 0
        var dts = 0L
        var groupStart = Double.POSITIVE_INFINITY
        var groupEnd = 0.0
        var firstByte = Long.MAX_VALUE
        var lastByte = 0L
        var previousAudio: CachedByteRange? = null
        fun flush() {
            if (firstByte == Long.MAX_VALUE || groupEnd <= groupStart) return
            val ownBytes = CachedByteRange(firstByte, lastByte)
            val bytes = if (kind == "soun" && previousAudio != null)
                CachedByteRange(minOf(previousAudio!!.start, firstByte), maxOf(previousAudio!!.end, lastByte)) else ownBytes
            if (kind == "soun") previousAudio = ownBytes
            if (edits == null) groups.add(Span(groupStart, groupEnd, bytes))
            else for (edit in edits) {
                val start = maxOf(groupStart, edit.mediaMs)
                val end = minOf(groupEnd, edit.mediaMs + edit.endMs - edit.startMs)
                if (end > start) groups.add(Span(edit.startMs + start - edit.mediaMs, edit.startMs + end - edit.mediaMs, bytes))
            }
            if (groups.size > 200_000) throw Invalid()
            groupStart = Double.POSITIVE_INFINITY; groupEnd = 0.0; firstByte = Long.MAX_VALUE; lastByte = 0
        }
        for (chunk in 1..chunkCount) {
            if (rule + 1 < chunkRules && uint(b, stsc.data + 8 + (rule + 1) * 12) == chunk.toLong()) rule++
            val n = uint(b, stsc.data + 12 + rule * 12).checkedCount(samples)
            if (n > samples - sample) throw Invalid()
            var offset = if (offsets.type == "co64") b.getLong(offsets.data + 8 + (chunk - 1) * 8)
                else uint(b, offsets.data + 8 + (chunk - 1) * 4)
            repeat(n) {
                val isSync = sync == null || (syncCursor < sync.size && sync[syncCursor] == sample)
                if (isSync && sync != null) syncCursor++
                if ((kind == "soun" || isSync) && groupEnd - groupStart >= 1_000.0) flush()
                val delta = durations.next()
                if (delta <= 0 || dts > Long.MAX_VALUE - delta) throw Invalid()
                val composition = compositions?.next() ?: 0L
                val ptsMs = (dts.toDouble() + composition) * 1_000.0 / scale
                val endMs = (dts.toDouble() + composition + delta) * 1_000.0 / scale
                val size = if (fixedSize > 0) fixedSize else uint(b, stsz.data + 12 + sample * 4)
                if (offset < 0 || size <= 0 || offset > fileSize - size) throw Invalid()
                firstByte = minOf(firstByte, offset); lastByte = maxOf(lastByte, offset + size)
                groupStart = minOf(groupStart, ptsMs); groupEnd = maxOf(groupEnd, endMs)
                offset += size; dts += delta; sample++
            }
        }
        if (sample != samples || !durations.exhausted() || compositions?.exhausted() == false) throw Invalid()
        flush()
        return groups.sortedBy { it.startMs }
    }

    private class Runs(private val b: ByteBuffer, private val atom: Atom, private val signed: Boolean) {
        private val count = count(b, atom, 8)
        private var index = 0
        private var remaining = 0L
        private var value = 0L
        init { if (b.get(atom.data).toInt() !in 0..1) throw Invalid() }
        fun next(): Long {
            if (remaining == 0L) {
                if (index >= count) throw Invalid()
                val at = atom.data + 8 + index++ * 8
                remaining = uint(b, at)
                value = if (signed) b.getInt(at + 4).toLong() else uint(b, at + 4)
                if (remaining == 0L) throw Invalid()
            }
            remaining--
            return value
        }
        fun exhausted() = remaining == 0L && index == count
    }

    private fun edits(b: ByteBuffer, atom: Atom, movieScale: Long, mediaScale: Long): List<Edit> {
        val version = b.get(atom.data).toInt()
        if (version !in 0..1) throw Invalid()
        val width = if (version == 1) 20 else 12
        val n = count(b, atom, width)
        if (n > 1024) throw Invalid()
        var start = 0.0
        return buildList {
            repeat(n) { i ->
                val at = atom.data + 8 + i * width
                val duration = if (version == 1) b.getLong(at) else uint(b, at)
                val media = if (version == 1) b.getLong(at + 8) else b.getInt(at + 4).toLong()
                if (duration < 0 || media < -1 || b.getInt(at + width - 4) != 0x00010000) throw Invalid()
                val end = start + duration.toDouble() * 1000 / movieScale
                if (media >= 0) add(Edit(start, end, media.toDouble() * 1000 / mediaScale))
                start = end
            }
        }
    }

    companion object {
        private fun need(atom: Atom, length: Int) { if (atom.end - atom.data < length) throw Invalid() }
        private fun uint(b: ByteBuffer, at: Int) = b.getInt(at).toLong() and 0xffffffffL
        private fun Long.checkedCount(maximum: Int = 10_000_000): Int {
            if (this !in 0..maximum.toLong()) throw Invalid()
            return toInt()
        }
        private fun count(b: ByteBuffer, atom: Atom, width: Int): Int {
            need(atom, 8)
            val n = uint(b, atom.data + 4).checkedCount()
            if (n.toLong() * width > atom.end - atom.data - 8) throw Invalid()
            return n
        }
        private fun fourcc(b: ByteBuffer, at: Int) = String(ByteArray(4) { b.get(at + it) }, Charsets.ISO_8859_1)
        private fun timescale(b: ByteBuffer, atom: Atom): Long {
            need(atom, 4)
            val version = b.get(atom.data).toInt()
            if (version !in 0..1) throw Invalid()
            val offset = if (version == 1) 20 else 12
            need(atom, offset + 4)
            return uint(b, atom.data + offset).takeIf { it > 0 } ?: throw Invalid()
        }
        private fun atoms(b: ByteBuffer, start: Int, end: Int): List<Atom> = buildList {
            var at = start
            while (at < end) {
                if (end - at < 8 || size > 100_000) throw Invalid()
                var length = uint(b, at)
                val type = fourcc(b, at + 4)
                var header = 8
                if (length == 1L) { if (end - at < 16) throw Invalid(); length = b.getLong(at + 8); header = 16 }
                if (length == 0L) length = (end - at).toLong()
                if (length < header || length > end - at) throw Invalid()
                add(Atom(type, at + header, at + length.toInt()))
                at += length.toInt()
            }
        }
    }
}
