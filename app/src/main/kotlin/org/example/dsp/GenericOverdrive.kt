package org.example.dsp

import kotlin.math.tanh

/**
 * Generic overdrive placeholder effect.
 *
 * Signal path: input gain (drive) -> tanh soft-clip saturation -> one-pole
 * low-pass tone control -> output level. Purely for testing; not a model of
 * any real pedal.
 */
class GenericOverdrive(
    sampleRate: Int = DEFAULT_SAMPLE_RATE
) : BaseDSPModule(sampleRate) {

    override val type: String = "overdrive"

    override val parameters: List<ParameterInfo> = listOf(
        ParameterInfo(name = "drive", min = 1f, max = 50f, default = 10f),
        ParameterInfo(name = "tone", min = 0f, max = 1f, default = 0.5f),
        ParameterInfo(name = "level", min = 0f, max = 1f, default = 0.7f)
    )

    // One-pole low-pass state for the tone control.
    private var lpState = 0f

    override suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit> {
        validateBuffers(input, output, nframes)?.let { return it }

        val drive = getParameter("drive")
        val tone = getParameter("tone")
        val level = getParameter("level")

        // Tone controls two things:
        // - filterCoeff: how quickly the one-pole tracks (lower = darker, but never 0)
        // - mix: how much unfiltered signal is blended back in (0 = fully filtered)
        val mix = tone.coerceIn(0f, 1f)
        val filterCoeff = 0.01f + mix * 0.99f
        var lp = lpState

        for (i in 0 until nframes) {
            val x = input[i]
            val driven = tanh(x * drive)
            lp += filterCoeff * (driven - lp)
            val toned = lp * (1f - mix) + driven * mix
            output[i] = toned * level
        }

        lpState = lp
        return Result.success(Unit)
    }

    override fun reset() {
        lpState = 0f
    }

    override fun getLatencySamples(): Int = 0

    override fun getCpuLoad(): Float = 0.05f
}
