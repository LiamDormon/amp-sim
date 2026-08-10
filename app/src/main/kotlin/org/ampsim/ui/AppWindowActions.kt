package org.ampsim.ui

import org.gnome.gio.SimpleAction

/** Handlers for every "win.*" action registered on an [AppWindow]. Kept as plain lambdas so tests can inject fakes without touching App.kt/main(). */
class AppWindowActionHandlers(
    val showDashboard: () -> Unit,
    val showChainEditor: () -> Unit,
    val showPresets: () -> Unit,
    val showSettings: () -> Unit,
    val toggleLibrary: () -> Unit,
    val save: () -> Unit,
    val load: () -> Unit,
    val newChain: () -> Unit,
    val undo: () -> Unit
)

/**
 * Register the "win.*" actions this window exposes and return them keyed by
 * action name (minus the "win." prefix), so callers/tests can drive
 * [SimpleAction.emitActivate] directly without a real key-press pipeline —
 * the same "invoke the handler, skip the gesture machinery" convention every
 * other view's simulate*() hook already uses. Accelerators themselves are
 * NOT set here — that's `Application.setAccelsForAction`, which needs the
 * Application instance and is called once from App.kt's onActivate.
 */
fun AppWindow.registerWindowActions(handlers: AppWindowActionHandlers): Map<String, SimpleAction> {
    fun action(name: String, run: () -> Unit): SimpleAction =
        SimpleAction(name, null).also {
            it.onActivate { _ -> run() }
            addAction(it)
        }

    return listOf(
        action("show-dashboard", handlers.showDashboard),
        action("show-chain-editor", handlers.showChainEditor),
        action("show-presets", handlers.showPresets),
        action("show-settings", handlers.showSettings),
        action("toggle-library", handlers.toggleLibrary),
        action("save", handlers.save),
        action("load", handlers.load),
        action("new-chain", handlers.newChain),
        action("undo", handlers.undo)
    ).associateBy { it.name }
}
