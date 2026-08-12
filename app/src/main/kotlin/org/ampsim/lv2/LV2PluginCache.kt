package org.ampsim.lv2

import org.ampsim.dsp.ModuleDescriptor

/**
 * Process-wide cache of the last [LV2Discovery.discover] result. Discovery
 * does a full disk scan (potentially seconds), so it must run at most once
 * per explicit [refresh] — never implicitly on every read.
 */
object LV2PluginCache {

    @Volatile private var cached: List<LV2PluginInfo>? = null

    /** Triggers discovery if it hasn't run yet, blocking the calling thread. Call off the GTK main thread. */
    val discovered: List<LV2PluginInfo> get() = cached ?: refresh()

    /** Never blocks; empty until the first [refresh] completes. Safe to call from the GTK thread. */
    fun discoveredOrEmpty(): List<LV2PluginInfo> = cached ?: emptyList()

    /** Re-runs discovery and replaces the cache. Blocking — call off the GTK main thread. */
    fun refresh(): List<LV2PluginInfo> = LV2Discovery.discover().also { cached = it }

    /**
     * The info for [type] (an `"lv2:<uri>"` string), matched by exact,
     * case-sensitive URI comparison — unlike
     * [org.ampsim.dsp.ModuleCatalog.descriptorFor], LV2 URIs are
     * case-sensitive, so neither side is ever lower-cased here.
     */
    fun infoFor(type: String): LV2PluginInfo? =
        discoveredOrEmpty().find { "${LV2PluginInfo.LV2_TYPE_PREFIX}${it.uri}" == type }

    fun descriptorFor(type: String): ModuleDescriptor? = infoFor(type)?.toModuleDescriptor()

    fun asDescriptors(): List<ModuleDescriptor> = discoveredOrEmpty().map { it.toModuleDescriptor() }
}
