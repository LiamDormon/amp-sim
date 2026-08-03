package org.ampsim.chain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ampsim.events.UIEvent
import org.ampsim.events.UIEventBus
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import org.ampsim.model.Preset

/**
 * Owns the canonical [Chain] and publishes [UIEvent]s over a [UIEventBus] on
 * every modification, so other components (UI, persistence, audio wiring)
 * can react to chain changes without holding a direct reference to whatever
 * mutated it.
 */
class ChainManager(
    private val eventBus: UIEventBus,
    initialChain: Chain = Chain()
) {

    private val _chain = MutableStateFlow(initialChain)
    val chain: StateFlow<Chain> = _chain.asStateFlow()

    /** Insert [unit] at [index] (default: append). Publishes [UIEvent.ErrorOccurred] instead of throwing on an invalid index. */
    fun addUnit(unit: EffectUnit, index: Int = _chain.value.effectUnits.size) {
        val updated = runCatching { _chain.value.addUnit(unit, index) }
            .getOrElse {
                eventBus.publish(UIEvent.ErrorOccurred("Could not add unit '${unit.id}': ${it.message}", SOURCE))
                return
            }
        _chain.value = updated
        eventBus.publish(UIEvent.UnitAdded(unit, index))
        eventBus.publish(UIEvent.ChainModified(updated))
    }

    /** Replace the entire chain (e.g. loading a bare chain rather than a full [Preset]). */
    fun setChain(newChain: Chain) {
        _chain.value = newChain
        eventBus.publish(UIEvent.ChainModified(newChain))
    }

    fun removeUnit(unitId: String) {
        val updated = _chain.value.removeUnit(unitId)
        _chain.value = updated
        eventBus.publish(UIEvent.UnitRemoved(unitId))
        eventBus.publish(UIEvent.ChainModified(updated))
    }

    fun moveUnit(fromIndex: Int, toIndex: Int) {
        val updated = _chain.value.moveUnit(fromIndex, toIndex)
        _chain.value = updated
        eventBus.publish(UIEvent.ChainModified(updated))
    }

    fun setUnitEnabled(unitId: String, enabled: Boolean) {
        val updated = _chain.value.updateUnit(unitId) { copy(enabled = enabled) }
        _chain.value = updated
        eventBus.publish(UIEvent.ChainModified(updated))
    }

    /** Update a single parameter. Publishes only [UIEvent.ParameterChanged], not [UIEvent.ChainModified] —
     * a parameter tweak is a lightweight, high-frequency change (e.g. a dial drag) that subscribers
     * interested in the chain's overall shape shouldn't need to re-process on every tick. */
    fun setUnitParameter(unitId: String, name: String, value: Float) {
        val updated = _chain.value.updateUnit(unitId) { setParameter(name, value) }
        _chain.value = updated
        eventBus.publish(UIEvent.ParameterChanged(unitId, name, value))
    }

    fun loadPreset(preset: Preset) {
        _chain.value = preset.chain
        eventBus.publish(UIEvent.PresetLoaded(preset))
        eventBus.publish(UIEvent.ChainModified(preset.chain))
    }

    companion object {
        private const val SOURCE = "ChainManager"
    }
}
