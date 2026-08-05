package org.ampsim.ui.chain

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gnome.gtk.Gtk

class ParameterToggleTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    @Test
    fun initialValueIsCoercedToBinary() {
        assertEquals(1f, ParameterToggle(initialValue = 0.7f).value)
        assertEquals(0f, ParameterToggle(initialValue = 0.2f).value)
    }

    @Test
    fun togglingOnNotifiesOnChanged() {
        val changes = mutableListOf<Float>()
        val toggle = ParameterToggle(initialValue = 0f, onChanged = { changes.add(it) })

        toggle.simulateToggle(true)

        assertEquals(1f, toggle.value)
        assertEquals(listOf(1f), changes)
    }

    @Test
    fun togglingToTheSameStateIsANoOp() {
        val changes = mutableListOf<Float>()
        val toggle = ParameterToggle(initialValue = 1f, onChanged = { changes.add(it) })

        toggle.simulateToggle(true)

        assertTrue(changes.isEmpty())
    }

    @Test
    fun setValueSilentlyUpdatesWithoutNotifying() {
        val changes = mutableListOf<Float>()
        val toggle = ParameterToggle(initialValue = 0f, onChanged = { changes.add(it) })

        toggle.setValueSilently(1f)

        assertEquals(1f, toggle.value)
        assertTrue(changes.isEmpty())
    }
}
