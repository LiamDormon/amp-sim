package org.ampsim.ui

import org.ampsim.dsp.ParameterInfo
import org.ampsim.dsp.ParameterKind
import org.ampsim.ui.chain.Dial
import org.ampsim.ui.chain.ParameterControl
import org.ampsim.ui.chain.ParameterDropdown
import org.ampsim.ui.chain.ParameterToggle
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Entry
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.Widget

/**
 * Build a labeled parameter control for [info], seeded at [initialValue],
 * whose concrete widget is chosen by [ParameterInfo.kind]: a (log-aware)
 * [Dial] with a companion numeric entry for continuous parameters, a
 * [ParameterToggle] for booleans, or a [ParameterDropdown] for choices.
 *
 * Shared by the Chain Editor's per-unit parameter drawer and the Library's
 * details panel so a control looks and reads the same in both places. The
 * Library passes a no-op [onChanged]: its controls show a module's ranges
 * and defaults before you add it, and are wired to nothing.
 */
fun parameterTile(
    info: ParameterInfo,
    initialValue: Float = info.default,
    onChanged: (Float) -> Unit = {}
): Pair<ParameterControl, Widget> {
    val nameLabel = Label(info.name.replaceFirstChar { it.uppercase() })
    nameLabel.addCssClass("amp-legend")
    nameLabel.addCssClass("chain-dial-name")
    nameLabel.halign = Align.CENTER

    val tile = Box(Orientation.VERTICAL, 6)
    tile.addCssClass("chain-dial-tile")
    tile.append(nameLabel)

    val control: ParameterControl = when (info.kind) {
        ParameterKind.CONTINUOUS_LINEAR, ParameterKind.CONTINUOUS_LOG -> {
            lateinit var entry: Entry
            val dial = Dial(
                min = info.min,
                max = info.max,
                initialValue = initialValue,
                unitLabel = info.unit,
                logarithmic = info.kind == ParameterKind.CONTINUOUS_LOG,
                step = info.step,
                // The companion entry below is the readout; showing the
                // dial's own built-in label too would just repeat the number.
                showValueLabel = false,
                onChanged = { newValue ->
                    entry.text = formatEntryValue(newValue)
                    entry.removeCssClass("error")
                    onChanged(newValue)
                }
            )
            entry = numericEntry { parsed -> dial.setValue(parsed) }
            entry.text = formatEntryValue(dial.value)
            tile.append(dial)
            tile.append(entry)
            DialWithEntry(dial, entry)
        }
        ParameterKind.BOOLEAN -> {
            val toggle = ParameterToggle(initialValue = initialValue, onChanged = onChanged)
            tile.append(toggle)
            toggle
        }
        ParameterKind.CHOICE -> {
            val dropdown = ParameterDropdown(choices = info.choices, initialValue = initialValue, onChanged = onChanged)
            tile.append(dropdown)
            dropdown
        }
    }

    return control to tile
}

/**
 * A small editable text field for precise numeric entry alongside a [Dial]
 * slider. Commits on Enter; a value that fails to parse as a float is
 * rejected (left unchanged, flagged with GTK's standard entry "error" style)
 * rather than silently discarded — a parseable but out-of-range value is
 * clamped/snapped by [Dial.setValue] exactly as a drag or scroll would be.
 */
private fun numericEntry(onCommit: (Float) -> Unit): Entry {
    val entry = Entry()
    entry.addCssClass("chain-dial-entry")
    entry.halign = Align.CENTER
    entry.setAlignment(0.5f)
    entry.setWidthChars(ENTRY_WIDTH_CHARS)
    entry.setMaxWidthChars(ENTRY_WIDTH_CHARS)
    entry.onActivate {
        val parsed = entry.text.toFloatOrNull()
        if (parsed == null) {
            entry.addCssClass("error")
        } else {
            entry.removeCssClass("error")
            onCommit(parsed)
        }
    }
    return entry
}

private fun formatEntryValue(value: Float): String = "%.2f".format(value)

/** Chars wide enough for e.g. "-1234.56" without stretching to fill the tile column. */
private const val ENTRY_WIDTH_CHARS = 7

/**
 * Keeps a [Dial] and its companion numeric [Entry] in sync as one
 * [ParameterControl]. Internal (not private) so callers that need to drive
 * the underlying dial directly — e.g. [org.ampsim.ui.chain.ChainEditor]'s
 * tests, the same way [org.ampsim.ui.chain.ParameterToggle]/[org.ampsim.ui.chain.ParameterDropdown]
 * expose their own `simulate*` hooks — can reach it without a full GTK event.
 */
internal class DialWithEntry(val dial: Dial, private val entry: Entry) : ParameterControl {
    override val value: Float get() = dial.value

    /** Drive the same value change a user's drag/scroll on [dial] would. */
    fun setValue(newValue: Float) = dial.setValue(newValue)

    override fun setValueSilently(newValue: Float) {
        dial.setValueSilently(newValue)
        entry.text = formatEntryValue(dial.value)
    }
}
