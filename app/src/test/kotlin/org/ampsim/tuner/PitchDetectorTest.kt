package org.ampsim.tuner

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 44100
private const val WINDOW_SAMPLES = 4096

private fun sineWave(frequencyHz: Float, amplitude: Float = 0.5f, sampleRate: Int = SAMPLE_RATE, length: Int = WINDOW_SAMPLES): FloatArray =
    FloatArray(length) { i -> (amplitude * sin(2.0 * PI * frequencyHz * i / sampleRate)).toFloat() }

private fun centsOff(detectedHz: Float, expectedHz: Float): Float = 1200f * log2(detectedHz / expectedHz)

class PitchDetectorTest {

    @Test
    fun detectsAKnownFrequencyWithinAFewCents() {
        for (frequencyHz in listOf(82.41f, 110f, 440f, 659.25f)) {
            val estimate = PitchDetector.detect(sineWave(frequencyHz), SAMPLE_RATE)
            assertNotNull(estimate, "expected a pitch estimate for a clean $frequencyHz Hz tone")
            assertTrue(
                abs(centsOff(estimate.frequencyHz, frequencyHz)) < 5f,
                "expected ~$frequencyHz Hz, detected ${estimate.frequencyHz} Hz"
            )
        }
    }

    @Test
    fun silenceReturnsNoEstimate() {
        val silence = FloatArray(WINDOW_SAMPLES)
        assertNull(PitchDetector.detect(silence, SAMPLE_RATE))
    }

    @Test
    fun nearSilenceBelowTheRmsFloorReturnsNoEstimate() {
        val tinySignal = sineWave(220f, amplitude = 0.0001f)
        assertNull(PitchDetector.detect(tinySignal, SAMPLE_RATE))
    }

    @Test
    fun aFundamentalWithAStrongHarmonicStillResolvesToTheFundamental() {
        val fundamentalHz = 110f
        val mixed = FloatArray(WINDOW_SAMPLES) { i ->
            val fundamental = 0.5 * sin(2.0 * PI * fundamentalHz * i / SAMPLE_RATE)
            val secondHarmonic = 0.3 * sin(2.0 * PI * (2 * fundamentalHz) * i / SAMPLE_RATE)
            (fundamental + secondHarmonic).toFloat()
        }

        val estimate = PitchDetector.detect(mixed, SAMPLE_RATE)
        assertNotNull(estimate, "expected a pitch estimate for a fundamental + second harmonic")
        assertTrue(
            abs(centsOff(estimate.frequencyHz, fundamentalHz)) < 15f,
            "expected the fundamental ~$fundamentalHz Hz, detected ${estimate.frequencyHz} Hz (likely locked onto the harmonic instead)"
        )
    }

    @Test
    fun emptyInputReturnsNoEstimate() {
        assertNull(PitchDetector.detect(FloatArray(0), SAMPLE_RATE))
    }
}
