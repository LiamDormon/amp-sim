package org.ampsim.ui.chain

/**
 * Common surface for a chain unit's per-parameter widget, regardless of its
 * concrete [org.ampsim.dsp.ParameterKind] (dial, toggle, dropdown, ...), so
 * [ChainEditor] can sync any control from the model without knowing its
 * concrete widget type.
 */
interface ParameterControl {
    /** Current value, always a plain Float (see [org.ampsim.dsp.ParameterInfo]). */
    val value: Float

    /** Update the displayed value without notifying the control's onChanged callback. */
    fun setValueSilently(newValue: Float)
}
