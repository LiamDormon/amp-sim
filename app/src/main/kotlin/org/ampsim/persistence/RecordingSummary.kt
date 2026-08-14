package org.ampsim.persistence

import java.io.File
import kotlin.time.Instant

/**
 * List-friendly projection of a saved take, read straight off its WAV file -
 * unlike [PresetSummary] there's no JSON metadata sidecar backing this: the
 * WAV header carries [sampleRate]/[durationSeconds] and the timestamped
 * filename doubles as [createdAt].
 */
data class RecordingSummary(
    val name: String,
    val file: File,
    val durationSeconds: Double,
    val sampleRate: Int,
    val createdAt: Instant
)
