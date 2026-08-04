package org.ampsim.persistence

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant
import org.ampsim.model.Preset

/** Lightweight, list-friendly view of a preset without its full [Preset.chain]. */
data class PresetSummary(
    val name: String,
    val description: String,
    val author: String?,
    val modified: Instant
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
}
