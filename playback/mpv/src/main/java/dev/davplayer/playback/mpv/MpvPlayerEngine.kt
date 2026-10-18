package dev.davplayer.playback.mpv

import android.view.Surface
import dev.davplayer.playback.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deliberately unavailable in the shell build: no native binary is bundled yet.
 * Replace this implementation with a libmpv binding without changing the app's screens.
 * Do not implement simulated progress: an unavailable engine must stay visibly unavailable.
 */
class MpvPlayerEngine : PlayerEngine {
    override val id = EngineId.MPV
    override val availability = EngineAvailability.Unavailable(
        "当前版本可浏览媒体库和预览播放界面，视频播放组件尚未接入。",
    )
    override val capabilities = PlayerCapabilities()
    private val mutableState = MutableStateFlow(unavailableState())
    override val state = mutableState.asStateFlow()

    override fun attachSurface(surface: Surface?) = Unit
    override fun setSurfaceSize(width: Int, height: Int) = Unit
    override fun open(request: PlaybackRequest) { mutableState.value = unavailableState() }
    override fun play() { mutableState.value = unavailableState() }
    override fun pause() = Unit
    override fun stop() { mutableState.value = unavailableState() }
    override fun seekTo(positionMs: Long) = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun selectTrack(type: TrackType, trackId: String?) = Unit
    override fun release() { mutableState.value = unavailableState() }

    private fun unavailableState() = PlaybackState(
        phase = PlaybackPhase.UNAVAILABLE,
        message = availability.reason,
    )
}
