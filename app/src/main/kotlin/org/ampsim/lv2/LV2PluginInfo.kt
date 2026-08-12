package org.ampsim.lv2

import org.ampsim.dsp.ModuleDescriptor
import org.ampsim.dsp.ParameterInfo

/**
 * A discovered LV2 control port's metadata — no live plugin instance
 * required. [symbol] (not [name]) becomes the [ParameterInfo] name: it's the
 * LV2 spec-stable per-plugin identifier, unlike a display name that can be
 * reworded across plugin versions, which is what keeps
 * [org.ampsim.model.EffectUnit.parameters] keys stable across plugin
 * updates for reliable preset round-tripping.
 */
data class LV2ControlPortInfo(
    val index: Int,
    val symbol: String,
    val name: String,
    val default: Float,
    val min: Float,
    val max: Float
) {
    fun toParameterInfo(): ParameterInfo = ParameterInfo(name = symbol, min = min, max = max, default = default)
}

/**
 * Everything needed to list an LV2 plugin in the Library and build an
 * [org.ampsim.dsp.LV2ModuleAdapter] for it. This is what [LV2PluginCache]
 * caches — cheap, GC-friendly, holds no native handles, so the Library never
 * needs a live plugin instance just to display one.
 */
data class LV2PluginInfo(
    val uri: String,
    val name: String,
    val audioInPortIndex: Int,
    val audioOutPortIndex: Int,
    val controlPorts: List<LV2ControlPortInfo>
) {
    fun toModuleDescriptor(): ModuleDescriptor = ModuleDescriptor(
        type = "$LV2_TYPE_PREFIX$uri",
        name = name,
        category = LV2_CATEGORY,
        description = "LV2 plugin loaded from the system plugin path ($uri)."
    )

    companion object {
        /**
         * Marks an [org.ampsim.model.EffectUnit.type]/[ModuleDescriptor.type]
         * as an LV2 plugin, with the full plugin URI appended. Kept as the
         * single source of truth for this prefix so [org.ampsim.dsp.DSPModuleFactory],
         * [org.ampsim.dsp.LV2ModuleAdapter], and [LV2PluginCache] never drift.
         */
        const val LV2_TYPE_PREFIX = "lv2:"

        /** The Library category heading every LV2 plugin is grouped under. */
        const val LV2_CATEGORY = "LV2 Plugins"
    }
}
