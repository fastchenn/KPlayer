package dev.davplayer.app.ui

import android.view.Surface
import dev.davplayer.playback.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class SwipeToSeekTest {
    @Test fun previewIsRelativeToStartAndSeeksOnlyOnceOnRelease() {
        val engine = FakeEngine()
        val swipe = SwipeToSeek(engine)
        swipe.start(1_000f)
        swipe.drag(250f)
        assertEquals("00:03:30/00:08:30", swipe.preview?.label)
        engine.state.value = engine.state.value.copy(positionMs = 185_000)
        swipe.drag(500f)
        assertEquals(240_000L, swipe.preview?.positionMs)
        assertTrue(engine.seeks.isEmpty())
        swipe.finish()
        swipe.finish()
        assertEquals(listOf(240_000L), engine.seeks)
        assertNull(swipe.preview)
    }

    @Test fun draggingLeftAndRightClampsAtBothEnds() {
        val swipe = SwipeToSeek(FakeEngine())
        swipe.start(1_000f)
        swipe.drag(-10_000f)
        assertEquals(0L, swipe.preview?.positionMs)
        swipe.drag(10_000f)
        assertEquals(510_000L, swipe.preview?.positionMs)
    }

    @Test fun shortClipsUseTheirDurationAndWorkWhilePaused() {
        val engine = FakeEngine(PlaybackState(phase = PlaybackPhase.PAUSED, seekable = true, durationMs = 30_000))
        val swipe = SwipeToSeek(engine)
        swipe.start(600f)
        swipe.drag(300f)
        swipe.finish()
        assertEquals(listOf(15_000L), engine.seeks)
        assertEquals(PlaybackPhase.PAUSED, engine.state.value.phase)
    }

    @Test fun cancellationNeverSeeksAndNextGestureStartsFromLatestPosition() {
        val engine = FakeEngine()
        val swipe = SwipeToSeek(engine)
        swipe.start(1_000f)
        swipe.drag(500f)
        swipe.cancel()
        swipe.finish()
        assertNull(swipe.preview)
        assertTrue(engine.seeks.isEmpty())
        engine.state.value = engine.state.value.copy(positionMs = 209_000)
        swipe.start(1_000f)
        assertEquals("00:03:29/00:08:30", swipe.preview?.label)
    }

    @Test fun unknownDurationUnseekableAndInactivePlaybackIgnoreSwipes() {
        val base = FakeEngine().state.value
        listOf(base.copy(durationMs = null), base.copy(durationMs = 0), base.copy(seekable = false),
            base.copy(phase = PlaybackPhase.PREPARING), base.copy(phase = PlaybackPhase.ERROR)).forEach { state ->
            val engine = FakeEngine(state)
            val swipe = SwipeToSeek(engine)
            swipe.start(1_000f)
            swipe.drag(500f)
            swipe.finish()
            assertNull(swipe.preview)
            assertTrue(engine.seeks.isEmpty())
        }
        val engine = FakeEngine(supportsSeek = false)
        SwipeToSeek(engine).apply { start(1_000f); drag(500f); finish() }
        assertTrue(engine.seeks.isEmpty())
    }

    @Test fun becomingUnseekableBeforeReleaseDiscardsPreview() {
        val engine = FakeEngine()
        val swipe = SwipeToSeek(engine)
        swipe.start(1_000f)
        swipe.drag(500f)
        engine.state.value = engine.state.value.copy(seekable = false)
        swipe.finish()
        assertTrue(engine.seeks.isEmpty())
        assertNull(swipe.preview)
    }

    @Test fun invalidViewportAndDistancesAreIgnored() {
        val swipe = SwipeToSeek(FakeEngine())
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            swipe.start(it)
            assertNull(swipe.preview)
        }
        swipe.start(1_000f)
        swipe.drag(Float.NaN)
        assertEquals(180_000L, swipe.preview?.positionMs)
    }

    @Test fun hoursAreAlwaysShownIncludingClipsLongerThanOneHour() {
        assertEquals("00:00:00/01:01:01", SeekPreview(0, 3_661_000).label)
        assertEquals("01:02:03/12:34:56", SeekPreview(3_723_000, 45_296_000).label)
    }

    private class FakeEngine(
        initial: PlaybackState = PlaybackState(phase = PlaybackPhase.PLAYING, seekable = true, positionMs = 180_000, durationMs = 510_000),
        supportsSeek: Boolean = true,
    ) : PlayerEngine {
        override val id = EngineId.MPV
        override val availability = EngineAvailability.Available
        override val capabilities = PlayerCapabilities(seek = supportsSeek)
        override val state = MutableStateFlow(initial)
        val seeks = mutableListOf<Long>()
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        override fun attachSurface(surface: Surface?) = Unit
        override fun setSurfaceSize(width: Int, height: Int) = Unit
        override fun open(request: PlaybackRequest) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun selectTrack(type: TrackType, trackId: String?) = Unit
        override fun release() = Unit
    }
}
