package org.ampsim.audio

import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoiseGateTest {

    // A low sample rate keeps the fixed attack/hold/release sample counts
    // small so tests don't need thousands of frames to reach steady state.
    private val testSampleRate = 1000

    @Test
    fun testThresholdClampsToRange() {
        val gate = NoiseGate()
        gate.thresholdDb = 100f
        assertEquals(NoiseGate.MAX_THRESHOLD_DB, gate.thresholdDb)
        gate.thresholdDb = -1000f
        assertEquals(NoiseGate.MIN_THRESHOLD_DB, gate.thresholdDb)
    }

    @Test
    fun testDisabledPassesThroughUnchanged() {
        val gate = NoiseGate(testSampleRate)
        val values = FloatArray(32) { 0.0001f }
        val buffer = FloatBuffer.wrap(values.copyOf())
        gate.process(buffer, 32)
        for (i in values.indices) {
            assertEquals(values[i], buffer.get(i))
        }
    }

    @Test
    fun testSignalAboveThresholdPassesAtUnityGain() {
        val gate = NoiseGate(testSampleRate)
        gate.enabled = true
        val buffer = FloatBuffer.wrap(FloatArray(50) { 0.5f })
        gate.process(buffer, 50)
        // Well above the -40dB default threshold; the gate should have fully
        // opened by the end of the block and pass the signal essentially unchanged.
        assertTrue(abs(buffer.get(49) - 0.5f) < 0.01f)
    }

    @Test
    fun testSignalBelowThresholdFullyAttenuatedAfterRelease() {
        val gate = NoiseGate(testSampleRate)
        gate.enabled = true

        // Open the gate with a loud signal first.
        val loud = FloatBuffer.wrap(FloatArray(30) { 0.5f })
        gate.process(loud, 30)
        assertTrue(abs(loud.get(29) - 0.5f) < 0.01f)

        // A long enough quiet passage lets the envelope decay below threshold,
        // the hold timer expire, and the gain ramp all the way to zero.
        val quiet = FloatBuffer.wrap(FloatArray(400) { 0.001f })
        gate.process(quiet, 400)
        assertTrue(abs(quiet.get(399)) < 1e-4f)
    }

    @Test
    fun testHoldPreventsChatterOnShortDip() {
        val gate = NoiseGate(testSampleRate)
        gate.enabled = true

        // Open the gate with a loud signal first.
        val loud = FloatBuffer.wrap(FloatArray(30) { 0.5f })
        gate.process(loud, 30)

        // A short dip below threshold, shorter than the hold+release window,
        // must not close the gate: gain should stay at 1 throughout.
        val dipLevel = 0.001f
        val dip = FloatBuffer.wrap(FloatArray(20) { dipLevel })
        gate.process(dip, 20)
        for (i in 0 until 20) {
            assertTrue(abs(dip.get(i) - dipLevel) < 1e-5f)
        }
    }

    @Test
    fun testDisablingResetsGateState() {
        val gate = NoiseGate(testSampleRate)
        gate.enabled = true

        val loud = FloatBuffer.wrap(FloatArray(30) { 0.5f })
        gate.process(loud, 30)
        assertTrue(abs(loud.get(29) - 0.5f) < 0.01f)

        gate.enabled = false
        gate.enabled = true

        val quiet = FloatBuffer.wrap(FloatArray(1) { 0.001f })
        gate.process(quiet, 1)
        assertEquals(0f, quiet.get(0))
    }
}
