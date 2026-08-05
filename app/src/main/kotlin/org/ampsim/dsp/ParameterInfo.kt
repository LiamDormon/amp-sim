package org.ampsim.dsp

import kotlin.math.round
import kotlinx.serialization.Serializable

/**
 * How a parameter's value should be presented and edited in the UI. Storage
 * is always a plain [Float] (see [ParameterInfo]) regardless of kind:
 * [BOOLEAN] uses 0f/1f, [CHOICE] uses the selected option's index.
 */
@Serializable
enum class ParameterKind { CONTINUOUS_LINEAR, CONTINUOUS_LOG, BOOLEAN, CHOICE }

/**
 * Metadata describing a single DSP parameter: its valid range, default value,
 * an optional human-readable unit (e.g. "ms", "dB"), and how it should be
 * presented in the UI ([kind]).
 */
@Serializable
data class ParameterInfo(
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val kind: ParameterKind = ParameterKind.CONTINUOUS_LINEAR,
    /** Snap increment for UI input; 0 means continuous (no snapping). */
    val step: Float = 0f,
    /** Display labels for a [ParameterKind.CHOICE] parameter; value = selected index. */
    val choices: List<String> = emptyList()
) {
    init {
        require(min <= max) { "min ($min) must be <= max ($max) for parameter '$name'" }
        require(default in min..max) {
            "default ($default) must be within [$min, $max] for parameter '$name'"
        }
        require(step >= 0f) { "step ($step) must be >= 0 for parameter '$name'" }
        if (kind == ParameterKind.BOOLEAN) {
            require(min == 0f && max == 1f) {
                "BOOLEAN parameter '$name' must declare min=0, max=1 (got [$min, $max])"
            }
        }
        if (kind == ParameterKind.CHOICE) {
            require(choices.isNotEmpty()) { "CHOICE parameter '$name' must declare at least one choice" }
            require(min == 0f && max == (choices.size - 1).toFloat()) {
                "CHOICE parameter '$name' must declare min=0, max=${choices.size - 1} for its " +
                    "${choices.size} choices (got [$min, $max])"
            }
        }
    }

    /** Clamp an arbitrary value into this parameter's valid range. */
    fun clamp(value: Float): Float = value.coerceIn(min, max)

    /**
     * Clamp [value] into range and, if [step] is set, snap it to the nearest
     * step increment from [min]. UI-only concern — not used by the real-time
     * clamp path in [clamp]/[org.ampsim.dsp.BaseDSPModule].
     */
    fun snapToStep(value: Float): Float {
        val clamped = clamp(value)
        if (step <= 0f) return clamped
        val steps = round((clamped - min) / step)
        return clamp(min + steps * step)
    }
}
