package dev.davplayer.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.davplayer.playback.api.PlaybackPhase
import dev.davplayer.playback.api.PlayerEngine
import java.util.Locale

internal data class SeekPreview(val positionMs: Long, val durationMs: Long) {
    val label: String get() = "${gestureTime(positionMs)}/${gestureTime(durationMs)}"
}

/** Preview relative to the position at gesture start; send one seek only on release. */
internal class SwipeToSeek(private val engine: PlayerEngine) {
    var preview by mutableStateOf<SeekPreview?>(null)
        private set
    private var startPositionMs = 0L
    private var widthPx = 0f

    fun start(viewportWidthPx: Float) {
        cancel()
        val state = engine.state.value
        val duration = state.durationMs?.takeIf { it > 0 } ?: return
        if (!engine.capabilities.seek || !state.seekable ||
            state.phase !in seekablePhases || !viewportWidthPx.isFinite() || viewportWidthPx <= 0f
        ) return
        startPositionMs = state.positionMs.coerceIn(0, duration)
        widthPx = viewportWidthPx
        preview = SeekPreview(startPositionMs, duration)
    }

    fun drag(distancePx: Float) {
        val current = preview ?: return
        if (!distancePx.isFinite()) return
        // One viewport moves up to two minutes, or the full duration for shorter clips.
        val delta = distancePx.toDouble() / widthPx * minOf(current.durationMs, 120_000L)
        preview = current.copy(positionMs = (startPositionMs.toDouble() + delta)
            .coerceIn(0.0, current.durationMs.toDouble()).toLong())
    }

    fun finish() {
        val target = preview ?: return
        cancel()
        val state = engine.state.value
        if (engine.capabilities.seek && state.seekable && state.phase in seekablePhases) {
            engine.seekTo(target.positionMs.coerceIn(0, state.durationMs?.takeIf { it > 0 } ?: target.durationMs))
        }
    }

    fun cancel() { preview = null }

    private companion object {
        val seekablePhases = setOf(PlaybackPhase.PLAYING, PlaybackPhase.PAUSED, PlaybackPhase.BUFFERING, PlaybackPhase.ENDED)
    }
}

private fun gestureTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
