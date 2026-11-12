package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.CachedTimeRange
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Mp4CacheIndexTest {
    private val content = javaClass.getResourceAsStream("/timeline-vbr.mp4")!!.readBytes()
    private val metadata = topLevelMetadata(content)
    private var available = metadata
    private val missing = mutableListOf<CachedByteRange>()
    private fun parser(bytes: ByteArray = content) = Mp4CacheIndex(bytes.size.toLong(), { position, count ->
        if (coversBytes(mergeByteRanges(available), position, position + count)) bytes.copyOfRange(position.toInt(), position.toInt() + count) else null
    }, { position, count -> missing.add(CachedByteRange(position, position + count)) })

    @Test fun vbrMp4AdvancesFromDiskWithoutWaitingForNativeBufferOrWholeFile() {
        val index = parser()
        available = mergeByteRanges(metadata + CachedByteRange(0, content.size / 3L))
        val first = index.proofs(available, 16_000).map { it.time }
        assertTrue(first.isNotEmpty())
        assertTrue(first.first().startMs <= 1)
        available = mergeByteRanges(metadata + CachedByteRange(0, content.size * 3L / 4))
        val next = index.proofs(available, 16_000).map { it.time }
        assertTrue(next.last().endMs > first.last().endMs)
        assertTrue(next.last().endMs < 16_000)
    }

    @Test fun completeSampleDataHasNoMillisecondHolesBetweenAudioChunksOrBFrames() {
        available = listOf(CachedByteRange(0, content.size.toLong()))
        val ranges = parser().proofs(available, 16_000).map { it.time }
        assertEquals(1, ranges.size)
        assertEquals(0L, ranges.single().startMs)
        assertTrue(ranges.single().endMs >= 15_800)
    }

    @Test fun actualUncachedMiddleIsNeverFilledByAnAverageBitrateEstimate() {
        available = mergeByteRanges(metadata + listOf(CachedByteRange(0, content.size / 3L),
            CachedByteRange(content.size * 2L / 3, content.size.toLong())))
        val ranges = parser().proofs(available, 16_000).map { it.time }
        assertEquals(2, ranges.size)
        assertTrue(ranges[0].endMs < ranges[1].startMs)
    }

    @Test fun aMissingAudioTrackOrMissingIndexCannotAdvertiseVideoAsFullyCached() {
        val packets = org.json.JSONObject(javaClass.getResourceAsStream("/timeline-vbr-mp4-packets.json")!!.bufferedReader().readText()).getJSONArray("packets")
        available = mergeByteRanges(metadata + (0 until packets.length()).mapNotNull { n ->
            val p = packets.getJSONObject(n)
            if (p.getInt("stream_index") != 0) null else p.getString("pos").toLong().let { CachedByteRange(it, it + p.getString("size").toLong()) }
        })
        assertTrue(parser().proofs(available, 16_000).isEmpty())
        available = listOf(CachedByteRange(0, content.size / 2L))
        assertTrue(parser().proofs(available, 16_000).isEmpty())
        assertTrue(missing.isNotEmpty())
    }

    @Test fun threeHourMp4CoverageIsContinuousAndSurvivesRebuildingItsIndex() {
        val bytes = javaClass.getResourceAsStream("/timeline-3h.mp4")!!.readBytes()
        val header = topLevelMetadata(bytes)
        fun ranges(end: Long): List<CachedTimeRange> {
            val available = mergeByteRanges(header + CachedByteRange(0, end))
            val index = Mp4CacheIndex(bytes.size.toLong(), { position, count ->
                if (coversBytes(available, position, position + count)) bytes.copyOfRange(position.toInt(), position.toInt() + count) else null
            })
            return index.proofs(available, 10_800_000).map { it.time }
        }
        val half = ranges(bytes.size / 2L)
        assertEquals(1, half.size)
        assertEquals(0L, half.single().startMs)
        assertTrue(half.single().endMs in 3_600_000L..7_200_000L)
        assertEquals(half, ranges(bytes.size / 2L))
        assertEquals(listOf(CachedTimeRange(0, 10_800_000)), ranges(bytes.size.toLong()))
    }

    @Test fun fourHour64BitOffsetsBeyond4GiBAreNotTruncatedOrMappedByFileRatio() {
        val fixture = largeFile()
        val index = Mp4CacheIndex(fixture.size, { at, length -> fixture.read(at, length) })
        val available = mergeByteRanges(fixture.metadata + CachedByteRange(fixture.payload, fixture.payload + 720_000))
        assertEquals(listOf(CachedTimeRange(0, 7_200_000)), index.proofs(available, 14_400_000).map { it.time })
        assertTrue(index.ready)
        val later = mergeByteRanges(fixture.metadata + CachedByteRange(fixture.payload + 900_000, fixture.payload + 1_200_000))
        assertEquals(listOf(CachedTimeRange(9_000_000, 12_000_000)), index.proofs(later, 14_400_000).map { it.time })
    }

    @Test fun fragmentedAndMalformedTablesFailClosedWithoutInventingCoverage() {
        val fragmented = atom("moov", atom("mvex", byteArrayOf()))
        val all = listOf(CachedByteRange(0, fragmented.size.toLong()))
        assertTrue(Mp4CacheIndex(fragmented.size.toLong(), { at, n -> fragmented.copyOfRange(at.toInt(), at.toInt() + n) }).proofs(all, 1000).isEmpty())
        val malformed = content.copyOf()
        val stsz = findAscii(malformed, "stsz")
        ByteBuffer.wrap(malformed).putInt(stsz + 12, Int.MAX_VALUE)
        available = listOf(CachedByteRange(0, malformed.size.toLong()))
        assertTrue(parser(malformed).proofs(available, 16_000).isEmpty())
    }

    private data class SparseFile(val size: Long, val payload: Long, val pieces: List<Pair<Long, ByteArray>>) {
        val metadata get() = pieces.map { CachedByteRange(it.first, it.first + it.second.size) }
        fun read(at: Long, length: Int): ByteArray? = pieces.firstOrNull { at >= it.first && at + length <= it.first + it.second.size }
            ?.let { it.second.copyOfRange((at - it.first).toInt(), (at - it.first).toInt() + length) }
    }

    private fun largeFile(): SparseFile {
        val payload = 4_400_000_000L
        val ftyp = atom("ftyp", "isom0000isom".toByteArray())
        val free = data { writeInt(1); writeBytes("free"); writeLong(payload - 16 - ftyp.size) }
        val mdat = data { writeInt(1); writeBytes("mdat"); writeLong(1_440_016) }
        val header = atom("mvhd", data { writeInt(0); writeLong(0); writeInt(1000); writeInt(14_400_000) })
        val mdhd = atom("mdhd", data { writeInt(0); writeLong(0); writeInt(1000); writeInt(14_400_000) })
        val hdlr = atom("hdlr", data { writeLong(0); writeBytes("vide") })
        val stts = atom("stts", data { writeInt(0); writeInt(1); writeInt(14_400); writeInt(1000) })
        val stsc = atom("stsc", data { writeInt(0); writeInt(1); writeInt(1); writeInt(14_400); writeInt(1) })
        val stsz = atom("stsz", data { writeInt(0); writeInt(100); writeInt(14_400) })
        val co64 = atom("co64", data { writeInt(0); writeInt(1); writeLong(payload) })
        val stss = atom("stss", data { writeInt(0); writeInt(240); repeat(240) { writeInt(it * 60 + 1) } })
        val moov = atom("moov", header + atom("trak", atom("mdia", mdhd + hdlr + atom("minf", atom("stbl", stts + stsc + stsz + co64 + stss)))))
        val end = payload + 1_440_000
        return SparseFile(end + moov.size, payload, listOf(0L to ftyp, ftyp.size.toLong() to free, payload - 16 to mdat, end to moov))
    }

    companion object {
        internal fun topLevelMetadata(bytes: ByteArray): List<CachedByteRange> {
            val ranges = mutableListOf<CachedByteRange>()
            var at = 0
            val b = ByteBuffer.wrap(bytes)
            while (at < bytes.size) {
                val length = b.getInt(at)
                val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
                ranges.add(CachedByteRange(at.toLong(), (at + if (type == "mdat") 8 else length).toLong()))
                at += length
            }
            return mergeByteRanges(ranges)
        }
        private fun findAscii(bytes: ByteArray, text: String): Int = (0..bytes.size - text.length).first { at -> text.indices.all { bytes[at + it] == text[it].code.toByte() } }
        private fun data(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use(block) }.toByteArray()
        private fun atom(type: String, body: ByteArray) = data { writeInt(body.size + 8); writeBytes(type); write(body) }
    }
}
