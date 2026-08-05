package org.ampsim.ui

import org.gnome.glib.GLib

/**
 * Coalesces a rapid burst of [trigger] calls (e.g. every tick of a knob drag)
 * into a single downstream [action], run [delayMs] after the *last* call in
 * the burst rather than the first — so continuous parameter changes don't
 * flood the audio command queue on every mouse-move event.
 *
 * [schedule] defaults to [GLib.timeoutAdd] (same one-shot-timeout idiom used
 * for the volume/status poll in `App.kt`), and is injectable so tests can
 * verify the coalescing logic without depending on real timing or a running
 * GLib main loop.
 */
class Debouncer(
    private val delayMs: Int = DEFAULT_DELAY_MS,
    private val schedule: (Int, () -> Unit) -> Unit = ::glibSchedule
) {
    private var latestToken: Any? = null

    /** Schedule [action] to run after [delayMs] of no further [trigger] calls. */
    fun trigger(action: () -> Unit) {
        val token = Any()
        latestToken = token
        schedule(delayMs) {
            if (latestToken === token) action()
        }
    }

    companion object {
        const val DEFAULT_DELAY_MS = 30
    }
}

private fun glibSchedule(delayMs: Int, action: () -> Unit) {
    GLib.timeoutAddOnce(delayMs) { action() }
}
