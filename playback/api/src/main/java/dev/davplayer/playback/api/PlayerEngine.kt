package dev.davplayer.playback.api

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

enum class EngineId { MPV, EXO }

sealed interface EngineAvailability {
    data object Available : EngineAvailability
    data class Unavailable(val reason: String) : EngineAvailability
}

enum class PlaybackPhase { IDLE, PREPARING, PLAYING, PAUSED, BUFFERING, ENDED, ERROR, UNAVAILABLE }
enum class TrackType { AUDIO, SUBTITLE }

data class MediaTrack(
    val id: String,
    val type: TrackType,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
)

data class PlayerCapabilities(
    val seek: Boolean = false,
    val speed: Boolean = false,
    val audioTracks: Boolean = false,
    val subtitles: Boolean = false,
    val subtitleDelay: Boolean = false,
    val shaders: Boolean = false,
)

data class PlaybackState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val speed: Float = 1f,
    val tracks: List<MediaTrack> = emptyList(),
    val message: String? = null,
)

/** Credentials must never appear in logs or playback-history records. */
class PlaybackRequest(
    val mediaId: String,
    val url: String,
    val title: String,
    val headers: Map<String, String> = emptyMap(),
    val startPositionMs: Long = 0,
    val speed: Float = 1f,
) {
    override fun toString() = "PlaybackRequest(mediaId=$mediaId, credentials=redacted)"
}

/**
 * All positions and durations use milliseconds. Commands are called on the main thread.
 * Native callbacks must be marshalled to a safe state update by each implementation.
 * Track identifiers belong to their engine and must not be reused after switching engines.
 */
interface PlayerEngine {
    val id: EngineId
    val availability: EngineAvailability
    val capabilities: PlayerCapabilities
    val state: StateFlow<PlaybackState>

    fun attachSurface(surface: Surface?)
    fun setSurfaceSize(width: Int, height: Int)
    fun open(request: PlaybackRequest)
    fun play()
    fun pause()
    /** Close the current media while leaving the engine reusable. */
    fun stop()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun selectTrack(type: TrackType, trackId: String?)
    /** Terminal cleanup, called when the engine is discarded. */
    fun release()
}
