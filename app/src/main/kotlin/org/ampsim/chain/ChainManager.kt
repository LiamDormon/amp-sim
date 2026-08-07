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

    /**
     * The [Preset] the current [chain] was loaded from (via [loadPreset]) or
     * last saved as (via [markSaved]), or `null` if the chain has since
     * diverged from any known preset via a structural edit or parameter
     * tweak. Used to display the active preset name and to pre-fill a save
     * dialog.
     */
    private val _activePreset = MutableStateFlow<Preset?>(null)
    val activePreset: StateFlow<Preset?> = _activePreset.asStateFlow()

    /**
     * The name of the last preset [chain] was loaded from or saved as, kept even
     * after [activePreset] goes back to `null` on a subsequent edit. Lets a "dirty"
     * indicator keep showing "SomeName" (with an unsaved marker) instead of falling
     * back to an untitled state the instant a saved preset is tweaked.
     */
    private val _lastKnownPresetName = MutableStateFlow<String?>(null)
    val lastKnownPresetName: StateFlow<String?> = _lastKnownPresetName.asStateFlow()

    /** Insert [unit] at [index] (default: append). Publishes [UIEvent.ErrorOccurred] instead of throwing on an invalid index. */
    fun addUnit(unit: EffectUnit, index: Int = _chain.value.effectUnits.size) {
        val updated = runCatching { _chain.value.addUnit(unit, index) }
            .getOrElse {
                eventBus.publish(UIEvent.ErrorOccurred("Could not add unit '${unit.id}': ${it.message}", SOURCE))
                return
            }
        _chain.value = updated
        _activePreset.value = null
        eventBus.publish(UIEvent.UnitAdded(unit, index))
        eventBus.publish(UIEvent.ChainModified(updated))
    }

    /** Replace the entire chain (e.g. loading a bare chain rather than a full [Preset]). */
    fun setChain(newChain: Chain) {
        _chain.value = newChain
        _activePreset.value = null
        eventBus.publish(UIEvent.ChainModified(newChain))
    }

    /**
     * Start a brand-new, empty chain with no associated preset. Distinct from
     * [setChain], which replaces the chain's contents but is used for
     * canvas-driven full-chain replacement (e.g. drag-drop reorder) where the
     * caller isn't expressing "the user asked to start fresh". Also clears
     * [lastKnownPresetName], so a display derived from it falls all the way
     * back to an untitled state — there's nothing to be dirty relative to.
     */
    fun newChain() {
        _chain.value = Chain()
        _activePreset.value = null
        _lastKnownPresetName.value = null
        eventBus.publish(UIEvent.ChainModified(_chain.value))
    }

    fun removeUnit(unitId: String) {
        val updated = _chain.value.removeUnit(unitId)
        _chain.value = updated
        _activePreset.value = null
        eventBus.publish(UIEvent.UnitRemoved(unitId))
        eventBus.publish(UIEvent.ChainModified(updated))
    }

    fun moveUnit(fromIndex: Int, toIndex: Int) {
        val updated = _chain.value.moveUnit(fromIndex, toIndex)
        _chain.value = updated
        _activePreset.value = null
        eventBus.publish(UIEvent.ChainModified(updated))
    }

    fun setUnitEnabled(unitId: String, enabled: Boolean) {
        val updated = _chain.value.updateUnit(unitId) { copy(enabled = enabled) }
        _chain.value = updated
        _activePreset.value = null
        eventBus.publish(UIEvent.ChainModified(updated))
    }

    /** Update a single parameter. Publishes only [UIEvent.ParameterChanged], not [UIEvent.ChainModified] —
     * a parameter tweak is a lightweight, high-frequency change (e.g. a dial drag) that subscribers
     * interested in the chain's overall shape shouldn't need to re-process on every tick. */
    fun setUnitParameter(unitId: String, name: String, value: Float) {
        val updated = _chain.value.updateUnit(unitId) { setParameter(name, value) }
        _chain.value = updated
        _activePreset.value = null
        eventBus.publish(UIEvent.ParameterChanged(unitId, name, value))
    }

    /**
     * Load [preset] as the active chain. Publishes only [UIEvent.PresetLoaded]
     * (not [UIEvent.ChainModified]) — subscribers that need to react to a
     * preset load specifically (e.g. the audio engine's crossfade, or the
     * Chain Editor canvas) listen for [UIEvent.PresetLoaded] instead, so an
     * ordinary structural-edit subscriber doesn't also fire an instant hard
     * swap in parallel with a preset-load crossfade.
     */
    fun loadPreset(preset: Preset) {
        _chain.value = preset.chain
        _activePreset.value = preset
        _lastKnownPresetName.value = preset.metadata.name
        eventBus.publish(UIEvent.PresetLoaded(preset))
    }

    /**
     * Record that [preset] now reflects what's on disk, without touching the
     * live [chain] or publishing [UIEvent.PresetLoaded] — the chain itself
     * didn't change, only its saved representation, so nothing downstream
     * (audio engine, Chain Editor canvas) needs to react.
     */
    fun markSaved(preset: Preset) {
        _activePreset.value = preset
        _lastKnownPresetName.value = preset.metadata.name
    }

    companion object {
        private const val SOURCE = "ChainManager"
    }
}
