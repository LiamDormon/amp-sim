package org.ampsim.lv2

/**
 * Thrown when instantiating an LV2 plugin fails: bad/unknown URI, unsupported
 * port topology, a native `instantiate()` returning `NULL`, or a bounded
 * timeout being exceeded. Always caught at the call site and translated into
 * a graceful skip (`null` return, warning log) — never expected to reach UI
 * code, mirroring how an unknown built-in effect type is skipped rather than
 * failing chain load.
 */
class LV2InstantiationException(message: String, cause: Throwable? = null) : Exception(message, cause)
