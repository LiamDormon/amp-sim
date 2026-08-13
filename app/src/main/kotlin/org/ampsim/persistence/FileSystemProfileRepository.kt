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
import org.ampsim.model.ConfigurationProfile
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Clock

/**
 * File-backed [ProfileRepository]. Each profile is stored as its own JSON
 * file named after a sanitized version of [ConfigurationProfile.metadata]'s
 * `name`, inside [profilesDir] — a direct mirror of
 * [FileSystemPresetRepository], stored in its own sibling directory so
 * profiles never mix with the live config file.
 */
class FileSystemProfileRepository(
    private val profilesDir: File
) : ProfileRepository {

    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _profiles = MutableStateFlow<List<ProfileSummary>>(emptyList())
    override val profiles = _profiles.asStateFlow()

    init {
        repoScope.launch(ioDispatcher) { rescan() }
    }

    override suspend fun save(profile: ConfigurationProfile): Result<Unit> = withContext(ioDispatcher) {
        val result = runCatching {
            profilesDir.mkdirs()
            val target = fileFor(profile.metadata.name)
            val tmp = File(profilesDir, "${target.name}.tmp-${System.nanoTime()}")
            tmp.writeText(json.encodeToString(ConfigurationProfile.serializer(), profile))
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Unit
        }.onFailure { e ->
            System.err.println("Failed to save profile '${profile.metadata.name}': ${e.message}")
        }
        rescan()
        result
    }

    override suspend fun load(name: String): ConfigurationProfile? = withContext(ioDispatcher) {
        val file = fileFor(name)
        if (!file.exists()) return@withContext null

        val text = runCatching { file.readText() }.getOrElse {
            System.err.println("Failed to read profile '$name': ${it.message}")
            return@withContext null
        }

        decodeProfile(text).getOrElse {
            System.err.println("Failed to parse profile '$name': ${it.message}")
            null
        }
    }

    override suspend fun delete(name: String): Result<Unit> = withContext(ioDispatcher) {
        val result = runCatching { fileFor(name).delete(); Unit }
            .onFailure { e -> System.err.println("Failed to delete profile '$name': ${e.message}") }
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
        if (exists(newName)) return Result.failure(IllegalStateException("A profile named '$newName' already exists"))
        val profile = load(oldName) ?: return Result.failure(IllegalStateException("Profile '$oldName' does not exist"))
        val saveResult = save(profile.copy(metadata = profile.metadata.copy(name = newName, modified = Clock.System.now())))
        if (saveResult.isFailure) return saveResult
        return delete(oldName)
    }

    /** Stop the repository's background scope. Call once on shutdown. */
    fun cancel() = repoScope.cancel()

    /** Must only be called while already on [ioDispatcher]. */
    private fun rescan() {
        val files = profilesDir.listFiles { f -> f.isFile && f.extension == "json" && ".tmp-" !in f.name }
            ?: emptyArray()
        _profiles.value = files.mapNotNull { f ->
            runCatching { f.readText() }.getOrNull()
                ?.let { decodeProfile(it).getOrNull() }
                ?.let { ProfileSummary(it.metadata.name, it.metadata.description, it.metadata.modified) }
        }.sortedByDescending { it.modified }
    }

    /** Decode+validate JSON profile [text]. Shared by [load], [rescan]. */
    private fun decodeProfile(text: String): Result<ConfigurationProfile> {
        val profile = runCatching { json.decodeFromString(ConfigurationProfile.serializer(), text) }
            .getOrElse { return Result.failure(IllegalArgumentException("Not a valid profile file: ${it.message}")) }
        if (!isValid(profile)) return Result.failure(IllegalArgumentException("Profile is missing a required name or version"))
        return Result.success(profile)
    }

    private fun isValid(profile: ConfigurationProfile): Boolean =
        profile.metadata.name.isNotBlank() && profile.version.isNotBlank()

    private fun slugify(name: String): String {
        val slug = name.trim()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else '_' }
            .joinToString("")
            .replace(Regex(" +"), "_")
        return slug.ifBlank { "profile" }
    }

    private fun fileFor(name: String): File = File(profilesDir, "${slugify(name)}.json")

    companion object {
        fun getOrCreateProfilesDir(): File {
            val dir = File("${System.getProperty("user.home")}/.local/share/amp-sim/profiles")
            dir.mkdirs()
            return dir
        }
    }
}
