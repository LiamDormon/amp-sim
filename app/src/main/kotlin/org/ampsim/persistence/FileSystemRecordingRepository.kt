package org.ampsim.persistence

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.sound.sampled.AudioSystem
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * File-backed [RecordingRepository]. Each take is a single self-describing
 * WAV file inside [recordingsDir] - unlike [FileSystemPresetRepository], no
 * JSON metadata sidecar is needed: the WAV header already carries sample
 * rate and duration, and the sortable timestamped filename doubles as the
 * creation time.
 *
 * All I/O is serialized onto a single background dispatcher (mirroring
 * [ConfigManager]/[FileSystemPresetRepository]) so overlapping mutations
 * apply in a deterministic order instead of racing.
 */
class FileSystemRecordingRepository(
    private val recordingsDir: File = getOrCreateRecordingsDir()
) : RecordingRepository {

    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _recordings = MutableStateFlow<List<RecordingSummary>>(emptyList())
    override val recordings = _recordings.asStateFlow()

    init {
        repoScope.launch(ioDispatcher) { rescan() }
    }

    override suspend fun refresh() = withContext(ioDispatcher) { rescan() }

    override suspend fun rename(oldName: String, newName: String): Result<Unit> = withContext(ioDispatcher) {
        if (newName.isBlank()) return@withContext Result.failure(IllegalArgumentException("newName must not be blank"))
        if (newName == oldName) return@withContext Result.success(Unit)
        val source = fileFor(oldName)
        val target = fileFor(newName)
        if (!source.exists()) return@withContext Result.failure(IllegalStateException("Recording '$oldName' does not exist"))
        if (target.exists()) return@withContext Result.failure(IllegalStateException("A recording named '$newName' already exists"))
        val result = runCatching { check(source.renameTo(target)) { "renameTo returned false" } }
            .onFailure { e -> System.err.println("Failed to rename recording '$oldName': ${e.message}") }
        rescan()
        result
    }

    override suspend fun delete(name: String): Result<Unit> = withContext(ioDispatcher) {
        val result = runCatching { fileFor(name).delete(); Unit }
            .onFailure { e -> System.err.println("Failed to delete recording '$name': ${e.message}") }
        rescan()
        result
    }

    override suspend fun export(name: String, destination: File): Result<Unit> = withContext(ioDispatcher) {
        val source = fileFor(name)
        if (!source.exists()) return@withContext Result.failure(IllegalStateException("Recording '$name' does not exist"))
        runCatching {
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Unit
        }.onFailure { e -> System.err.println("Failed to export recording '$name': ${e.message}") }
    }

    /** Stop the repository's background scope. Call once on shutdown. */
    fun cancel() = repoScope.cancel()

    /** Must only be called while already on [ioDispatcher]. */
    private fun rescan() {
        val files = recordingsDir.listFiles { f -> f.isFile && f.extension == "wav" } ?: emptyArray()
        _recordings.value = files.mapNotNull { f -> summarize(f) }.sortedByDescending { it.createdAt }
    }

    private fun summarize(file: File): RecordingSummary? = runCatching {
        val format = AudioSystem.getAudioFileFormat(file)
        val frameLength = format.frameLength.toLong()
        val sampleRate = format.format.sampleRate.toInt()
        val durationSeconds = if (frameLength > 0 && sampleRate > 0) frameLength.toDouble() / sampleRate else 0.0
        val name = file.nameWithoutExtension
        RecordingSummary(
            name = name,
            file = file,
            durationSeconds = durationSeconds,
            sampleRate = sampleRate,
            createdAt = parseTimestamp(name) ?: Instant.fromEpochMilliseconds(file.lastModified())
        )
    }.onFailure { e ->
        System.err.println("Failed to read recording '${file.name}': ${e.message}")
    }.getOrNull()

    private fun parseTimestamp(name: String): Instant? {
        val stamp = name.removePrefix(TAKE_PREFIX)
        if (stamp == name) return null
        return runCatching {
            val local = LocalDateTime.parse(stamp, FILENAME_FORMATTER)
            Instant.fromEpochMilliseconds(local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }.getOrNull()
    }

    private fun fileFor(name: String): File = File(recordingsDir, "$name.wav")

    companion object {
        private const val TAKE_PREFIX = "Take_"
        private val FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

        /** Filename for a take started right now - shared with [org.ampsim.recording.RecordingCaptureService]. */
        fun newTakeFileName(): String = "$TAKE_PREFIX${LocalDateTime.now().format(FILENAME_FORMATTER)}.wav"

        fun getOrCreateRecordingsDir(): File {
            val dir = File("${System.getProperty("user.home")}/.local/share/amp-sim/recordings")
            dir.mkdirs()
            return dir
        }
    }
}
