package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.CachedTimeRange

internal data class CacheReadSnapshot(val seekEpoch: Long, val playbackEpoch: Long, val bytes: List<CachedByteRange>, val eligible: Boolean)
internal data class PlaybackTimelineSample(val positionMs: Long?, val playing: Boolean, val speed: Float = 1f, val originMs: Long = 0)
internal data class CachedTimeProof(val time: CachedTimeRange, val bytes: List<CachedByteRange>)

/** Actual played intervals fill memory-cache gaps, but never bridge a seek or a stalled poll. */
internal class PlayedTimeCoverage(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Point(val epoch: Long, val position: Long, val clock: Long)
    private var previous: Point? = null
    fun observe(epoch: Long, sample: PlaybackTimelineSample?): List<CachedTimeRange> {
        val now = clockMs()
        val old = previous
        val position = sample?.positionMs
        if (sample == null || !sample.playing || position == null || position < 0) { previous = null; return emptyList() }
        previous = Point(epoch, position, now)
        if (old == null || old.epoch != epoch) return emptyList()
        val elapsed = now - old.clock
        val distance = position - old.position
        val maximum = elapsed * sample.speed.coerceIn(0.25f, 4f) * 1.5 + 1_000
        if (elapsed !in 1..10_000 || distance <= 0 || distance > maximum) return emptyList()
        return listOf(CachedTimeRange(old.position, position))
    }
}
