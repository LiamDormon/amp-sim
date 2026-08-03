package org.ampsim.ui.chain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit

/**
 * Holds the [Chain] being edited by the Chain Editor canvas and exposes the
 * operations the UI needs. Kept free of any GTK dependency so the editing
 * logic can be unit tested without a display.
 *
 * Mutations notify registered [addListener] callbacks synchronously (GTK has
 * no multi-threaded UI, so there is no dispatcher to hop through) so the
 * canvas stays in sync with the chain regardless of what triggered a change.
 *
 * Parameter tweaks (see [setUnitParameter]) are reported through a separate
 * [addParameterListener] channel rather than [addListener]: a structural
 * change (add/remove/reorder/toggle) needs the whole real-time DSP chain
 * rebuilt, but a single parameter update should only touch that one value —
 * rebuilding the chain on every dial tick would reset every other module's
 * internal state (e.g. a delay's buffer) and glitch the audio.
 */
class ChainEditorModel(initialChain: Chain = Chain()) {

    private val _chain = MutableStateFlow(initialChain)
    val chain: StateFlow<Chain> = _chain.asStateFlow()

    private val _selectedUnitId = MutableStateFlow<String?>(null)
    val selectedUnitId: StateFlow<String?> = _selectedUnitId.asStateFlow()

    private val listeners = mutableListOf<(Chain) -> Unit>()
    private val parameterListeners = mutableListOf<(unitId: String, name: String, value: Float) -> Unit>()

    /** Register a callback invoked with the new chain after every structural mutation. */
    fun addListener(listener: (Chain) -> Unit) {
        listeners.add(listener)
    }

    /** Register a callback invoked after every [setUnitParameter] call. */
    fun addParameterListener(listener: (unitId: String, name: String, value: Float) -> Unit) {
        parameterListeners.add(listener)
    }

    fun units(): List<EffectUnit> = _chain.value.effectUnits

    fun setChain(newChain: Chain) {
        mutate { newChain }
        if (_selectedUnitId.value != null && units().none { it.id == _selectedUnitId.value }) {
            _selectedUnitId.value = null
        }
    }

    fun indexOf(unitId: String): Int = units().indexOfFirst { it.id == unitId }

    /** Index of [unitId] among only the *enabled* units, or -1 if absent/disabled. */
    fun enabledIndexOf(unitId: String): Int = _chain.value.enabledUnits().indexOfFirst { it.id == unitId }

    fun canReorder(fromIndex: Int, toIndex: Int): Boolean {
        val indices = units().indices
        return fromIndex != toIndex && fromIndex in indices && toIndex in indices
    }

    fun moveUnit(fromIndex: Int, toIndex: Int) {
        mutate { it.moveUnit(fromIndex, toIndex) }
    }

    fun moveUnit(unitId: String, toIndex: Int) {
        val fromIndex = indexOf(unitId)
        if (fromIndex >= 0) moveUnit(fromIndex, toIndex)
    }

    fun addUnit(unit: EffectUnit, index: Int = units().size) {
        mutate { it.addUnit(unit, index) }
    }

    fun removeUnit(unitId: String) {
        mutate { it.removeUnit(unitId) }
        if (_selectedUnitId.value == unitId) _selectedUnitId.value = null
    }

    fun toggleUnit(unitId: String) {
        mutate { it.updateUnit(unitId) { toggle() } }
    }

    fun setUnitEnabled(unitId: String, enabled: Boolean) {
        mutate { it.updateUnit(unitId) { copy(enabled = enabled) } }
    }

    /**
     * Set a single parameter of [unitId] and notify [addParameterListener]
     * callbacks only — this deliberately does not go through [mutate], so it
     * never triggers a structural rebuild of the real-time DSP chain.
     */
    fun setUnitParameter(unitId: String, name: String, value: Float) {
        _chain.value = _chain.value.updateUnit(unitId) { setParameter(name, value) }
        parameterListeners.forEach { it(unitId, name, value) }
    }

    fun selectUnit(unitId: String?) {
        _selectedUnitId.value = unitId
    }

    private fun mutate(block: (Chain) -> Chain) {
        _chain.value = block(_chain.value)
        listeners.forEach { it(_chain.value) }
    }
}
