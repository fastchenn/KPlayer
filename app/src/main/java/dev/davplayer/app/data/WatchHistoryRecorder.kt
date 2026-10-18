package dev.davplayer.app.data

import dev.davplayer.playback.api.PlaybackPhase
import dev.davplayer.playback.api.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread coordinator. Only native timestamps for the active media can update history. */
class WatchHistoryRecorder(
    private val persist: (List<WatchRecord>) -> Unit,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val entries = MutableStateFlow<List<WatchRecord>>(emptyList())
    val records = entries.asStateFlow()
    private var loaded = false
    private var dirty = false
    private var lastWriteMs = 0L
    private var session: Session? = null

    fun loaded(saved: List<WatchRecord>) {
        entries.value = (entries.value + saved).distinctBy { it.id }.sortedByDescending { it.updatedAtMs }.take(MAX_RECORDS)
        loaded = true
        flush()
    }

    fun start(seed: WatchRecord, mediaId: String, requestedStartMs: Long) {
        session = Session(seed, mediaId, requestedStartMs)
    }

    fun observe(playback: PlaybackState) {
        val current = session ?: return
        val phaseChanged = current.lastPhase != playback.phase
        current.lastPhase = playback.phase
        current.update(playback, wallClock())?.let { record ->
            entries.value = (listOf(record) + entries.value.filterNot { it.id == record.id })
                .sortedByDescending { it.updatedAtMs }.take(MAX_RECORDS)
            dirty = true
        }
        if (phaseChanged && playback.phase in setOf(PlaybackPhase.PAUSED, PlaybackPhase.ENDED) ||
            monotonicClock() - lastWriteMs >= 2_000
        ) flush()
    }

    fun checkpoint(playback: PlaybackState) { observe(playback); flush() }
    fun finish(playback: PlaybackState) { checkpoint(playback); session = null }
    fun retrySave() { if (loaded) { dirty = true; flush() } }
    fun remove(id: String) { entries.value = entries.value.filterNot { it.id == id }; dirty = true; flush() }
    fun clear() { entries.value = emptyList(); dirty = true; flush() }

    private fun flush() {
        if (loaded && dirty) {
            persist(entries.value)
            dirty = false
            lastWriteMs = monotonicClock()
        }
    }

    private class Session(private var record: WatchRecord, val mediaId: String, val requestedStartMs: Long) {
        var lastPhase: PlaybackPhase? = null
        private var observed = false
        fun update(state: PlaybackState, now: Long): WatchRecord? {
            if (state.mediaId != mediaId || state.phase !in setOf(
                    PlaybackPhase.PLAYING, PlaybackPhase.PAUSED, PlaybackPhase.BUFFERING, PlaybackPhase.ENDED,
                )) return null
            val ended = state.phase == PlaybackPhase.ENDED
            if (!state.positionKnown && !(ended && state.durationMs != null)) return null
            if (!observed && requestedStartMs > 0 && state.positionMs == 0L && !ended) return null
            val duration = state.durationMs?.takeIf { it > 0 } ?: record.durationMs
            val position = if (ended && duration != null) duration else state.positionMs.coerceAtLeast(0).let {
                if (duration != null) it.coerceAtMost(duration) else it
            }
            if (observed && record.positionMs == position && record.durationMs == duration && record.completed == ended) return null
            observed = true
            record = record.copy(positionMs = position, durationMs = duration, completed = ended, updatedAtMs = now)
            return record
        }
    }

    companion object { const val MAX_RECORDS = 500 }
}
