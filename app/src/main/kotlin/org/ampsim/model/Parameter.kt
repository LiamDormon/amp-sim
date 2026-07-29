package org.ampsim.model
import kotlinx.serialization.Serializable

@Serializable
data class Parameter(
    val name: String,
    val value: Float,
    val min: Float = 0f,
    val max: Float = 1f,
    val step: Float = 0.01f
) {
    fun clamp(): Parameter = copy(
        value = value.coerceIn(min, max)
    )
}