package dev.davplayer.playback.api

/** A half-open media-time interval backed by persistent local data, in milliseconds. */
data class CachedTimeRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) }
}
