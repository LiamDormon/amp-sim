package org.ampsim.ui.chain

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gnome.gtk.Gtk

class ParameterDropdownTest {

    private val choices = listOf("Clean", "Crunch", "Lead")

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    @Test
    fun initialValueIsClampedToAValidChoiceIndex() {
        assertEquals(2f, ParameterDropdown(choices, initialValue = 99f).value)
        assertEquals(0f, ParameterDropdown(choices, initialValue = -1f).value)
    }

    @Test
    fun selectingADifferentChoiceNotifiesOnChanged() {
        val changes = mutableListOf<Float>()
        val dropdown = ParameterDropdown(choices, initialValue = 0f, onChanged = { changes.add(it) })

        dropdown.simulateSelect(2)

        assertEquals(2f, dropdown.value)
        assertEquals(listOf(2f), changes)
    }

    @Test
    fun selectingTheSameChoiceIsANoOp() {
        val changes = mutableListOf<Float>()
        val dropdown = ParameterDropdown(choices, initialValue = 1f, onChanged = { changes.add(it) })

        dropdown.simulateSelect(1)

        assertTrue(changes.isEmpty())
    }

    @Test
    fun setValueSilentlyUpdatesWithoutNotifying() {
        val changes = mutableListOf<Float>()
        val dropdown = ParameterDropdown(choices, initialValue = 0f, onChanged = { changes.add(it) })

        dropdown.setValueSilently(2f)

        assertEquals(2f, dropdown.value)
        assertTrue(changes.isEmpty())
    }
}
