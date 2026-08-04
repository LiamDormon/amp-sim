package org.ampsim.persistence

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant
import org.ampsim.model.Preset
import java.io.File

/** Lightweight, list-friendly view of a preset without its full [Preset.chain]. */
data class PresetSummary(
    val name: String,
    val description: String,
    val author: String?,
    val modified: Instant,
    val tags: List<String> = emptyList()
)

/**
 * Persistence operations for [Preset]s. Implementations must never throw to
 * the caller: mutating operations report failure via [Result.failure]; reads
 * degrade to `null`/empty and log rather than throwing, mirroring
 * [ConfigManager]'s "always fall back gracefully" philosophy — important here
 * because [save] may be called from an unattended auto-save path where an
 * uncaught exception must not take down the app.
 */
interface PresetRepository {
    /** Summaries of every valid preset on disk, refreshed after every save/delete. */
    val presets: StateFlow<List<PresetSummary>>

    suspend fun save(preset: Preset): Result<Unit>
    suspend fun load(name: String): Preset?
    suspend fun delete(name: String): Result<Unit>
    suspend fun exists(name: String): Boolean

    /** Re-scan the backing directory (e.g. after an external change). */
    suspend fun refresh()

    /**
     * Rename [oldName] to [newName]. A no-op success if the names are equal.
     * Fails if [oldName] doesn't exist or [newName] is already taken by a
     * different preset. If the underlying save succeeds but the following
     * cleanup of the old file fails, both names may end up on disk (no data
     * loss, just a leftover duplicate).
     */
    suspend fun rename(oldName: String, newName: String): Result<Unit>

    /**
     * Copy [sourceName]'s preset under [newName] with fresh `created`/`modified`
     * timestamps, leaving [sourceName] untouched. Fails if [sourceName] doesn't
     * exist or [newName] is already taken.
     */
    suspend fun duplicate(sourceName: String, newName: String): Result<Unit>

    /**
     * Copy [name]'s on-disk file to an arbitrary [destination] chosen by the
     * caller (e.g. via a file-save dialog). Fails if [name] doesn't exist.
     */
    suspend fun export(name: String, destination: File): Result<Unit>
}
