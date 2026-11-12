package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.CacheDownloadProgress
import dev.davplayer.playback.api.CachedTimeRange

/** A media-time map derived from container offsets, never from average bitrate. */
internal interface ContainerCacheIndex {
    val ready: Boolean
    fun proofs(available: List<CachedByteRange>, durationMs: Long?, originMs: Long = 0): List<CachedTimeProof>
    fun metadataRanges(): List<CachedByteRange>
}

internal data class CachedTimelineState(val ranges: List<CachedTimeRange>, val progress: CacheDownloadProgress)

internal fun coversBytes(available: List<CachedByteRange>, start: Long, end: Long): Boolean {
    var low = 0
    var high = available.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (available[mid].start <= start) low = mid + 1 else high = mid
    }
    return low > 0 && available[low - 1].end >= end
}
