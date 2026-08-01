package org.ampsim.dsp

import org.ampsim.dsp.effects.GenericAmp
import org.ampsim.dsp.effects.GenericDelay
import org.ampsim.dsp.effects.GenericOverdrive
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
    val supportedTypes: Set<String> = setOf("overdrive", "amp", "delay")

    /**
     * Create a bare module for the given [type], or `null` if the type is not
     * recognized.
     */
    fun create(type: String, sampleRate: Int = BaseDSPModule.DEFAULT_SAMPLE_RATE): DSPModule? =
        when (type.lowercase()) {
            "overdrive" -> GenericOverdrive(sampleRate)
            "amp" -> GenericAmp(sampleRate)
            "delay" -> GenericDelay(sampleRate)
            else -> null
        }

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
}
