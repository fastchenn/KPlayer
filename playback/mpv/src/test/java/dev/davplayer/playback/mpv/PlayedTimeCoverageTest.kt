package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.CachedTimeRange
import org.junit.Assert.*
import org.junit.Test

class PlayedTimeCoverageTest {
    private var clock = 0L
    private val coverage = PlayedTimeCoverage { clock }
    private fun sample(epoch: Long, position: Long, playing: Boolean = true, speed: Float = 1f): List<CachedTimeRange> {
        clock += 1000
        return coverage.observe(epoch, PlaybackTimelineSample(position, playing, speed))
    }
    @Test fun continuousPlaybackFillsTheGapLeftByAFrozenNativeRange() {
        assertTrue(sample(0, 300000).isEmpty())
        assertEquals(listOf(CachedTimeRange(300000, 301000)), sample(0, 301000))
        assertEquals(listOf(CachedTimeRange(301000, 302000)), sample(0, 302000))
    }
    @Test fun seekAndPauseNeverJoinUnplayedTime() {
        sample(0, 300000)
        assertTrue(sample(1, 600000).isEmpty())
        assertEquals(listOf(CachedTimeRange(600000, 601000)), sample(1, 601000))
        assertTrue(sample(1, 601000, false).isEmpty())
        assertTrue(sample(1, 601000).isEmpty())
        assertEquals(listOf(CachedTimeRange(601000, 602000)), sample(1, 602000))
    }
    @Test fun implicitJumpsAndStalledObservationsDoNotPaintMissingSegments() {
        sample(0, 1000)
        assertTrue(sample(0, 100000).isEmpty())
        clock += 20000
        assertTrue(sample(0, 101000).isEmpty())
        assertTrue(sample(0, 90000).isEmpty())
    }
    @Test fun highSpeedPlaybackStillRecordsEveryPlayedInterval() {
        sample(0, 1000, speed = 4f)
        assertEquals(listOf(CachedTimeRange(1000, 5000)), sample(0, 5000, speed = 4f))
    }
}
