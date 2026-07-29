package org.example.dsp

import kotlinx.serialization.Serializable

/**
 * Metadata describing a single DSP parameter: its valid range, default value
 * and an optional human-readable unit (e.g. "ms", "dB").
 */
@Serializable
data class ParameterInfo(
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = ""
) {
    init {
        require(min <= max) { "min ($min) must be <= max ($max) for parameter '$name'" }
        require(default in min..max) {
            "default ($default) must be within [$min, $max] for parameter '$name'"
        }
    }

    /** Clamp an arbitrary value into this parameter's valid range. */
    fun clamp(value: Float): Float = value.coerceIn(min, max)
}
