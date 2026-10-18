package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.CachedTimeRange
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor

/** Byte offsets, never timestamps or an approximation based on average bitrate. */
internal data class CachedByteRange(val start: Long, val end: Long) {
    init { require(start >= 0 && end > start) }
}

internal fun mergeByteRanges(ranges: List<CachedByteRange>): List<CachedByteRange> {
    val merged = mutableListOf<CachedByteRange>()
    for (range in ranges.sortedBy { it.start }) {
        val previous = merged.lastOrNull()
        if (previous != null && range.start <= previous.end) {
            merged[merged.lastIndex] = CachedByteRange(previous.start, maxOf(previous.end, range.end))
        } else merged.add(range)
    }
    return merged
}

internal fun mergeTimeRanges(ranges: List<CachedTimeRange>): List<CachedTimeRange> =
    mergeByteRanges(ranges.map { CachedByteRange(it.startMs, it.endMs) })
        .map { CachedTimeRange(it.start, it.end) }

/** mpv's node properties can be retrieved as JSON with the existing JNI string getter. */
internal fun parseSeekableRanges(json: String?): List<CachedTimeRange> {
    if (json == null || json.length > 65_536) return emptyList()
    return try {
        val array = JSONObject(json).optJSONArray("seekable-ranges") ?: return emptyList()
        if (array.length() > 256) return emptyList()
        mergeTimeRanges((0 until array.length()).mapNotNull { index ->
            val range = array.optJSONObject(index) ?: return@mapNotNull null
            val start = (range.opt("start") as? Number)?.toDouble() ?: return@mapNotNull null
            val end = (range.opt("end") as? Number)?.toDouble() ?: return@mapNotNull null
            if (!start.isFinite() || !end.isFinite() || end <= 0 || end >= Long.MAX_VALUE / 1000.0) return@mapNotNull null
            // Round inward: the bar must never advertise a millisecond outside the observed range.
            val first = ceil(start.coerceAtLeast(0.0) * 1000).toLong()
            val last = floor(end * 1000).toLong()
            if (last > first) CachedTimeRange(first, last) else null
        })
    } catch (_: org.json.JSONException) { emptyList() }
}

/**
 * A conservative proof of cached media time. Each observation keeps all bytes delivered to
 * the demuxer up to that observation (including container headers/indexes). A proof is shown
 * only while every dependency is still on disk. This may under-report after eviction, but
 * cannot paint gaps by pretending that byte offsets are proportional to media timestamps.
 * Access is serialized by PlaybackDiskCache's lock; no filesystem work belongs on main.
 */
internal class CachedTimeline(private val file: File) {
    private data class Proof(val time: CachedTimeRange, val bytes: List<CachedByteRange>)
    private var proofs = read()

    fun update(observed: List<CachedTimeRange>, dependencies: List<CachedByteRange>, available: List<CachedByteRange>): List<CachedTimeRange> {
        val valid = proofs.filter { covered(it.bytes, available) }.toMutableList()
        val required = mergeByteRanges(dependencies)
        if (required.isNotEmpty() && required.size <= MAX_DEPENDENCIES && covered(required, available)) {
            for (time in mergeTimeRanges(observed)) {
                // Preserve earlier/smaller proofs. Growing a range must not make its old part
                // depend on all later downloads; that would erase it unnecessarily on eviction.
                var remaining = listOf(time)
                for (existing in valid) remaining = remaining.flatMap { subtract(it, existing.time) }
                for (part in remaining) valid.add(Proof(part, required))
            }
        }
        // Bound metadata for long videos by coalescing adjacent proofs conservatively.
        valid.sortBy { it.time.startMs }
        while (valid.size > MAX_PROOFS) {
            val index = (0 until valid.lastIndex).firstOrNull { valid[it].time.endMs == valid[it + 1].time.startMs }
            if (index == null) { valid.removeAt(0); continue }
            val a = valid[index]; val b = valid[index + 1]
            val bytes = mergeByteRanges(a.bytes + b.bytes)
            if (bytes.size > MAX_DEPENDENCIES) { valid.removeAt(index); continue }
            valid[index] = Proof(CachedTimeRange(a.time.startMs, b.time.endMs), bytes)
            valid.removeAt(index + 1)
        }
        if (valid != proofs) { proofs = valid; write() }
        return mergeTimeRanges(valid.map { it.time })
    }

    private fun read(): List<Proof> {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return emptyList()
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return emptyList()
                val count = input.readInt()
                if (count !in 0..MAX_PROOFS) return emptyList()
                List(count) {
                    val time = CachedTimeRange(input.readLong(), input.readLong())
                    val dependencies = input.readInt()
                    if (dependencies !in 1..MAX_DEPENDENCIES) return emptyList()
                    Proof(time, List(dependencies) { CachedByteRange(input.readLong(), input.readLong()) })
                }
            }
        } catch (_: IOException) { emptyList() }
        catch (_: IllegalArgumentException) { emptyList() }
    }

    private fun write() {
        var temporary: File? = null
        try {
            if (proofs.isEmpty()) { file.delete(); return }
            if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) return
            temporary = File.createTempFile("timeline-", ".part", file.parentFile)
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC); output.writeInt(proofs.size)
                for (proof in proofs) {
                    output.writeLong(proof.time.startMs); output.writeLong(proof.time.endMs)
                    output.writeInt(proof.bytes.size)
                    for (range in proof.bytes) { output.writeLong(range.start); output.writeLong(range.end) }
                }
            }
            if (temporary.length() <= MAX_FILE_BYTES) Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) { /* The video cache still works if optional timeline metadata cannot be saved. */ }
        finally { temporary?.delete() }
    }

    companion object {
        private const val MAGIC = 0x4b505431
        private const val MAX_PROOFS = 256
        private const val MAX_DEPENDENCIES = 256
        private const val MAX_FILE_BYTES = 1_100_000L

        private fun covered(required: List<CachedByteRange>, available: List<CachedByteRange>) =
            required.all { need -> available.any { it.start <= need.start && it.end >= need.end } }

        private fun subtract(time: CachedTimeRange, other: CachedTimeRange): List<CachedTimeRange> {
            if (other.endMs <= time.startMs || other.startMs >= time.endMs) return listOf(time)
            return buildList {
                if (other.startMs > time.startMs) add(CachedTimeRange(time.startMs, other.startMs))
                if (other.endMs < time.endMs) add(CachedTimeRange(other.endMs, time.endMs))
            }
        }
    }
}
