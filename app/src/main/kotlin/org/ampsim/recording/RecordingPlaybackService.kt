package org.ampsim.recording

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.ampsim.persistence.RecordingSummary

/** Playback state of a [RecordingPlaybackService]; each variant carries position/duration for a transport UI. */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Playing(val name: String, val positionMicros: Long, val durationMicros: Long) : PlaybackState
    data class Paused(val name: String, val positionMicros: Long, val durationMicros: Long) : PlaybackState
}

/**
 * Plays back a recorded take through the system default audio device via
 * `javax.sound.sampled`, deliberately independent of the JACK-based
 * [org.ampsim.audio.AudioEngine]: a take is always a complete, bounded file,
 * so a `Clip` gives play/pause/resume/seek and natural-end detection for
 * free, versus adding a real-time-safe player path into the audio engine
 * for no benefit.
 */
class RecordingPlaybackService {
    private var clip: Clip? = null

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state = _state.asStateFlow()

    /** Opens and starts playing [summary] from the beginning, replacing whatever was previously loaded. */
    suspend fun play(summary: RecordingSummary) = withContext(Dispatchers.IO) {
        closeClip()
        runCatching {
            val audioInputStream = AudioSystem.getAudioInputStream(summary.file)
            val newClip = AudioSystem.getClip()
            newClip.open(audioInputStream)
            newClip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP && newClip.framePosition >= newClip.frameLength) {
                    _state.value = PlaybackState.Idle
                }
            }
            newClip.start()
            clip = newClip
            _state.value = PlaybackState.Playing(summary.name, 0, newClip.microsecondLength)
        }.onFailure { e ->
            System.err.println("Failed to play recording '${summary.name}': ${e.message}")
            _state.value = PlaybackState.Idle
        }
        Unit
    }

    /** Pauses playback, retaining position so [resume] continues from where it left off. No-op unless currently playing. */
    fun pause() {
        val c = clip ?: return
        val current = _state.value as? PlaybackState.Playing ?: return
        c.stop()
        _state.value = PlaybackState.Paused(current.name, c.microsecondPosition, current.durationMicros)
    }

    /** Resumes a paused clip. No-op unless currently paused. */
    fun resume() {
        val c = clip ?: return
        val current = _state.value as? PlaybackState.Paused ?: return
        c.start()
        _state.value = PlaybackState.Playing(current.name, current.positionMicros, current.durationMicros)
    }

    /** Stops playback entirely and rewinds to the start. */
    fun stop() {
        clip?.let { it.stop(); it.microsecondPosition = 0 }
        _state.value = PlaybackState.Idle
    }

    /** Seeks the current clip to [micros]. No-op if nothing is loaded. */
    fun seekTo(micros: Long) {
        val c = clip ?: return
        c.microsecondPosition = micros.coerceIn(0, c.microsecondLength)
    }

    /** Refreshes the current state's position from the underlying clip. Call periodically from a UI timer while something is playing. */
    fun pollPosition() {
        clip ?: return
        val current = _state.value as? PlaybackState.Playing ?: return
        _state.value = current.copy(positionMicros = clip?.microsecondPosition ?: current.positionMicros)
    }

    /** Releases the underlying clip's native resources. Call once on shutdown. */
    fun close() = closeClip()

    private fun closeClip() {
        clip?.let {
            it.stop()
            it.close()
        }
        clip = null
    }
}
