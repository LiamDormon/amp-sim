package org.ampsim.ui.preset

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gnome.gtk.Gtk

class PresetNameDialogTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    @Test
    fun prefillsNameFromConstructorArgument() {
        val dialog = PresetNameDialog("Rename Preset", "Rename", "Heavy Metal Lead") { }

        assertEquals("Heavy Metal Lead", dialog.nameText())
        assertTrue(dialog.isConfirmEnabled(), "confirm should start enabled when the name is prefilled")
    }

    @Test
    fun confirmDisabledWhenNameBlank() {
        val dialog = PresetNameDialog("Rename Preset", "Rename", "") { }

        assertFalse(dialog.isConfirmEnabled())
    }

    @Test
    fun triggerConfirmInvokesCallbackWithTrimmedName() {
        var confirmedName: String? = null
        val dialog = PresetNameDialog("Duplicate Preset", "Duplicate", "  My Preset copy  ") { name ->
            confirmedName = name
        }

        dialog.triggerConfirm()

        assertEquals("My Preset copy", confirmedName)
    }

    @Test
    fun blankNamePreventsConfirmEvenIfTriggeredDirectly() {
        var called = false
        val dialog = PresetNameDialog("Rename Preset", "Rename", "") { called = true }

        dialog.triggerConfirm()

        assertFalse(called, "onConfirm must not fire when the name is blank")
    }
}
