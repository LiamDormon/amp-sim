package org.ampsim.util

/**
 * A minimal, config-gated logging sink for verbose diagnostic output. Not a
 * replacement for [java.util.logging.Logger]-based warnings/errors used
 * elsewhere — this is purely for opt-in extra detail a user can enable via
 * the "Debug Logging" advanced setting.
 */
object DebugLog {
    @Volatile
    var enabled: Boolean = false

    inline fun log(message: () -> String) {
        if (enabled) System.err.println("[debug] ${message()}")
    }
}
