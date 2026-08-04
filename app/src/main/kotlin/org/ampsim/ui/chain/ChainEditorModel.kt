package org.ampsim.ui.chain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ampsim.chain.ChainManager
import org.ampsim.events.EventBusImpl
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit

/**
 * GTK-facing adapter over a [ChainManager]: exposes the operations the Chain
 * Editor canvas needs and, on top of [ChainManager]'s own [org.ampsim.events.UIEventBus]
 * publishing, notifies local listeners *synchronously and on the calling
 * thread* so the canvas can safely touch GTK widgets straight from a
 * mutation's call stack — no dispatcher hop, no risk of a GTK call landing
 * on the wrong thread.
 *
 * Parameter tweaks (see [setUnitParameter]) are reported through a separate
 * [addParameterListener] channel rather than [addListener]: a structural
 * change (add/remove/reorder/toggle) needs the whole real-time DSP chain
 * rebuilt, but a single parameter update should only touch that one value —
 * rebuilding the chain on every dial tick would reset every other module's
 * internal state (e.g. a delay's buffer) and glitch the audio. [ChainManager]
 * mirrors this same split when it publishes to the event bus.
 */
class ChainEditorModel(private val chainManager: ChainManager) {

    /** Convenience for standalone use (tests, previews): owns a private, unshared [ChainManager]. */
    constructor(initialChain: Chain = Chain()) : this(ChainManager(EventBusImpl(), initialChain))

    val chain: StateFlow<Chain> = chainManager.chain

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

    fun units(): List<EffectUnit> = chain.value.effectUnits

    fun setChain(newChain: Chain) {
        chainManager.setChain(newChain)
        notifyListeners()
        if (_selectedUnitId.value != null && units().none { it.id == _selectedUnitId.value }) {
            _selectedUnitId.value = null
        }
    }

    fun indexOf(unitId: String): Int = units().indexOfFirst { it.id == unitId }

    /** Index of [unitId] among only the *enabled* units, or -1 if absent/disabled. */
    fun enabledIndexOf(unitId: String): Int = chain.value.enabledUnits().indexOfFirst { it.id == unitId }

    fun canReorder(fromIndex: Int, toIndex: Int): Boolean {
        val indices = units().indices
        return fromIndex != toIndex && fromIndex in indices && toIndex in indices
    }

    fun moveUnit(fromIndex: Int, toIndex: Int) {
        chainManager.moveUnit(fromIndex, toIndex)
        notifyListeners()
    }

    fun moveUnit(unitId: String, toIndex: Int) {
        val fromIndex = indexOf(unitId)
        if (fromIndex >= 0) moveUnit(fromIndex, toIndex)
    }

    fun addUnit(unit: EffectUnit, index: Int = units().size) {
        chainManager.addUnit(unit, index)
        notifyListeners()
    }

    fun removeUnit(unitId: String) {
        chainManager.removeUnit(unitId)
        notifyListeners()
        if (_selectedUnitId.value == unitId) _selectedUnitId.value = null
    }

    fun toggleUnit(unitId: String) {
        val currentlyEnabled = units().firstOrNull { it.id == unitId }?.enabled ?: return
        setUnitEnabled(unitId, !currentlyEnabled)
    }

    fun setUnitEnabled(unitId: String, enabled: Boolean) {
        chainManager.setUnitEnabled(unitId, enabled)
        notifyListeners()
    }

    /**
     * Set a single parameter of [unitId] and notify [addParameterListener]
     * callbacks only — never [addListener], so a dial drag never triggers a
     * structural rebuild of the real-time DSP chain.
     */
    fun setUnitParameter(unitId: String, name: String, value: Float) {
        chainManager.setUnitParameter(unitId, name, value)
        parameterListeners.forEach { it(unitId, name, value) }
    }

    fun selectUnit(unitId: String?) {
        _selectedUnitId.value = unitId
    }

    /**
     * Re-render from an external mutation this model didn't itself trigger —
     * e.g. a preset load driven directly through [ChainManager.loadPreset]
     * rather than through one of this model's own methods.
     */
    fun notifyExternalChange() = notifyListeners()

    private fun notifyListeners() {
        val current = chain.value
        listeners.forEach { it(current) }
    }
}
