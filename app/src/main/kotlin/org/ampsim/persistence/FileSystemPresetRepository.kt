package org.ampsim.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.ampsim.model.Preset
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Clock

/**
 * File-backed [PresetRepository]. Each preset is stored as its own JSON file
 * named after a sanitized version of [Preset.metadata]'s `name`, inside
 * [presetsDir].
 *
 * Writes go through a temp-file-then-atomic-rename dance so a reader never
 * observes a half-written file, and all I/O is serialized onto a single
 * background dispatcher (mirroring [ConfigManager]) so overlapping writes to
 * the same name apply in a deterministic order instead of racing.
 */
class FileSystemPresetRepository(
    private val presetsDir: File
) : PresetRepository {

    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _presets = MutableStateFlow<List<PresetSummary>>(emptyList())
    override val presets = _presets.asStateFlow()

    init {
        repoScope.launch(ioDispatcher) { rescan() }
    }

    override suspend fun save(preset: Preset): Result<Unit> = withContext(ioDispatcher) {
        val result = runCatching {
            presetsDir.mkdirs()
            val target = fileFor(preset.metadata.name)
            val tmp = File(presetsDir, "${target.name}.tmp-${System.nanoTime()}")
            tmp.writeText(json.encodeToString(Preset.serializer(), preset))
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                // Fallback for filesystems without atomic rename support; still correct,
                // just no longer guaranteed atomic.
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Unit
        }.onFailure { e ->
            System.err.println("Failed to save preset '${preset.metadata.name}': ${e.message}")
        }
        rescan()
        result
    }

    override suspend fun load(name: String): Preset? = withContext(ioDispatcher) {
        val file = fileFor(name)
        if (!file.exists()) return@withContext null

        val text = runCatching { file.readText() }.getOrElse {
            System.err.println("Failed to read preset '$name': ${it.message}")
            return@withContext null
        }

        val preset = runCatching { json.decodeFromString(Preset.serializer(), text) }.getOrElse {
            System.err.println("Failed to parse preset '$name': ${it.message}")
            return@withContext null
        }

        preset.takeIf { isValid(it) }
    }

    override suspend fun delete(name: String): Result<Unit> = withContext(ioDispatcher) {
        val result = runCatching { fileFor(name).delete(); Unit }
            .onFailure { e -> System.err.println("Failed to delete preset '$name': ${e.message}") }
        rescan()
        result
    }

    override suspend fun exists(name: String): Boolean = withContext(ioDispatcher) {
        fileFor(name).exists()
    }

    override suspend fun refresh() = withContext(ioDispatcher) {
        rescan()
    }

    override suspend fun rename(oldName: String, newName: String): Result<Unit> {
        if (newName.isBlank()) return Result.failure(IllegalArgumentException("newName must not be blank"))
        if (newName == oldName) return Result.success(Unit)
        if (exists(newName)) return Result.failure(IllegalStateException("A preset named '$newName' already exists"))
        val preset = load(oldName) ?: return Result.failure(IllegalStateException("Preset '$oldName' does not exist"))
        val saveResult = save(preset.copy(metadata = preset.metadata.copy(name = newName, modified = Clock.System.now())))
        if (saveResult.isFailure) return saveResult
        return delete(oldName)
    }

    override suspend fun duplicate(sourceName: String, newName: String): Result<Unit> {
        if (newName.isBlank()) return Result.failure(IllegalArgumentException("newName must not be blank"))
        if (exists(newName)) return Result.failure(IllegalStateException("A preset named '$newName' already exists"))
        val source = load(sourceName) ?: return Result.failure(IllegalStateException("Preset '$sourceName' does not exist"))
        val now = Clock.System.now()
        return save(source.copy(metadata = source.metadata.copy(name = newName, created = now, modified = now)))
    }

    override suspend fun export(name: String, destination: File): Result<Unit> = withContext(ioDispatcher) {
        val source = fileFor(name)
        if (!source.exists()) return@withContext Result.failure(IllegalStateException("Preset '$name' does not exist"))
        runCatching {
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Unit
        }.onFailure { e -> System.err.println("Failed to export preset '$name': ${e.message}") }
    }

    /** Stop the repository's background scope. Call once on shutdown. */
    fun cancel() = repoScope.cancel()

    /** Must only be called while already on [ioDispatcher]. */
    private fun rescan() {
        val files = presetsDir.listFiles { f -> f.isFile && f.extension == "json" && ".tmp-" !in f.name }
            ?: emptyArray()
        _presets.value = files.mapNotNull { f ->
            runCatching { json.decodeFromString(Preset.serializer(), f.readText()) }
                .getOrNull()
                ?.takeIf { isValid(it) }
                ?.let { PresetSummary(it.metadata.name, it.metadata.description, it.metadata.author, it.metadata.modified, it.metadata.tags) }
        }.sortedByDescending { it.modified }
    }

    private fun isValid(preset: Preset): Boolean =
        preset.metadata.name.isNotBlank() && preset.version.isNotBlank()

    private fun slugify(name: String): String {
        val slug = name.trim()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else '_' }
            .joinToString("")
            .replace(Regex(" +"), "_")
        return slug.ifBlank { "preset" }
    }

    private fun fileFor(name: String): File = File(presetsDir, "${slugify(name)}.json")

    companion object {
        fun getOrCreatePresetsDir(): File {
            val dir = File("${System.getProperty("user.home")}/.local/share/amp-sim/presets")
            dir.mkdirs()
            return dir
        }

        fun getOrCreateAutoSaveDir(): File {
            val dir = File("${System.getProperty("user.home")}/.cache/amp-sim/autosave")
            dir.mkdirs()
            return dir
        }
    }
}
