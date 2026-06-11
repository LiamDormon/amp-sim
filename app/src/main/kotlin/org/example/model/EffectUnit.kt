package org.example.model
import kotlinx.serialization.Serializable

@Serializable
data class EffectUnit(
    val id: String,
    val type: String, // "amp", "overdrive", "delay", etc.
    val model: String,
    val enabled: Boolean = true,
    val parameters: Map<String, Float> = emptyMap()
) {
    fun getParameter(name: String): Float = parameters[name] ?: 0f

    fun setParameter(name: String, value: Float): EffectUnit =
        copy(parameters = parameters + (name to value))

    fun bypass(): EffectUnit = copy(enabled = false)
    fun resume(): EffectUnit = copy(enabled = true)
    fun toggle(): EffectUnit = copy(enabled = !enabled)
}