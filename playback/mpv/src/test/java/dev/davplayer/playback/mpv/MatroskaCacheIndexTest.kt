package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.CachedTimeRange
import org.junit.Assert.*
import org.junit.Test

class MatroskaCacheIndexTest {
    // FFmpeg-generated H.264/FLAC with two-second keyframes and very different scene sizes.
    // Reference keyframe PTS from ffprobe: 0,2,4,6,8,10,12,14 seconds.
    private val content = javaClass.getResourceAsStream("/timeline-vbr.mkv")!!.use { it.readBytes() }
    private val positions = listOf(780L, 7308L, 12534L, 20314L, 28457L, 37919L, 47585L, 57104L)
    private val cuesStart = 71900L
    private val metadata = listOf(CachedByteRange(0, 780), CachedByteRange(cuesStart, content.size.toLong()))
    private var available = metadata
    private val missing = mutableListOf<Pair<Long, Int>>()
    private fun parser(bytes: ByteArray = content) = MatroskaCacheIndex(bytes.size.toLong(), { position, count ->
        if (available.any { it.start <= position && it.end >= position + count }) bytes.copyOfRange(position.toInt(), position.toInt() + count) else null
    }, { position, count -> missing.add(position to count) })
    private fun times(index: MatroskaCacheIndex) = index.proofs(mergeByteRanges(available), 16000).map { it.time }

    @Test fun vbrDiskCacheAdvancesWhileNativeBufferIsUnchanged() {
        val index = parser()
        available = metadata + CachedByteRange(positions[0], positions[2])
        assertEquals(listOf(CachedTimeRange(0, 4000)), times(index))
        available = metadata + CachedByteRange(positions[0], positions[5])
        assertEquals(listOf(CachedTimeRange(0, 10000)), times(index))
        assertTrue("Fixture must not have proportional byte/time boundaries", positions[4] != content.size / 2L)
    }

    @Test fun disjointCachedRegionsNeverPaintAnUncachedGap() {
        val index = parser()
        available = metadata + listOf(CachedByteRange(positions[1], positions[3]), CachedByteRange(positions[5], positions[7]))
        assertEquals(listOf(CachedTimeRange(2000, 6000), CachedTimeRange(10000, 14000)), times(index))
        available = metadata + CachedByteRange(positions[1], positions[7])
        assertEquals(listOf(CachedTimeRange(2000, 14000)), times(index))
    }

    @Test fun incompleteClustersWaitForRealIndexBoundariesAndReachActualEof() {
        val index = parser()
        available = metadata + CachedByteRange(positions[3], positions[4] - 1)
        assertTrue(times(index).isEmpty())
        available = metadata + CachedByteRange(positions[3], positions[5])
        assertEquals(listOf(CachedTimeRange(6000, 10000)), times(index))
        available = mergeByteRanges(metadata + CachedByteRange(positions[3], content.size.toLong()))
        assertEquals(listOf(CachedTimeRange(6000, 16000)), times(index))
    }

    @Test fun missingIndexRequestsOnlyMetadataAndReusesItWhenItArrives() {
        available = listOf(CachedByteRange(0, 780), CachedByteRange(positions[2], positions[4]))
        val index = parser()
        assertTrue(times(index).isEmpty())
        assertTrue(missing.any { it.first >= cuesStart })
        available = metadata + CachedByteRange(positions[2], positions[4])
        assertEquals(listOf(CachedTimeRange(4000, 8000)), times(index))
        missing.clear()
        available = listOf(CachedByteRange(positions[2], positions[4]), CachedByteRange(cuesStart, content.size.toLong()))
        assertTrue(times(index).isEmpty())
        assertTrue(missing.any { it.first == 0L })
    }

    @Test fun unknownSegmentSizeIsValidAndNonDefaultTimeScaleIsRespected() {
        val unknown = content.copyOf()
        unknown[44] = 1
        for (i in 45..51) unknown[i] = 255.toByte()
        available = metadata + CachedByteRange(positions[2], positions[4])
        assertEquals(listOf(CachedTimeRange(4000, 8000)), times(parser(unknown)))
        val scaled = content.copyOf()
        // TimestampScale at 224: ID 3 bytes, size 1 byte, unsigned value 3 bytes.
        scaled[228] = 0x07; scaled[229] = 0xA1.toByte(); scaled[230] = 0x20
        assertEquals(listOf(CachedTimeRange(2000, 4000)), times(parser(scaled)))
    }

    @Test fun invalidOrUnsupportedContainersNeverProduceEstimatedTimeRanges() {
        available = listOf(CachedByteRange(0, content.size.toLong()))
        for (bytes in listOf(ByteArray(content.size), content.copyOf().also { it[44] = 0 }))
            assertTrue(times(parser(bytes)).isEmpty())
    }
}
