package dev.davplayer.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.davplayer.playback.api.PlaybackPhase
import dev.davplayer.playback.api.PlayerEngine

/** A temporary override: every way of ending a press must restore the selected speed. */
internal class HoldToDoubleSpeed(private val engine: PlayerEngine) {
    var active by mutableStateOf(false)
        private set
    private var previousSpeed: Float? = null

    fun start() {
        if (active || !engine.capabilities.speed ||
            engine.state.value.phase !in setOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING)
        ) return
        previousSpeed = engine.state.value.speed
        active = true
        engine.setSpeed(2f)
    }

    fun stop() {
        val speed = previousSpeed ?: return
        previousSpeed = null
        active = false
        engine.setSpeed(speed)
    }
}
