package org.ampsim.ui.preset

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gnome.gtk.Gtk

class SavePresetDialogTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        // Safe to call repeatedly; these tests only build the widget tree, never realize/show it.
        Gtk.init()
    }

    @Test
    fun prefillsFieldsFromConstructorArguments() {
        val dialog = SavePresetDialog(
            initialName = "Heavy Metal Lead",
            initialDescription = "High-gain",
            initialAuthor = "Liam"
        ) { _, _, _ -> }

        assertEquals("Heavy Metal Lead", dialog.nameText())
        assertEquals("High-gain", dialog.descriptionText())
        assertEquals("Liam", dialog.authorText())
        assertTrue(dialog.isSaveEnabled(), "save should start enabled when the name is prefilled")
    }

    @Test
    fun saveDisabledWhenNameBlank() {
        val dialog = SavePresetDialog { _, _, _ -> }

        assertFalse(dialog.isSaveEnabled())
    }

    @Test
    fun triggerSaveInvokesCallbackWithTrimmedFields() {
        var savedName: String? = null
        var savedDescription: String? = null
        var savedAuthor: String? = null

        val dialog = SavePresetDialog(
            initialName = "  My Preset  ",
            initialDescription = "  desc  ",
            initialAuthor = "  Liam  "
        ) { name, description, author ->
            savedName = name
            savedDescription = description
            savedAuthor = author
        }

        dialog.triggerSave()

        assertEquals("My Preset", savedName)
        assertEquals("desc", savedDescription)
        assertEquals("Liam", savedAuthor)
    }

    @Test
    fun triggerSaveWithBlankAuthorPassesNull() {
        var savedAuthor: String? = "not-null-sentinel"
        val dialog = SavePresetDialog(initialName = "Preset", initialAuthor = null) { _, _, author ->
            savedAuthor = author
        }

        dialog.triggerSave()

        assertNull(savedAuthor)
    }

    @Test
    fun blankNamePreventsSaveEvenIfTriggeredDirectly() {
        var called = false
        val dialog = SavePresetDialog { _, _, _ -> called = true }

        dialog.triggerSave()

        assertFalse(called, "onSave must not fire when the name is blank")
    }
}
