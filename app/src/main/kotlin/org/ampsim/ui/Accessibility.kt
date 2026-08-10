package org.ampsim.ui

import org.gnome.gobject.Value
import org.gnome.gtk.AccessibleProperty
import org.gnome.gtk.Widget
import org.javagi.gobject.types.Types

/**
 * Set a widget's accessible name (and, optionally, a longer description) via
 * [Widget.updateProperty] — there is no `setAccessibleLabel`/typed convenience
 * on java-gi 1.0.0-RC1's `Accessible` interface (only getters, plus
 * updateProperty/updateRelation/updateState), so every icon-only control
 * needing a real name goes through this once instead of repeating the raw
 * Value/Types boilerplate.
 */
fun Widget.setAccessibleLabel(label: String, description: String? = null) {
    val props = mutableListOf(AccessibleProperty.LABEL)
    val values = mutableListOf(Value().apply { init(Types.STRING); setString(label) })
    if (description != null) {
        props += AccessibleProperty.DESCRIPTION
        values += Value().apply { init(Types.STRING); setString(description) }
    }
    updateProperty(props.toTypedArray(), values.toTypedArray())
}
