package org.ampsim.recording

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.ampsim.audio.AudioEngine
import org.ampsim.dsp.BaseDSPModule
import org.ampsim.persistence.FileSystemRecordingRepository
import org.ampsim.persistence.RecordingRepository

/** Elapsed time and health of the take currently in progress, or `null` if nothing is recording. */
data class RecordingProgress(val elapsedMs: Long, val overrunDetected: Boolean)

/**
 * Owns the record start/stop lifecycle and the background loop that drains
 * [AudioEngine]'s lock-free recording ring buffer to a [WavFileWriter].
 * None of this runs on the real-time audio thread - it's just another
 * control-thread consumer of an RT-safe handoff, the same shape as tuner
 * capture's window polling but lossless and continuous instead of
 * best-effort.
 *
 * [writer]/[stopRequested]/[startNanos]/[overrunBaseline] are confined to
 * [ioDispatcher] (`limitedParallelism(1)`, mirroring [org.ampsim.persistence.ConfigManager]):
 * every mutation of them happens inside a coroutine launched on that single
 * serialized dispatcher, so no explicit locking is needed - the same
 * thread-confinement idiom [AudioEngine] uses for its RT-only fields.
 */
class RecordingCaptureService(
    private val audioEngine: AudioEngine,
    private val recordingRepository: RecordingRepository,
    private val recordingsDir: File = FileSystemRecordingRepository.getOrCreateRecordingsDir()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _progress = MutableStateFlow<RecordingProgress?>(null)
    val progress = _progress.asStateFlow()

    private var writer: WavFileWriter? = null
    private var stopRequested = false
    private var startNanos = 0L
    private var overrunBaseline = 0L

    init {
        scope.launch(ioDispatcher) { drainLoop() }
    }

    /** No-op if a take is already in progress. Safe to call from any thread. */
    fun startRecording() {
        scope.launch(ioDispatcher) {
            if (writer != null) return@launch
            val sampleRate = audioEngine.getStatus().sampleRate.takeIf { it > 0 } ?: BaseDSPModule.DEFAULT_SAMPLE_RATE
            val newWriter = WavFileWriter(File(recordingsDir, FileSystemRecordingRepository.newTakeFileName()), sampleRate)
            overrunBaseline = audioEngine.getRecordingOverrunCount()
            startNanos = System.nanoTime()
            stopRequested = false
            writer = newWriter
            audioEngine.setRecordingEnabled(true)
            _progress.value = RecordingProgress(0, false)
        }
    }

    /**
     * No-op if nothing is currently recording. The drain loop keeps flushing
     * whatever the ring buffer still holds and only closes the file once a
     * tick finds nothing left - a few extra ms of tail audio is preferable
     * to a hard cutoff. Safe to call from any thread.
     */
    fun stopRecording() {
        scope.launch(ioDispatcher) {
            if (writer == null) return@launch
            audioEngine.setRecordingEnabled(false)
            stopRequested = true
        }
    }

    /**
     * Force-finalize an in-progress take synchronously - used on app
     * shutdown, where there's no time to wait for the drain loop's next
     * tick. Blocks the calling thread briefly; only intended for the
     * one-time shutdown path, never the real-time audio path.
     */
    fun flushForShutdown() {
        runBlocking(ioDispatcher) {
            val w = writer ?: return@runBlocking
            audioEngine.setRecordingEnabled(false)
            drainOnce(w)
            w.close()
            writer = null
        }
    }

    /** Stop the service's background scope. Call once on shutdown, after [flushForShutdown]. */
    fun cancel() = scope.cancel()

    private suspend fun drainLoop() {
        val chunk = FloatArray(DRAIN_CHUNK_SAMPLES)
        while (true) {
            val w = writer
            if (w != null) {
                val drainedAny = drainOnce(w, chunk)
                val overrun = audioEngine.getRecordingOverrunCount() - overrunBaseline
                _progress.value = RecordingProgress((System.nanoTime() - startNanos) / 1_000_000, overrun > 0)
                if (stopRequested && !drainedAny) {
                    w.close()
                    writer = null
                    stopRequested = false
                    _progress.value = null
                    recordingRepository.refresh()
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun drainOnce(writer: WavFileWriter, chunk: FloatArray = FloatArray(DRAIN_CHUNK_SAMPLES)): Boolean {
        var drainedAny = false
        while (true) {
            val n = audioEngine.pollRecordingCapture(chunk, chunk.size)
            if (n == 0) break
            writer.writeSamples(chunk, n)
            drainedAny = true
        }
        writer.patchHeader()
        return drainedAny
    }

    companion object {
        private const val DRAIN_CHUNK_SAMPLES = 4096
        private const val POLL_INTERVAL_MS = 30L
    }
}
