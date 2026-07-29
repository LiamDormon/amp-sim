package org.ampsim.dsp

/**
 * Base implementation providing parameter management, state serialization and
 * default latency / CPU reporting shared by all generic DSP modules.
 *
 * Parameter values are stored in a mutable map keyed by name. Unset parameters
 * fall back to the [ParameterInfo.default] so the module is always in a valid
 * state without requiring initialization order tricks in subclasses.
 */
abstract class BaseDSPModule(
    protected val sampleRate: Int = DEFAULT_SAMPLE_RATE
) : DSPModule {

    private val values = HashMap<String, Float>()

    protected fun info(name: String): ParameterInfo? = parameters.find { it.name == name }

    override fun getParameter(name: String): Float =
        values[name] ?: info(name)?.default ?: 0f

    override fun setParameter(name: String, value: Float) {
        val info = info(name) ?: return
        values[name] = info.clamp(value)
    }

    override fun getState(): Map<String, Float> =
        parameters.associate { it.name to getParameter(it.name) }

    override fun setState(state: Map<String, Float>) {
        for ((name, value) in state) {
            setParameter(name, value)
        }
    }

    override fun reset() {
        // No stateful DSP by default; subclasses override to clear filters/buffers.
    }

    override fun getLatencySamples(): Int = 0

    override fun getCpuLoad(): Float = 0f

    /**
     * Validate the buffers of a [process] call. Returns a failure [Result] if
     * they are unusable, or `null` if everything is valid.
     */
    protected fun validateBuffers(
        input: FloatArray,
        output: FloatArray,
        nframes: Int
    ): Result<Unit>? {
        if (nframes < 0) {
            return Result.failure(IllegalArgumentException("nframes must be >= 0, was $nframes"))
        }
        if (input.size < nframes) {
            return Result.failure(
                IllegalArgumentException("input buffer too small: ${input.size} < $nframes")
            )
        }
        if (output.size < nframes) {
            return Result.failure(
                IllegalArgumentException("output buffer too small: ${output.size} < $nframes")
            )
        }
        return null
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE: Int = 48_000
    }
}
