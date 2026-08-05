package org.ampsim.dsp

import kotlinx.coroutines.runBlocking
import org.ampsim.dsp.effects.GenericChorus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericChorusTest {

    @Test
    fun testDefaultParameters() {
        val chorus = GenericChorus()
        assertEquals("chorus", chorus.type)
        assertEquals(1.5f, chorus.getParameter("rate"))
        assertEquals(0.5f, chorus.getParameter("depth"))
        assertEquals(0.5f, chorus.getParameter("mix"))
    }

    @Test
    fun testParametersClamped() {
        val chorus = GenericChorus()
        chorus.setParameter("rate", 100f)
        assertEquals(10f, chorus.getParameter("rate"))
        chorus.setParameter("rate", 0f)
        assertEquals(0.1f, chorus.getParameter("rate"))
        chorus.setParameter("depth", 3f)
        assertEquals(1f, chorus.getParameter("depth"))
    }

    @Test
    fun testUnknownParameterIgnored() {
        val chorus = GenericChorus()
        chorus.setParameter("nonexistent", 1f)
        assertEquals(0f, chorus.getParameter("nonexistent"))
    }

    @Test
    fun testFullyDryPassesInputThrough() = runBlocking {
        val chorus = GenericChorus(8_000)
        chorus.setParameter("mix", 0f)

        val n = 64
        val input = FloatArray(n) { it * 0.01f }
        val output = FloatArray(n)
        assertTrue(chorus.process(input, output, n).isSuccess)

        for (i in 0 until n) {
            assertEquals(input[i], output[i], 1e-6f)
        }
    }

    @Test
    fun testWetSignalIsDelayedNotImmediate() = runBlocking {
        val sampleRate = 8_000
        val chorus = GenericChorus(sampleRate)
        chorus.setParameter("mix", 1f) // fully wet
        chorus.setParameter("depth", 0f) // no sweep -> fixed 15 ms delay

        val n = 256
        val input = FloatArray(n)
        input[0] = 1f // impulse
        val output = FloatArray(n)
        assertTrue(chorus.process(input, output, n).isSuccess)

        // 15 ms at 8 kHz is 120 samples; nothing should arrive before then.
        val expectedDelay = (GenericChorus.BASE_DELAY_MS / 1000f * sampleRate).toInt()
        for (i in 0 until expectedDelay) {
            assertEquals(0f, output[i], 1e-6f)
        }
        assertTrue(
            output.drop(expectedDelay).any { abs(it) > 0.5f },
            "expected the impulse to reappear around sample $expectedDelay"
        )
    }

    @Test
    fun testProcessProducesFiniteBoundedOutput() = runBlocking {
        val chorus = GenericChorus(8_000)
        chorus.setParameter("depth", 1f)
        chorus.setParameter("rate", 10f)
        chorus.setParameter("mix", 1f)

        val n = 512
        val input = FloatArray(n) { kotlin.math.sin(it * 0.1f) }
        val output = FloatArray(n)
        assertTrue(chorus.process(input, output, n).isSuccess)

        for (v in output) {
            assertTrue(v.isFinite(), "chorus produced a non-finite sample: $v")
            // No feedback path, so the wet signal can never exceed the input peak.
            assertTrue(abs(v) <= 1f + 1e-5f, "chorus output exceeded input range: $v")
        }
    }

    @Test
    fun testResetClearsBufferAndPhase() = runBlocking {
        val chorus = GenericChorus(8_000)
        chorus.setParameter("mix", 1f)

        chorus.process(FloatArray(256) { 1f }, FloatArray(256), 256)
        chorus.reset()

        val output = FloatArray(64)
        chorus.process(FloatArray(64), output, 64)
        for (v in output) assertEquals(0f, v)
    }

    @Test
    fun testDeterministicAcrossInstances() = runBlocking {
        val n = 256
        val input = FloatArray(n) { kotlin.math.sin(it * 0.05f) }

        val first = FloatArray(n)
        GenericChorus(8_000).process(input, first, n)

        val second = FloatArray(n)
        GenericChorus(8_000).process(input, second, n)

        assertTrue(first.contentEquals(second), "two fresh choruses disagreed on identical input")
    }

    @Test
    fun testRejectsUndersizedBuffers() = runBlocking {
        val chorus = GenericChorus()
        assertTrue(chorus.process(FloatArray(4), FloatArray(16), 16).isFailure)
        assertTrue(chorus.process(FloatArray(16), FloatArray(4), 16).isFailure)
    }

    @Test
    fun testStateSaveRestore() {
        val chorus = GenericChorus()
        chorus.setParameter("rate", 4f)
        chorus.setParameter("depth", 0.9f)
        chorus.setParameter("mix", 0.25f)
        val state = chorus.getState()

        val restored = GenericChorus()
        restored.setState(state)
        assertEquals(4f, restored.getParameter("rate"))
        assertEquals(0.9f, restored.getParameter("depth"))
        assertEquals(0.25f, restored.getParameter("mix"))
    }

    @Test
    fun testLatencyAndCpuLoad() {
        val chorus = GenericChorus()
        assertEquals(0, chorus.getLatencySamples())
        assertTrue(chorus.getCpuLoad() in 0f..1f)
    }
}
