package org.ampsim.dsp

import kotlin.math.tanh

/**
 * Generic amplifier placeholder effect.
 *
 * Signal path: pre-gain -> tanh saturation -> simple 3-band tone stack
 * (bass / mid / treble) -> master volume. The tone stack is built from a pair
 * of one-pole filters splitting the signal into low, mid and high bands which
 * are recombined with the corresponding gains. For testing only.
 */
class GenericAmp(
    sampleRate: Int = DEFAULT_SAMPLE_RATE
) : BaseDSPModule(sampleRate) {

    override val type: String = "amp"

    override val parameters: List<ParameterInfo> = listOf(
        ParameterInfo(name = "gain", min = 1f, max = 100f, default = 20f),
        ParameterInfo(name = "bass", min = 0f, max = 2f, default = 1f),
        ParameterInfo(name = "mid", min = 0f, max = 2f, default = 1f),
        ParameterInfo(name = "treble", min = 0f, max = 2f, default = 1f),
        ParameterInfo(name = "master", min = 0f, max = 1f, default = 0.7f)
    )

    // One-pole low-pass states for the two crossover filters.
    private var lowState = 0f
    private var lowMidState = 0f

    override suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit> {
        validateBuffers(input, output, nframes)?.let { return it }

        val gain = getParameter("gain")
        val bass = getParameter("bass")
        val mid = getParameter("mid")
        val treble = getParameter("treble")
        val master = getParameter("master")

        var low = lowState
        var lowMid = lowMidState

        for (i in 0 until nframes) {
            val driven = tanh(input[i] * gain)

            // Split into bands using two fixed one-pole low-passes.
            low += LOW_COEFF * (driven - low)
            lowMid += MID_COEFF * (driven - lowMid)

            val lowBand = low
            val midBand = lowMid - low
            val highBand = driven - lowMid

            val shaped = lowBand * bass + midBand * mid + highBand * treble
            output[i] = shaped * master
        }

        lowState = low
        lowMidState = lowMid
        return Result.success(Unit)
    }

    override fun reset() {
        lowState = 0f
        lowMidState = 0f
    }

    override fun getLatencySamples(): Int = 0

    override fun getCpuLoad(): Float = 0.12f

    companion object {
        // Fixed crossover coefficients (bass/mid boundary and mid/treble boundary).
        private const val LOW_COEFF = 0.05f
        private const val MID_COEFF = 0.35f
    }
}
