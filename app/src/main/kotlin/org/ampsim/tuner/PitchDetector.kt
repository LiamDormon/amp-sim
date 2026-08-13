package org.ampsim.tuner

import kotlin.math.sqrt

/** A single pitch-detection result: the estimated fundamental and how confident the estimate is. */
data class PitchEstimate(val frequencyHz: Float, val confidence: Float)

/**
 * Monophonic fundamental-frequency estimation via YIN (cumulative mean
 * normalized difference function + absolute threshold + parabolic
 * interpolation). Chosen over plain autocorrelation because YIN's
 * normalization corrects autocorrelation's tendency toward octave errors on
 * harmonic-rich tones (guitar's timbre); chosen over FFT because monophonic
 * fundamental tracking at guitar frequencies doesn't need frequency-domain
 * resolution and would need an impractically large transform for comparable
 * precision to YIN's parabolic-interpolated lag estimate.
 *
 * Stateless, pure function - no GTK/JACK/audio-thread dependency - so it
 * stays trivially testable against synthetic signals.
 */
object PitchDetector {

    fun detect(
        samples: FloatArray,
        sampleRate: Int,
        minFrequencyHz: Float = 70f,
        maxFrequencyHz: Float = 1000f,
        yinThreshold: Float = 0.15f,
        silenceRmsFloor: Float = 0.003f
    ): PitchEstimate? {
        if (samples.isEmpty() || sampleRate <= 0) return null

        // Defense in depth: the upstream noise gate usually handles silence,
        // but a tuner reading garbage off a near-zero buffer is worse than a
        // conservative "no signal" here.
        var sumSquares = 0f
        for (sample in samples) sumSquares += sample * sample
        val rms = sqrt(sumSquares / samples.size)
        if (rms < silenceRmsFloor) return null

        val tauMin = (sampleRate / maxFrequencyHz).toInt().coerceAtLeast(1)
        val tauMax = (sampleRate / minFrequencyHz).toInt().coerceAtMost(samples.size / 2)
        if (tauMax <= tauMin) return null

        // Steps 1-2: difference function d(tau), cumulative-mean-normalized into d'(tau).
        val diff = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var sum = 0f
            for (i in 0 until samples.size - tau) {
                val delta = samples[i] - samples[i + tau]
                sum += delta * delta
            }
            diff[tau] = sum
        }

        val cmnd = FloatArray(tauMax + 1)
        cmnd[0] = 1f
        var runningSum = 0f
        for (tau in 1..tauMax) {
            runningSum += diff[tau]
            cmnd[tau] = if (runningSum == 0f) 1f else diff[tau] * tau / runningSum
        }

        // Step 3: smallest tau >= tauMin whose normalized difference clears the
        // threshold at a local minimum; fall back to the global minimum in range.
        var chosenTau = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (cmnd[tau] < yinThreshold) {
                var candidate = tau
                while (candidate + 1 <= tauMax && cmnd[candidate + 1] < cmnd[candidate]) candidate++
                chosenTau = candidate
                break
            }
            tau++
        }
        if (chosenTau < 0) {
            var bestTau = tauMin
            for (t in tauMin..tauMax) {
                if (cmnd[t] < cmnd[bestTau]) bestTau = t
            }
            if (cmnd[bestTau] > REJECT_THRESHOLD) return null
            chosenTau = bestTau
        }

        // Step 4: parabolic interpolation around chosenTau for sub-sample precision.
        val refinedTau = parabolicInterpolate(cmnd, chosenTau, tauMax)
        if (refinedTau <= 0f) return null

        val frequencyHz = sampleRate / refinedTau
        val confidence = (1f - cmnd[chosenTau]).coerceIn(0f, 1f)
        return PitchEstimate(frequencyHz = frequencyHz, confidence = confidence)
    }

    private fun parabolicInterpolate(cmnd: FloatArray, tau: Int, tauMax: Int): Float {
        val prev = if (tau > 0) cmnd[tau - 1] else cmnd[tau]
        val curr = cmnd[tau]
        val next = if (tau < tauMax) cmnd[tau + 1] else cmnd[tau]
        val denominator = prev - 2f * curr + next
        if (denominator == 0f) return tau.toFloat()
        val shift = 0.5f * (prev - next) / denominator
        return tau + shift
    }

    private const val REJECT_THRESHOLD = 0.5f
}
