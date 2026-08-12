package org.ampsim.lv2

/**
 * Whether a plugin's full port list is one this mono-only v1 host can load:
 * exactly one audio input, exactly one audio output, and no CV/Atom ports
 * (unsupported extensions — a plugin requiring them is skipped, not treated
 * as an error, the same "skip, don't fail" pattern already used elsewhere
 * for unknown built-in effect types).
 *
 * A pure function so discovery's filtering logic is deterministically
 * testable without any FFI or a real installed plugin.
 */
fun isSupportedTopology(kinds: List<PortKind>): Boolean =
    kinds.count { it == PortKind.AUDIO_IN } == 1 &&
        kinds.count { it == PortKind.AUDIO_OUT } == 1 &&
        kinds.none { it == PortKind.CV || it == PortKind.ATOM }
