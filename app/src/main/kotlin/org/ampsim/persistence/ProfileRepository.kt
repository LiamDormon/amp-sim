package org.ampsim.persistence

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant
import org.ampsim.model.ConfigurationProfile

/** Lightweight, list-friendly view of a profile without its full [ConfigurationProfile.configuration]. */
data class ProfileSummary(
    val name: String,
    val description: String,
    val modified: Instant
)

/**
 * Persistence operations for [ConfigurationProfile]s — named snapshots of the
 * app's full [org.ampsim.model.AppConfiguration]. Implementations must never
 * throw to the caller: mutating operations report failure via
 * [Result.failure]; reads degrade to `null`/empty and log rather than
 * throwing, mirroring [PresetRepository]'s contract.
 */
interface ProfileRepository {
    /** Summaries of every valid profile on disk, refreshed after every save/delete. */
    val profiles: StateFlow<List<ProfileSummary>>

    suspend fun save(profile: ConfigurationProfile): Result<Unit>
    suspend fun load(name: String): ConfigurationProfile?
    suspend fun delete(name: String): Result<Unit>
    suspend fun exists(name: String): Boolean

    /** Re-scan the backing directory (e.g. after an external change). */
    suspend fun refresh()

    /**
     * Rename [oldName] to [newName]. A no-op success if the names are equal.
     * Fails if [oldName] doesn't exist or [newName] is already taken by a
     * different profile.
     */
    suspend fun rename(oldName: String, newName: String): Result<Unit>
}
