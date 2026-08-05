package org.ampsim.ui.chain

import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Orientation
import org.gnome.gtk.Switch

/**
 * A labeled on/off control for a [org.ampsim.dsp.ParameterKind.BOOLEAN]
 * parameter. Value is 0f (off) / 1f (on), matching how boolean parameters
 * are stored everywhere else in the DSP/model layers (there is no separate
 * Boolean type on the wire, see [org.ampsim.dsp.ParameterInfo]).
 */
class ParameterToggle(
    initialValue: Float,
    private val onChanged: (Float) -> Unit = {}
) : Box(Orientation.VERTICAL, 2), ParameterControl {

    private val switch = Switch()

    /** Guards against [setValueSilently]'s programmatic `switch.active` write re-entering [onChanged]. */
    private var suppressCallback = false

    override var value: Float = toBinary(initialValue)
        private set

    init {
        addCssClass("chain-parameter-toggle")
        halign = Align.CENTER

        switch.addCssClass("chain-parameter-toggle-switch")
        switch.active = value == 1f
        switch.onStateSet { state -> handleStateChanged(state); false }

        append(switch)
    }

    private fun handleStateChanged(state: Boolean) {
        if (suppressCallback) return
        val newValue = if (state) 1f else 0f
        if (newValue != value) {
            value = newValue
            onChanged(value)
        }
    }

    private fun toBinary(v: Float): Float = if (v >= 0.5f) 1f else 0f

    override fun setValueSilently(newValue: Float) {
        val clamped = toBinary(newValue)
        if (clamped == value) return
        value = clamped
        suppressCallback = true
        switch.active = value == 1f
        suppressCallback = false
    }

    /** Drive the same logic the real switch's state-set signal would, without a real GTK event. */
    internal fun simulateToggle(active: Boolean) = handleStateChanged(active)
}
