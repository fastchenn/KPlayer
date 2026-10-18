package dev.davplayer.app.ui

import android.view.Surface
import dev.davplayer.playback.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class HoldToDoubleSpeedTest {
    @Test fun releaseRestoresTheUsersSelectedSpeed() {
        val engine = FakeEngine(speed = 1.5f)
        val hold = HoldToDoubleSpeed(engine)
        hold.start()
        assertTrue(hold.active)
        assertEquals(2f, engine.state.value.speed)
        hold.stop()
        assertFalse(hold.active)
        assertEquals(listOf(2f, 1.5f), engine.speedChanges)
    }

    @Test fun repeatedStartDoesNotReplaceTheSpeedToRestore() {
        val engine = FakeEngine(speed = .75f)
        val hold = HoldToDoubleSpeed(engine)
        hold.start()
        hold.start()
        hold.stop()
        assertEquals(listOf(2f, .75f), engine.speedChanges)
    }

    @Test fun releaseAndCleanupCanBothStopTheSameHold() {
        val engine = FakeEngine()
        val hold = HoldToDoubleSpeed(engine)
        hold.stop()
        hold.start()
        hold.stop()
        hold.stop()
        assertEquals(listOf(2f, 1f), engine.speedChanges)
    }

    @Test fun eachHoldUsesTheLatestUserSelection() {
        val engine = FakeEngine()
        val hold = HoldToDoubleSpeed(engine)
        hold.start()
        hold.stop()
        engine.setSpeed(1.25f)
        hold.start()
        hold.stop()
        assertEquals(1.25f, engine.state.value.speed)
    }

    @Test fun pausedUnloadedEndedAndFailedVideosAreNotAccelerated() {
        listOf(PlaybackPhase.PAUSED, PlaybackPhase.PREPARING, PlaybackPhase.IDLE,
            PlaybackPhase.ENDED, PlaybackPhase.ERROR, PlaybackPhase.UNAVAILABLE).forEach { phase ->
            val engine = FakeEngine(phase = phase)
            val hold = HoldToDoubleSpeed(engine)
            hold.start()
            hold.stop()
            assertFalse(hold.active)
            assertTrue(engine.speedChanges.isEmpty())
        }
    }

    @Test fun bufferingPlaybackCanBeAccelerated() {
        val engine = FakeEngine(phase = PlaybackPhase.BUFFERING)
        val hold = HoldToDoubleSpeed(engine)
        hold.start()
        hold.stop()
        assertEquals(listOf(2f, 1f), engine.speedChanges)
    }

    @Test fun unsupportedEngineIsNotSentSpeedCommands() {
        val engine = FakeEngine(supportsSpeed = false)
        val hold = HoldToDoubleSpeed(engine)
        hold.start()
        hold.stop()
        assertFalse(hold.active)
        assertTrue(engine.speedChanges.isEmpty())
    }

    @Test fun alreadySelectedDoubleSpeedStaysSelectedAfterRelease() {
        val engine = FakeEngine(speed = 2f)
        val hold = HoldToDoubleSpeed(engine)
        hold.start()
        hold.stop()
        assertEquals(2f, engine.state.value.speed)
        assertFalse(hold.active)
    }

    private class FakeEngine(speed: Float = 1f, phase: PlaybackPhase = PlaybackPhase.PLAYING, supportsSpeed: Boolean = true) : PlayerEngine {
        override val id = EngineId.MPV
        override val availability = EngineAvailability.Available
        override val capabilities = PlayerCapabilities(speed = supportsSpeed)
        override val state = MutableStateFlow(PlaybackState(phase = phase, speed = speed))
        val speedChanges = mutableListOf<Float>()
        override fun setSpeed(speed: Float) { speedChanges.add(speed); state.value = state.value.copy(speed = speed) }
        override fun attachSurface(surface: Surface?) = Unit
        override fun setSurfaceSize(width: Int, height: Int) = Unit
        override fun open(request: PlaybackRequest) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun selectTrack(type: TrackType, trackId: String?) = Unit
        override fun release() = Unit
    }
}
