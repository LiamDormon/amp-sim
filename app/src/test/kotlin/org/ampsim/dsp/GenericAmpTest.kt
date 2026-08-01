package org.ampsim.dsp

import kotlinx.coroutines.runBlocking
import org.ampsim.dsp.effects.GenericAmp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericAmpTest {

    @Test
    fun testDefaultParameters() {
        val amp = GenericAmp()
        assertEquals("amp", amp.type)
        assertEquals(20f, amp.getParameter("gain"))
        assertEquals(1f, amp.getParameter("bass"))
        assertEquals(1f, amp.getParameter("mid"))
        assertEquals(1f, amp.getParameter("treble"))
        assertEquals(0.7f, amp.getParameter("master"))
    }

    @Test
    fun testParameterClamping() {
        val amp = GenericAmp()
        amp.setParameter("gain", 500f)
        assertEquals(100f, amp.getParameter("gain"))
        amp.setParameter("master", 2f)
        assertEquals(1f, amp.getParameter("master"))
    }

    @Test
    fun testProcessBlockSucceeds() = runBlocking {
        val amp = GenericAmp()
        val input = FloatArray(128) { kotlin.math.sin(it * 0.1f) * 0.5f }
        val output = FloatArray(128)
        val result = amp.process(input, output, 128)
        assertTrue(result.isSuccess)
        for (v in output) assertTrue(v.isFinite())
    }

    @Test
    fun testMasterZeroSilences() = runBlocking {
        val amp = GenericAmp()
        amp.setParameter("master", 0f)
        val input = FloatArray(64) { 0.5f }
        val output = FloatArray(64)
        amp.process(input, output, 64)
        for (v in output) assertEquals(0f, v)
    }

    @Test
    fun testDeterministicProcessing() = runBlocking {
        val a = GenericAmp()
        val b = GenericAmp()
        val input = FloatArray(64) { kotlin.math.sin(it.toFloat()) * 0.3f }
        val outA = FloatArray(64)
        val outB = FloatArray(64)
        a.process(input, outA, 64)
        b.process(input, outB, 64)
        assertTrue(outA.contentEquals(outB))
    }

    @Test
    fun testStateSaveRestore() {
        val amp = GenericAmp()
        amp.setParameter("gain", 55f)
        amp.setParameter("bass", 1.5f)
        amp.setParameter("mid", 0.2f)
        amp.setParameter("treble", 1.8f)
        amp.setParameter("master", 0.4f)
        val state = amp.getState()

        val restored = GenericAmp()
        restored.setState(state)
        assertEquals(55f, restored.getParameter("gain"))
        assertEquals(1.5f, restored.getParameter("bass"))
        assertEquals(0.2f, restored.getParameter("mid"))
        assertEquals(1.8f, restored.getParameter("treble"))
        assertEquals(0.4f, restored.getParameter("master"))
    }

    @Test
    fun testLatencyAndCpu() {
        val amp = GenericAmp()
        assertEquals(0, amp.getLatencySamples())
        assertTrue(amp.getCpuLoad() in 0f..1f)
    }
}
