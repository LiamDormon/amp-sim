package org.ampsim.ui

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppWindowActionsTest {

    @BeforeTest
    fun ensureAppWindowIsRegistered() = AppWindowTestSupport.ensureAppWindowIsRegistered()

    private fun noopHandlers() = AppWindowActionHandlers(
        showDashboard = {}, showChainEditor = {}, showPresets = {}, showTuner = {}, showRecording = {}, showSettings = {},
        toggleLibrary = {}, save = {}, load = {}, newChain = {}, undo = {}, redo = {}
    )

    @Test
    fun everyExpectedActionNameIsRegistered() {
        val window = AppWindow()
        val actions = window.registerWindowActions(noopHandlers())

        assertEquals(
            setOf(
                "show-dashboard", "show-chain-editor", "show-presets", "show-tuner", "show-recording", "show-settings",
                "toggle-library", "save", "load", "new-chain", "undo", "redo"
            ),
            actions.keys
        )
    }

    @Test
    fun showDashboardActionInvokesItsHandler() {
        val window = AppWindow()
        var invoked = false
        val actions = window.registerWindowActions(
            AppWindowActionHandlers(
                showDashboard = { invoked = true },
                showChainEditor = {}, showPresets = {}, showTuner = {}, showRecording = {}, showSettings = {},
                toggleLibrary = {}, save = {}, load = {}, newChain = {}, undo = {}, redo = {}
            )
        )

        actions.getValue("show-dashboard").emitActivate(null)

        assertTrue(invoked)
    }

    @Test
    fun showChainEditorActionSwitchesTheVisibleStackPage() {
        val window = AppWindow()
        val actions = window.registerWindowActions(
            AppWindowActionHandlers(
                showDashboard = { window.showPage("dashboard") },
                showChainEditor = { window.showPage("editor") },
                showPresets = {}, showTuner = {}, showRecording = {}, showSettings = {}, toggleLibrary = {}, save = {}, load = {}, newChain = {}, undo = {}, redo = {}
            )
        )

        actions.getValue("show-chain-editor").emitActivate(null)

        assertEquals("editor", window.contentStack?.visibleChildName)
    }

    @Test
    fun toggleLibraryActionTogglesTheSidebar() {
        val window = AppWindow()
        val actions = window.registerWindowActions(
            AppWindowActionHandlers(
                showDashboard = {}, showChainEditor = {}, showPresets = {}, showTuner = {}, showRecording = {}, showSettings = {},
                toggleLibrary = { window.toggleLibraryPanel() },
                save = {}, load = {}, newChain = {}, undo = {}, redo = {}
            )
        )
        assertFalse(window.isLibraryPanelVisible())

        actions.getValue("toggle-library").emitActivate(null)

        assertTrue(window.isLibraryPanelVisible())
    }

    @Test
    fun undoActionInvokesItsHandler() {
        val window = AppWindow()
        var invoked = false
        val actions = window.registerWindowActions(
            AppWindowActionHandlers(
                showDashboard = {}, showChainEditor = {}, showPresets = {}, showTuner = {}, showRecording = {}, showSettings = {},
                toggleLibrary = {}, save = {}, load = {}, newChain = {},
                undo = { invoked = true }, redo = {}
            )
        )

        actions.getValue("undo").emitActivate(null)

        assertTrue(invoked)
    }

    @Test
    fun redoActionInvokesItsHandler() {
        val window = AppWindow()
        var invoked = false
        val actions = window.registerWindowActions(
            AppWindowActionHandlers(
                showDashboard = {}, showChainEditor = {}, showPresets = {}, showTuner = {}, showRecording = {}, showSettings = {},
                toggleLibrary = {}, save = {}, load = {}, newChain = {}, undo = {},
                redo = { invoked = true }
            )
        )

        actions.getValue("redo").emitActivate(null)

        assertTrue(invoked)
    }
}
