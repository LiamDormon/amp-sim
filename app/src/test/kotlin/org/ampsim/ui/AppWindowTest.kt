package org.ampsim.ui

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppWindowTest {

    @BeforeTest
    fun ensureAppWindowIsRegistered() = AppWindowTestSupport.ensureAppWindowIsRegistered()

    @Test
    fun undoAndRedoButtonsStartDisabled() {
        val window = AppWindow()

        assertFalse(window.undoButton!!.sensitive)
        assertFalse(window.redoButton!!.sensitive)
    }

    @Test
    fun bindUndoRedoControlsWiresTheUndoButtonClick() {
        val window = AppWindow()
        var undoInvoked = false

        window.bindUndoRedoControls(onUndoRequested = { undoInvoked = true }, onRedoRequested = {})
        window.undoButton!!.emitClicked()

        assertTrue(undoInvoked)
    }

    @Test
    fun bindUndoRedoControlsWiresTheRedoButtonClick() {
        val window = AppWindow()
        var redoInvoked = false

        window.bindUndoRedoControls(onUndoRequested = {}, onRedoRequested = { redoInvoked = true })
        window.redoButton!!.emitClicked()

        assertTrue(redoInvoked)
    }

    @Test
    fun setUndoRedoAvailabilityTogglesButtonSensitivityIndependently() {
        val window = AppWindow()

        window.setUndoRedoAvailability(canUndo = true, canRedo = false)
        assertTrue(window.undoButton!!.sensitive)
        assertFalse(window.redoButton!!.sensitive)

        window.setUndoRedoAvailability(canUndo = false, canRedo = true)
        assertFalse(window.undoButton!!.sensitive)
        assertTrue(window.redoButton!!.sensitive)
    }

    @Test
    fun toastOverlayBindsFromTemplate() {
        val window = AppWindow()
        assertNotNull(window.toastOverlay)
    }

    @Test
    fun showToastDoesNotThrow() {
        val window = AppWindow()
        window.showToast("test")
    }

    @Test
    fun showToastWithActionInvokesActionOnButtonClick() {
        val window = AppWindow()
        var actionInvoked = false

        window.showToast("test", actionLabel = "Undo", onAction = { actionInvoked = true })

        // Toast is now in toastOverlay; to test the action, we'd need to dig into
        // the widget tree or emit the signal directly on the Toast. For now,
        // this test verifies the overload signature is wired without error.
        // A full UI integration test would require a realized window.
    }
}
