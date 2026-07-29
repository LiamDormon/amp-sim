package org.ampsim.model
import kotlinx.serialization.Serializable

@Serializable
data class Chain(
    val effectUnits: List<EffectUnit> = emptyList()
) {
    fun isEmpty(): Boolean = effectUnits.isEmpty()

    fun moveUnit(fromIndex: Int, toIndex: Int): Chain {
        if (fromIndex !in effectUnits.indices || toIndex !in effectUnits.indices) {
            return this
        }
        return effectUnits.toMutableList()
            .apply { add(toIndex, removeAt(fromIndex)) }
            .let { copy(effectUnits = it) }
    }

    fun addUnit(effectUnit: EffectUnit, index: Int = effectUnits.size): Chain =
        copy(effectUnits = effectUnits.toMutableList().apply { add(index, effectUnit) })

    fun removeUnit(unitId: String): Chain =
        copy(effectUnits = effectUnits.filterNot { it.id == unitId })

    fun updateUnit(unitId: String, block: EffectUnit.() -> EffectUnit): Chain =
        copy(effectUnits = effectUnits.map { if (it.id == unitId) it.run(block) else it })

    fun enabledUnits(): List<EffectUnit> = effectUnits.filter { it.enabled }
}