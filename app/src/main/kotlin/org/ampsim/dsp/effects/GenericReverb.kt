package org.ampsim.dsp.effects

import org.ampsim.dsp.BaseDSPModule
import org.ampsim.dsp.ParameterInfo

/**
 * Generic reverb placeholder effect.
 *
 * A small Schroeder/Freeverb-style network: four parallel damped comb filters
 * feeding two series allpass filters. All delay lines are allocated once in the
 * constructor (sized from the sample rate) so [process] performs no allocations
 * on the audio thread. Purely for testing; not a model of any real unit.
 */
class GenericReverb(
    sampleRate: Int = DEFAULT_SAMPLE_RATE
) : BaseDSPModule(sampleRate) {

    override val type: String = "reverb"

    override val parameters: List<ParameterInfo> = listOf(
        ParameterInfo(name = "size", min = 0f, max = 1f, default = 0.5f),
        ParameterInfo(name = "damping", min = 0f, max = 1f, default = 0.5f),
        ParameterInfo(name = "mix", min = 0f, max = 1f, default = 0.3f)
    )

    // Classic Schroeder delay lengths, tuned at 44.1 kHz and rescaled to the
    // actual sample rate so the reverb keeps its character at any rate.
    private val combBuffers: Array<FloatArray> = COMB_LENGTHS_44K
        .map { FloatArray(scaleLength(it, sampleRate)) }
        .toTypedArray()
    private val combIndices = IntArray(combBuffers.size)

    /** One-pole lowpass state inside each comb's feedback path (the "damping"). */
    private val combFilterStores = FloatArray(combBuffers.size)

    private val allpassBuffers: Array<FloatArray> = ALLPASS_LENGTHS_44K
        .map { FloatArray(scaleLength(it, sampleRate)) }
        .toTypedArray()
    private val allpassIndices = IntArray(allpassBuffers.size)

    override suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit> {
        validateBuffers(input, output, nframes)?.let { return it }

        val size = getParameter("size")
        val damping = getParameter("damping")
        val mix = getParameter("mix")

        // Longer decay for bigger rooms, but always strictly < 1 so the comb
        // feedback loops stay stable.
        val feedback = MIN_FEEDBACK + size * (MAX_FEEDBACK - MIN_FEEDBACK)
        val damp = damping * MAX_DAMPING

        for (i in 0 until nframes) {
            val dry = input[i]
            val fed = dry * INPUT_GAIN

            var wet = 0f
            for (c in combBuffers.indices) {
                val buffer = combBuffers[c]
                val index = combIndices[c]
                val delayed = buffer[index]

                val filtered = delayed * (1f - damp) + combFilterStores[c] * damp
                combFilterStores[c] = filtered
                buffer[index] = fed + filtered * feedback

                combIndices[c] = if (index + 1 >= buffer.size) 0 else index + 1
                wet += delayed
            }
            wet *= 1f / combBuffers.size

            for (a in allpassBuffers.indices) {
                val buffer = allpassBuffers[a]
                val index = allpassIndices[a]
                val delayed = buffer[index]

                buffer[index] = wet + delayed * ALLPASS_FEEDBACK
                wet = delayed - wet

                allpassIndices[a] = if (index + 1 >= buffer.size) 0 else index + 1
            }

            output[i] = dry * (1f - mix) + wet * mix
        }

        return Result.success(Unit)
    }

    override fun reset() {
        for (buffer in combBuffers) buffer.fill(0f)
        for (buffer in allpassBuffers) buffer.fill(0f)
        combIndices.fill(0)
        allpassIndices.fill(0)
        combFilterStores.fill(0f)
    }

    /** The dry signal passes through unshifted, so the reverb adds no latency. */
    override fun getLatencySamples(): Int = 0

    override fun getCpuLoad(): Float = 0.15f

    companion object {
        private val COMB_LENGTHS_44K = intArrayOf(1557, 1617, 1491, 1422)
        private val ALLPASS_LENGTHS_44K = intArrayOf(225, 556)
        private const val REFERENCE_SAMPLE_RATE = 44_100f

        private const val MIN_FEEDBACK = 0.70f
        private const val MAX_FEEDBACK = 0.95f
        private const val MAX_DAMPING = 0.4f
        private const val ALLPASS_FEEDBACK = 0.5f

        /** Keeps the summed comb output in a sane range before the wet/dry mix. */
        private const val INPUT_GAIN = 0.35f

        private fun scaleLength(lengthAt44k: Int, sampleRate: Int): Int =
            (lengthAt44k * (sampleRate / REFERENCE_SAMPLE_RATE)).toInt().coerceAtLeast(1)
    }
}
