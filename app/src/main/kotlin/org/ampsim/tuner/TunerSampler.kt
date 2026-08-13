package org.ampsim.tuner

import org.ampsim.audio.AudioEngine

/**
 * Reads the latest raw-input capture window from an [AudioEngine] and runs
 * [PitchDetector] over it. Control-thread work, deliberately stateless -
 * mirrors [org.ampsim.metrics.MetricsSampler]'s testability discipline.
 */
object TunerSampler {
    fun sample(engine: AudioEngine, sampleRate: Int): PitchEstimate? {
        val window = engine.pollTunerWindow() ?: return null
        return PitchDetector.detect(window, sampleRate)
    }
}
