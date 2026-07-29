package org.example.dsp

/**
 * Common contract implemented by every DSP effect module.
 *
 * Implementations are expected to be real-time friendly: [process] must not
 * allocate on the audio thread and should provide deterministic, repeatable
 * output for a given input and parameter set.
 */
interface DSPModule {

    /** Short type identifier of the module, e.g. "overdrive", "delay", "amp". */
    val type: String

    /** Metadata for every parameter exposed by this module. */
    val parameters: List<ParameterInfo>

    /** Current value of the named parameter, or 0 if it does not exist. */
    fun getParameter(name: String): Float

    /**
     * Set the named parameter. Unknown parameters are ignored; known ones are
     * clamped to their declared [ParameterInfo] range.
     */
    fun setParameter(name: String, value: Float)

    /**
     * Process a block of [nframes] samples from [input] into [output].
     *
     * Returns [Result.success] on success, or [Result.failure] if the buffers
     * are too small or the frame count is invalid.
     */
    suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit>

    /** Reset any internal state (filters, delay lines) to silence. */
    fun reset()

    /** Additional latency introduced by this module, in samples. */
    fun getLatencySamples(): Int

    /** Rough estimate of the CPU load of this module, as a fraction in [0, 1]. */
    fun getCpuLoad(): Float

    /** Serialize the current parameter state into a plain map. */
    fun getState(): Map<String, Float>

    /** Restore parameter state previously produced by [getState]. */
    fun setState(state: Map<String, Float>)
}
