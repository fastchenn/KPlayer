package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.PlaybackPhase
import org.junit.Assert.*
import org.junit.Test

class MpvPlaybackStateTest {
    @Test fun historyCanDistinguishRealNativePositionsFromDefaultProgressAndIdentifyTheFile() {
        val state = MpvPlaybackState()
        state.prepare(1f, "first")
        assertEquals("first", state.value.mediaId)
        assertFalse(state.value.positionKnown)
        state.fileLoaded()
        assertFalse(state.value.positionKnown)
        state.property("time-pos", 0.0)
        assertTrue(state.value.positionKnown)
        state.property("time-pos", 12.345)
        assertEquals(12_345L, state.value.positionMs)
        state.prepare(1f, "second")
        assertEquals("second", state.value.mediaId)
        assertFalse(state.value.positionKnown)
        state.idle()
        assertNull(state.value.mediaId)
    }

    @Test fun preparationWaitsForNativeFileLoadedAndNeverInventsProgress() {
        val state = MpvPlaybackState()
        state.prepare(1.25f)
        state.property("pause", false)
        state.property("paused-for-cache", false)
        assertEquals(PlaybackPhase.PREPARING, state.value.phase)
        assertEquals(0L, state.value.positionMs)
        assertNull(state.value.durationMs)
        state.fileLoaded()
        assertEquals(PlaybackPhase.PLAYING, state.value.phase)
        state.property("time-pos", 1.234)
        state.property("duration", 90.5)
        assertEquals(1234L, state.value.positionMs)
        assertEquals(90500L, state.value.durationMs)
    }

    @Test fun userPauseTakesPrecedenceOverNetworkBuffering() {
        val state = MpvPlaybackState()
        state.prepare(1f)
        state.fileLoaded()
        state.property("paused-for-cache", true)
        assertEquals(PlaybackPhase.BUFFERING, state.value.phase)
        state.property("pause", true)
        assertEquals(PlaybackPhase.PAUSED, state.value.phase)
        state.property("paused-for-cache", false)
        assertEquals(PlaybackPhase.PAUSED, state.value.phase)
        state.property("pause", false)
        assertEquals(PlaybackPhase.PLAYING, state.value.phase)
    }

    @Test fun aFailedOpenIsAnErrorRatherThanASuccessfulEnd() {
        val state = MpvPlaybackState()
        state.prepare(1f)
        state.endFile(false, "视频认证失败，请检查账号和密码")
        assertEquals(PlaybackPhase.ERROR, state.value.phase)
        assertEquals("视频认证失败，请检查账号和密码", state.value.message)
        state.property("pause", true)
        assertEquals(PlaybackPhase.ERROR, state.value.phase)
        state.prepare(1f)
        assertEquals(PlaybackPhase.PREPARING, state.value.phase)
        assertNull(state.value.message)
    }

    @Test fun eofRemainsEndedWhenKeepOpenPausesTheNativePlayer() {
        val state = MpvPlaybackState()
        state.prepare(1f)
        state.fileLoaded()
        state.property("eof-reached", true)
        state.property("pause", true)
        state.endFile(false)
        assertEquals(PlaybackPhase.ENDED, state.value.phase)
        state.property("eof-reached", false)
        state.property("pause", false)
        assertEquals(PlaybackPhase.PLAYING, state.value.phase)
    }

    @Test fun invalidNativeTimestampsNeverBecomeBogusProgress() {
        val state = MpvPlaybackState()
        state.prepare(1f)
        state.property("time-pos", 2.0)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, Double.MAX_VALUE)) {
            state.property("time-pos", invalid)
            state.property("duration", invalid)
            assertEquals(2000L, state.value.positionMs)
            assertNull(state.value.durationMs)
        }
    }

    @Test fun changingFilesClearsAllPreviousPlaybackState() {
        val state = MpvPlaybackState()
        state.prepare(2f)
        state.fileLoaded()
        state.property("seekable", true)
        state.property("time-pos", 100.0)
        state.property("duration", 200.0)
        state.property("eof-reached", true)
        state.prepare(1f)
        assertFalse(state.value.seekable)
        assertEquals(0L, state.value.positionMs)
        assertNull(state.value.durationMs)
        assertEquals(1f, state.value.speed)
        assertEquals(PlaybackPhase.PREPARING, state.value.phase)
        state.idle()
        assertEquals(PlaybackPhase.IDLE, state.value.phase)
    }
}
