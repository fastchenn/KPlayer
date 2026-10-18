package dev.davplayer.playback.mpv

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = PlaybackDiskCache.key("video-id")

    @Test fun completedBlocksSurviveReopeningAndReportTheirActualSize() {
        val directory = temporary.newFolder()
        val cache = PlaybackDiskCache(directory, maxBytes = 100)
        cache.put(key, 0, byteArrayOf(1, 2, 3), cache.generation())
        assertEquals(3L, cache.usedBytes.value)
        val reopened = PlaybackDiskCache(directory, maxBytes = 100)
        reopened.refreshUsage()
        assertEquals(3L, reopened.usedBytes.value)
        assertArrayEquals(byteArrayOf(1, 2, 3), reopened.read(key, 0, 3))
    }

    @Test fun evictsTheLeastRecentlyReadBlockToStayWithinTheLimit() {
        val cache = PlaybackDiskCache(temporary.newFolder(), maxBytes = 6)
        repeat(2) { cache.put(key, it.toLong(), ByteArray(3) { 1 }, cache.generation()) }
        assertNotNull(cache.read(key, 0, 3))
        cache.put(key, 2, ByteArray(3) { 2 }, cache.generation())
        assertEquals(6L, cache.usedBytes.value)
        assertNull(cache.read(key, 1, 3))
        assertNotNull(cache.read(key, 0, 3))
        assertNotNull(cache.read(key, 2, 3))
    }

    @Test fun reducingTheLimitTrimsExistingDataImmediately() {
        val cache = PlaybackDiskCache(temporary.newFolder(), maxBytes = 9)
        repeat(3) { cache.put(key, it.toLong(), ByteArray(3), cache.generation()) }
        cache.configure(true, 3)
        assertEquals(3L, cache.usedBytes.value)
        assertNull(cache.read(key, 0, 3))
        assertNotNull(cache.read(key, 2, 3))
    }

    @Test fun disablingBypassesReadsAndWritesButRetainsExistingData() {
        val cache = PlaybackDiskCache(temporary.newFolder(), maxBytes = 10)
        cache.put(key, 0, ByteArray(3), cache.generation())
        cache.configure(false, 10)
        assertNull(cache.read(key, 0, 3))
        cache.put(key, 1, ByteArray(3), cache.generation())
        assertEquals(3L, cache.usedBytes.value)
        cache.configure(true, 10)
        assertNotNull(cache.read(key, 0, 3))
        assertNull(cache.read(key, 1, 3))
    }

    @Test fun clearingInvalidatesInflightWritesAndAllowsFutureDownloads() {
        val cache = PlaybackDiskCache(temporary.newFolder(), maxBytes = 100)
        val generation = cache.generation()
        cache.put(key, 0, ByteArray(3), generation)
        cache.clear()
        cache.put(key, 1, ByteArray(3), generation)
        assertEquals(0L, cache.usedBytes.value)
        assertNull(cache.read(key, 0, 3))
        assertNull(cache.read(key, 1, 3))
        cache.put(key, 1, ByteArray(3), cache.generation())
        assertEquals(3L, cache.usedBytes.value)
    }

    @Test fun deletedAndTruncatedBlocksDoNotLeaveInflatedUsage() {
        val directory = temporary.newFolder()
        val cache = PlaybackDiskCache(directory, maxBytes = 100)
        cache.put(key, 0, ByteArray(3), cache.generation())
        directory.listFiles()!!.single().writeBytes(byteArrayOf(1))
        assertNull(cache.read(key, 0, 3))
        assertEquals(0L, cache.usedBytes.value)
        cache.put(key, 0, ByteArray(3), cache.generation())
        directory.listFiles()!!.single().delete()
        assertNull(cache.read(key, 0, 3))
        assertEquals(0L, cache.usedBytes.value)
    }

    @Test fun incompleteTemporaryFilesAreRemovedOnStartup() {
        val directory = temporary.newFolder()
        File(directory, "abandoned.part").writeBytes(ByteArray(10))
        val cache = PlaybackDiskCache(directory)
        cache.refreshUsage()
        assertEquals(0L, cache.usedBytes.value)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun unwritableCacheDirectoryDoesNotThrowFromPlaybackWrites() {
        val directory = temporary.newFile()
        val cache = PlaybackDiskCache(directory)
        cache.put(key, 0, ByteArray(3), cache.generation())
        assertEquals(0L, cache.usedBytes.value)
        assertNull(cache.read(key, 0, 3))
        assertNotNull(cache.writeIssue.value)
    }

    @Test fun partialBlocksSurviveRestartAndCanBeContinuedWithoutHoles() {
        val directory = temporary.newFolder()
        var cache = PlaybackDiskCache(directory, maxBytes = 100)
        assertTrue(cache.append(key, 0, 0, byteArrayOf(1, 2, 3), 0, 3, cache.generation()))
        assertEquals(3L, cache.usedBytes.value)
        cache = PlaybackDiskCache(directory, maxBytes = 100)
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.readPrefix(key, 0, 6))
        assertFalse(cache.append(key, 0, 4, byteArrayOf(9), 0, 1, cache.generation()))
        assertTrue(cache.append(key, 0, 2, byteArrayOf(3, 4, 5), 0, 3, cache.generation()))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), cache.readPrefix(key, 0, 6))
        assertEquals(5L, cache.usedBytes.value)
    }

    @Test fun growingBlockEvictsOtherBlocksAndClearRejectsItsOldWriter() {
        val cache = PlaybackDiskCache(temporary.newFolder(), maxBytes = 6)
        val generation = cache.generation()
        cache.append(key, 0, 0, ByteArray(3), 0, 3, generation)
        cache.append(key, 1, 0, ByteArray(3), 0, 3, generation)
        cache.append(key, 0, 3, byteArrayOf(4), 0, 1, generation)
        assertEquals(4L, cache.usedBytes.value)
        assertNull(cache.readPrefix(key, 1, 3))
        cache.clear()
        assertFalse(cache.append(key, 0, 0, byteArrayOf(1), 0, 1, generation))
        assertEquals(0L, cache.usedBytes.value)
    }

    @Test fun persistentIndexRequiresPayloadAndDoesNotContainServerCredentials() {
        val directory = temporary.newFolder()
        val cache = PlaybackDiskCache(directory)
        val namespace = PlaybackDiskCache.namespace("account-file")
        val info = CachedMediaInfo(key, 16, "version", "video/mp4", listOf(CachedSubtitle("/dav/电影.sc.srt", "电影.sc.srt")))
        cache.remember(namespace, info, cache.generation())
        assertNull(cache.cachedMedia("account-file"))
        cache.append(key, 0, 0, byteArrayOf(1, 2), 0, 2, cache.generation())
        assertEquals(info, PlaybackDiskCache(directory).cachedMedia("account-file"))
        cache.clear()
        assertNull(PlaybackDiskCache(directory).cachedMedia("account-file"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
