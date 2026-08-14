package org.ampsim.ui.recording

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import org.ampsim.persistence.RecordingRepository
import org.ampsim.persistence.RecordingSummary
import org.ampsim.recording.PlaybackState
import org.ampsim.recording.RecordingProgress

/** Combined, render-ready state for [RecordingBoothView]. */
data class RecordingBoothState(
    val isRecording: Boolean,
    val elapsedMs: Long,
    val overrunWarning: Boolean,
    val meterLevel: Float,
    val playback: PlaybackState
)

/**
 * GTK-free view model backing the Recording Booth tab: search over the
 * recordings library, plus a combined [state] fusing record progress,
 * playback status, and the live output meter. Mirrors
 * [org.ampsim.ui.preset.PresetsViewModel]'s split from its widget - no owned
 * `CoroutineScope`, just cold [Flow]s collected by whoever binds this to
 * widgets. The actual start/stop/play/rename/delete orchestration lives in
 * `App`, which owns the underlying services together.
 */
class RecordingBoothViewModel(
    private val recordingRepository: RecordingRepository,
    recordingProgress: Flow<RecordingProgress?>,
    playbackState: Flow<PlaybackState>,
    meterLevel: Flow<Float>
) {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // See PresetsViewModel.debouncedQuery for why this isn't a plain
    // kotlinx debounce(): that delays the very first emission too, which
    // would leave filteredRecordings emitting nothing for 300ms on every
    // fresh subscribe.
    private val debouncedQuery: Flow<String> = channelFlow {
        var isFirst = true
        _searchQuery.collectLatest { query ->
            if (isFirst) isFirst = false else delay(SEARCH_DEBOUNCE_MS)
            send(query)
        }
    }

    /** Recordings matching the current (debounced) search query, newest first (as published by the repository). */
    val filteredRecordings: Flow<List<RecordingSummary>> =
        combine(recordingRepository.recordings, debouncedQuery) { all, query ->
            if (query.isBlank()) all else all.filter { it.name.contains(query, ignoreCase = true) }
        }

    val state: Flow<RecordingBoothState> =
        combine(recordingProgress, playbackState, meterLevel) { progress, playback, level ->
            RecordingBoothState(
                isRecording = progress != null,
                elapsedMs = progress?.elapsedMs ?: 0L,
                overrunWarning = progress?.overrunDetected ?: false,
                meterLevel = level,
                playback = playback
            )
        }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}
