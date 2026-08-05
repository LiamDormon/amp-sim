package org.ampsim.audio

import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Built-in engine-level noise gate applied to the raw input before it reaches
 * the DSP chain, so muting a quiet passage also mutes upstream of any
 * chain's own decaying tails (delay, reverb) rather than chopping them off
 * after the fact.
 *
 * Unlike [org.ampsim.dsp.DSPModule]s, this is not an insertable chain effect:
 * it's a program feature toggled from the sidebar's quick controls, applied
 * unconditionally to whatever is currently routed into the app (guitar, mic,
 * or anything else), since only one input source can be selected at a time.
 *
 * Real-time safe: [process] never allocates, mirroring the DSP module
 * contract even though this class doesn't implement that interface.
 */
class NoiseGate(var sampleRate: Int = DEFAULT_SAMPLE_RATE) {

    // RT-thread-owned, like AudioEngine's own state: only ever mutated inside
    // applyCommand() and read inside process(), both on the audio thread, so
    // no @Volatile is needed.
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) reset()
        }

    var thresholdDb: Float = DEFAULT_THRESHOLD_DB
        set(value) {
            field = value.coerceIn(MIN_THRESHOLD_DB, MAX_THRESHOLD_DB)
        }

    // Real-time-thread-owned state, only touched inside process()/reset().
    private var envelope = 0f
    private var gain = 0f
    private var isOpen = false
    private var holdCounter = 0

    /** Mute [buffer] below [thresholdDb] in place, over the first [nframes] samples. */
    fun process(buffer: FloatBuffer, nframes: Int) {
        if (!enabled) return

        val detectorCoeff = 1f - exp(-1f / (DETECTOR_TIME_CONSTANT_S * sampleRate))
        val thresholdLinear = 10f.pow(thresholdDb / 20f)
        val attackSamples = (ATTACK_MS / 1000f * sampleRate).toInt().coerceAtLeast(1)
        val holdSamples = (HOLD_MS / 1000f * sampleRate).toInt().coerceAtLeast(0)
        val releaseSamples = (RELEASE_MS / 1000f * sampleRate).toInt().coerceAtLeast(1)
        val attackStep = 1f / attackSamples
        val releaseStep = 1f / releaseSamples

        var env = envelope
        var g = gain
        var open = isOpen
        var hold = holdCounter

        for (i in 0 until nframes) {
            val x = buffer.get(i)
            env += detectorCoeff * (abs(x) - env)

            if (env > thresholdLinear) {
                open = true
                hold = holdSamples
            } else if (open) {
                if (hold > 0) hold-- else open = false
            }

            val target = if (open) 1f else 0f
            g = when {
                g < target -> (g + attackStep).coerceAtMost(target)
                g > target -> (g - releaseStep).coerceAtLeast(target)
                else -> g
            }

            buffer.put(i, x * g)
        }

        envelope = env
        gain = g
        isOpen = open
        holdCounter = hold
    }

    private fun reset() {
        envelope = 0f
        gain = 0f
        isOpen = false
        holdCounter = 0
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE: Int = 48_000
        const val DEFAULT_THRESHOLD_DB: Float = -40f
        const val MIN_THRESHOLD_DB: Float = -80f
        const val MAX_THRESHOLD_DB: Float = 0f

        // Fixed (not user-facing) gain envelope shaping: fast enough to open on
        // a pick attack, long enough of a hold to not chatter on decaying notes.
        private const val ATTACK_MS = 2f
        private const val HOLD_MS = 30f
        private const val RELEASE_MS = 150f

        // Fixed detector smoothing time constant: fast enough to react to real
        // level changes, slow enough that a single sample near a zero-crossing
        // doesn't register as silence.
        private const val DETECTOR_TIME_CONSTANT_S = 0.003f
    }
}
