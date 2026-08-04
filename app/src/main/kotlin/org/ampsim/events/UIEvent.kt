package org.ampsim.events

import org.ampsim.audio.AudioStatus
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import org.ampsim.model.Preset

/**
 * Immutable events broadcast over a [UIEventBus] for reactive UI updates and
 * decoupled communication between components that would otherwise need a
 * direct reference to one another.
 */
sealed class UIEvent {

    /** The active chain changed shape, order, or a unit's enabled state. */
    data class ChainModified(val chain: Chain) : UIEvent()

    /** [preset] was loaded and is now (or is about to become) the active chain. */
    data class PresetLoaded(val preset: Preset) : UIEvent()

    /** [preset] was successfully written to disk (via an explicit save, not auto-save). */
    data class PresetSaved(val preset: Preset) : UIEvent()

    /** A single parameter of the unit identified by [unitId] was set to [value]. */
    data class ParameterChanged(val unitId: String, val parameterName: String, val value: Float) : UIEvent()

    /** The audio engine published a new status snapshot. */
    data class AudioStatusChanged(val status: AudioStatus) : UIEvent()

    /** [unit] was inserted into the chain at [index]. */
    data class UnitAdded(val unit: EffectUnit, val index: Int) : UIEvent()

    /** The unit identified by [unitId] was removed from the chain. */
    data class UnitRemoved(val unitId: String) : UIEvent()

    /** Something went wrong; [message] is safe to show to the user. [source] names the origin. */
    data class ErrorOccurred(val message: String, val source: String? = null) : UIEvent()
}
