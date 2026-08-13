package org.ampsim.dsp

import org.ampsim.dsp.effects.GenericAmp
import org.ampsim.dsp.effects.GenericChorus
import org.ampsim.dsp.effects.GenericDelay
import org.ampsim.dsp.effects.GenericOverdrive
import org.ampsim.dsp.effects.GenericReverb
import org.ampsim.lv2.LV2PluginInfo
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit

/**
 * Creates fully-initialized [DSPModule] instances from model descriptions.
 *
 * All construction and parameter application happens here, off the real-time
 * audio thread, so that the resulting modules can be handed to the audio thread
 * (via a command queue) without any allocation happening in the real-time path.
 */
object DSPModuleFactory {

    /** Effect [type] identifiers this factory knows how to build. */
    val supportedTypes: Set<String> = setOf("overdrive", "amp", "delay", "reverb", "chorus")

    /**
     * Create a bare module for the given [type], or `null` if the type is not
     * recognized.
     *
     * Built-in types are matched case-insensitively (via `type.lowercase()`
     * below); an [LV2PluginInfo.LV2_TYPE_PREFIX]-prefixed type is dispatched
     * to [LV2ModuleAdapter.create] on the *original*-case [type] string —
     * LV2 URIs are case-sensitive, so that prefix/URI check must never
     * lower-case either side, unlike the built-in branch.
     */
    fun create(type: String, sampleRate: Int = BaseDSPModule.DEFAULT_SAMPLE_RATE): DSPModule? =
        when (type.lowercase()) {
            "overdrive" -> GenericOverdrive(sampleRate)
            "amp" -> GenericAmp(sampleRate)
            "delay" -> GenericDelay(sampleRate)
            "reverb" -> GenericReverb(sampleRate)
            "chorus" -> GenericChorus(sampleRate)
            else -> if (type.startsWith(LV2PluginInfo.LV2_TYPE_PREFIX)) {
                LV2ModuleAdapter.create(type.removePrefix(LV2PluginInfo.LV2_TYPE_PREFIX), sampleRate)
            } else {
                null
            }
        }

    /**
     * Parameter metadata (name, range, default, unit) for [type], or empty if
     * the type is unknown. Used by the UI to build parameter controls without
     * duplicating each module's declared ranges.
     */
    fun parametersFor(type: String, sampleRate: Int = BaseDSPModule.DEFAULT_SAMPLE_RATE): List<ParameterInfo> =
        create(type, sampleRate)?.parameters ?: emptyList()

    /**
     * Create a module for [unit], applying its stored parameters. Returns `null`
     * if the unit's type is unknown so callers can skip it safely.
     */
    fun create(unit: EffectUnit, sampleRate: Int = BaseDSPModule.DEFAULT_SAMPLE_RATE): DSPModule? {
        val module = create(unit.type, sampleRate) ?: return null
        for ((name, value) in unit.parameters) {
            module.setParameter(name, value)
        }
        return module
    }

    /**
     * Build the ordered list of modules for the enabled units of [chain].
     * Unknown effect types are skipped rather than causing a failure, keeping
     * chain loading robust against malformed presets.
     */
    fun createChain(chain: Chain, sampleRate: Int = BaseDSPModule.DEFAULT_SAMPLE_RATE): List<DSPModule> =
        chain.enabledUnits().mapNotNull { create(it, sampleRate) }

    /**
     * Same ordering/skip rules as [createChain], but also returns each built
     * module's originating [EffectUnit.id] alongside it, 1:1 by index — needed
     * wherever per-module output (e.g. per-unit CPU timing) must be correlated
     * back to a unit afterwards, which plain [createChain] discards.
     */
    fun createChainWithIds(chain: Chain, sampleRate: Int = BaseDSPModule.DEFAULT_SAMPLE_RATE): Pair<List<DSPModule>, List<String>> {
        val pairs = chain.enabledUnits().mapNotNull { unit -> create(unit, sampleRate)?.let { unit.id to it } }
        return pairs.map { it.second } to pairs.map { it.first }
    }
}
