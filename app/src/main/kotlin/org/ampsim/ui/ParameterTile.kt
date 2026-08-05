package org.ampsim.ui

import org.ampsim.dsp.ParameterInfo
import org.ampsim.ui.chain.Dial
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.Widget

/**
 * Build a labeled [Dial] tile for [info], seeded at [initialValue].
 *
 * Shared by the Chain Editor's per-unit parameter drawer and the Library's
 * details panel so a knob looks and reads the same in both places. The Library
 * passes a no-op [onChanged]: its dials show a module's ranges and defaults
 * before you add it, and are wired to nothing.
 */
fun parameterTile(
    info: ParameterInfo,
    initialValue: Float = info.default,
    onChanged: (Float) -> Unit = {}
): Pair<Dial, Widget> {
    val nameLabel = Label(info.name.replaceFirstChar { it.uppercase() })
    nameLabel.addCssClass("amp-legend")
    nameLabel.addCssClass("chain-dial-name")
    nameLabel.halign = Align.CENTER

    val dial = Dial(
        min = info.min,
        max = info.max,
        initialValue = initialValue,
        unitLabel = info.unit,
        onChanged = onChanged
    )

    val tile = Box(Orientation.VERTICAL, 6)
    tile.addCssClass("chain-dial-tile")
    tile.append(nameLabel)
    tile.append(dial)

    return dial to tile
}
