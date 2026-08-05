package org.ampsim.dsp

import kotlinx.serialization.Serializable

/**
 * Display metadata for one buildable DSP module type, as listed in
 * `resources/library/modules.json` and surfaced by the Library view.
 *
 * This deliberately carries only what can be known *without* instantiating the
 * module. Latency and CPU load are properties of a live [DSPModule] instance
 * ([DSPModule.getLatencySamples], [DSPModule.getCpuLoad]), so the Library reads
 * those lazily from a throwaway module built via [DSPModuleFactory] rather than
 * duplicating them here where they could drift out of sync with the DSP code.
 *
 * @property type the [DSPModule.type] id, matching a [DSPModuleFactory.supportedTypes] entry
 * @property name human-readable name shown in the library and on new chain units
 * @property category grouping heading, e.g. "Amps", "Overdrives"
 * @property description one-paragraph summary shown in the details panel
 */
@Serializable
data class ModuleDescriptor(
    val type: String,
    val name: String,
    val category: String,
    val description: String
)
