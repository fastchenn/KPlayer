package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.*

/** Native observations are serialized on the main thread before entering this reducer. */
internal class MpvPlaybackState {
    var value = PlaybackState()
        private set
    private var loaded = false
    private var paused = false
    private var buffering = false
    private var ended = false

    fun prepare(speed: Float, mediaId: String? = null) {
        loaded = false
        paused = false
        buffering = false
        ended = false
        value = PlaybackState(phase = PlaybackPhase.PREPARING, speed = speed, mediaId = mediaId)
    }

    fun fileLoaded() { loaded = true; refreshPhase() }

    fun property(name: String, data: Any?) {
        when (name) {
            "time-pos" -> secondsToMillis(data as? Double)?.let { value = value.copy(positionMs = it, positionKnown = true) }
            "duration" -> value = value.copy(durationMs = secondsToMillis(data as? Double)?.takeIf { it > 0 })
            "speed" -> (data as? Double)?.takeIf { it.isFinite() && it > 0 }?.let { value = value.copy(speed = it.toFloat()) }
            "pause" -> { paused = data == true; refreshPhase() }
            "paused-for-cache" -> { buffering = data == true; refreshPhase() }
            "eof-reached" -> { ended = data == true; refreshPhase() }
            "seekable" -> value = value.copy(seekable = data == true)
            "demuxer-cache-time" -> {
                val end = secondsToMillis(data as? Double)
                if (end != null) value = value.copy(bufferedUntilMs = end)
            }
            "demuxer-cache-duration" -> {
                val duration = secondsToMillis(data as? Double)?.takeIf { it > 0 } ?: return
                value = value.copy(bufferedUntilMs = (value.positionMs + duration).coerceAtMost(value.durationMs ?: Long.MAX_VALUE))
            }
        }
    }

    fun tracks(tracks: List<MediaTrack>) { value = value.copy(tracks = tracks) }
    fun networkSpeed(bytesPerSecond: Long) { value = value.copy(networkBytesPerSecond = bytesPerSecond.coerceAtLeast(0)) }

    fun endFile(eof: Boolean, errorMessage: String? = null) {
        if (eof || ended) { ended = true; refreshPhase() }
        else fail(errorMessage ?: "无法播放此视频，请检查文件格式、网络和服务器权限")
    }

    fun fail(message: String) { value = value.copy(phase = PlaybackPhase.ERROR, message = message) }

    fun idle() {
        loaded = false
        paused = false
        buffering = false
        ended = false
        value = PlaybackState()
    }

    private fun refreshPhase() {
        if (value.phase == PlaybackPhase.ERROR || value.phase == PlaybackPhase.IDLE) return
        value = value.copy(phase = when {
            ended -> PlaybackPhase.ENDED
            !loaded -> PlaybackPhase.PREPARING
            paused -> PlaybackPhase.PAUSED
            buffering -> PlaybackPhase.BUFFERING
            else -> PlaybackPhase.PLAYING
        })
    }

    companion object {
        internal fun secondsToMillis(seconds: Double?): Long? = seconds
            ?.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE / 1000.0 }
            ?.let { (it * 1000).toLong() }
    }
}
