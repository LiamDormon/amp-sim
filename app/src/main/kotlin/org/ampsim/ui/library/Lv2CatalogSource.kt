package org.ampsim.ui.library

import org.ampsim.dsp.ModuleDescriptor
import org.ampsim.lv2.LV2PluginCache

/**
 * Where [LibraryViewModel] gets LV2 plugin listings from — a seam so tests
 * can inject a fake source instead of the real, potentially slow (full disk
 * scan) [LV2PluginCache].
 */
interface LV2CatalogSource {
    /** Currently-cached LV2 plugin descriptors; empty until the first background scan completes. */
    fun descriptors(): List<ModuleDescriptor>

    /**
     * Details for an `"lv2:<uri>"` [type], built directly from cached
     * discovery metadata — never a live instantiation, unlike the built-in
     * throwaway-instantiate path in [LibraryViewModel.detailsFor].
     */
    fun detailsFor(type: String): ModuleDetails?
}

/** Default [LV2CatalogSource], backed by the process-wide [LV2PluginCache]. */
object DefaultLv2CatalogSource : LV2CatalogSource {
    override fun descriptors(): List<ModuleDescriptor> = LV2PluginCache.asDescriptors()

    override fun detailsFor(type: String): ModuleDetails? {
        val info = LV2PluginCache.infoFor(type) ?: return null
        return ModuleDetails(
            descriptor = info.toModuleDescriptor(),
            parameters = info.controlPorts.map { it.toParameterInfo() },
            latencySamples = 0,
            latencyMs = 0f,
            cpuLoad = 0f
        )
    }
}
