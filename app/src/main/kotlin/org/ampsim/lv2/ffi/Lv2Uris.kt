package org.ampsim.lv2.ffi

/**
 * Stable LV2 core/atom vocabulary URIs, used to classify port kinds during
 * discovery. These are spec-defined strings, not values liblilv computes.
 */
object Lv2Uris {
    private const val CORE = "http://lv2plug.in/ns/lv2core#"

    const val AUDIO_PORT = "${CORE}AudioPort"
    const val CONTROL_PORT = "${CORE}ControlPort"
    const val INPUT_PORT = "${CORE}InputPort"
    const val OUTPUT_PORT = "${CORE}OutputPort"
    const val CV_PORT = "${CORE}CVPort"
    const val ATOM_PORT = "http://lv2plug.in/ns/ext/atom#AtomPort"
}
