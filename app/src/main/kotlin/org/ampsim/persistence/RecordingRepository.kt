package org.ampsim.persistence

import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * A persistent library of recorded takes. Implementations must never throw
 * to the caller: mutations return [Result.failure] and reads degrade
 * gracefully, mirroring [PresetRepository]'s error-handling contract.
 */
interface RecordingRepository {
    val recordings: StateFlow<List<RecordingSummary>>

    /** Re-scan the backing store and republish [recordings]. */
    suspend fun refresh()

    suspend fun rename(oldName: String, newName: String): Result<Unit>

    suspend fun delete(name: String): Result<Unit>

    /** Copy the take named [name] to [destination]. */
    suspend fun export(name: String, destination: File): Result<Unit>
}
