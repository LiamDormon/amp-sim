package org.ampsim.dsp.effects

import org.ampsim.dsp.BaseDSPModule
import org.ampsim.dsp.ParameterInfo

/**
 * Generic delay placeholder effect.
 *
 * A single circular delay line with feedback and a dry/wet mix. The delay
 * buffer is allocated once (sized for [MAX_DELAY_MS]) so [process] performs no
 * allocations on the audio thread.
 */
class GenericDelay(
    sampleRate: Int = DEFAULT_SAMPLE_RATE
) : BaseDSPModule(sampleRate) {

    override val type: String = "delay"

    override val parameters: List<ParameterInfo> = listOf(
        ParameterInfo(name = "time", min = 1f, max = MAX_DELAY_MS, default = 250f, unit = "ms"),
        ParameterInfo(name = "feedback", min = 0f, max = 0.95f, default = 0.3f),
        ParameterInfo(name = "mix", min = 0f, max = 1f, default = 0.35f)
    )

    private val maxDelaySamples: Int = (MAX_DELAY_MS / 1000f * sampleRate).toInt() + 1
    private val buffer = FloatArray(maxDelaySamples)
    private var writeIndex = 0

    private fun delaySamples(): Int {
        val time = getParameter("time")
        return (time / 1000f * sampleRate).toInt().coerceIn(1, maxDelaySamples - 1)
    }

    override suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit> {
        validateBuffers(input, output, nframes)?.let { return it }

        val feedback = getParameter("feedback")
        val mix = getParameter("mix")
        val delay = delaySamples()

        for (i in 0 until nframes) {
            val dry = input[i]
            var readIndex = writeIndex - delay
            if (readIndex < 0) readIndex += maxDelaySamples
            val delayed = buffer[readIndex]

            buffer[writeIndex] = dry + delayed * feedback
            writeIndex++
            if (writeIndex >= maxDelaySamples) writeIndex = 0

            output[i] = dry * (1f - mix) + delayed * mix
        }

        return Result.success(Unit)
    }

    override fun reset() {
        buffer.fill(0f)
        writeIndex = 0
    }

    /** The delay line contributes latency equal to its current time setting. */
    override fun getLatencySamples(): Int = delaySamples()

    override fun getCpuLoad(): Float = 0.08f

    companion object {
        const val MAX_DELAY_MS: Float = 2000f
    }
}
