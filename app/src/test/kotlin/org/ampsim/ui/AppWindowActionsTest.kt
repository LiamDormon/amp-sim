package org.ampsim.ui

import java.nio.file.Paths
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gnome.gio.Resource
import org.gnome.gtk.Gtk
import org.javagi.gtk.types.TemplateTypes

class AppWindowActionsTest {

    @BeforeTest
    fun ensureAppWindowIsRegistered() {
        if (registered) return
        Gtk.init()
        val resourceUrl = checkNotNull(javaClass.getResource("/ampsim.gresource")) {
            "Missing ampsim.gresource on the test classpath"
        }
        val resource = Resource.load(Paths.get(resourceUrl.toURI()).toString())
        resource.resourcesRegister()
        TemplateTypes.register(AppWindow::class.java)
        registered = true
    }

    private fun noopHandlers() = AppWindowActionHandlers(
        showDashboard = {}, showChainEditor = {}, showPresets = {}, showSettings = {},
        toggleLibrary = {}, save = {}, load = {}, newChain = {}, undo = {}
    )

    @Test
    fun everyExpectedActionNameIsRegistered() {
        val window = AppWindow()
        val actions = window.registerWindowActions(noopHandlers())

        assertEquals(
            setOf(
                "show-dashboard", "show-chain-editor", "show-presets", "show-settings",
                "toggle-library", "save", "load", "new-chain", "undo"
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
                showChainEditor = {}, showPresets = {}, showSettings = {},
                toggleLibrary = {}, save = {}, load = {}, newChain = {}, undo = {}
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
                showPresets = {}, showSettings = {}, toggleLibrary = {}, save = {}, load = {}, newChain = {}, undo = {}
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
                showDashboard = {}, showChainEditor = {}, showPresets = {}, showSettings = {},
                toggleLibrary = { window.toggleLibraryPanel() },
                save = {}, load = {}, newChain = {}, undo = {}
            )
        )
        assertFalse(window.isLibraryPanelVisible())

        actions.getValue("toggle-library").emitActivate(null)

        assertTrue(window.isLibraryPanelVisible())
    }

    @Test
    fun undoActionIsRegisteredAndTriggerableWithoutThrowing() {
        // Stub only — no undo history exists yet. This just proves Ctrl+Z has
        // somewhere to go rather than feeling unbound.
        val window = AppWindow()
        val actions = window.registerWindowActions(noopHandlers())

        actions.getValue("undo").emitActivate(null)
    }

    companion object {
        private var registered = false
    }
}
