package dev.davplayer.app.data

import dev.davplayer.playback.api.PlaybackPhase
import dev.davplayer.playback.api.PlaybackState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchHistoryRecorderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = ServerConnection.create("NAS", "https://nas.invalid/dav/", "user", "secret", "server")
    private val entry = MediaEntry("https://nas.invalid/dav/movie.mkv", "movie.mkv", false, sizeBytes = 100)
    private val seed = WatchRecord.create(server, entry)!!
    private var now = 10_000L
    private val writes = mutableListOf<List<WatchRecord>>()
    private fun recorder() = WatchHistoryRecorder(persist = { writes.add(it) }, wallClock = { now }, monotonicClock = { now })
    private fun playback(position: Long, phase: PlaybackPhase = PlaybackPhase.PLAYING, id: String = "movie", known: Boolean = true, duration: Long? = 100_000) =
        PlaybackState(phase = phase, positionMs = position, durationMs = duration, mediaId = id, positionKnown = known)

    @Test fun nativeProgressIsPersistedAndSurvivesReopeningTheStore() {
        val store = WatchHistoryStore(File(temporary.root, "history.bin"))
        val recorder = WatchHistoryRecorder(store::save, { now }, { now })
        recorder.loaded(emptyList())
        recorder.start(seed, "movie", 0)
        recorder.observe(playback(12_345))
        recorder.finish(playback(12_678))
        val reopened = store.load().single()
        assertEquals(12_678L, reopened.positionMs)
        assertEquals(100_000L, reopened.durationMs)
        assertEquals(12_678L, reopened.resumePositionMs)
    }

    @Test fun seekingBackwardsSavesTheActualPositionRatherThanTheFarthestPoint() {
        val recorder = recorder()
        recorder.loaded(emptyList())
        recorder.start(seed, "movie", 0)
        recorder.observe(playback(80_000))
        now += 100
        recorder.checkpoint(playback(10_000))
        assertEquals(10_000L, writes.last().single().positionMs)
    }

    @Test fun resumedPlaybackDoesNotOverwriteSavedProgressWithPreparationOrDefaultZero() {
        val previous = seed.copy(positionMs = 60_000, durationMs = 100_000, updatedAtMs = 1)
        val recorder = recorder()
        recorder.loaded(listOf(previous))
        recorder.start(previous, "movie", 60_000)
        recorder.observe(playback(0, PlaybackPhase.PREPARING))
        recorder.observe(playback(0, known = false))
        recorder.checkpoint(playback(0))
        assertEquals(previous, recorder.records.value.single())
        assertTrue(writes.isEmpty())
        recorder.checkpoint(playback(60_100))
        assertEquals(60_100L, writes.last().single().positionMs)
    }

    @Test fun explicitPlaybackFromTheBeginningCanReplaceAnOldPositionWithZero() {
        val previous = seed.copy(positionMs = 60_000, durationMs = 100_000)
        val recorder = recorder()
        recorder.loaded(listOf(previous))
        recorder.start(previous, "movie", 0)
        recorder.checkpoint(playback(0))
        assertEquals(0L, writes.last().single().positionMs)
    }

    @Test fun returningToZeroAfterASeekIsRecordedEvenForAResumedSession() {
        val recorder = recorder()
        recorder.loaded(emptyList())
        recorder.start(seed, "movie", 50_000)
        recorder.observe(playback(50_000))
        recorder.checkpoint(playback(0, PlaybackPhase.PAUSED))
        assertEquals(0L, writes.last().single().positionMs)
    }

    @Test fun eventsFromAnotherVideoCannotPolluteTheCurrentRecord() {
        val recorder = recorder()
        recorder.loaded(emptyList())
        recorder.start(seed, "movie", 0)
        recorder.observe(playback(90_000, id = "old-movie"))
        assertTrue(recorder.records.value.isEmpty())
        recorder.observe(playback(5_000))
        val second = seed.copy(relativePath = "second.mkv", name = "second.mkv")
        recorder.finish(playback(5_100))
        recorder.start(second, "second", 0)
        recorder.observe(playback(99_000, id = "movie"))
        recorder.observe(playback(2_000, id = "second"))
        assertEquals(5_100L, recorder.records.value.first { it.id == seed.id }.positionMs)
        assertEquals(2_000L, recorder.records.value.first { it.id == second.id }.positionMs)
    }

    @Test fun pauseAndExitFlushTheLatestProgressWithoutWaitingForTheTimer() {
        val recorder = recorder()
        recorder.loaded(emptyList())
        recorder.start(seed, "movie", 0)
        recorder.observe(playback(1_000))
        val count = writes.size
        now += 100
        recorder.observe(playback(1_200, PlaybackPhase.PAUSED))
        assertEquals(count + 1, writes.size)
        recorder.finish(playback(1_234, PlaybackPhase.PAUSED))
        assertEquals(1_234L, writes.last().single().positionMs)
    }

    @Test fun activePlaybackIsSavedEveryTwoSecondsWithoutRewritingForUnchangedSamples() {
        val recorder = recorder()
        recorder.loaded(emptyList())
        recorder.start(seed, "movie", 0)
        recorder.observe(playback(1_000))
        val count = writes.size
        now += 1_000
        recorder.observe(playback(2_000))
        assertEquals(count, writes.size)
        now += 1_000
        recorder.observe(playback(3_000))
        assertEquals(count + 1, writes.size)
        now += 3_000
        recorder.observe(playback(3_000))
        assertEquals(count + 1, writes.size)
    }

    @Test fun onlyActualEofMarksTheVideoCompletedAndCompletedVideosRestartAtZero() {
        val recorder = recorder()
        recorder.loaded(emptyList())
        recorder.start(seed, "movie", 0)
        recorder.observe(playback(99_000))
        assertFalse(recorder.records.value.single().completed)
        recorder.observe(playback(99_900, PlaybackPhase.ENDED))
        val ended = writes.last().single()
        assertTrue(ended.completed)
        assertEquals(100_000L, ended.positionMs)
        assertEquals(0L, ended.resumePositionMs)
    }

    @Test fun unavailablePreparingAndFailedPlaybackDoNotCreateFalseHistory() {
        val recorder = recorder()
        recorder.loaded(emptyList())
        recorder.start(seed, "movie", 0)
        for (phase in listOf(PlaybackPhase.IDLE, PlaybackPhase.PREPARING, PlaybackPhase.ERROR, PlaybackPhase.UNAVAILABLE)) {
            recorder.checkpoint(playback(50_000, phase))
        }
        assertTrue(recorder.records.value.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test fun repeatedViewingUpdatesOneRecordAndOrdersByLastWatchTime() {
        val recorder = recorder()
        recorder.loaded(listOf(seed.copy(relativePath = "other.mkv", updatedAtMs = 100)))
        repeat(2) { index ->
            recorder.start(seed, "movie", 0)
            recorder.finish(playback(5_000L + index))
            now += 100
        }
        assertEquals(2, recorder.records.value.size)
        assertEquals(seed.id, recorder.records.value.first().id)
        assertEquals(5_001L, recorder.records.value.first().positionMs)
    }

    @Test fun delayedDiskLoadingMergesExistingRecordsWithProgressAlreadySeenInMemory() {
        val recorder = recorder()
        recorder.start(seed, "movie", 0)
        recorder.observe(playback(10_000))
        assertTrue(writes.isEmpty())
        val other = seed.copy(relativePath = "other.mkv", updatedAtMs = 1)
        recorder.loaded(listOf(seed.copy(positionMs = 2_000), other))
        assertEquals(2, writes.last().size)
        assertEquals(10_000L, writes.last().first { it.id == seed.id }.positionMs)
    }

    @Test fun removingAndClearingRecordsAlsoPersistsTheDeletion() {
        val recorder = recorder()
        recorder.loaded(listOf(seed, seed.copy(relativePath = "other.mkv")))
        recorder.remove(seed.id)
        assertEquals(1, writes.last().size)
        recorder.clear()
        assertTrue(writes.last().isEmpty())
    }
}
