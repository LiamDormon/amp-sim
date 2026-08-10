package org.ampsim.ui

import kotlin.test.BeforeTest
import kotlin.test.Test
import org.gnome.gtk.Button
import org.gnome.gtk.Gtk

class AccessibilityTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    @Test
    fun setAccessibleLabelDoesNotThrowOnAPlainWidget() {
        // Accessible exposes no getter for LABEL/DESCRIPTION to assert
        // against directly (only setters, via updateProperty) — this is a
        // smoke test proving the call succeeds, consistent with how other
        // AT-SPI-only state in this codebase can't be asserted at the value level.
        val button = Button()
        button.setAccessibleLabel("Save preset", "Opens the save dialog")
    }

    @Test
    fun setAccessibleLabelWithoutADescriptionDoesNotThrow() {
        val button = Button()
        button.setAccessibleLabel("Close library panel")
    }
}
