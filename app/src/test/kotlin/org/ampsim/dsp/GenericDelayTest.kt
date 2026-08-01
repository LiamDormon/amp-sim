package org.ampsim.dsp

import kotlinx.coroutines.runBlocking
import org.ampsim.dsp.effects.GenericDelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericDelayTest {

    @Test
    fun testDefaultParameters() {
        val delay = GenericDelay()
        assertEquals("delay", delay.type)
        assertEquals(250f, delay.getParameter("time"))
        assertEquals(0.3f, delay.getParameter("feedback"))
        assertEquals(0.35f, delay.getParameter("mix"))
    }

    @Test
    fun testFeedbackClamped() {
        val delay = GenericDelay()
        delay.setParameter("feedback", 5f)
        assertEquals(0.95f, delay.getParameter("feedback"))
    }

    @Test
    fun testProcessProducesDelayedSignal() = runBlocking {
        val sampleRate = 1000
        val delay = GenericDelay(sampleRate)
        delay.setParameter("time", 10f) // 10 ms -> 10 samples
        delay.setParameter("feedback", 0f)
        delay.setParameter("mix", 1f) // fully wet

        val n = 64
        val input = FloatArray(n)
        input[0] = 1f // impulse
        val output = FloatArray(n)
        val result = delay.process(input, output, n)
        assertTrue(result.isSuccess)

        // Wet-only impulse should appear delayed by 10 samples.
        assertEquals(0f, output[0])
        assertEquals(1f, output[10])
    }

    @Test
    fun testLatencyMatchesTime() {
        val sampleRate = 48_000
        val delay = GenericDelay(sampleRate)
        delay.setParameter("time", 500f) // 500 ms
        val expected = (500f / 1000f * sampleRate).toInt()
        assertEquals(expected, delay.getLatencySamples())
    }

    @Test
    fun testResetClearsBuffer() = runBlocking {
        val delay = GenericDelay(1000)
        delay.setParameter("time", 5f)
        delay.setParameter("mix", 1f)
        val input = FloatArray(16) { 1f }
        val output = FloatArray(16)
        delay.process(input, output, 16)
        delay.reset()

        val input2 = FloatArray(16)
        val output2 = FloatArray(16)
        delay.process(input2, output2, 16)
        for (v in output2) assertEquals(0f, v)
    }

    @Test
    fun testStateSaveRestore() {
        val delay = GenericDelay()
        delay.setParameter("time", 400f)
        delay.setParameter("feedback", 0.5f)
        delay.setParameter("mix", 0.8f)
        val state = delay.getState()

        val restored = GenericDelay()
        restored.setState(state)
        assertEquals(400f, restored.getParameter("time"))
        assertEquals(0.5f, restored.getParameter("feedback"))
        assertEquals(0.8f, restored.getParameter("mix"))
    }
}
