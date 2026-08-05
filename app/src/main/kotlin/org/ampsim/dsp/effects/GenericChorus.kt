package org.ampsim.dsp.effects

import org.ampsim.dsp.BaseDSPModule
import org.ampsim.dsp.ParameterInfo
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generic chorus placeholder effect.
 *
 * A single delay line whose read position is swept by a sine LFO, mixed back
 * against the dry signal. Fractional delay positions are linearly interpolated
 * so the sweep stays smooth. The delay buffer is allocated once (sized for
 * [BASE_DELAY_MS] + [MAX_SWEEP_MS]) so [process] performs no allocations on the
 * audio thread. Purely for testing; not a model of any real unit.
 */
class GenericChorus(
    sampleRate: Int = DEFAULT_SAMPLE_RATE
) : BaseDSPModule(sampleRate) {

    override val type: String = "chorus"

    override val parameters: List<ParameterInfo> = listOf(
        ParameterInfo(name = "rate", min = 0.1f, max = 10f, default = 1.5f, unit = "Hz"),
        ParameterInfo(name = "depth", min = 0f, max = 1f, default = 0.5f),
        ParameterInfo(name = "mix", min = 0f, max = 1f, default = 0.5f)
    )

    private val baseDelaySamples: Float = BASE_DELAY_MS / 1000f * sampleRate
    private val maxSweepSamples: Float = MAX_SWEEP_MS / 1000f * sampleRate

    // Sized for the deepest possible sweep, plus a sample of headroom for the
    // interpolator's second tap.
    private val bufferSize: Int = (baseDelaySamples + maxSweepSamples).toInt() + 2
    private val buffer = FloatArray(bufferSize)
    private var writeIndex = 0

    /** LFO phase in radians, wrapped to [0, 2pi) to stay precise over long runs. */
    private var lfoPhase = 0f

    override suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit> {
        validateBuffers(input, output, nframes)?.let { return it }

        val rate = getParameter("rate")
        val depth = getParameter("depth")
        val mix = getParameter("mix")

        val phaseIncrement = TWO_PI * rate / sampleRate
        val sweep = maxSweepSamples * depth
        var phase = lfoPhase

        for (i in 0 until nframes) {
            val dry = input[i]

            buffer[writeIndex] = dry

            // Sine in [-1, 1] mapped to [0, 1] so the read position stays behind
            // the write head no matter the depth.
            val modulation = (sin(phase.toDouble()).toFloat() + 1f) * 0.5f
            val delay = baseDelaySamples + sweep * modulation

            val readPosition = writeIndex - delay
            val wrappedPosition = if (readPosition < 0f) readPosition + bufferSize else readPosition
            val readIndex = wrappedPosition.toInt()
            val fraction = wrappedPosition - readIndex

            val nextIndex = if (readIndex + 1 >= bufferSize) 0 else readIndex + 1
            val delayed = buffer[readIndex] * (1f - fraction) + buffer[nextIndex] * fraction

            writeIndex++
            if (writeIndex >= bufferSize) writeIndex = 0

            phase += phaseIncrement
            if (phase >= TWO_PI) phase -= TWO_PI

            output[i] = dry * (1f - mix) + delayed * mix
        }

        lfoPhase = phase
        return Result.success(Unit)
    }

    override fun reset() {
        buffer.fill(0f)
        writeIndex = 0
        lfoPhase = 0f
    }

    /** The dry signal passes through unshifted, so the chorus adds no latency. */
    override fun getLatencySamples(): Int = 0

    override fun getCpuLoad(): Float = 0.1f

    companion object {
        const val BASE_DELAY_MS: Float = 15f
        const val MAX_SWEEP_MS: Float = 10f
        private val TWO_PI: Float = (2.0 * PI).toFloat()
    }
}
