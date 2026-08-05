package org.ampsim.ui.chain

import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.DropDown
import org.gnome.gtk.Orientation
import org.gnome.gtk.StringList

/**
 * A labeled dropdown for a [org.ampsim.dsp.ParameterKind.CHOICE] parameter.
 * Value is the selected option's index as a Float, matching how choice
 * parameters are stored everywhere else in the DSP/model layers.
 */
class ParameterDropdown(
    choices: List<String>,
    initialValue: Float,
    private val onChanged: (Float) -> Unit = {}
) : Box(Orientation.VERTICAL, 2), ParameterControl {

    private val choiceCount = choices.size
    private val dropDown = DropDown(StringList(choices.toTypedArray()), null)

    /** Guards against [setValueSilently]'s programmatic `setSelected` write re-entering [onChanged]. */
    private var suppressCallback = false

    override var value: Float = clampIndex(initialValue)
        private set

    init {
        require(choiceCount > 0) { "ParameterDropdown requires at least one choice" }
        addCssClass("chain-parameter-dropdown")
        halign = Align.CENTER

        dropDown.addCssClass("chain-parameter-dropdown-control")
        dropDown.setSelected(value.toInt())
        dropDown.onActivate { handleSelectionChanged(dropDown.getSelected()) }

        append(dropDown)
    }

    private fun handleSelectionChanged(index: Int) {
        if (suppressCallback) return
        val newValue = index.toFloat()
        if (newValue != value) {
            value = newValue
            onChanged(value)
        }
    }

    private fun clampIndex(v: Float): Float = v.toInt().coerceIn(0, choiceCount - 1).toFloat()

    override fun setValueSilently(newValue: Float) {
        val clamped = clampIndex(newValue)
        if (clamped == value) return
        value = clamped
        suppressCallback = true
        dropDown.setSelected(value.toInt())
        suppressCallback = false
    }

    /** Drive the same logic the real dropdown's activate signal would, without a real GTK event. */
    internal fun simulateSelect(index: Int) = handleSelectionChanged(index)
}
