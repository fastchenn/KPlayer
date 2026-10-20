package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.CachedTimeRange
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads only existing disk bytes. Cue times/cluster positions are never inferred from bitrate. */
internal class MatroskaCacheIndex(
    private val fileSize: Long,
    private val readAt: (Long, Int) -> ByteArray?,
    private val onMissing: (Long, Int) -> Unit = { _, _ -> },
) {
    private data class Element(val id: Long, val start: Long, val data: Long, val end: Long)
    private data class Cue(val position: Long, val ticks: Long)
    private data class Index(val cues: List<Cue>, val scale: Long, val durationMs: Long?, val metadata: List<CachedByteRange>)
    private class Missing : RuntimeException()
    private class Invalid : RuntimeException()
    private var index: Index? = null
    private var unsupported = false

    fun proofs(available: List<CachedByteRange>, durationMs: Long?, originMs: Long = 0): List<CachedTimeProof> {
        if (unsupported) return emptyList()
        val current = index ?: try { load().also { index = it } }
        catch (_: Missing) { return emptyList() }
        catch (_: Invalid) { unsupported = true; return emptyList() }
        val missing = current.metadata.filter { need -> available.none { it.start <= need.start && it.end >= need.end } }
        if (missing.isNotEmpty()) {
            missing.forEach { onMissing(it.start, (it.end - it.start).toInt()) }
            return emptyList()
        }
        val cues = current.cues
        fun time(cue: Cue, start: Boolean = true): Long {
            val ms = cue.ticks.toDouble() * current.scale / 1_000_000 - originMs
            if (!ms.isFinite() || ms >= Long.MAX_VALUE) throw Invalid()
            return (if (start) kotlin.math.ceil(ms.coerceAtLeast(0.0)) else kotlin.math.floor(ms.coerceAtLeast(0.0))).toLong()
        }
        return try {
            buildList {
                for (span in available) {
                    var low = 0; var high = cues.size
                    while (low < high) {
                        val mid = (low + high) ushr 1
                        if (cues[mid].position < span.start) low = mid + 1 else high = mid
                    }
                    val first = low
                    if (first >= cues.size || cues[first].position >= span.end) continue
                    var last = first
                    while (last + 1 < cues.size && cues[last + 1].position <= span.end) last++
                    val throughEnd = span.end >= fileSize
                    val endTime = if (throughEnd) durationMs ?: current.durationMs?.minus(originMs) else time(cues[last], false)
                    val startTime = time(cues[first])
                    val endByte = if (throughEnd) fileSize else cues[last].position
                    if (endTime != null && endTime > startTime && endByte > cues[first].position) {
                        add(CachedTimeProof(CachedTimeRange(startTime, endTime),
                            mergeByteRanges(current.metadata + CachedByteRange(cues[first].position, endByte))))
                    }
                }
            }
        } catch (_: Invalid) { emptyList() }
    }

    fun metadataRanges(): List<CachedByteRange> = index?.metadata.orEmpty()

    private fun load(): Index {
        val dependencies = mutableListOf<CachedByteRange>()
        fun read(position: Long, count: Int): ByteArray {
            if (position < 0 || count <= 0 || position > fileSize - count) throw Invalid()
            val bytes = readAt(position, count) ?: run { onMissing(position, count); throw Missing() }
            if (bytes.size != count) throw Missing()
            dependencies.add(CachedByteRange(position, position + count))
            return bytes
        }
        fun header(position: Long, limit: Long): Element {
            if (position < 0 || position >= limit) throw Invalid()
            val bytes = read(position, minOf(12L, limit - position).toInt())
            return element(bytes, 0, bytes.size, position, limit, allowUnknown = true)
        }
        fun body(element: Element, maximum: Int): ByteArray {
            val length = element.end - element.data
            if (length !in 1..maximum.toLong()) throw Invalid()
            return read(element.data, length.toInt())
        }
        val ebml = header(0, fileSize)
        if (ebml.id != EBML) throw Invalid()
        val doc = children(body(ebml, 65536)).firstOrNull { it.first.id == 0x4282L }?.second
            ?.toString(Charsets.US_ASCII)
        if (doc !in setOf("matroska", "webm")) throw Invalid()
        var segment = header(ebml.end, fileSize)
        var padding = 0
        while (segment.id == 0xECL && padding++ < 16) segment = header(segment.end, fileSize)
        if (segment.id != SEGMENT) throw Invalid()
        var scale = 1_000_000L
        var durationTicks: Double? = null
        var haveInfo = false
        var videoTrack: Long? = null
        var audioTrack: Long? = null
        var cuesBody: ByteArray? = null
        val visited = mutableSetOf<Long>()
        fun visit(node: Element, depth: Int) {
            if (depth > 4 || !visited.add(node.start)) return
            if (visited.size > 256) throw Invalid()
            when (node.id) {
                INFO -> {
                    for ((part, bytes) in children(body(node, 1_048_576))) when (part.id) {
                        0x2AD7B1L -> scale = unsigned(bytes).takeIf { it > 0 } ?: throw Invalid()
                        0x4489L -> durationTicks = floating(bytes)
                    }
                    haveInfo = true
                }
                TRACKS -> {
                    for ((entry, bytes) in children(body(node, 4_194_304))) if (entry.id == 0xAEL) {
                        val parts = children(bytes)
                        val number = parts.firstOrNull { it.first.id == 0xD7L }?.second?.let(::unsigned) ?: continue
                        val type = parts.firstOrNull { it.first.id == 0x83L }?.second?.let(::unsigned)
                        if (type == 1L && videoTrack == null) videoTrack = number
                        if (type == 2L && audioTrack == null) audioTrack = number
                    }
                }
                CUES -> cuesBody = body(node, 16_777_216)
                SEEK_HEAD -> {
                    for ((seek, bytes) in children(body(node, 1_048_576))) if (seek.id == 0x4DBBL) {
                        val parts = children(bytes)
                        val id = parts.firstOrNull { it.first.id == 0x53ABL }?.second?.let(::unsigned) ?: continue
                        val offset = parts.firstOrNull { it.first.id == 0x53ACL }?.second?.let(::unsigned) ?: continue
                        if (id !in setOf(INFO, TRACKS, CUES, SEEK_HEAD)) continue
                        if (offset >= segment.end - segment.data) throw Invalid()
                        val target = header(segment.data + offset, segment.end)
                        if (target.id != id) throw Invalid()
                        visit(target, depth + 1)
                    }
                }
            }
        }
        var cursor = segment.data
        var count = 0
        while (cursor < segment.end && count++ < 256) {
            val node = header(cursor, segment.end)
            if (node.id == CLUSTER) break
            visit(node, 0)
            cursor = node.end
            if (haveInfo && cuesBody != null && (videoTrack != null || audioTrack != null)) break
        }
        if (!haveInfo || cuesBody == null) throw Missing()
        val track = videoTrack ?: audioTrack ?: throw Invalid()
        val cues = mutableListOf<Cue>()
        for ((point, bytes) in children(cuesBody!!)) if (point.id == 0xBBL) {
            if (cues.size >= 100_000) throw Invalid()
            val parts = children(bytes)
            val ticks = parts.firstOrNull { it.first.id == 0xB3L }?.second?.let(::unsigned) ?: continue
            for ((positions, data) in parts) if (positions.id == 0xB7L) {
                val fields = children(data)
                val cueTrack = fields.firstOrNull { it.first.id == 0xF7L }?.second?.let(::unsigned)
                if (cueTrack != track) continue
                val relative = fields.firstOrNull { it.first.id == 0xF1L }?.second?.let(::unsigned) ?: continue
                if (relative >= segment.end - segment.data) throw Invalid()
                cues.add(Cue(segment.data + relative, ticks))
            }
        }
        // Multiple keyframes can refer to the same cluster; require the whole cluster once.
        val ordered = cues.groupBy { it.position }.map { (position, entries) -> Cue(position, entries.minOf { it.ticks }) }.sortedBy { it.position }
        if (ordered.isEmpty() || ordered.zipWithNext().any { (a, b) -> b.ticks < a.ticks }) throw Invalid()
        val duration = durationTicks?.let {
            val ms = it * scale / 1_000_000
            if (!ms.isFinite() || ms <= 0 || ms >= Long.MAX_VALUE) null else kotlin.math.floor(ms).toLong()
        }
        return Index(ordered, scale, duration, mergeByteRanges(dependencies))
    }

    companion object {
        private const val EBML = 0x1A45DFA3L
        private const val SEGMENT = 0x18538067L
        private const val SEEK_HEAD = 0x114D9B74L
        private const val INFO = 0x1549A966L
        private const val TRACKS = 0x1654AE6BL
        private const val CUES = 0x1C53BB6BL
        private const val CLUSTER = 0x1F43B675L

        private fun unsigned(bytes: ByteArray): Long {
            if (bytes.size !in 1..8 || (bytes.size == 8 && bytes[0] < 0)) throw Invalid()
            var result = 0L
            for (byte in bytes) result = (result shl 8) or (byte.toLong() and 255)
            return result
        }
        private fun floating(bytes: ByteArray): Double {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            return when (bytes.size) { 4 -> buffer.float.toDouble(); 8 -> buffer.double; else -> throw Invalid() }
        }
        private fun element(bytes: ByteArray, offset: Int, limit: Int, base: Long, parentEnd: Long, allowUnknown: Boolean = false): Element {
            fun vint(at: Int, maximum: Int, keepMarker: Boolean): Pair<Long, Int> {
                if (at >= limit) throw Invalid()
                val first = bytes[at].toInt() and 255
                var marker = 128; var length = 1
                while (length <= maximum && first and marker == 0) { marker = marker ushr 1; length++ }
                if (length > maximum || at > limit - length) throw Invalid()
                var value = (if (keepMarker) first else first and (marker - 1)).toLong()
                for (i in 1 until length) value = (value shl 8) or (bytes[at + i].toLong() and 255)
                return value to length
            }
            val (id, idLength) = vint(offset, 4, true)
            val (length, lengthBytes) = vint(offset + idLength, 8, false)
            val data = base + offset + idLength + lengthBytes
            val unknown = length == (1L shl (7 * lengthBytes)) - 1
            if (unknown && !allowUnknown) throw Invalid()
            val end = if (unknown) parentEnd else {
                if (length > parentEnd - data) throw Invalid()
                data + length
            }
            if (end <= base + offset || data > parentEnd) throw Invalid()
            return Element(id, base + offset, data, end)
        }
        private fun children(bytes: ByteArray): List<Pair<Element, ByteArray>> {
            val elements = mutableListOf<Pair<Element, ByteArray>>()
            var cursor = 0
            while (cursor < bytes.size) {
                if (elements.size > 100_000) throw Invalid()
                val element = element(bytes, cursor, bytes.size, 0, bytes.size.toLong())
                elements.add(element to bytes.copyOfRange(element.data.toInt(), element.end.toInt()))
                cursor = element.end.toInt()
            }
            return elements
        }
    }
}
