package org.ampsim.dsp

import kotlinx.coroutines.runBlocking
import org.ampsim.dsp.effects.GenericReverb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericReverbTest {

    @Test
    fun testDefaultParameters() {
        val reverb = GenericReverb()
        assertEquals("reverb", reverb.type)
        assertEquals(0.5f, reverb.getParameter("size"))
        assertEquals(0.5f, reverb.getParameter("damping"))
        assertEquals(0.3f, reverb.getParameter("mix"))
    }

    @Test
    fun testParametersClamped() {
        val reverb = GenericReverb()
        reverb.setParameter("size", 5f)
        assertEquals(1f, reverb.getParameter("size"))
        reverb.setParameter("damping", -2f)
        assertEquals(0f, reverb.getParameter("damping"))
    }

    @Test
    fun testUnknownParameterIgnored() {
        val reverb = GenericReverb()
        reverb.setParameter("nonexistent", 1f)
        assertEquals(0f, reverb.getParameter("nonexistent"))
    }

    @Test
    fun testFullyDryPassesInputThrough() = runBlocking {
        val reverb = GenericReverb(8_000)
        reverb.setParameter("mix", 0f)

        val n = 64
        val input = FloatArray(n) { it * 0.01f }
        val output = FloatArray(n)
        assertTrue(reverb.process(input, output, n).isSuccess)

        for (i in 0 until n) {
            assertEquals(input[i], output[i], 1e-6f)
        }
    }

    @Test
    fun testProcessProducesFiniteBoundedOutput() = runBlocking {
        val reverb = GenericReverb(8_000)
        reverb.setParameter("size", 1f) // longest decay, most feedback
        reverb.setParameter("mix", 1f)

        val n = 512
        val input = FloatArray(n) { 1f } // sustained DC, the worst case for comb feedback
        val output = FloatArray(n)
        assertTrue(reverb.process(input, output, n).isSuccess)

        for (v in output) {
            assertTrue(v.isFinite(), "reverb produced a non-finite sample: $v")
            assertTrue(abs(v) <= 4f, "reverb output ran away: $v")
        }
    }

    @Test
    fun testTailContinuesAfterInputStops() = runBlocking {
        val reverb = GenericReverb(8_000)
        reverb.setParameter("mix", 1f)

        // Excite the network, then feed it silence and confirm something rings on.
        val excitation = FloatArray(256) { 1f }
        reverb.process(excitation, FloatArray(256), 256)

        val silence = FloatArray(512)
        val tail = FloatArray(512)
        assertTrue(reverb.process(silence, tail, 512).isSuccess)
        assertTrue(tail.any { abs(it) > 1e-4f }, "expected a decaying tail after input stopped")
    }

    @Test
    fun testResetClearsTail() = runBlocking {
        val reverb = GenericReverb(8_000)
        reverb.setParameter("mix", 1f)

        reverb.process(FloatArray(256) { 1f }, FloatArray(256), 256)
        reverb.reset()

        val output = FloatArray(256)
        reverb.process(FloatArray(256), output, 256)
        for (v in output) assertEquals(0f, v)
    }

    @Test
    fun testDeterministicAcrossInstances() = runBlocking {
        val n = 256
        val input = FloatArray(n) { kotlin.math.sin(it * 0.05f) }

        val first = FloatArray(n)
        GenericReverb(8_000).process(input, first, n)

        val second = FloatArray(n)
        GenericReverb(8_000).process(input, second, n)

        assertTrue(first.contentEquals(second), "two fresh reverbs disagreed on identical input")
    }

    @Test
    fun testRejectsUndersizedBuffers() = runBlocking {
        val reverb = GenericReverb()
        assertTrue(reverb.process(FloatArray(4), FloatArray(16), 16).isFailure)
        assertTrue(reverb.process(FloatArray(16), FloatArray(4), 16).isFailure)
    }

    @Test
    fun testStateSaveRestore() {
        val reverb = GenericReverb()
        reverb.setParameter("size", 0.8f)
        reverb.setParameter("damping", 0.2f)
        reverb.setParameter("mix", 0.6f)
        val state = reverb.getState()

        val restored = GenericReverb()
        restored.setState(state)
        assertEquals(0.8f, restored.getParameter("size"))
        assertEquals(0.2f, restored.getParameter("damping"))
        assertEquals(0.6f, restored.getParameter("mix"))
    }

    @Test
    fun testLatencyAndCpuLoad() {
        val reverb = GenericReverb()
        assertEquals(0, reverb.getLatencySamples())
        assertTrue(reverb.getCpuLoad() in 0f..1f)
    }
}
