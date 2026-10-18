package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.CachedTimeRange
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedTimelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = PlaybackDiskCache.key("timeline-video")

    @Test fun parsesActualSeekRangesWithoutFillingGapsOrRoundingOutward() {
        assertEquals(listOf(CachedTimeRange(1001, 2000), CachedTimeRange(20_000, 25_000)),
            parseSeekableRanges("""{"seekable-ranges":[{"start":20,"end":25},{"start":1.0001,"end":2.0009}]}"""))
        assertEquals(listOf(CachedTimeRange(0, 3000)),
            parseSeekableRanges("""{"seekable-ranges":[{"start":-1,"end":2},{"start":1,"end":3}]}"""))
        for (invalid in listOf(null, "garbage", "{}", """{"seekable-ranges":[{"start":5,"end":2},{"start":"1","end":8}]}"""))
            assertTrue(parseSeekableRanges(invalid).isEmpty())
    }

    @Test fun disjointRangesSurviveReopeningWhileUnobservedHolesStayEmpty() {
        val directory = temporary.newFolder()
        val cache = PlaybackDiskCache(directory)
        cache.put(key, 0, ByteArray(8), cache.generation())
        cache.put(key, 2, ByteArray(8), cache.generation())
        val ranges = listOf(CachedTimeRange(0, 2000), CachedTimeRange(20_000, 22_000))
        assertEquals(ranges, cache.timeline(key, ranges, cache.byteRanges(key), cache.generation()))
        val reopened = PlaybackDiskCache(directory)
        assertEquals(ranges, reopened.timeline(key, emptyList(), emptyList(), reopened.generation()))
    }

    @Test fun evictingLaterDependenciesDoesNotEraseAnEarlierSmallerProof() {
        val cache = PlaybackDiskCache(temporary.newFolder(), maxBytes = 8)
        cache.put(key, 0, ByteArray(4), cache.generation())
        cache.timeline(key, listOf(CachedTimeRange(0, 2000)), cache.byteRanges(key), cache.generation())
        cache.put(key, 1, ByteArray(4), cache.generation())
        cache.timeline(key, listOf(CachedTimeRange(0, 4000)), cache.byteRanges(key), cache.generation())
        cache.read(key, 0, 4) // Make the later block the eviction candidate.
        cache.put(key, 2, ByteArray(4), cache.generation())
        assertEquals(listOf(CachedTimeRange(0, 2000)), cache.timeline(key, emptyList(), emptyList(), cache.generation()))
    }

    @Test fun missingAndTruncatedDependenciesCannotBePaintedAsCached() {
        val directory = temporary.newFolder()
        val cache = PlaybackDiskCache(directory)
        cache.put(key, 0, ByteArray(8), cache.generation())
        val time = listOf(CachedTimeRange(0, 2000))
        assertTrue(cache.timeline(key, time, listOf(CachedByteRange(0, 9)), cache.generation()).isEmpty())
        assertEquals(time, cache.timeline(key, time, listOf(CachedByteRange(0, 8)), cache.generation()))
        File(directory, "$key.0.blk").writeBytes(ByteArray(4))
        assertTrue(cache.timeline(key, emptyList(), emptyList(), cache.generation()).isEmpty())
    }

    @Test fun capacityChangesKeepValidProofsButClearingRejectsAnOldSession() {
        val directory = temporary.newFolder()
        val cache = PlaybackDiskCache(directory, maxBytes = 16)
        val oldSession = cache.generation()
        cache.put(key, 0, ByteArray(4), oldSession)
        val time = listOf(CachedTimeRange(10_000, 12_000))
        val dependencies = cache.byteRanges(key)
        cache.timeline(key, time, dependencies, oldSession)
        cache.configure(true, 8)
        assertEquals(time, cache.timeline(key, emptyList(), emptyList(), oldSession))
        cache.configure(false, 8)
        assertTrue(cache.timeline(key, emptyList(), emptyList(), oldSession).isEmpty())
        cache.configure(true, 8)
        assertEquals(time, cache.timeline(key, emptyList(), emptyList(), cache.generation()))
        cache.clear()
        cache.put(key, 0, ByteArray(4), cache.generation())
        assertTrue(cache.timeline(key, time, dependencies, oldSession).isEmpty())
        assertFalse(File(directory, "$key.ranges").exists())
    }

    @Test fun byteInspectionReportsPrefixesAndNeverTouchesEvictionOrder() {
        val cache = PlaybackDiskCache(temporary.newFolder(), maxBytes = 8)
        cache.put(key, 0, ByteArray(4), cache.generation())
        cache.put(key, 1, ByteArray(4), cache.generation())
        assertEquals(listOf(CachedByteRange(0, 4), CachedByteRange(2_097_152, 2_097_156)), cache.byteRanges(key))
        cache.put(key, 2, ByteArray(4), cache.generation())
        assertNull(cache.read(key, 0, 4))
        assertNotNull(cache.read(key, 1, 4))
    }

    @Test fun corruptTimelineIsIgnoredAndClearRemovesItsMetadata() {
        val directory = temporary.newFolder()
        File(directory, "$key.ranges").writeBytes(byteArrayOf(1, 2, 3))
        val cache = PlaybackDiskCache(directory)
        assertTrue(cache.timeline(key, emptyList(), emptyList(), cache.generation()).isEmpty())
        cache.clear()
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
